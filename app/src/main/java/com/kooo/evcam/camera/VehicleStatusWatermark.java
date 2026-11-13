package com.kooo.evcam.camera;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import com.kooo.evcam.vehicle.RecordingVehicleSource;
import com.kooo.evcam.vehicle.VehicleStatus;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** Small output-space overlay. Created and released on the existing encoder GL thread. */
final class VehicleStatusWatermark implements AutoCloseable {
    private static final int WIDTH = 640;
    private static final int HEIGHT = 44;
    private static final String VERTEX = "attribute vec4 aPosition; attribute vec2 aUv;"
            + "varying vec2 vUv; void main(){gl_Position=aPosition;vUv=aUv;}";
    private static final String FRAGMENT = "precision mediump float; varying vec2 vUv;"
            + "uniform sampler2D sImage; void main(){gl_FragColor=texture2D(sImage,vUv);}";
    private final Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
    private final Canvas canvas = new Canvas(bitmap);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final FloatBuffer vertices = buffer(new float[]{-1,-1, 1,-1, -1,1, 1,1});
    // Bitmap row zero is the top of the overlay, independent of the camera transform.
    private final FloatBuffer uv = buffer(new float[]{0,1, 1,1, 0,0, 1,0});
    private RecordingVehicleSource.Lease lease;
    private VehicleStatus previous;
    private int program;
    private int texture;
    private int position;
    private int coordinates;
    private int sampler;
    private boolean uploaded;
    private java.util.function.Supplier<VehicleStatus> statusSupplier;

    VehicleStatusWatermark() {
        this(null);
    }

    VehicleStatusWatermark(java.util.function.Supplier<VehicleStatus> supplier) {
        text.setColor(Color.WHITE);
        text.setTextSize(23);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        try {
            int vertex = shader(GLES20.GL_VERTEX_SHADER, VERTEX);
            int fragment;
            try { fragment = shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT); }
            catch (RuntimeException e) { GLES20.glDeleteShader(vertex); throw e; }
            program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, vertex);
            GLES20.glAttachShader(program, fragment);
            GLES20.glLinkProgram(program);
            GLES20.glDeleteShader(vertex);
            GLES20.glDeleteShader(fragment);
            int[] linked = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
            if (linked[0] == 0) throw new IllegalStateException("Status watermark shader link failed");
            position = GLES20.glGetAttribLocation(program, "aPosition");
            coordinates = GLES20.glGetAttribLocation(program, "aUv");
            sampler = GLES20.glGetUniformLocation(program, "sImage");
            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            texture = textures[0];
            if (texture == 0) throw new IllegalStateException("Status watermark texture unavailable");
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            if (supplier == null) {
                lease = RecordingVehicleSource.acquire();
                statusSupplier = lease::snapshot;
            } else statusSupplier = supplier;
        } catch (RuntimeException e) { close(); throw e; }
    }

    void draw(int outputWidth, int outputHeight) {
        VehicleStatus status = statusSupplier.get();
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        if (status != previous) {
            bitmap.eraseColor(Color.argb(170, 12, 16, 22));
            canvas.drawText(status.displayText(), 10, 30, text);
            if (uploaded) GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap);
            else {
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
                uploaded = true;
            }
            previous = status;
        }
        int margin = Math.min(12, Math.min(outputWidth, outputHeight) / 16);
        int displayWidth = Math.min(WIDTH, Math.max(1, outputWidth - margin * 2));
        int displayHeight = Math.min(Math.max(1, outputHeight - margin * 2),
                Math.max(1, HEIGHT * displayWidth / WIDTH));
        GLES20.glViewport(margin, margin, displayWidth, displayHeight);
        GLES20.glUseProgram(program);
        GLES20.glUniform1i(sampler, 0);
        vertices.position(0);
        uv.position(0);
        GLES20.glEnableVertexAttribArray(position);
        GLES20.glEnableVertexAttribArray(coordinates);
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, vertices);
        GLES20.glVertexAttribPointer(coordinates, 2, GLES20.GL_FLOAT, false, 0, uv);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glDisableVertexAttribArray(position);
        GLES20.glDisableVertexAttribArray(coordinates);
        GLES20.glViewport(0, 0, outputWidth, outputHeight);
    }

    private static FloatBuffer buffer(float[] values) {
        FloatBuffer b = ByteBuffer.allocateDirect(values.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(values).position(0);
        return b;
    }

    private static int shader(int type, String source) {
        int id = GLES20.glCreateShader(type);
        GLES20.glShaderSource(id, source);
        GLES20.glCompileShader(id);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            GLES20.glDeleteShader(id);
            throw new IllegalStateException("Status watermark shader compilation failed");
        }
        return id;
    }

    @Override public void close() {
        if (lease != null) { lease.close(); lease = null; }
        if (texture != 0) { GLES20.glDeleteTextures(1, new int[]{texture}, 0); texture = 0; }
        if (program != 0) { GLES20.glDeleteProgram(program); program = 0; }
        if (!bitmap.isRecycled()) bitmap.recycle();
    }
}
