package com.kooo.evcam.surround;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.blindspot.CameraMirrorPolicy;
import com.kooo.evcam.blindspot.MirrorTarget;
import com.kooo.evcam.surround.SurroundCalibrationProfile;
import com.kooo.evcam.surround.SurroundGuidanceCalculator.LineStripBatch;
import com.kooo.evcam.surround.SurroundLayoutCalculator.SurroundLayoutSpec;
import com.kooo.evcam.surround.SurroundLayoutCalculator.ViewportBox;
import com.kooo.evcam.surround.SurroundMeshBuilder.GridMeshData;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * 360 模式 A 专用 OpenGL ES 2.0 渲染器（仅在模式 A 激活时随 GLSurfaceView 创建，退出模式 A 立即销毁）。
 * 直接消费 Camera2 输出的四路 OES SurfaceTexture（零 2560x1600 Atlas FBO、零 glReadPixels 阻塞）。
 */
public final class SurroundGlRenderer implements GLSurfaceView.Renderer {

    private static final String TAG = "SurroundGlRenderer";
    private static final int BYTES_PER_FLOAT = 4;
    private static final int BYTES_PER_SHORT = 2;

    public interface SurfaceLifecycleListener {
        void onSurroundSurfacesCreated(@NonNull Surface front,
                                       @NonNull Surface back,
                                       @NonNull Surface left,
                                       @NonNull Surface right,
                                       @NonNull SurfaceTexture stFront,
                                       @NonNull SurfaceTexture stBack,
                                       @NonNull SurfaceTexture stLeft,
                                       @NonNull SurfaceTexture stRight);
    }

    // ---- Shaders ----

    private static final String BIRD_VERTEX_SHADER =
            "attribute vec2 aPosition;\n"
                    + "attribute vec2 aPrimaryUv;\n"
                    + "attribute vec2 aSecondaryUv;\n"
                    + "attribute float aPrimaryIdx;\n"
                    + "attribute float aSecondaryIdx;\n"
                    + "attribute float aBlendWeight;\n"
                    + "attribute float aUnderCarWeight;\n"
                    + "varying vec2 vPrimaryUv;\n"
                    + "varying vec2 vSecondaryUv;\n"
                    + "varying float vPrimaryIdx;\n"
                    + "varying float vSecondaryIdx;\n"
                    + "varying float vBlendWeight;\n"
                    + "varying float vUnderCarWeight;\n"
                    + "void main() {\n"
                    + "    gl_Position = vec4(aPosition, 0.0, 1.0);\n"
                    + "    vPrimaryUv = aPrimaryUv;\n"
                    + "    vSecondaryUv = aSecondaryUv;\n"
                    + "    vPrimaryIdx = aPrimaryIdx;\n"
                    + "    vSecondaryIdx = aSecondaryIdx;\n"
                    + "    vBlendWeight = aBlendWeight;\n"
                    + "    vUnderCarWeight = aUnderCarWeight;\n"
                    + "}\n";

    private static final String BIRD_FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n"
                    + "precision highp float;\n"
                    + "uniform samplerExternalOES uTexFront;\n"
                    + "uniform samplerExternalOES uTexBack;\n"
                    + "uniform samplerExternalOES uTexLeft;\n"
                    + "uniform samplerExternalOES uTexRight;\n"
                    + "uniform mat4 uMatFront;\n"
                    + "uniform mat4 uMatBack;\n"
                    + "uniform mat4 uMatLeft;\n"
                    + "uniform mat4 uMatRight;\n"
                    + "uniform vec4 uMirrorFront;\n"  // (scaleX, offsetX, scaleY, offsetY)
                    + "uniform vec4 uMirrorBack;\n"
                    + "uniform vec4 uMirrorLeft;\n"
                    + "uniform vec4 uMirrorRight;\n"
                    + "varying vec2 vPrimaryUv;\n"
                    + "varying vec2 vSecondaryUv;\n"
                    + "varying float vPrimaryIdx;\n"
                    + "varying float vSecondaryIdx;\n"
                    + "varying float vBlendWeight;\n"
                    + "varying float vUnderCarWeight;\n"
                    + "vec2 applyMirror(vec2 uv, vec4 m) {\n"
                    + "    return vec2(uv.x * m.x + m.y, uv.y * m.z + m.w);\n"
                    + "}\n"
                    + "vec3 sampleOes(float idx, vec2 uv) {\n"
                    + "    vec2 safeUv = clamp(uv, 0.001, 0.999);\n"
                    + "    if (idx < 0.5) {\n"
                    + "        vec2 mapped = (uMatFront * vec4(applyMirror(safeUv, uMirrorFront), 0.0, 1.0)).xy;\n"
                    + "        return texture2D(uTexFront, mapped).rgb;\n"
                    + "    } else if (idx < 1.5) {\n"
                    + "        vec2 mapped = (uMatBack * vec4(applyMirror(safeUv, uMirrorBack), 0.0, 1.0)).xy;\n"
                    + "        return texture2D(uTexBack, mapped).rgb;\n"
                    + "    } else if (idx < 2.5) {\n"
                    + "        vec2 mapped = (uMatLeft * vec4(applyMirror(safeUv, uMirrorLeft), 0.0, 1.0)).xy;\n"
                    + "        return texture2D(uTexLeft, mapped).rgb;\n"
                    + "    } else {\n"
                    + "        vec2 mapped = (uMatRight * vec4(applyMirror(safeUv, uMirrorRight), 0.0, 1.0)).xy;\n"
                    + "        return texture2D(uTexRight, mapped).rgb;\n"
                    + "    }\n"
                    + "}\n"
                    + "void main() {\n"
                    + "    vec3 cPrimary = sampleOes(vPrimaryIdx, vPrimaryUv);\n"
                    + "    vec3 cSecondary = sampleOes(vSecondaryIdx, vSecondaryUv);\n"
                    + "    vec3 stitched = mix(cPrimary, cSecondary, clamp(vBlendWeight, 0.0, 1.0));\n"
                    + "    stitched = mix(stitched, vec3(0.08, 0.10, 0.13), clamp(vUnderCarWeight * 0.72, 0.0, 0.72));\n"
                    + "    gl_FragColor = vec4(pow(clamp(stitched, 0.0, 1.0), vec3(0.92)), 1.0);\n"
                    + "}\n";

    private static final String PATCH_VERTEX_SHADER =
            "attribute vec2 aPosition;\n"
                    + "attribute vec2 aTexCoord;\n"
                    + "attribute float aValid;\n"
                    + "varying vec2 vTexCoord;\n"
                    + "varying float vValid;\n"
                    + "void main() {\n"
                    + "    gl_Position = vec4(aPosition, 0.0, 1.0);\n"
                    + "    vTexCoord = aTexCoord;\n"
                    + "    vValid = aValid;\n"
                    + "}\n";

    private static final String PATCH_FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n"
                    + "precision highp float;\n"
                    + "uniform samplerExternalOES uOesTex;\n"
                    + "uniform mat4 uTexMatrix;\n"
                    + "uniform vec4 uMirror;\n"
                    + "varying vec2 vTexCoord;\n"
                    + "varying float vValid;\n"
                    + "void main() {\n"
                    + "    vec2 baseUv = clamp(vTexCoord, 0.001, 0.999);\n"
                    + "    vec2 mirrored = vec2(baseUv.x * uMirror.x + uMirror.y, baseUv.y * uMirror.z + uMirror.w);\n"
                    + "    vec2 finalUv = (uTexMatrix * vec4(mirrored, 0.0, 1.0)).xy;\n"
                    + "    vec3 rgb = texture2D(uOesTex, finalUv).rgb;\n"
                    + "    float mask = smoothstep(0.05, 0.35, vValid);\n"
                    + "    gl_FragColor = vec4(pow(clamp(rgb, 0.0, 1.0), vec3(0.92)) * mask, 1.0);\n"
                    + "}\n";

    private static final String CAR_VERTEX_SHADER =
            "attribute vec2 aPosition;\n"
                    + "attribute vec2 aTexCoord;\n"
                    + "varying vec2 vTexCoord;\n"
                    + "void main() {\n"
                    + "    gl_Position = vec4(aPosition, 0.0, 1.0);\n"
                    + "    vTexCoord = aTexCoord;\n"
                    + "}\n";

    private static final String CAR_FRAGMENT_SHADER =
            "precision mediump float;\n"
                    + "uniform sampler2D uCarTex;\n"
                    + "varying vec2 vTexCoord;\n"
                    + "void main() {\n"
                    + "    gl_FragColor = texture2D(uCarTex, vTexCoord);\n"
                    + "}\n";

    private static final String COLOR_VERTEX_SHADER =
            "attribute vec2 aPosition;\n"
                    + "attribute vec4 aColor;\n"
                    + "varying vec4 vColor;\n"
                    + "void main() {\n"
                    + "    gl_Position = vec4(aPosition, 0.0, 1.0);\n"
                    + "    vColor = aColor;\n"
                    + "}\n";

    private static final String COLOR_FRAGMENT_SHADER =
            "precision mediump float;\n"
                    + "varying vec4 vColor;\n"
                    + "void main() {\n"
                    + "    gl_FragColor = vColor;\n"
                    + "}\n";

    // ---- State ----

    private final Context context;
    private final AppConfig appConfig;
    @Nullable
    private final GLSurfaceView hostGlView;
    @Nullable
    private final SurfaceLifecycleListener surfaceLifecycleListener;
    private final Object stateLock = new Object();

    private final int[] oesTextureIds = new int[4];
    private final SurfaceTexture[] surfaceTextures = new SurfaceTexture[4];
    private final Surface[] inputSurfaces = new Surface[4];
    private final boolean[] frameAvailableFlags = new boolean[4];
    private final float[][] texMatrices = new float[4][16];

    private int birdProgram;
    private int patchProgram;
    private int carProgram;
    private int colorProgram;
    private int carTextureId;

    private int viewportWidth;
    private int viewportHeight;

    // 业务参数快照（外部 UI 线程写入，GL 线程读取）
    private volatile SurroundCalibrationProfile activeProfile;
    private volatile SurroundViewMode effectiveTopMode = SurroundViewMode.FRONT;
    private volatile SurroundViewMode effectiveBottomMode = SurroundViewMode.FRONT_WHEELS;
    private volatile boolean wheelsCollapsed = false;
    private volatile boolean stretchRoadViews = false;
    private volatile boolean guidanceEnabled = true;
    private volatile float wheelAngleDeg = 0f;
    private volatile boolean inReverseGear = false;
    private volatile int gapPx = 12;

    private final AtomicBoolean birdMeshDirty = new AtomicBoolean(true);
    private final AtomicBoolean roadMeshDirty = new AtomicBoolean(true);
    private final AtomicBoolean carBitmapDirty = new AtomicBoolean(true);
    private final AtomicBoolean guidanceDirty = new AtomicBoolean(true);
    private volatile boolean released = false;

    @Nullable
    private Bitmap pendingCarBitmap;

    // 缓存的 GPU 缓冲
    private FloatBuffer birdVertexBuffer;
    private ShortBuffer birdIndexBuffer;
    private int birdIndexCount;

    private FloatBuffer roadVertexBuffer;
    private ShortBuffer roadIndexBuffer;
    private int roadIndexCount;
    private boolean cachedRoadIsRear;
    private boolean cachedRoadStretched;
    private float cachedRoadAspect;

    private final FloatBuffer[] wheelVertexBuffers = new FloatBuffer[4];
    private final ShortBuffer[] wheelIndexBuffers = new ShortBuffer[4];
    private final int[] wheelIndexCounts = new int[4];

    private FloatBuffer carQuadBuffer;
    private FloatBuffer birdGuidanceBuffer;
    private int[] birdGuidanceCounts;
    private FloatBuffer roadGuidanceBuffer;
    private int[] roadGuidanceCounts;

    public SurroundGlRenderer(@NonNull Context context,
                              @Nullable GLSurfaceView hostGlView,
                              @Nullable SurfaceLifecycleListener listener) {
        this.context = context.getApplicationContext();
        this.appConfig = new AppConfig(this.context);
        this.hostGlView = hostGlView;
        this.surfaceLifecycleListener = listener;
        for (int i = 0; i < 4; i++) {
            Matrix.setIdentityM(texMatrices[i], 0);
        }
        reloadConfigurationFromPrefs();
    }

    public void reloadConfigurationFromPrefs() {
        synchronized (stateLock) {
            this.activeProfile = SurroundCalibrationManager.resolveActiveProfile(appConfig);
            this.wheelsCollapsed = appConfig.isSurroundWheelsCollapsed();
            this.stretchRoadViews = appConfig.isSurroundStretchRoadViews();
            this.guidanceEnabled = appConfig.isPreviewGuidanceEnabled();
            this.pendingCarBitmap = SurroundCarModelRepository.loadActiveCarModelBitmap(appConfig);
            this.birdMeshDirty.set(true);
            this.roadMeshDirty.set(true);
            this.carBitmapDirty.set(true);
            this.guidanceDirty.set(true);
        }
        requestRenderSafe();
    }

    public void updateRenderState(@NonNull SurroundViewMode topMode,
                                  @NonNull SurroundViewMode bottomMode,
                                  boolean wheelsCollapsed,
                                  boolean stretchRoadViews,
                                  boolean guidanceEnabled,
                                  float wheelAngleDeg,
                                  boolean inReverseGear,
                                  int gapPx) {
        synchronized (stateLock) {
            boolean roadChanged = (this.effectiveTopMode != topMode)
                    || (this.stretchRoadViews != stretchRoadViews);
            boolean guideChanged = (this.guidanceEnabled != guidanceEnabled)
                    || (Math.abs(this.wheelAngleDeg - wheelAngleDeg) > 0.4f)
                    || (this.inReverseGear != inReverseGear);
            this.effectiveTopMode = topMode;
            this.effectiveBottomMode = bottomMode;
            this.wheelsCollapsed = wheelsCollapsed;
            this.stretchRoadViews = stretchRoadViews;
            this.guidanceEnabled = guidanceEnabled;
            this.wheelAngleDeg = wheelAngleDeg;
            this.inReverseGear = inReverseGear;
            this.gapPx = Math.max(4, gapPx);
            if (roadChanged) {
                roadMeshDirty.set(true);
            }
            if (guideChanged) {
                guidanceDirty.set(true);
            }
        }
        requestRenderSafe();
    }

    public void requestRenderSafe() {
        if (!released && hostGlView != null) {
            hostGlView.requestRender();
        }
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        released = false;
        GLES20.glClearColor(0.05f, 0.06f, 0.09f, 1.0f);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);

        birdProgram = buildProgram(BIRD_VERTEX_SHADER, BIRD_FRAGMENT_SHADER);
        patchProgram = buildProgram(PATCH_VERTEX_SHADER, PATCH_FRAGMENT_SHADER);
        carProgram = buildProgram(CAR_VERTEX_SHADER, CAR_FRAGMENT_SHADER);
        colorProgram = buildProgram(COLOR_VERTEX_SHADER, COLOR_FRAGMENT_SHADER);

        initOesInputTexturesAndSurfaces();
        initCarTexture();
        prebuildStaticWheelMeshes();
        birdMeshDirty.set(true);
        roadMeshDirty.set(true);
        carBitmapDirty.set(true);
        guidanceDirty.set(true);
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        this.viewportWidth = Math.max(1, width);
        this.viewportHeight = Math.max(1, height);
        roadMeshDirty.set(true);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        if (released || viewportWidth <= 0 || viewportHeight <= 0) {
            return;
        }

        // 更新四路 OES 纹理帧
        for (int i = 0; i < 4; i++) {
            SurfaceTexture st = surfaceTextures[i];
            if (st != null && frameAvailableFlags[i]) {
                try {
                    st.updateTexImage();
                    st.getTransformMatrix(texMatrices[i]);
                } catch (Exception e) {
                    AppLog.w(TAG, "updateTexImage ignored for cam " + i + ": " + e.getMessage());
                } finally {
                    frameAvailableFlags[i] = false;
                }
            }
        }

        final SurroundCalibrationProfile profile;
        final SurroundViewMode topMode;
        final SurroundViewMode bottomMode;
        final boolean collapsed;
        final boolean stretched;
        final boolean showGuidance;
        final float angleDeg;
        final boolean reverse;
        final int gap;
        synchronized (stateLock) {
            profile = this.activeProfile;
            topMode = this.effectiveTopMode;
            bottomMode = this.effectiveBottomMode;
            collapsed = this.wheelsCollapsed;
            stretched = this.stretchRoadViews;
            showGuidance = this.guidanceEnabled;
            angleDeg = this.wheelAngleDeg;
            reverse = this.inReverseGear;
            gap = this.gapPx;
        }

        SurroundLayoutSpec layout = SurroundLayoutCalculator.calculate(
                viewportWidth,
                viewportHeight,
                bottomMode,
                collapsed,
                profile.groundWidthCm,
                profile.groundLengthCm,
                profile.groundFrontExtensionCm,
                gap
        );

        GLES20.glViewport(0, 0, viewportWidth, viewportHeight);
        GLES20.glClearColor(0.05f, 0.06f, 0.09f, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        // 1. 左侧 360 全景鸟瞰 + 离线 E5 车模 + 鸟瞰转向引导线
        drawBirdViewport(layout.birdBox, profile, showGuidance, angleDeg, reverse);

        // 2. 右上视角（随档位 / 前视 / 后视 / 前轮 / 后轮）
        drawTopDetailViewport(layout.topDetailBox, topMode, stretched, showGuidance, angleDeg, reverse);

        // 3. 右下双侧轮位 / 左右侧视（未折叠时）
        if (!layout.wheelsCollapsed) {
            drawBottomDetailViewports(layout, bottomMode);
        }
    }

    private void drawBirdViewport(@NonNull ViewportBox box,
                                  @NonNull SurroundCalibrationProfile profile,
                                  boolean showGuidance,
                                  float angleDeg,
                                  boolean isReverse) {
        if (box.isEmpty()) return;
        ensureBirdMesh(profile);
        uploadCarTextureIfNeeded();
        updateCarQuad(profile);

        GLES20.glViewport(box.left, box.glY(viewportHeight), box.width, box.height);
        GLES20.glUseProgram(birdProgram);

        int[] texUnits = {GLES20.GL_TEXTURE0, GLES20.GL_TEXTURE1, GLES20.GL_TEXTURE2, GLES20.GL_TEXTURE3};
        for (int i = 0; i < 4; i++) {
            GLES20.glActiveTexture(texUnits[i]);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureIds[i]);
        }
        GLES20.glUniform1i(GLES20.glGetUniformLocation(birdProgram, "uTexFront"), 0);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(birdProgram, "uTexBack"), 1);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(birdProgram, "uTexLeft"), 2);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(birdProgram, "uTexRight"), 3);

        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(birdProgram, "uMatFront"), 1, false, texMatrices[0], 0);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(birdProgram, "uMatBack"), 1, false, texMatrices[1], 0);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(birdProgram, "uMatLeft"), 1, false, texMatrices[2], 0);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(birdProgram, "uMatRight"), 1, false, texMatrices[3], 0);

        applyMirrorUniform(birdProgram, "uMirrorFront", "front");
        applyMirrorUniform(birdProgram, "uMirrorBack", "back");
        applyMirrorUniform(birdProgram, "uMirrorLeft", "left");
        applyMirrorUniform(birdProgram, "uMirrorRight", "right");

        int strideBytes = SurroundMeshBuilder.BIRD_FLOATS_PER_VERTEX * BYTES_PER_FLOAT;
        int locPos = bindFloatAttrib(birdProgram, "aPosition", birdVertexBuffer, 0, 2, strideBytes);
        int locPUv = bindFloatAttrib(birdProgram, "aPrimaryUv", birdVertexBuffer, 2, 2, strideBytes);
        int locSUv = bindFloatAttrib(birdProgram, "aSecondaryUv", birdVertexBuffer, 4, 2, strideBytes);
        int locPIdx = bindFloatAttrib(birdProgram, "aPrimaryIdx", birdVertexBuffer, 6, 1, strideBytes);
        int locSIdx = bindFloatAttrib(birdProgram, "aSecondaryIdx", birdVertexBuffer, 7, 1, strideBytes);
        int locBlend = bindFloatAttrib(birdProgram, "aBlendWeight", birdVertexBuffer, 8, 1, strideBytes);
        int locUnder = bindFloatAttrib(birdProgram, "aUnderCarWeight", birdVertexBuffer, 9, 1, strideBytes);

        birdIndexBuffer.position(0);
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, birdIndexCount, GLES20.GL_UNSIGNED_SHORT, birdIndexBuffer);

        disableAttrib(locPos);
        disableAttrib(locPUv);
        disableAttrib(locSUv);
        disableAttrib(locPIdx);
        disableAttrib(locSIdx);
        disableAttrib(locBlend);
        disableAttrib(locUnder);

        if (showGuidance) {
            ensureGuidanceBatches(profile, angleDeg, isReverse);
            drawGuidanceBatch(birdGuidanceBuffer, birdGuidanceCounts);
        }

        // 叠绘离线 E5 俯视车模纹理
        if (carTextureId != 0 && carQuadBuffer != null) {
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            GLES20.glUseProgram(carProgram);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE4);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, carTextureId);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(carProgram, "uCarTex"), 4);

            int carStride = 4 * BYTES_PER_FLOAT;
            int cPos = bindFloatAttrib(carProgram, "aPosition", carQuadBuffer, 0, 2, carStride);
            int cUv = bindFloatAttrib(carProgram, "aTexCoord", carQuadBuffer, 2, 2, carStride);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            disableAttrib(cPos);
            disableAttrib(cUv);
            GLES20.glDisable(GLES20.GL_BLEND);
        }
    }

    private void drawTopDetailViewport(@NonNull ViewportBox box,
                                       @NonNull SurroundViewMode topMode,
                                       boolean stretchFill,
                                       boolean showGuidance,
                                       float angleDeg,
                                       boolean isReverse) {
        if (box.isEmpty()) return;
        GLES20.glViewport(box.left, box.glY(viewportHeight), box.width, box.height);

        if (topMode == SurroundViewMode.FRONT_WHEELS || topMode == SurroundViewMode.REAR_WHEELS) {
            // 若上方选了轮位，左右均分绘制双轮
            int halfW = Math.max(10, (box.width - gapPx) / 2);
            boolean frontWheel = topMode == SurroundViewMode.FRONT_WHEELS;
            GLES20.glViewport(box.left, box.glY(viewportHeight), halfW, box.height);
            drawWheelMesh(frontWheel ? 0 : 3, frontWheel ? 2 : 2);
            GLES20.glViewport(box.left + halfW + gapPx, box.glY(viewportHeight),
                    Math.max(10, box.width - halfW - gapPx), box.height);
            drawWheelMesh(frontWheel ? 1 : 2, 3);
            return;
        }

        boolean isRearRoad = (topMode == SurroundViewMode.REAR);
        int camSlot = isRearRoad ? SurroundMeshBuilder.CAM_BACK : SurroundMeshBuilder.CAM_FRONT;
        String camPos = isRearRoad ? "back" : "front";
        ensureRoadMesh(isRearRoad, box.aspectRatio(), stretchFill);

        drawPatchMesh(roadVertexBuffer, roadIndexBuffer, roadIndexCount, camSlot, camPos);

        if (showGuidance) {
            ensureGuidanceBatches(activeProfile, angleDeg, isRearRoad);
            drawGuidanceBatch(roadGuidanceBuffer, roadGuidanceCounts);
        }
    }

    private void drawBottomDetailViewports(@NonNull SurroundLayoutSpec layout,
                                           @NonNull SurroundViewMode bottomMode) {
        if (!layout.bottomLeftBox.isEmpty()) {
            ViewportBox leftBox = layout.bottomLeftBox;
            GLES20.glViewport(leftBox.left, leftBox.glY(viewportHeight), leftBox.width, leftBox.height);
            int leftWheelKind = (bottomMode == SurroundViewMode.REAR_WHEELS) ? 3 : 0;
            drawWheelMesh(leftWheelKind, SurroundMeshBuilder.CAM_LEFT);
        }
        if (!layout.bottomRightBox.isEmpty()) {
            ViewportBox rightBox = layout.bottomRightBox;
            GLES20.glViewport(rightBox.left, rightBox.glY(viewportHeight), rightBox.width, rightBox.height);
            int rightWheelKind = (bottomMode == SurroundViewMode.REAR_WHEELS) ? 2 : 1;
            drawWheelMesh(rightWheelKind, SurroundMeshBuilder.CAM_RIGHT);
        }
    }

    private void drawWheelMesh(int wheelKind, int camSlot) {
        int safeKind = Math.max(0, Math.min(3, wheelKind));
        FloatBuffer vb = wheelVertexBuffers[safeKind];
        ShortBuffer ib = wheelIndexBuffers[safeKind];
        int count = wheelIndexCounts[safeKind];
        if (vb == null || ib == null || count <= 0) return;
        String camPos = camSlot == SurroundMeshBuilder.CAM_RIGHT ? "right" : "left";
        drawPatchMesh(vb, ib, count, camSlot, camPos);
    }

    private void drawPatchMesh(@NonNull FloatBuffer vb,
                               @NonNull ShortBuffer ib,
                               int indexCount,
                               int camSlot,
                               @NonNull String camPos) {
        GLES20.glUseProgram(patchProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureIds[camSlot]);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(patchProgram, "uOesTex"), 0);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(patchProgram, "uTexMatrix"),
                1, false, texMatrices[camSlot], 0);
        applyMirrorUniform(patchProgram, "uMirror", camPos);

        int stride = SurroundMeshBuilder.PATCH_FLOATS_PER_VERTEX * BYTES_PER_FLOAT;
        int locPos = bindFloatAttrib(patchProgram, "aPosition", vb, 0, 2, stride);
        int locUv = bindFloatAttrib(patchProgram, "aTexCoord", vb, 2, 2, stride);
        int locVal = bindFloatAttrib(patchProgram, "aValid", vb, 4, 1, stride);

        ib.position(0);
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, ib);

        disableAttrib(locPos);
        disableAttrib(locUv);
        disableAttrib(locVal);
    }

    private void drawGuidanceBatch(@Nullable FloatBuffer vb, @Nullable int[] stripCounts) {
        if (vb == null || stripCounts == null || stripCounts.length == 0) return;
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glUseProgram(colorProgram);

        int stride = SurroundGuidanceCalculator.FLOATS_PER_COLOR_VERTEX * BYTES_PER_FLOAT;
        int locPos = bindFloatAttrib(colorProgram, "aPosition", vb, 0, 2, stride);
        int locCol = bindFloatAttrib(colorProgram, "aColor", vb, 2, 4, stride);

        int offset = 0;
        for (int count : stripCounts) {
            if (count > 0) {
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, offset, count);
                offset += count;
            }
        }
        disableAttrib(locPos);
        disableAttrib(locCol);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    private void applyMirrorUniform(int program, @NonNull String uniformName, @NonNull String cameraPos) {
        CameraMirrorPolicy.ResolvedMirror resolved =
                CameraMirrorPolicy.resolve(appConfig, MirrorTarget.MAIN_PREVIEW, cameraPos);
        boolean mirrorH = resolved.physicalH;
        boolean mirrorV = resolved.physicalV;
        float sx = mirrorH ? -1.0f : 1.0f;
        float ox = mirrorH ? 1.0f : 0.0f;
        float sy = mirrorV ? -1.0f : 1.0f;
        float oy = mirrorV ? 1.0f : 0.0f;
        GLES20.glUniform4f(GLES20.glGetUniformLocation(program, uniformName), sx, ox, sy, oy);
    }

    private void initOesInputTexturesAndSurfaces() {
        releaseSurfacesOnly();
        GLES20.glGenTextures(4, oesTextureIds, 0);
        for (int i = 0; i < 4; i++) {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureIds[i]);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            final int idx = i;
            SurfaceTexture st = new SurfaceTexture(oesTextureIds[i]);
            st.setDefaultBufferSize(SurroundCalibrationProfile.IMAGE_WIDTH, SurroundCalibrationProfile.IMAGE_HEIGHT);
            st.setOnFrameAvailableListener(texture -> {
                frameAvailableFlags[idx] = true;
                requestRenderSafe();
            });
            surfaceTextures[i] = st;
            inputSurfaces[i] = new Surface(st);
        }
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);

        if (surfaceLifecycleListener != null) {
            surfaceLifecycleListener.onSurroundSurfacesCreated(
                    inputSurfaces[0], inputSurfaces[1], inputSurfaces[2], inputSurfaces[3],
                    surfaceTextures[0], surfaceTextures[1], surfaceTextures[2], surfaceTextures[3]);
        }
    }

    private void initCarTexture() {
        int[] ids = new int[1];
        GLES20.glGenTextures(1, ids, 0);
        carTextureId = ids[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, carTextureId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    private void uploadCarTextureIfNeeded() {
        if (!carBitmapDirty.compareAndSet(true, false) || carTextureId == 0) {
            return;
        }
        Bitmap bmp;
        synchronized (stateLock) {
            bmp = pendingCarBitmap;
            pendingCarBitmap = null;
        }
        if (bmp != null && !bmp.isRecycled()) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, carTextureId);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            bmp.recycle();
        }
    }

    private void updateCarQuad(@NonNull SurroundCalibrationProfile profile) {
        double halfW = Math.max(50.0, profile.getHalfWorldWidthCm());
        double minY = profile.getWorldMinYCm();
        double spanY = Math.max(100.0, profile.getWorldMaxYCm() - minY);
        float left = (float) (SurroundCalibrationProfile.E5_CAR_MIN_X_CM / halfW);
        float right = (float) (SurroundCalibrationProfile.E5_CAR_MAX_X_CM / halfW);
        float bottom = (float) (((SurroundCalibrationProfile.E5_CAR_MIN_Y_CM - minY) / spanY) * 2.0 - 1.0);
        float top = (float) (((SurroundCalibrationProfile.E5_CAR_MAX_Y_CM - minY) / spanY) * 2.0 - 1.0);
        float[] quad = {
                left, top, 0f, 0f,
                left, bottom, 0f, 1f,
                right, top, 1f, 0f,
                right, bottom, 1f, 1f
        };
        carQuadBuffer = toFloatBuffer(quad, carQuadBuffer);
    }

    private void ensureBirdMesh(@NonNull SurroundCalibrationProfile profile) {
        if (!birdMeshDirty.compareAndSet(true, false) && birdVertexBuffer != null) {
            return;
        }
        GridMeshData mesh = SurroundMeshBuilder.buildBirdGroundMesh(profile, 36, 56);
        birdVertexBuffer = toFloatBuffer(mesh.vertices, birdVertexBuffer);
        birdIndexBuffer = toShortBuffer(mesh.indices, birdIndexBuffer);
        birdIndexCount = mesh.indices.length;
    }

    private void ensureRoadMesh(boolean isRear, float aspect, boolean stretched) {
        boolean needRebuild = roadMeshDirty.compareAndSet(true, false)
                || roadVertexBuffer == null
                || cachedRoadIsRear != isRear
                || cachedRoadStretched != stretched
                || Math.abs(cachedRoadAspect - aspect) > 0.03f;
        if (!needRebuild) return;
        GridMeshData mesh = SurroundMeshBuilder.buildRoadPatchMesh(isRear, aspect, stretched, 28, 20);
        roadVertexBuffer = toFloatBuffer(mesh.vertices, roadVertexBuffer);
        roadIndexBuffer = toShortBuffer(mesh.indices, roadIndexBuffer);
        roadIndexCount = mesh.indices.length;
        cachedRoadIsRear = isRear;
        cachedRoadStretched = stretched;
        cachedRoadAspect = aspect;
    }

    private void prebuildStaticWheelMeshes() {
        for (int k = 0; k < 4; k++) {
            GridMeshData mesh = SurroundMeshBuilder.buildWheelPatchMesh(k, 22, 18);
            wheelVertexBuffers[k] = toFloatBuffer(mesh.vertices, wheelVertexBuffers[k]);
            wheelIndexBuffers[k] = toShortBuffer(mesh.indices, wheelIndexBuffers[k]);
            wheelIndexCounts[k] = mesh.indices.length;
        }
    }

    private void ensureGuidanceBatches(@NonNull SurroundCalibrationProfile profile,
                                       float angleDeg,
                                       boolean isReverse) {
        if (!guidanceDirty.compareAndSet(true, false)
                && birdGuidanceBuffer != null && roadGuidanceBuffer != null) {
            return;
        }
        LineStripBatch birdBatch = SurroundGuidanceCalculator.buildBirdGuidanceStrips(angleDeg, isReverse, profile, 18);
        birdGuidanceBuffer = toFloatBuffer(birdBatch.triangleStripVertices, birdGuidanceBuffer);
        birdGuidanceCounts = birdBatch.stripVertexCounts;

        LineStripBatch roadBatch = SurroundGuidanceCalculator.buildRoadGuidanceStrips(angleDeg, isReverse, 18);
        roadGuidanceBuffer = toFloatBuffer(roadBatch.triangleStripVertices, roadGuidanceBuffer);
        roadGuidanceCounts = roadBatch.stripVertexCounts;
    }

    /** 退出模式 A 或退后台时释放全部 GL 资源、SurfaceTexture 与 OES 纹理，保证非模式 A 下零驻留。 */
    public void releaseGlResources() {
        released = true;
        releaseSurfacesOnly();
        try {
            if (oesTextureIds[0] != 0) GLES20.glDeleteTextures(4, oesTextureIds, 0);
            for (int i = 0; i < 4; i++) oesTextureIds[i] = 0;
            if (carTextureId != 0) {
                GLES20.glDeleteTextures(1, new int[]{carTextureId}, 0);
                carTextureId = 0;
            }
            deleteProgram(birdProgram);
            birdProgram = 0;
            deleteProgram(patchProgram);
            patchProgram = 0;
            deleteProgram(carProgram);
            carProgram = 0;
            deleteProgram(colorProgram);
            colorProgram = 0;
        } catch (Exception ignored) {
        }
        synchronized (stateLock) {
            if (pendingCarBitmap != null && !pendingCarBitmap.isRecycled()) {
                pendingCarBitmap.recycle();
                pendingCarBitmap = null;
            }
        }
    }

    private void releaseSurfacesOnly() {
        for (int i = 0; i < 4; i++) {
            frameAvailableFlags[i] = false;
            if (inputSurfaces[i] != null) {
                try {
                    inputSurfaces[i].release();
                } catch (Exception ignored) {
                }
                inputSurfaces[i] = null;
            }
            if (surfaceTextures[i] != null) {
                try {
                    surfaceTextures[i].setOnFrameAvailableListener(null);
                    surfaceTextures[i].release();
                } catch (Exception ignored) {
                }
                surfaceTextures[i] = null;
            }
        }
    }

    private static int bindFloatAttrib(int program,
                                       @NonNull String name,
                                       @NonNull FloatBuffer buffer,
                                       int floatOffset,
                                       int componentCount,
                                       int strideBytes) {
        int loc = GLES20.glGetAttribLocation(program, name);
        if (loc >= 0) {
            buffer.position(floatOffset);
            GLES20.glEnableVertexAttribArray(loc);
            GLES20.glVertexAttribPointer(loc, componentCount, GLES20.GL_FLOAT, false, strideBytes, buffer);
        }
        return loc;
    }

    private static void disableAttrib(int loc) {
        if (loc >= 0) {
            GLES20.glDisableVertexAttribArray(loc);
        }
    }

    private static FloatBuffer toFloatBuffer(@NonNull float[] data, @Nullable FloatBuffer reusable) {
        FloatBuffer buf = reusable;
        if (buf == null || buf.capacity() < data.length) {
            buf = ByteBuffer.allocateDirect(data.length * BYTES_PER_FLOAT)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer();
        }
        buf.clear();
        buf.put(data);
        buf.position(0);
        return buf;
    }

    private static ShortBuffer toShortBuffer(@NonNull short[] data, @Nullable ShortBuffer reusable) {
        ShortBuffer buf = reusable;
        if (buf == null || buf.capacity() < data.length) {
            buf = ByteBuffer.allocateDirect(data.length * BYTES_PER_SHORT)
                    .order(ByteOrder.nativeOrder())
                    .asShortBuffer();
        }
        buf.clear();
        buf.put(data);
        buf.position(0);
        return buf;
    }

    private static int buildProgram(@NonNull String vertexSource, @NonNull String fragmentSource) {
        int vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        if (vs == 0 || fs == 0) {
            if (vs != 0) GLES20.glDeleteShader(vs);
            if (fs != 0) GLES20.glDeleteShader(fs);
            return 0;
        }
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vs);
        GLES20.glAttachShader(program, fs);
        GLES20.glLinkProgram(program);
        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        if (linkStatus[0] == 0) {
            AppLog.e(TAG, "Program link failed: " + GLES20.glGetProgramInfoLog(program));
            GLES20.glDeleteProgram(program);
            return 0;
        }
        return program;
    }

    private static int compileShader(int type, @NonNull String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            AppLog.e(TAG, "Shader compile failed (" + type + "): " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    private static void deleteProgram(int program) {
        if (program != 0) {
            GLES20.glDeleteProgram(program);
        }
    }
}
