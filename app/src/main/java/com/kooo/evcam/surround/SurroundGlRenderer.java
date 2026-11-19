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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
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
                    + "uniform vec4 uViewTransform;\n" // (scaleX, offsetX, scaleY, offsetY)
                    + "varying vec2 vPrimaryUv;\n"
                    + "varying vec2 vSecondaryUv;\n"
                    + "varying float vPrimaryIdx;\n"
                    + "varying float vSecondaryIdx;\n"
                    + "varying float vBlendWeight;\n"
                    + "varying float vUnderCarWeight;\n"
                    + "void main() {\n"
                    + "    gl_Position = vec4(aPosition.x * uViewTransform.x + uViewTransform.y,\n"
                    + "                       aPosition.y * uViewTransform.z + uViewTransform.w, 0.0, 1.0);\n"
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
                    + "    stitched = mix(stitched, vec3(0.08, 0.10, 0.13), clamp(vUnderCarWeight * 0.72, 0.0, 1.0));\n"
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
    private volatile float roadGuidanceWidthScale = 1.0f;
    private volatile boolean showCenterCard = true;
    private volatile float wheelAngleDeg = 0f;
    private volatile boolean inReverseGear = false;
    private volatile int gapPx = 12;

    private final AtomicBoolean birdMeshDirty = new AtomicBoolean(true);
    private final AtomicBoolean roadMeshDirty = new AtomicBoolean(true);
    private final AtomicBoolean wheelMeshDirty = new AtomicBoolean(true);
    private final AtomicBoolean carBitmapDirty = new AtomicBoolean(true);
    private final AtomicBoolean guidanceDirty = new AtomicBoolean(true);
    private final Object surfaceReleaseLock = new Object();
    private volatile boolean rendererAttached = false;
    private volatile boolean released = false;
    private volatile boolean stopped = false;
    private volatile boolean birdOnlyPreview = false;

    @Nullable
    private SurroundCalibrationProfile activeBaseProfile;
    @Nullable
    private String loadedCalibrationBaseSignature;
    @Nullable
    private String loadedExtrinsicSignature;
    @Nullable
    private String loadedCarModelSignature;
    @Nullable
    private Bitmap pendingCarBitmap;

    // 缓存的 GPU 缓冲
    private FloatBuffer birdVertexBuffer;
    private ShortBuffer birdIndexBuffer;
    private int birdIndexCount;
    private volatile boolean birdMeshIsWorldCm;
    @Nullable
    private String cachedBirdAssetKey;
    @Nullable
    private SurroundCalibrationProfile cachedBirdProfile;

    private FloatBuffer roadVertexBuffer;
    private ShortBuffer roadIndexBuffer;
    private int roadIndexCount;
    @Nullable
    private SurroundCalibrationProfile cachedRoadProfile;
    private boolean cachedRoadIsRear;
    private boolean cachedRoadStretched;
    private float cachedRoadAspect;

    private final FloatBuffer[] wheelVertexBuffers = new FloatBuffer[4];
    private final ShortBuffer[] wheelIndexBuffers = new ShortBuffer[4];
    private final int[] wheelIndexCounts = new int[4];
    @Nullable
    private String cachedWheelAssetKey;
    @Nullable
    private SurroundCalibrationProfile cachedWheelProfile;

    private FloatBuffer carQuadBuffer;
    private FloatBuffer birdGuidanceBuffer;
    private int[] birdGuidanceCounts;
    @Nullable
    private SurroundCalibrationProfile cachedBirdGuidanceProfile;
    private float cachedBirdGuidanceAngle = Float.NaN;
    private boolean cachedBirdGuidanceReverse;

    private FloatBuffer roadGuidanceBuffer;
    private int[] roadGuidanceCounts;
    private float cachedRoadGuidanceAngle = Float.NaN;
    private float cachedRoadGuidanceWidthScale = Float.NaN;
    private boolean cachedRoadGuidanceReverse;

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
        // Defer requestRender until GLSurfaceView.setRenderer(...) initializes GLThread.
        reloadConfigurationInternal(false, true);
    }

    public void markRendererAttached() {
        this.rendererAttached = true;
    }

    public void setBirdOnlyPreview(boolean birdOnly) {
        if (this.birdOnlyPreview != birdOnly) {
            this.birdOnlyPreview = birdOnly;
            requestRenderSafe();
        }
    }

    public void markStopping() {
        this.stopped = true;
        this.released = true;
        this.rendererAttached = false;
    }

    public void reloadConfigurationFromPrefs() {
        reloadConfigurationInternal(true, false);
    }

    public void reloadConfigurationFromPrefs(boolean forceReloadAssets) {
        reloadConfigurationInternal(true, forceReloadAssets);
    }

    private void reloadConfigurationInternal(boolean triggerRender, boolean forceReloadAssets) {
        synchronized (stateLock) {
            int width = appConfig.getSurroundGroundWidthCm();
            int length = appConfig.getSurroundGroundLengthCm();
            int ext = appConfig.getSurroundGroundFrontExtensionCm();

            String calibSource = appConfig.getSurroundCalibrationSource();
            String customCalibPath = appConfig.getSurroundCustomCalibrationPath();
            String nextCalibSig = calibSource + "|" + customCalibPath + "|" + buildFileStamp(customCalibPath);

            SurroundCalibrationProfile existingBase = this.activeBaseProfile;
            SurroundCalibrationProfile existingProfile = this.activeProfile;
            boolean dimensionsChanged = existingBase == null
                    || existingProfile == null
                    || existingBase.groundWidthCm != width
                    || existingBase.groundLengthCm != length
                    || existingBase.groundFrontExtensionCm != ext;
            boolean baseCalibChanged = forceReloadAssets
                    || existingBase == null
                    || existingProfile == null
                    || !nextCalibSig.equals(loadedCalibrationBaseSignature);

            if (baseCalibChanged) {
                SurroundCalibrationProfile base = SurroundCalibrationManager.resolveActiveBaseProfile(appConfig);
                String effectiveSlot = base.isCustomCalibration
                        ? AppConfig.SURROUND_CALIBRATION_E5_CUSTOM
                        : AppConfig.SURROUND_CALIBRATION_E5_BUILTIN;
                SurroundExtrinsicAdjustment adj = appConfig.getSurroundExtrinsicAdjustmentForSlot(effectiveSlot);
                this.activeBaseProfile = base;
                this.activeProfile = adj.applyToProfile(base);
                this.loadedCalibrationBaseSignature = nextCalibSig;
                this.loadedExtrinsicSignature = adj.toCompactSignature();
                this.birdMeshDirty.set(true);
                this.roadMeshDirty.set(true);
                this.wheelMeshDirty.set(true);
                this.guidanceDirty.set(true);
            } else {
                String effectiveSlot = existingBase.isCustomCalibration
                        ? AppConfig.SURROUND_CALIBRATION_E5_CUSTOM
                        : AppConfig.SURROUND_CALIBRATION_E5_BUILTIN;
                SurroundExtrinsicAdjustment adj = appConfig.getSurroundExtrinsicAdjustmentForSlot(effectiveSlot);
                String nextExtrinsicSig = adj.toCompactSignature();
                boolean extrinsicChanged = !nextExtrinsicSig.equals(loadedExtrinsicSignature);
                if (dimensionsChanged || extrinsicChanged) {
                    if (dimensionsChanged) {
                        this.activeBaseProfile = new SurroundCalibrationProfile(
                                existingBase.profileName,
                                existingBase.isCustomCalibration,
                                existingBase.useBuiltInMeshes,
                                false,
                                width,
                                length,
                                ext,
                                existingBase.cameras);
                    }
                    this.activeProfile = adj.applyToProfile(this.activeBaseProfile);
                    this.loadedExtrinsicSignature = nextExtrinsicSig;
                    if (extrinsicChanged || !birdMeshIsWorldCm) {
                        this.birdMeshDirty.set(true);
                    }
                    if (dimensionsChanged) {
                        this.guidanceDirty.set(true);
                    }
                }
            }

            boolean nextCollapsed = appConfig.isSurroundWheelsCollapsed();
            boolean nextStretch = appConfig.isSurroundStretchRoadViews();
            boolean nextGuidance = appConfig.isPreviewGuidanceEnabled();
            float nextRoadWidthScale = appConfig.getSurroundRoadGuidanceWidthScale();
            boolean nextShowCenterCard = appConfig.isPreviewViewShortcutsEnabled()
                    && appConfig.isSurroundOverlayButtonsVisible();
            if (this.stretchRoadViews != nextStretch) {
                this.roadMeshDirty.set(true);
            }
            if (this.guidanceEnabled != nextGuidance
                    || Math.abs(this.roadGuidanceWidthScale - nextRoadWidthScale) > 0.005f) {
                this.guidanceDirty.set(true);
            }
            this.wheelsCollapsed = nextCollapsed;
            this.stretchRoadViews = nextStretch;
            this.guidanceEnabled = nextGuidance;
            this.roadGuidanceWidthScale = nextRoadWidthScale;
            this.showCenterCard = nextShowCenterCard;

            String carSource = appConfig.getSurroundCarModelSource();
            String customCarPath = appConfig.getSurroundCustomCarModelPath();
            String nextCarSig = carSource + "|" + customCarPath + "|" + buildFileStamp(customCarPath);
            if (forceReloadAssets || !nextCarSig.equals(loadedCarModelSignature)) {
                Bitmap oldPending = this.pendingCarBitmap;
                this.pendingCarBitmap = SurroundCarModelRepository.loadActiveCarModelBitmap(context, appConfig);
                if (oldPending != null && oldPending != this.pendingCarBitmap && !oldPending.isRecycled()) {
                    oldPending.recycle();
                }
                this.loadedCarModelSignature = nextCarSig;
                this.carBitmapDirty.set(true);
            }
        }
        if (triggerRender) {
            requestRenderSafe();
        }
    }

    @NonNull
    private static String buildFileStamp(@Nullable String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        File f = new File(path);
        return f.isFile() ? (f.length() + ":" + f.lastModified()) : "missing";
    }

    public void updateRenderState(@NonNull SurroundViewMode topMode,
                                  @NonNull SurroundViewMode bottomMode,
                                  boolean wheelsCollapsed,
                                  boolean stretchRoadViews,
                                  boolean guidanceEnabled,
                                  float wheelAngleDeg,
                                  boolean inReverseGear,
                                  int gapPx) {
        updateRenderState(topMode, bottomMode, wheelsCollapsed, stretchRoadViews, guidanceEnabled,
                this.roadGuidanceWidthScale, this.showCenterCard, wheelAngleDeg, inReverseGear, gapPx);
    }

    public void updateRenderState(@NonNull SurroundViewMode topMode,
                                  @NonNull SurroundViewMode bottomMode,
                                  boolean wheelsCollapsed,
                                  boolean stretchRoadViews,
                                  boolean guidanceEnabled,
                                  float roadGuidanceWidthScale,
                                  boolean showCenterCard,
                                  float wheelAngleDeg,
                                  boolean inReverseGear,
                                  int gapPx) {
        float clampedWidthScale = Math.max(SurroundGuidanceCalculator.MIN_ROAD_GUIDANCE_WIDTH_SCALE,
                Math.min(SurroundGuidanceCalculator.MAX_ROAD_GUIDANCE_WIDTH_SCALE, roadGuidanceWidthScale));
        synchronized (stateLock) {
            boolean roadChanged = (this.effectiveTopMode != topMode)
                    || (this.stretchRoadViews != stretchRoadViews);
            boolean guideChanged = (this.guidanceEnabled != guidanceEnabled)
                    || (this.effectiveTopMode != topMode)
                    || (Math.abs(this.roadGuidanceWidthScale - clampedWidthScale) > 0.005f)
                    || (Math.abs(this.wheelAngleDeg - wheelAngleDeg) > 0.25f)
                    || (this.inReverseGear != inReverseGear);
            this.effectiveTopMode = topMode;
            this.effectiveBottomMode = bottomMode;
            this.wheelsCollapsed = wheelsCollapsed;
            this.stretchRoadViews = stretchRoadViews;
            this.guidanceEnabled = guidanceEnabled;
            this.roadGuidanceWidthScale = clampedWidthScale;
            this.showCenterCard = showCenterCard;
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

    /** 仅实时更新右上前/后视引导线宽度缩放比（0.50 ~ 1.50）。 */
    public void updateRoadGuidanceWidthScale(float widthScale) {
        float clamped = Math.max(SurroundGuidanceCalculator.MIN_ROAD_GUIDANCE_WIDTH_SCALE,
                Math.min(SurroundGuidanceCalculator.MAX_ROAD_GUIDANCE_WIDTH_SCALE, widthScale));
        synchronized (stateLock) {
            if (Math.abs(this.roadGuidanceWidthScale - clamped) <= 0.005f) {
                return;
            }
            this.roadGuidanceWidthScale = clamped;
            this.guidanceDirty.set(true);
        }
        requestRenderSafe();
    }

    /** 仅更新方向盘转角对应的动态引导线状态（零布局重算、低于 0.25° 不重绘）。 */
    public void updateGuidanceAngle(float wheelAngleDeg) {
        synchronized (stateLock) {
            if (Math.abs(this.wheelAngleDeg - wheelAngleDeg) <= 0.25f) {
                return;
            }
            this.wheelAngleDeg = wheelAngleDeg;
            this.guidanceDirty.set(true);
        }
        requestRenderSafe();
    }

    public void requestRenderSafe() {
        if (!stopped && !released && rendererAttached && hostGlView != null) {
            try {
                hostGlView.requestRender();
            } catch (NullPointerException | IllegalStateException ignored) {
                // GLSurfaceView may already be detached or torn down.
            }
        }
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        if (stopped) {
            return;
        }
        released = false;
        rendererAttached = true;
        GLES20.glClearColor(0.05f, 0.06f, 0.09f, 1.0f);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);

        birdProgram = buildProgram(BIRD_VERTEX_SHADER, BIRD_FRAGMENT_SHADER);
        patchProgram = buildProgram(PATCH_VERTEX_SHADER, PATCH_FRAGMENT_SHADER);
        carProgram = buildProgram(CAR_VERTEX_SHADER, CAR_FRAGMENT_SHADER);
        colorProgram = buildProgram(COLOR_VERTEX_SHADER, COLOR_FRAGMENT_SHADER);

        initOesInputTexturesAndSurfaces();
        initCarTexture();
        synchronized (stateLock) {
            if (pendingCarBitmap == null || pendingCarBitmap.isRecycled()) {
                pendingCarBitmap = SurroundCarModelRepository.loadActiveCarModelBitmap(context, appConfig);
            }
        }
        birdMeshDirty.set(true);
        roadMeshDirty.set(true);
        wheelMeshDirty.set(true);
        carBitmapDirty.set(true);
        guidanceDirty.set(true);
        SurroundCalibrationProfile initProfile = this.activeProfile;
        if (initProfile != null) {
            ensureWheelMeshes(initProfile);
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        this.viewportWidth = Math.max(1, width);
        this.viewportHeight = Math.max(1, height);
        roadMeshDirty.set(true);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        if (stopped || released || viewportWidth <= 0 || viewportHeight <= 0) {
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
        final float roadWidthScale;
        final boolean centerCardVisible;
        final float angleDeg;
        final boolean reverse;
        final int gap;
        final boolean birdOnly = this.birdOnlyPreview;
        synchronized (stateLock) {
            profile = this.activeProfile;
            topMode = this.effectiveTopMode;
            bottomMode = this.effectiveBottomMode;
            collapsed = this.wheelsCollapsed;
            stretched = this.stretchRoadViews;
            showGuidance = this.guidanceEnabled;
            roadWidthScale = this.roadGuidanceWidthScale;
            centerCardVisible = this.showCenterCard;
            angleDeg = this.wheelAngleDeg;
            reverse = this.inReverseGear;
            gap = this.gapPx;
        }

        GLES20.glViewport(0, 0, viewportWidth, viewportHeight);
        GLES20.glClearColor(0.05f, 0.06f, 0.09f, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        if (birdOnly) {
            ViewportBox birdBox = SurroundLayoutCalculator.calculateBirdOnlyBox(
                    viewportWidth,
                    viewportHeight,
                    profile.groundWidthCm,
                    profile.groundLengthCm,
                    profile.groundFrontExtensionCm,
                    gap
            );
            drawBirdViewport(birdBox, profile, showGuidance, angleDeg, reverse);
            return;
        }

        SurroundLayoutSpec layout = SurroundLayoutCalculator.calculate(
                viewportWidth,
                viewportHeight,
                bottomMode,
                collapsed,
                centerCardVisible,
                profile.groundWidthCm,
                profile.groundLengthCm,
                profile.groundFrontExtensionCm,
                gap
        );

        // 1. 左侧 360 全景鸟瞰 + 离线 E5 车模 + 鸟瞰转向引导线
        boolean birdReverse = (topMode == SurroundViewMode.REAR)
                || (reverse && topMode != SurroundViewMode.FRONT);
        drawBirdViewport(layout.birdBox, profile, showGuidance, angleDeg, birdReverse);

        // 2. 右上视角（随档位 / 前视 / 后视 / 前轮 / 后轮）
        drawTopDetailViewport(layout.topDetailBox, profile, topMode, stretched, showGuidance,
                angleDeg, roadWidthScale, reverse);

        // 3. 右下双侧轮位 / 左右侧视（未折叠时）
        if (!layout.wheelsCollapsed) {
            drawBottomDetailViewports(layout, profile, bottomMode);
        }
    }

    private void drawBirdViewport(@NonNull ViewportBox box,
                                  @NonNull SurroundCalibrationProfile profile,
                                  boolean showGuidance,
                                  float angleDeg,
                                  boolean isReverse) {
        if (box.isEmpty() || birdProgram == 0) return;
        ensureBirdMesh(profile);
        uploadCarTextureIfNeeded();
        updateCarQuad(profile);
        if (birdVertexBuffer == null || birdIndexBuffer == null || birdIndexCount <= 0) return;

        int glY = box.glY(viewportHeight);
        GLES20.glViewport(box.left, glY, box.width, box.height);
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
        GLES20.glScissor(box.left, glY, box.width, box.height);
        try {
            GLES20.glUseProgram(birdProgram);

            float scaleX = 1.0f;
            float offsetX = 0.0f;
            float scaleY = 1.0f;
            float offsetY = 0.0f;
            if (birdMeshIsWorldCm) {
                double halfW = Math.max(50.0, profile.getHalfWorldWidthCm());
                double minY = profile.getWorldMinYCm();
                double maxY = profile.getWorldMaxYCm();
                double spanY = Math.max(100.0, maxY - minY);
                scaleX = (float) (1.0 / halfW);
                scaleY = (float) (2.0 / spanY);
                offsetY = (float) (-(maxY + minY) / spanY);
            }
            GLES20.glUniform4f(GLES20.glGetUniformLocation(birdProgram, "uViewTransform"),
                    scaleX, offsetX, scaleY, offsetY);

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
                ensureBirdGuidanceBatch(profile, angleDeg, isReverse);
                drawGuidanceBatch(birdGuidanceBuffer, birdGuidanceCounts);
            }

            // 叠绘离线 E5 俯视车模纹理（BitmapFactory / Canvas 均输出预乘 Alpha，需用 GL_ONE 避免二次乘 Alpha 变暗）
            if (carProgram != 0 && carTextureId != 0 && carQuadBuffer != null) {
                GLES20.glEnable(GLES20.GL_BLEND);
                GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
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
        } finally {
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        }
    }

    private void drawTopDetailViewport(@NonNull ViewportBox box,
                                       @NonNull SurroundCalibrationProfile profile,
                                       @NonNull SurroundViewMode topMode,
                                       boolean stretchFill,
                                       boolean showGuidance,
                                       float angleDeg,
                                       float roadWidthScale,
                                       boolean isReverse) {
        if (box.isEmpty()) return;
        GLES20.glViewport(box.left, box.glY(viewportHeight), box.width, box.height);

        if (topMode == SurroundViewMode.FRONT_WHEELS || topMode == SurroundViewMode.REAR_WHEELS) {
            ensureWheelMeshes(profile);
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
        ensureRoadMesh(profile, isRearRoad, box.aspectRatio(), stretchFill);

        drawPatchMesh(roadVertexBuffer, roadIndexBuffer, roadIndexCount, camSlot, camPos);

        if (showGuidance) {
            ensureRoadGuidanceBatch(angleDeg, isRearRoad, roadWidthScale);
            drawGuidanceBatch(roadGuidanceBuffer, roadGuidanceCounts);
        }
    }

    private void drawBottomDetailViewports(@NonNull SurroundLayoutSpec layout,
                                           @NonNull SurroundCalibrationProfile profile,
                                           @NonNull SurroundViewMode bottomMode) {
        ensureWheelMeshes(profile);
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
        if (patchProgram == 0 || indexCount <= 0) return;
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
        if (colorProgram == 0 || vb == null || stripCounts == null || stripCounts.length == 0) return;
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

        if (stopped) {
            releaseGlResources();
            return;
        }
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
        boolean dirty = birdMeshDirty.compareAndSet(true, false);
        String assetKey = resolveCalibrationAssetKey(profile, true);
        if (!dirty && birdVertexBuffer != null
                && assetKey.equals(cachedBirdAssetKey)
                && (birdMeshIsWorldCm || cachedBirdProfile == profile)) {
            return;
        }
        byte[] rawBird = profile.hasExtrinsicAdjustments ? null : loadCalibrationMeshBytes(
                profile,
                "bird.mesh",
                SurroundCalibrationManager.CUSTOM_BIRD_MESH_FILE_NAME);
        GridMeshData parsedBird = rawBird != null
                ? SurroundMeshBuilder.parseBirdMeshBinary(rawBird, SurroundCalibrationProfile.DEFAULT_BIRD_CENTER_OFFSET_Y_CM)
                : null;
        if (parsedBird != null) {
            birdVertexBuffer = toFloatBuffer(parsedBird.vertices, birdVertexBuffer);
            birdIndexBuffer = toShortBuffer(parsedBird.indices, birdIndexBuffer);
            birdIndexCount = parsedBird.indices.length;
            birdMeshIsWorldCm = true;
        } else {
            GridMeshData mesh = SurroundMeshBuilder.buildBirdGroundMesh(profile, 44, 68);
            birdVertexBuffer = toFloatBuffer(mesh.vertices, birdVertexBuffer);
            birdIndexBuffer = toShortBuffer(mesh.indices, birdIndexBuffer);
            birdIndexCount = mesh.indices.length;
            birdMeshIsWorldCm = false;
        }
        cachedBirdAssetKey = assetKey;
        cachedBirdProfile = profile;
    }

    private void ensureRoadMesh(@NonNull SurroundCalibrationProfile profile,
                                boolean isRear,
                                float aspect,
                                boolean stretched) {
        boolean needRebuild = roadMeshDirty.compareAndSet(true, false)
                || roadVertexBuffer == null
                || cachedRoadProfile != profile
                || cachedRoadIsRear != isRear
                || cachedRoadStretched != stretched
                || Math.abs(cachedRoadAspect - aspect) > 0.03f;
        if (!needRebuild) return;
        GridMeshData mesh = SurroundMeshBuilder.buildRoadPatchMesh(profile, isRear, aspect, stretched, 28, 20);
        roadVertexBuffer = toFloatBuffer(mesh.vertices, roadVertexBuffer);
        roadIndexBuffer = toShortBuffer(mesh.indices, roadIndexBuffer);
        roadIndexCount = mesh.indices.length;
        cachedRoadProfile = profile;
        cachedRoadIsRear = isRear;
        cachedRoadStretched = stretched;
        cachedRoadAspect = aspect;
    }

    private void ensureWheelMeshes(@NonNull SurroundCalibrationProfile profile) {
        boolean dirty = wheelMeshDirty.compareAndSet(true, false);
        String assetKey = resolveCalibrationAssetKey(profile, false);
        if (!dirty && wheelVertexBuffers[0] != null
                && assetKey.equals(cachedWheelAssetKey)) {
            return;
        }
        byte[] rawFront = loadCalibrationMeshBytes(
                profile,
                "front_wheels.mesh",
                SurroundCalibrationManager.CUSTOM_FRONT_WHEELS_MESH_FILE_NAME);
        byte[] rawRear = loadCalibrationMeshBytes(
                profile,
                "rear_wheels.mesh",
                SurroundCalibrationManager.CUSTOM_REAR_WHEELS_MESH_FILE_NAME);
        GridMeshData[] frontPair = rawFront != null ? SurroundMeshBuilder.parseWheelMeshPair(rawFront) : null;
        GridMeshData[] rearPair = rawRear != null ? SurroundMeshBuilder.parseWheelMeshPair(rawRear) : null;

        GridMeshData[] resolved = new GridMeshData[4];
        resolved[0] = (frontPair != null) ? frontPair[0] : SurroundMeshBuilder.buildWheelPatchMesh(profile, 0, 22, 18);
        resolved[1] = (frontPair != null) ? frontPair[1] : SurroundMeshBuilder.buildWheelPatchMesh(profile, 1, 22, 18);
        resolved[2] = (rearPair != null) ? rearPair[1] : SurroundMeshBuilder.buildWheelPatchMesh(profile, 2, 22, 18);
        resolved[3] = (rearPair != null) ? rearPair[0] : SurroundMeshBuilder.buildWheelPatchMesh(profile, 3, 22, 18);

        for (int k = 0; k < 4; k++) {
            wheelVertexBuffers[k] = toFloatBuffer(resolved[k].vertices, wheelVertexBuffers[k]);
            wheelIndexBuffers[k] = toShortBuffer(resolved[k].indices, wheelIndexBuffers[k]);
            wheelIndexCounts[k] = resolved[k].indices.length;
        }
        cachedWheelAssetKey = assetKey;
        cachedWheelProfile = profile;
    }

    @NonNull
    private String resolveCalibrationAssetKey(@NonNull SurroundCalibrationProfile profile, boolean includeTunedFlag) {
        String baseKey;
        if (!profile.isCustomCalibration && profile.useBuiltInMeshes) {
            baseKey = "builtin";
        } else if (profile.isCustomCalibration) {
            String customPath = appConfig.getSurroundCustomCalibrationPath();
            baseKey = "custom|" + customPath + "|" + buildFileStamp(customPath);
        } else {
            baseKey = "procedural|" + profile.profileName;
        }
        if (!includeTunedFlag) {
            return baseKey;
        }
        return baseKey + (profile.hasExtrinsicAdjustments ? "|tuned" : "|raw");
    }

    @Nullable
    private byte[] loadCalibrationMeshBytes(@NonNull SurroundCalibrationProfile profile,
                                            @NonNull String builtInAssetName,
                                            @NonNull String customFileName) {
        if (!profile.isCustomCalibration && profile.useBuiltInMeshes) {
            try (InputStream is = context.getAssets().open(
                    SurroundMeshBuilder.BUILTIN_MESH_ASSET_DIR + "/" + builtInAssetName)) {
                return readAllBytesBounded(is, 4 * 1024 * 1024);
            } catch (IOException e) {
                AppLog.w(TAG, "Built-in mesh load fallback (" + builtInAssetName + "): " + e.getMessage());
                return null;
            }
        }
        if (profile.isCustomCalibration) {
            String calibPath = appConfig.getSurroundCustomCalibrationPath();
            if (!calibPath.isEmpty()) {
                File parent = new File(calibPath).getParentFile();
                if (parent != null) {
                    File meshFile = new File(parent, customFileName);
                    if (meshFile.isFile() && meshFile.length() >= 32 && meshFile.length() <= 4L * 1024L * 1024L) {
                        try (FileInputStream fis = new FileInputStream(meshFile)) {
                            return readAllBytesBounded(fis, 4 * 1024 * 1024);
                        } catch (IOException e) {
                            AppLog.w(TAG, "Custom mesh load fallback (" + customFileName + "): " + e.getMessage());
                        }
                    }
                }
            }
        }
        return null;
    }

    @Nullable
    private static byte[] readAllBytesBounded(@NonNull InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > maxBytes) {
                return null;
            }
            bos.write(buf, 0, n);
        }
        return total > 0 ? bos.toByteArray() : null;
    }

    private void ensureBirdGuidanceBatch(@NonNull SurroundCalibrationProfile profile,
                                         float angleDeg,
                                         boolean isReverse) {
        boolean dirty = guidanceDirty.compareAndSet(true, false);
        boolean unchanged = !dirty
                && birdGuidanceBuffer != null
                && cachedBirdGuidanceProfile == profile
                && cachedBirdGuidanceReverse == isReverse
                && Math.abs(cachedBirdGuidanceAngle - angleDeg) <= 0.25f;
        if (unchanged) {
            return;
        }
        LineStripBatch birdBatch = SurroundGuidanceCalculator.buildBirdGuidanceStrips(angleDeg, isReverse, profile, 18);
        birdGuidanceBuffer = toFloatBuffer(birdBatch.triangleStripVertices, birdGuidanceBuffer);
        birdGuidanceCounts = birdBatch.stripVertexCounts;
        cachedBirdGuidanceProfile = profile;
        cachedBirdGuidanceAngle = angleDeg;
        cachedBirdGuidanceReverse = isReverse;
    }

    private void ensureRoadGuidanceBatch(float angleDeg, boolean isReverse, float widthScale) {
        boolean unchanged = roadGuidanceBuffer != null
                && cachedRoadGuidanceReverse == isReverse
                && Math.abs(cachedRoadGuidanceWidthScale - widthScale) <= 0.005f
                && Math.abs(cachedRoadGuidanceAngle - angleDeg) <= 0.25f;
        if (unchanged) {
            return;
        }
        LineStripBatch roadBatch = SurroundGuidanceCalculator.buildRoadGuidanceStrips(
                angleDeg, isReverse, widthScale, 18);
        roadGuidanceBuffer = toFloatBuffer(roadBatch.triangleStripVertices, roadGuidanceBuffer);
        roadGuidanceCounts = roadBatch.stripVertexCounts;
        cachedRoadGuidanceAngle = angleDeg;
        cachedRoadGuidanceWidthScale = widthScale;
        cachedRoadGuidanceReverse = isReverse;
    }

    /** 退出模式 A 或退后台时释放全部 GL 资源、SurfaceTexture 与 OES 纹理，保证非模式 A 下零驻留。 */
    public void releaseGlResources() {
        released = true;
        rendererAttached = false;
        releaseSurfacesOnly();
        try {
            for (int i = 0; i < 4; i++) {
                if (oesTextureIds[i] != 0) {
                    GLES20.glDeleteTextures(1, oesTextureIds, i);
                    oesTextureIds[i] = 0;
                }
            }
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

    /** 兜底释放 SurfaceTexture/Surface（例如窗口提前分离、GL 线程无法再调度 queueEvent 时）。 */
    public void releaseSurfacesFallback() {
        released = true;
        rendererAttached = false;
        releaseSurfacesOnly();
    }

    private void releaseSurfacesOnly() {
        synchronized (surfaceReleaseLock) {
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
        buf.flip();
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
        buf.flip();
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
