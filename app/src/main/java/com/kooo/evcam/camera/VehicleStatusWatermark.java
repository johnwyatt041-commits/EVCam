package com.kooo.evcam.camera;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.opengl.GLES20;
import android.opengl.GLUtils;

import com.kooo.evcam.vehicle.RecordingVehicleSource;
import com.kooo.evcam.vehicle.VehicleSignalHub;
import com.kooo.evcam.vehicle.VehicleSignalSnapshot;
import com.kooo.evcam.vehicle.VehicleStatus;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.function.Supplier;

/**
 * 3.3 合并单纹理视频水印渲染层（日期时间 / 自定义文字 / 车速 / 档位 / 转向灯，四角落 + 卡片/阴影双样式）。
 * 在编码器现有 EGL 上下文中按秒或车辆状态变化懒更新 texSubImage2D，零中间 FBO、关闭即释放零残留。
 */
public final class VehicleStatusWatermark implements AutoCloseable {

    public static final int MAX_CUSTOM_TEXT_LENGTH = 40;
    public static final int TEXTURE_WIDTH = 960;
    public static final int TEXTURE_HEIGHT = 92;

    public enum Position {
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT;

        public static Position fromKey(String key) {
            if (key == null) return BOTTOM_RIGHT;
            switch (key) {
                case "TOP_LEFT":
                    return TOP_LEFT;
                case "TOP_RIGHT":
                    return TOP_RIGHT;
                case "BOTTOM_LEFT":
                    return BOTTOM_LEFT;
                case "BOTTOM_RIGHT":
                default:
                    return BOTTOM_RIGHT;
            }
        }
    }

    public enum Style {
        CARD,
        TEXT;

        public static Style fromKey(String key) {
            if ("TEXT".equals(key)) {
                return TEXT;
            }
            return CARD;
        }
    }

    /** 不可变水印显示配置快照。 */
    public static final class WatermarkOptions {
        public final boolean showDateTime;
        public final boolean showCustomText;
        public final String customText;
        public final boolean showSpeed;
        public final boolean showGear;
        public final boolean showTurnSignal;
        public final Position position;
        public final Style style;

        public WatermarkOptions(
                boolean showDateTime,
                boolean showCustomText,
                String customText,
                boolean showSpeed,
                boolean showGear,
                boolean showTurnSignal,
                Position position,
                Style style) {
            this.showDateTime = showDateTime;
            this.showCustomText = showCustomText;
            this.customText = normalizeCustomText(customText);
            this.showSpeed = showSpeed;
            this.showGear = showGear;
            this.showTurnSignal = showTurnSignal;
            this.position = position != null ? position : Position.BOTTOM_RIGHT;
            this.style = style != null ? style : Style.CARD;
        }

        public static WatermarkOptions disabled() {
            return new WatermarkOptions(false, false, "", false, false, false, Position.BOTTOM_RIGHT, Style.CARD);
        }

        public static WatermarkOptions legacyVehicleStatusOnly() {
            return new WatermarkOptions(false, false, "", true, true, true, Position.BOTTOM_LEFT, Style.CARD);
        }

        public boolean hasAnyEnabled() {
            return showDateTime
                    || (showCustomText && !customText.isEmpty())
                    || showSpeed
                    || showGear
                    || showTurnSignal;
        }

        public boolean needsVehicleSource() {
            return showSpeed || showGear || showTurnSignal;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof WatermarkOptions)) return false;
            WatermarkOptions that = (WatermarkOptions) o;
            return showDateTime == that.showDateTime
                    && showCustomText == that.showCustomText
                    && showSpeed == that.showSpeed
                    && showGear == that.showGear
                    && showTurnSignal == that.showTurnSignal
                    && customText.equals(that.customText)
                    && position == that.position
                    && style == that.style;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(
                    showDateTime,
                    showCustomText,
                    customText,
                    showSpeed,
                    showGear,
                    showTurnSignal,
                    position,
                    style);
        }
    }

    private static final String VERTEX = "attribute vec4 aPosition; attribute vec2 aUv;"
            + "varying vec2 vUv; void main(){gl_Position=aPosition;vUv=aUv;}";
    private static final String FRAGMENT = "precision mediump float; varying vec2 vUv;"
            + "uniform sampler2D sImage; void main(){gl_FragColor=texture2D(sImage,vUv);}";

    private final Bitmap bitmap = Bitmap.createBitmap(TEXTURE_WIDTH, TEXTURE_HEIGHT, Bitmap.Config.ARGB_8888);
    private final Canvas canvas = new Canvas(bitmap);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF cardRect = new RectF();
    private final FloatBuffer vertices = buffer(new float[]{-1, -1, 1, -1, -1, 1, 1, 1});
    // 位图第 0 行在顶部，与相机旋转矩阵无关
    private final FloatBuffer uv = buffer(new float[]{0, 1, 1, 1, 0, 0, 1, 0});

    private RecordingVehicleSource.Lease lease;
    private WatermarkOptions options = WatermarkOptions.legacyVehicleStatusOnly();
    private boolean renderedOnce;
    private boolean optionsDirty = true;
    private long lastRenderedEpochSecond = Long.MIN_VALUE;
    private int lastRenderedSpeedKmh = Integer.MIN_VALUE;
    private VehicleSignalSnapshot.Gear lastRenderedGear;
    private int lastRenderedTurnLeft = Integer.MIN_VALUE;
    private int lastRenderedTurnRight = Integer.MIN_VALUE;
    private final int[] cachedViewport = new int[4];
    private int lastViewportOutputWidth = -1;
    private int lastViewportOutputHeight = -1;
    private Position lastViewportPosition;
    private int program;
    private int texture;
    private int positionAttr;
    private int coordinatesAttr;
    private int samplerUniform;
    private boolean uploaded;
    private Supplier<VehicleStatus> statusSupplier;
    private Supplier<VehicleSignalSnapshot.Gear> gearSupplier;

    public VehicleStatusWatermark() {
        this((Supplier<VehicleStatus>) null, null, WatermarkOptions.legacyVehicleStatusOnly());
    }

    public VehicleStatusWatermark(WatermarkOptions initialOptions) {
        this(null, null, initialOptions != null ? initialOptions : WatermarkOptions.legacyVehicleStatusOnly());
    }

    VehicleStatusWatermark(Supplier<VehicleStatus> supplier) {
        this(supplier, null, WatermarkOptions.legacyVehicleStatusOnly());
    }

    VehicleStatusWatermark(
            Supplier<VehicleStatus> supplier,
            Supplier<VehicleSignalSnapshot.Gear> gearSupplier,
            WatermarkOptions initialOptions) {
        this.options = initialOptions != null ? initialOptions : WatermarkOptions.legacyVehicleStatusOnly();
        this.statusSupplier = supplier;
        this.gearSupplier = gearSupplier != null
                ? gearSupplier
                : () -> VehicleSignalHub.getInstance().getSnapshot().gear;

        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(23f);
        textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));

        cardFillPaint.setColor(Color.argb(164, 12, 16, 22));
        cardFillPaint.setStyle(Paint.Style.FILL);

        cardStrokePaint.setColor(Color.argb(46, 255, 255, 255));
        cardStrokePaint.setStyle(Paint.Style.STROKE);
        cardStrokePaint.setStrokeWidth(1.5f);

        try {
            int vertex = shader(GLES20.GL_VERTEX_SHADER, VERTEX);
            int fragment;
            try {
                fragment = shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT);
            } catch (RuntimeException e) {
                GLES20.glDeleteShader(vertex);
                throw e;
            }
            program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, vertex);
            GLES20.glAttachShader(program, fragment);
            GLES20.glLinkProgram(program);
            GLES20.glDeleteShader(vertex);
            GLES20.glDeleteShader(fragment);
            int[] linked = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
            if (linked[0] == 0) {
                throw new IllegalStateException("Status watermark shader link failed");
            }
            positionAttr = GLES20.glGetAttribLocation(program, "aPosition");
            coordinatesAttr = GLES20.glGetAttribLocation(program, "aUv");
            samplerUniform = GLES20.glGetUniformLocation(program, "sImage");
            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            texture = textures[0];
            if (texture == 0) {
                throw new IllegalStateException("Status watermark texture unavailable");
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            ensureVehicleSourceLeaseIfNeeded();
        } catch (RuntimeException e) {
            close();
            throw e;
        }
    }

    public void setOptions(WatermarkOptions newOptions) {
        WatermarkOptions effective = newOptions != null ? newOptions : WatermarkOptions.disabled();
        if (!effective.equals(this.options)) {
            this.options = effective;
            this.optionsDirty = true;
            ensureVehicleSourceLeaseIfNeeded();
        }
    }

    private void ensureVehicleSourceLeaseIfNeeded() {
        if (options.needsVehicleSource()) {
            if (statusSupplier == null && lease == null) {
                lease = RecordingVehicleSource.acquire();
                statusSupplier = lease::snapshot;
            }
        } else if (lease != null) {
            lease.close();
            lease = null;
            statusSupplier = null;
        }
    }

    public void draw(int outputWidth, int outputHeight) {
        if (!options.hasAnyEnabled() || outputWidth <= 0 || outputHeight <= 0) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        long epochSecond = options.showDateTime ? (nowMs / 1000L) : 0L;
        VehicleStatus status = (statusSupplier != null && (options.showSpeed || options.showTurnSignal))
                ? statusSupplier.get()
                : VehicleStatus.UNKNOWN;
        if (status == null) {
            status = VehicleStatus.UNKNOWN;
        }
        VehicleSignalSnapshot.Gear gear = (options.showGear && gearSupplier != null)
                ? gearSupplier.get()
                : VehicleSignalSnapshot.Gear.UNKNOWN;
        if (gear == null) {
            gear = VehicleSignalSnapshot.Gear.UNKNOWN;
        }

        int speedVal = options.showSpeed ? status.speedKmh : 0;
        VehicleSignalSnapshot.Gear gearVal = options.showGear ? gear : VehicleSignalSnapshot.Gear.UNKNOWN;
        int leftVal = options.showTurnSignal ? status.left : 0;
        int rightVal = options.showTurnSignal ? status.right : 0;

        boolean stateChanged = !renderedOnce
                || optionsDirty
                || epochSecond != lastRenderedEpochSecond
                || speedVal != lastRenderedSpeedKmh
                || gearVal != lastRenderedGear
                || leftVal != lastRenderedTurnLeft
                || rightVal != lastRenderedTurnRight;

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);

        if (stateChanged) {
            String dateTimeText = options.showDateTime ? TimestampTextCache.get(nowMs) : "";
            renderBitmap(dateTimeText, status, gear);
            if (uploaded) {
                GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap);
            } else {
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
                uploaded = true;
            }
            renderedOnce = true;
            optionsDirty = false;
            lastRenderedEpochSecond = epochSecond;
            lastRenderedSpeedKmh = speedVal;
            lastRenderedGear = gearVal;
            lastRenderedTurnLeft = leftVal;
            lastRenderedTurnRight = rightVal;
        }

        if (outputWidth != lastViewportOutputWidth
                || outputHeight != lastViewportOutputHeight
                || options.position != lastViewportPosition) {
            computeViewportRectInto(options.position, outputWidth, outputHeight, TEXTURE_WIDTH, TEXTURE_HEIGHT, cachedViewport);
            lastViewportOutputWidth = outputWidth;
            lastViewportOutputHeight = outputHeight;
            lastViewportPosition = options.position;
        }
        GLES20.glViewport(cachedViewport[0], cachedViewport[1], cachedViewport[2], cachedViewport[3]);
        GLES20.glUseProgram(program);
        GLES20.glUniform1i(samplerUniform, 0);
        vertices.position(0);
        uv.position(0);
        GLES20.glEnableVertexAttribArray(positionAttr);
        GLES20.glEnableVertexAttribArray(coordinatesAttr);
        GLES20.glVertexAttribPointer(positionAttr, 2, GLES20.GL_FLOAT, false, 0, vertices);
        GLES20.glVertexAttribPointer(coordinatesAttr, 2, GLES20.GL_FLOAT, false, 0, uv);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glDisableVertexAttribArray(positionAttr);
        GLES20.glDisableVertexAttribArray(coordinatesAttr);
        GLES20.glViewport(0, 0, outputWidth, outputHeight);
    }

    private void renderBitmap(String dateTimeText, VehicleStatus status, VehicleSignalSnapshot.Gear gear) {
        bitmap.eraseColor(Color.TRANSPARENT);

        String line1 = formatFirstLine(options, dateTimeText, status);
        String line2 = formatSecondLine(options, status, gear);
        boolean hasLine1 = !line1.isEmpty();
        boolean hasLine2 = !line2.isEmpty();
        if (!hasLine1 && !hasLine2) {
            return;
        }

        String topText = hasLine1 ? line1 : line2;
        String bottomText = (hasLine1 && hasLine2) ? line2 : "";
        boolean twoLines = !bottomText.isEmpty();

        if (options.style == Style.TEXT) {
            textPaint.setShadowLayer(4f, 1.5f, 1.5f, 0xCC000000);
        } else {
            textPaint.clearShadowLayer();
        }

        float padH = 16f;
        float padV = 10f;
        float lineGap = 6f;
        float lineHeight = 28f;

        float textW = textPaint.measureText(topText);
        if (twoLines) {
            textW = Math.max(textW, textPaint.measureText(bottomText));
        }
        float boxW = Math.min(TEXTURE_WIDTH - 4f, textW + padH * 2f);
        float boxH = padV * 2f + (twoLines ? (lineHeight * 2f + lineGap) : lineHeight);

        boolean alignRight = options.position == Position.TOP_RIGHT || options.position == Position.BOTTOM_RIGHT;
        boolean alignBottom = options.position == Position.BOTTOM_LEFT || options.position == Position.BOTTOM_RIGHT;

        float left = alignRight ? Math.max(2f, TEXTURE_WIDTH - boxW - 2f) : 2f;
        float top = alignBottom ? Math.max(2f, TEXTURE_HEIGHT - boxH - 2f) : 2f;
        float right = left + boxW;
        float bottom = top + boxH;

        if (options.style == Style.CARD) {
            cardRect.set(left, top, right, bottom);
            canvas.drawRoundRect(cardRect, 12f, 12f, cardFillPaint);
            canvas.drawRoundRect(cardRect, 12f, 12f, cardStrokePaint);
        }

        float textX = left + padH;
        float baseline1 = top + padV + 21f;
        canvas.drawText(topText, textX, baseline1, textPaint);
        if (twoLines) {
            float baseline2 = baseline1 + lineHeight + lineGap;
            canvas.drawText(bottomText, textX, baseline2, textPaint);
        }
    }

    // ===== 纯逻辑辅助方法（可直接用于 JVM 单元测试） =====

    public static String normalizeCustomText(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim().replace('\n', ' ').replace('\r', ' ');
        if (trimmed.length() > MAX_CUSTOM_TEXT_LENGTH) {
            return trimmed.substring(0, MAX_CUSTOM_TEXT_LENGTH);
        }
        return trimmed;
    }

    public static String formatFirstLine(WatermarkOptions options, String dateTimeText, VehicleStatus status) {
        if (options == null) {
            return "";
        }
        VehicleStatus safeStatus = status != null ? status : VehicleStatus.UNKNOWN;
        StringBuilder sb = new StringBuilder();
        if (options.showDateTime && dateTimeText != null && !dateTimeText.isEmpty()) {
            sb.append(dateTimeText);
        }
        if (options.showCustomText && options.customText != null && !options.customText.isEmpty()) {
            if (sb.length() > 0) sb.append("   ");
            sb.append(options.customText);
        }
        if (options.showSpeed) {
            if (sb.length() > 0) sb.append("   ");
            sb.append("车速 ").append(safeStatus.speedKmh < 0 ? "--" : safeStatus.speedKmh).append(" km/h");
        }
        return sb.toString();
    }

    public static String formatSecondLine(
            WatermarkOptions options,
            VehicleStatus status,
            VehicleSignalSnapshot.Gear gear) {
        if (options == null || (!options.showGear && !options.showTurnSignal)) {
            return "";
        }
        VehicleStatus safeStatus = status != null ? status : VehicleStatus.UNKNOWN;
        StringBuilder sb = new StringBuilder();
        if (options.showGear) {
            sb.append("档位 ").append(formatGear(gear));
        }
        if (options.showTurnSignal) {
            if (sb.length() > 0) sb.append("   ");
            sb.append("左 ").append(signalLabel(safeStatus.left))
                    .append("   右 ").append(signalLabel(safeStatus.right));
        }
        return sb.toString();
    }

    private static String formatGear(VehicleSignalSnapshot.Gear gear) {
        if (gear == null) return "未知";
        switch (gear) {
            case PARK:
                return "P";
            case REVERSE:
                return "R";
            case NEUTRAL:
                return "N";
            case DRIVE:
                return "D";
            case OTHER:
                return "其他";
            case UNKNOWN:
            default:
                return "未知";
        }
    }

    private static String signalLabel(int value) {
        return value < 0 ? "未知" : value == 0 ? "关" : "开";
    }

    public static String buildCacheKey(
            WatermarkOptions options,
            long epochSecond,
            VehicleStatus status,
            VehicleSignalSnapshot.Gear gear) {
        if (options == null || !options.hasAnyEnabled()) {
            return "disabled";
        }
        VehicleStatus s = status != null ? status : VehicleStatus.UNKNOWN;
        VehicleSignalSnapshot.Gear g = gear != null ? gear : VehicleSignalSnapshot.Gear.UNKNOWN;
        long timePart = options.showDateTime ? epochSecond : 0L;
        String speedPart = options.showSpeed ? String.valueOf(s.speedKmh) : "-";
        String gearPart = options.showGear ? String.valueOf(g.ordinal()) : "-";
        String turnPart = options.showTurnSignal ? (s.left + ":" + s.right) : "-";
        return timePart + "|" + (options.showCustomText ? options.customText : "")
                + "|" + speedPart + "|" + gearPart + "|" + turnPart
                + "|" + options.position.name() + "|" + options.style.name();
    }

    /**
     * 计算 OpenGL glViewport 像素矩形 [x, y, width, height]（原点在画面左下角）。
     */
    public static int[] computeViewportRect(
            Position position,
            int outputWidth,
            int outputHeight,
            int boxWidth,
            int boxHeight) {
        int[] out = new int[4];
        computeViewportRectInto(position, outputWidth, outputHeight, boxWidth, boxHeight, out);
        return out;
    }

    static void computeViewportRectInto(
            Position position,
            int outputWidth,
            int outputHeight,
            int boxWidth,
            int boxHeight,
            int[] out) {
        if (outputWidth <= 0 || outputHeight <= 0 || boxWidth <= 0 || boxHeight <= 0) {
            out[0] = 0;
            out[1] = 0;
            out[2] = 1;
            out[3] = 1;
            return;
        }
        Position pos = position != null ? position : Position.BOTTOM_RIGHT;
        int margin = Math.min(16, Math.max(4, Math.min(outputWidth, outputHeight) / 24));
        int displayWidth = Math.min(boxWidth, Math.max(1, outputWidth - margin * 2));
        int displayHeight = Math.min(
                Math.max(1, outputHeight - margin * 2),
                Math.max(1, Math.round((float) boxHeight * displayWidth / boxWidth)));
        int vx = (pos == Position.TOP_RIGHT || pos == Position.BOTTOM_RIGHT)
                ? Math.max(margin, outputWidth - displayWidth - margin)
                : margin;
        int vy = (pos == Position.TOP_LEFT || pos == Position.TOP_RIGHT)
                ? Math.max(margin, outputHeight - displayHeight - margin)
                : margin;
        out[0] = vx;
        out[1] = vy;
        out[2] = displayWidth;
        out[3] = displayHeight;
    }

    /**
     * 计算归一化 OpenGL NDC 全屏空间顶点 [x0, y0, x1, y1, x2, y2, x3, y3]。
     */
    public static float[] computeQuadVertices(
            Position position,
            int outputWidth,
            int outputHeight,
            int boxWidth,
            int boxHeight) {
        int[] rect = computeViewportRect(position, outputWidth, outputHeight, boxWidth, boxHeight);
        float w = Math.max(1, outputWidth);
        float h = Math.max(1, outputHeight);
        float left = (2f * rect[0] / w) - 1f;
        float right = (2f * (rect[0] + rect[2]) / w) - 1f;
        float bottom = (2f * rect[1] / h) - 1f;
        float top = (2f * (rect[1] + rect[3]) / h) - 1f;
        return new float[]{
                left, bottom,
                right, bottom,
                left, top,
                right, top
        };
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

    @Override
    public void close() {
        if (lease != null) {
            lease.close();
            lease = null;
        }
        statusSupplier = null;
        if (texture != 0) {
            GLES20.glDeleteTextures(1, new int[]{texture}, 0);
            texture = 0;
        }
        if (program != 0) {
            GLES20.glDeleteProgram(program);
            program = 0;
        }
        if (!bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }
}
