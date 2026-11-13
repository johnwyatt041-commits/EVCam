package com.kooo.evcam.camera;

import android.graphics.SurfaceTexture;
import android.opengl.*;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Surface;
import com.kooo.evcam.AppLog;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;

/** Latest-frame mosaic feeding one recorder; no CPU image readback and no synthetic duplicate fps. */
final class MultiCameraCompositor implements AutoCloseable {
    private static final String VS = "attribute vec4 aPosition;attribute vec2 aUv;uniform mat4 uMatrix;"
            + "varying vec2 vUv;void main(){gl_Position=aPosition;vUv=(uMatrix*vec4(aUv,0.,1.)).xy;}";
    private static final String FS = "#extension GL_OES_EGL_image_external : require\n"
            + "precision mediump float;varying vec2 vUv;uniform samplerExternalOES sImage;"
            + "void main(){gl_FragColor=texture2D(sImage,vUv);}";
    private static final class Input {
        final String name;
        final Size size;
        final float[] matrix = new float[16];
        SurfaceTexture texture;
        Surface surface;
        int id;
        boolean pending;
        long lastLatch;
        long callbacks;
        Input(String name, Size size) { this.name = name; this.size = size; }
    }
    private final Map<String, Input> inputs = new LinkedHashMap<>();
    private final HandlerThread thread = new HandlerThread("RecordingCompositor");
    private final Handler handler;
    private final int width, height;
    private final long intervalNs;
    private final FloatBuffer positions = floats(new float[]{-1,-1,1,-1,-1,1,1,1});
    private final FloatBuffer uv = floats(new float[]{0,0,1,0,0,1,1,1});
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface window = EGL14.EGL_NO_SURFACE;
    private int program, positionLocation, uvLocation, matrixLocation, samplerLocation;
    private boolean drawQueued;
    private boolean paused;
    private volatile boolean closed;
    private long lastDraw, frames, maxSwapUs, lastLog, firstLatch;

    MultiCameraCompositor(Surface output, Map<String, Size> sources, int width, int height, int fps)
            throws Exception {
        this.width = width; this.height = height; intervalNs = 1_000_000_000L / fps;
        for (String name : new String[]{"left", "back", "right", "front"}) {
            if (sources.containsKey(name)) inputs.put(name, new Input(name, sources.get(name)));
        }
        if (inputs.isEmpty()) throw new IllegalArgumentException("No composite inputs");
        thread.start(); handler = new Handler(thread.getLooper());
        try { onGl(() -> initialize(output)); }
        catch (Exception e) { close(); throw e; }
    }

    Surface getInput(String camera) { Input input = inputs.get(camera); return input == null ? null : input.surface; }
    void pause() { try { onGl(() -> paused = true); } catch (Exception e) { AppLog.w("Compositor", "Pause failed"); } }

    private void initialize(Surface output) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (!EGL14.eglInitialize(display, new int[2], 0, new int[2], 0)) throw new IllegalStateException("EGL init failed");
        int[] attrs = {EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,
                EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT,
                EGLExt.EGL_RECORDABLE_ANDROID,1,EGL14.EGL_NONE};
        EGLConfig[] config = new EGLConfig[1]; int[] count = new int[1];
        if (!EGL14.eglChooseConfig(display, attrs, 0, config, 0, 1, count, 0) || count[0] == 0)
            throw new IllegalStateException("No compositor EGL config");
        context = EGL14.eglCreateContext(display, config[0], EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE},0);
        window = EGL14.eglCreateWindowSurface(display, config[0], output, new int[]{EGL14.EGL_NONE},0);
        if (!EGL14.eglMakeCurrent(display, window, window, context)) throw new IllegalStateException("Compositor EGL bind failed");
        int vertex = shader(GLES20.GL_VERTEX_SHADER, VS), fragment = shader(GLES20.GL_FRAGMENT_SHADER, FS);
        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertex); GLES20.glAttachShader(program, fragment);
        GLES20.glLinkProgram(program); GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment);
        int[] status = new int[1]; GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,status,0);
        if (status[0] == 0) throw new IllegalStateException("Compositor shader link failed");
        positionLocation=GLES20.glGetAttribLocation(program,"aPosition"); uvLocation=GLES20.glGetAttribLocation(program,"aUv");
        matrixLocation=GLES20.glGetUniformLocation(program,"uMatrix"); samplerLocation=GLES20.glGetUniformLocation(program,"sImage");
        for (Input input : inputs.values()) {
            int[] texture = new int[1]; GLES20.glGenTextures(1,texture,0); input.id=texture[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,input.id);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
            input.texture = new SurfaceTexture(input.id);
            input.texture.setDefaultBufferSize(input.size.getWidth(),input.size.getHeight());
            input.surface = new Surface(input.texture);
            input.texture.setOnFrameAvailableListener(ignored -> {
                if (closed) return;
                input.pending=true; input.callbacks++;
                if (!drawQueued) {
                    drawQueued=true;
                    long remaining = Math.max(0,lastDraw+intervalNs-System.nanoTime());
                    handler.postDelayed(this::draw,(remaining+999999)/1000000);
                }
            },handler);
        }
    }

    private void draw() {
        drawQueued=false;
        if (closed) return;
        try {
            if (!EGL14.eglMakeCurrent(display,window,window,context)) throw new IllegalStateException("Composite EGL lost");
            long now=System.nanoTime(); boolean updated=false;
            for (Input input : inputs.values()) if (input.pending) {
                input.pending=false; input.texture.updateTexImage(); input.texture.getTransformMatrix(input.matrix);
                input.lastLatch=now; updated=true;
            }
            if (paused || !updated) return;
            if (firstLatch==0) firstLatch=now;
            // Normally begin with all requested views. Allow a bounded startup grace for a
            // missing camera, after which that tile stays visibly black rather than duplicating another view.
            if (frames==0 && now-firstLatch<1_500_000_000L) {
                for (Input input : inputs.values()) if (input.lastLatch==0) return;
            }
            lastDraw=now;
            GLES20.glViewport(0,0,width,height); GLES20.glClearColor(0,0,0,1); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(program); GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glUniform1i(samplerLocation,0);
            positions.position(0); uv.position(0);
            GLES20.glEnableVertexAttribArray(positionLocation); GLES20.glEnableVertexAttribArray(uvLocation);
            GLES20.glVertexAttribPointer(positionLocation,2,GLES20.GL_FLOAT,false,0,positions);
            GLES20.glVertexAttribPointer(uvLocation,2,GLES20.GL_FLOAT,false,0,uv);
            int cellWidth=width/inputs.size(), index=0;
            for (Input input : inputs.values()) {
                if (input.lastLatch>0 && now-input.lastLatch<1_500_000_000L) {
                    float scale=Math.min((float)cellWidth/input.size.getWidth(),(float)height/input.size.getHeight());
                    int w=Math.max(1,Math.round(input.size.getWidth()*scale)), h=Math.max(1,Math.round(input.size.getHeight()*scale));
                    GLES20.glViewport(index*cellWidth+(cellWidth-w)/2,(height-h)/2,w,h);
                    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,input.id);
                    GLES20.glUniformMatrix4fv(matrixLocation,1,false,input.matrix,0);
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
                }
                index++;
            }
            GLES20.glDisableVertexAttribArray(positionLocation); GLES20.glDisableVertexAttribArray(uvLocation);
            EGLExt.eglPresentationTimeANDROID(display,window,now);
            long swap=System.nanoTime();
            if (!EGL14.eglSwapBuffers(display,window)) throw new IllegalStateException("Composite swap failed");
            maxSwapUs=Math.max(maxSwapUs,(System.nanoTime()-swap)/1000); frames++;
            if (now-lastLog>10_000_000_000L) {
                StringBuilder counts=new StringBuilder();
                for (Input input : inputs.values()) counts.append(input.name).append('=').append(input.callbacks).append(';');
                AppLog.i("Compositor","size="+width+"x"+height+", frames="+frames+", inputs="+counts+", maxSwapUs="+maxSwapUs);
                lastLog=now;
            }
        } catch (Exception e) { paused=true; AppLog.e("Compositor","Composite output paused after failure",e); }
    }

    private void onGl(Runnable action) throws Exception {
        FutureTask<Void> task=new FutureTask<>(action,null);
        if (!handler.post(task)) throw new IllegalStateException("Compositor thread stopped");
        task.get(10,TimeUnit.SECONDS);
    }
    private static FloatBuffer floats(float[] values) {
        FloatBuffer buffer=ByteBuffer.allocateDirect(values.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        buffer.put(values).position(0); return buffer;
    }
    private static int shader(int type,String source) {
        int id=GLES20.glCreateShader(type); GLES20.glShaderSource(id,source); GLES20.glCompileShader(id);
        int[] ok=new int[1]; GLES20.glGetShaderiv(id,GLES20.GL_COMPILE_STATUS,ok,0);
        if (ok[0]==0) { GLES20.glDeleteShader(id); throw new IllegalStateException("Compositor shader failed"); }
        return id;
    }
    @Override public void close() {
        if (closed) return;
        closed=true;
        try { onGl(() -> {
            handler.removeCallbacksAndMessages(null);
            for (Input input : inputs.values()) {
                if (input.surface!=null) input.surface.release();
                if (input.texture!=null) input.texture.release();
                if (input.id!=0) GLES20.glDeleteTextures(1,new int[]{input.id},0);
            }
            if (program!=0) GLES20.glDeleteProgram(program);
            EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);
            if (window!=EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,window);
            if (context!=EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display,context);
            if (display!=EGL14.EGL_NO_DISPLAY) EGL14.eglTerminate(display);
        }); } catch (Exception e) { AppLog.w("Compositor","Compositor close failed: "+e.getMessage()); }
        thread.quitSafely();
        try { thread.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
