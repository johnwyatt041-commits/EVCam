package com.kooo.evcam.surround;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.kooo.evcam.AppConfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 纯离线银河 E5 俯视车模仓库（内置矢量绘制 E5 车模 + 本地 Download PNG 导入校验，彻底移除任何云端车模库请求）。
 */
public final class SurroundCarModelRepository {

    public static final long MAX_CAR_MODEL_PNG_BYTES = 4L * 1024L * 1024L;
    private static final byte[] PNG_MAGIC = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    public static final class ImportResult {
        public final boolean success;
        @NonNull
        public final String message;
        @Nullable
        public final String importedFilePath;

        public ImportResult(boolean success, @NonNull String message, @Nullable String importedFilePath) {
            this.success = success;
            this.message = message;
            this.importedFilePath = importedFilePath;
        }
    }

    private SurroundCarModelRepository() {
    }

    /** 加载当前生效的离线车模 Bitmap（若自定义 PNG 不可读则自动回退内置 E5 俯视车模）。 */
    @NonNull
    public static Bitmap loadActiveCarModelBitmap(@NonNull AppConfig config) {
        if (AppConfig.SURROUND_CAR_MODEL_CUSTOM_PNG.equals(config.getSurroundCarModelSource())) {
            String path = config.getSurroundCustomCarModelPath();
            if (!path.isEmpty()) {
                File f = new File(path);
                if (f.isFile() && f.length() > 8 && f.length() <= MAX_CAR_MODEL_PNG_BYTES) {
                    Bitmap decoded = decodeScaledPng(f, 512, 1024);
                    if (decoded != null) {
                        return decoded;
                    }
                }
            }
        }
        return createBuiltInE5CarBitmap(360, 820);
    }

    /** 扫描目录下可导入的本地透明车模 PNG 文件（≤ 4 MB）。 */
    @NonNull
    public static List<File> findCustomPngCandidates(@Nullable File searchRoot) {
        List<File> out = new ArrayList<>();
        if (searchRoot == null || !searchRoot.isDirectory()) {
            return out;
        }
        File[] files = searchRoot.listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (f.isFile() && isCandidatePngFile(f)) {
                out.add(f);
            }
        }
        return out;
    }

    /** 校验 PNG 魔数头、体积和尺寸后导入应用私有目录 `files/surround/custom_e5_car_model.png`。 */
    @NonNull
    public static ImportResult importCustomCarModelPng(@NonNull File sourceFile, @NonNull File internalDestDir) {
        if (!sourceFile.isFile() || !sourceFile.canRead()) {
            return new ImportResult(false, "所选车模文件不存在或不可读", null);
        }
        long len = sourceFile.length();
        if (len <= PNG_MAGIC.length || len > MAX_CAR_MODEL_PNG_BYTES) {
            return new ImportResult(false, "车模 PNG 大小需在 1B ~ 4MB 之间", null);
        }
        if (!hasValidPngSignature(sourceFile)) {
            return new ImportResult(false, "文件不是有效的透明 PNG 图片", null);
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(sourceFile.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 32 || bounds.outHeight <= 32) {
            return new ImportResult(false, "图片分辨率过小，无法作为车模使用", null);
        }
        if (!internalDestDir.exists() && !internalDestDir.mkdirs()) {
            return new ImportResult(false, "无法创建本地车模缓存目录", null);
        }
        File destFile = new File(internalDestDir, "custom_e5_car_model.png");
        try (FileInputStream in = new FileInputStream(sourceFile);
             FileOutputStream out = new FileOutputStream(destFile)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new ImportResult(true, "已离线导入车模：" + sourceFile.getName(), destFile.getAbsolutePath());
        } catch (IOException e) {
            return new ImportResult(false, "保存本地车模失败：" + e.getMessage(), null);
        }
    }

    public static boolean isValidPngSignatureBytes(@Nullable byte[] header) {
        if (header == null || header.length < PNG_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < PNG_MAGIC.length; i++) {
            if (header[i] != PNG_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 使用原生 Canvas 绘制银河 E5 俯视金属质感矢量车模（流线车头、全景天幕、隐藏门把手线条、外后视镜与前后灯组）。
     */
    @NonNull
    public static Bitmap createBuiltInE5CarBitmap(int width, int height) {
        int w = Math.max(180, width);
        int h = Math.max(400, height);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.TRANSPARENT);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        // 1. 柔和车底接触阴影
        float bodyL = w * 0.14f;
        float bodyR = w * 0.86f;
        float bodyT = h * 0.045f;
        float bodyB = h * 0.955f;
        RectF shadowRect = new RectF(bodyL - w * 0.03f, bodyT + h * 0.008f,
                bodyR + w * 0.03f, bodyB + h * 0.012f);
        paint.setShader(new RadialGradient(
                w * 0.5f, h * 0.5f, Math.max(w, h) * 0.55f,
                new int[]{0xC805070B, 0x6005070B, 0x0005070B},
                new float[]{0.45f, 0.78f, 1.0f},
                Shader.TileMode.CLAMP));
        canvas.drawRoundRect(shadowRect, w * 0.22f, h * 0.09f, paint);
        paint.setShader(null);

        // 2. 左右外后视镜（E5 位置约车长前 29% 处）
        paint.setColor(0xFFD6DCE5);
        RectF leftMirror = new RectF(w * 0.025f, h * 0.275f, bodyL + w * 0.02f, h * 0.320f);
        RectF rightMirror = new RectF(bodyR - w * 0.02f, h * 0.275f, w * 0.975f, h * 0.320f);
        canvas.drawRoundRect(leftMirror, w * 0.03f, w * 0.02f, paint);
        canvas.drawRoundRect(rightMirror, w * 0.03f, w * 0.02f, paint);

        // 3. 主车身银灰金属渐变
        RectF bodyRect = new RectF(bodyL, bodyT, bodyR, bodyB);
        paint.setShader(new LinearGradient(
                bodyL, 0f, bodyR, 0f,
                new int[]{0xFF8C96A4, 0xFFE5EAF0, 0xFFF3F6FA, 0xFFE5EAF0, 0xFF8C96A4},
                new float[]{0.0f, 0.20f, 0.50f, 0.80f, 1.0f},
                Shader.TileMode.CLAMP));
        canvas.drawRoundRect(bodyRect, w * 0.21f, h * 0.082f, paint);
        paint.setShader(null);

        // 4. 前机盖折线阴影与轮廓高光
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, w * 0.008f));
        paint.setColor(0x55475569);
        canvas.drawRoundRect(bodyRect, w * 0.21f, h * 0.082f, paint);
        paint.setStyle(Paint.Style.FILL);

        // 5. 前风挡玻璃 + 全景天幕 + 后风挡玻璃（暗蓝玻璃渐变）
        float glassL = w * 0.225f;
        float glassR = w * 0.775f;
        Path cabinGlass = new Path();
        cabinGlass.moveTo(glassL + w * 0.04f, h * 0.215f);
        cabinGlass.quadTo(w * 0.5f, h * 0.185f, glassR - w * 0.04f, h * 0.215f);
        cabinGlass.lineTo(glassR, h * 0.37f);
        cabinGlass.lineTo(glassR - w * 0.025f, h * 0.805f);
        cabinGlass.quadTo(w * 0.5f, h * 0.845f, glassL + w * 0.025f, h * 0.805f);
        cabinGlass.lineTo(glassL, h * 0.37f);
        cabinGlass.close();

        paint.setShader(new LinearGradient(
                0f, h * 0.19f, 0f, h * 0.84f,
                new int[]{0xFF1F2A3A, 0xFF101722, 0xFF131C2A, 0xFF1E293B},
                new float[]{0.0f, 0.26f, 0.72f, 1.0f},
                Shader.TileMode.CLAMP));
        canvas.drawPath(cabinGlass, paint);
        paint.setShader(null);

        // 车顶天幕纵梁与光泽反射线
        paint.setColor(0xFFCFD8E3);
        canvas.drawRect(w * 0.22f, h * 0.355f, w * 0.242f, h * 0.765f, paint);
        canvas.drawRect(w * 0.758f, h * 0.355f, w * 0.78f, h * 0.765f, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, w * 0.007f));
        paint.setColor(0x4594A3B8);
        canvas.drawLine(w * 0.30f, h * 0.335f, w * 0.70f, h * 0.335f, paint);
        canvas.drawLine(w * 0.31f, h * 0.715f, w * 0.69f, h * 0.715f, paint);

        // 6. 前日行灯（冷白光带）与 贯穿式后尾灯（绯红光带）
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(Math.max(3f, w * 0.015f));
        paint.setColor(0xFFE0F2FE);
        canvas.drawArc(new RectF(w * 0.20f, h * 0.055f, w * 0.42f, h * 0.105f), 195, 70, false, paint);
        canvas.drawArc(new RectF(w * 0.58f, h * 0.055f, w * 0.80f, h * 0.105f), 275, 70, false, paint);

        paint.setColor(0xFFEF4444);
        paint.setStrokeWidth(Math.max(3f, w * 0.014f));
        canvas.drawLine(w * 0.24f, h * 0.935f, w * 0.76f, h * 0.935f, paint);

        return bmp;
    }

    private static boolean isCandidatePngFile(@NonNull File f) {
        return f.getName().toLowerCase(Locale.US).endsWith(".png")
                && f.length() > PNG_MAGIC.length
                && f.length() <= MAX_CAR_MODEL_PNG_BYTES;
    }

    private static boolean hasValidPngSignature(@NonNull File f) {
        byte[] header = new byte[PNG_MAGIC.length];
        try (FileInputStream fis = new FileInputStream(f)) {
            int n = fis.read(header);
            return n == PNG_MAGIC.length && isValidPngSignatureBytes(header);
        } catch (IOException ignored) {
            return false;
        }
    }

    @Nullable
    private static Bitmap decodeScaledPng(@NonNull File file, int maxW, int maxH) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        int sampleSize = 1;
        while (opts.outWidth / sampleSize > maxW * 2 || opts.outHeight / sampleSize > maxH * 2) {
            sampleSize *= 2;
        }
        BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
        decodeOpts.inSampleSize = sampleSize;
        decodeOpts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), decodeOpts);
    }
}
