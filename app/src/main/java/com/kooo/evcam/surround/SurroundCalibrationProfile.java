package com.kooo.evcam.surround;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 银河 E5 360 环视四路鱼眼标定模型（纯 Java，支持 E5 内置参数及本地 E5 calibration.json 导入，彻底剔除领克 08）。
 * 世界坐标系约定（单位 cm）：X 向右为正，Y 向前为正，Z 向上为正；车辆质心原点在车底中心 z=0。
 */
public final class SurroundCalibrationProfile {

    public static final int IMAGE_WIDTH = 1280;
    public static final int IMAGE_HEIGHT = 800;

    // 银河 E5 实车物理包络（cm，约 190.0 × 462.0 cm）
    public static final float E5_CAR_MIN_X_CM = -95.0f;
    public static final float E5_CAR_MAX_X_CM = 95.0f;
    public static final float E5_CAR_MIN_Y_CM = -231.0f;
    public static final float E5_CAR_MAX_Y_CM = 231.0f;

    // 银河 E5 俯视车模 PNG 显示框（含外后视镜扩展，保持 504×1024 图幅下车身≈190×462 cm 的 1:1 比例）
    public static final float E5_CAR_QUAD_MIN_X_CM = -113.0f;
    public static final float E5_CAR_QUAD_MAX_X_CM = 113.0f;
    public static final float E5_CAR_QUAD_MIN_Y_CM = -232.0f;
    public static final float E5_CAR_QUAD_MAX_Y_CM = 232.0f;

    // 360 鸟瞰车周 stitched 地面可视边距（cm）：让车模周围留出充足路面与转向轨线空间（对标 SC 俯视视场）
    public static final double BIRD_SIDE_MARGIN_CM = 125.0;
    public static final double BIRD_REAR_MARGIN_CM = 170.0;
    public static final double BIRD_FRONT_MARGIN_CM = 185.0;

    // 默认标定文件（星舰7 / E5 avm_calib_res）原始世界坐标的纵向车体中心偏移（cm，-1.43m）
    public static final double DEFAULT_BIRD_CENTER_OFFSET_Y_CM = -143.0;

    /** 单路相机标定参数：支持统一球面（xi + focal）及 OpenCV Kannala-Brandt 鱼眼畸变（fx/fy + k1..k4）。 */
    public static final class CameraCalibration {
        @NonNull
        public final String position; // "front", "back", "left", "right"
        public final double posXCm;
        public final double posYCm;
        public final double posZCm;
        @NonNull
        public final double[] r0;
        @NonNull
        public final double[] r1;
        @NonNull
        public final double[] r2;
        public final double fx;
        public final double fy;
        public final double cx;
        public final double cy;
        public final double xi;
        public final boolean usePolynomialDistortion;
        @NonNull
        public final double[] distortion; // k1..k4

        public CameraCalibration(@NonNull String position,
                                 double posXCm, double posYCm, double posZCm,
                                 @NonNull double[] r0, @NonNull double[] r1, @NonNull double[] r2,
                                 double fx, double fy, double cx, double cy,
                                 double xi, boolean usePolynomialDistortion,
                                 @Nullable double[] distortion) {
            this.position = position;
            this.posXCm = posXCm;
            this.posYCm = posYCm;
            this.posZCm = posZCm;
            this.r0 = r0.clone();
            this.r1 = r1.clone();
            this.r2 = r2.clone();
            this.fx = fx;
            this.fy = fy;
            this.cx = cx;
            this.cy = cy;
            this.xi = xi;
            this.usePolynomialDistortion = usePolynomialDistortion;
            this.distortion = distortion != null ? distortion.clone() : new double[]{0d, 0d, 0d, 0d};
        }

        /**
         * 将世界坐标点（cm）投影至归一化纹理坐标 [u, v, valid]：
         * u ∈ [0,1] 从左到右，v ∈ [0,1] 采用 Android OpenGL 纹理下方为 0 / 上方为 1。
         */
        @NonNull
        public double[] projectWorldToUv(double worldXCm, double worldYCm, double worldZCm) {
            double dx = worldXCm - posXCm;
            double dy = worldYCm - posYCm;
            double dz = worldZCm - posZCm;

            double camX = r0[0] * dx + r0[1] * dy + r0[2] * dz;
            double camY = r1[0] * dx + r1[1] * dy + r1[2] * dz;
            double camZ = r2[0] * dx + r2[1] * dy + r2[2] * dz;

            double px;
            double py;
            double valid = 1.0;

            if (usePolynomialDistortion) {
                double r = Math.hypot(camX, camY);
                if (r < 1e-9 && camZ <= 0.0) {
                    return new double[]{0.5, 0.5, 0.0};
                }
                double theta = Math.atan2(r, camZ);
                if (theta > Math.PI * 0.58) {
                    valid = 0.0;
                }
                double t2 = theta * theta;
                double k1 = distortion.length > 0 ? distortion[0] : 0d;
                double k2 = distortion.length > 1 ? distortion[1] : 0d;
                double k3 = distortion.length > 2 ? distortion[2] : 0d;
                double k4 = distortion.length > 3 ? distortion[3] : 0d;
                double thetaD = theta * (1.0 + t2 * (k1 + t2 * (k2 + t2 * (k3 + t2 * k4))));
                double scale = r > 1e-8 ? (thetaD / r) : 1.0;
                px = fx * camX * scale + cx;
                py = fy * camY * scale + cy;
            } else {
                double norm = Math.sqrt(camX * camX + camY * camY + camZ * camZ);
                double denom = camZ + xi * norm;
                if (denom <= 1e-4) {
                    return new double[]{0.5, 0.5, 0.0};
                }
                px = fx * camX / denom + cx;
                py = fy * camY / denom + cy;
            }

            if (px < 0.0 || px > (IMAGE_WIDTH - 1.0) || py < 0.0 || py > (IMAGE_HEIGHT - 1.0)) {
                valid = 0.0;
            }
            double u = clamp(px / IMAGE_WIDTH, 0.001, 0.999);
            double v = clamp(1.0 - (py / IMAGE_HEIGHT), 0.001, 0.999);
            return new double[]{u, v, valid};
        }
    }

    @NonNull
    public final String profileName;
    public final boolean isCustomCalibration;
    public final boolean useBuiltInMeshes;
    public final boolean hasExtrinsicAdjustments;
    public final double birdCenterOffsetYCm;
    public final int groundWidthCm;
    public final int groundLengthCm;
    public final int groundFrontExtensionCm;
    @NonNull
    public final Map<String, CameraCalibration> cameras;

    public SurroundCalibrationProfile(@NonNull String profileName,
                                      boolean isCustomCalibration,
                                      int groundWidthCm,
                                      int groundLengthCm,
                                      int groundFrontExtensionCm,
                                      @NonNull Map<String, CameraCalibration> cameras) {
        this(profileName, isCustomCalibration, !isCustomCalibration, false,
                DEFAULT_BIRD_CENTER_OFFSET_Y_CM,
                groundWidthCm, groundLengthCm, groundFrontExtensionCm, cameras);
    }

    public SurroundCalibrationProfile(@NonNull String profileName,
                                      boolean isCustomCalibration,
                                      boolean useBuiltInMeshes,
                                      int groundWidthCm,
                                      int groundLengthCm,
                                      int groundFrontExtensionCm,
                                      @NonNull Map<String, CameraCalibration> cameras) {
        this(profileName, isCustomCalibration, useBuiltInMeshes, false,
                DEFAULT_BIRD_CENTER_OFFSET_Y_CM,
                groundWidthCm, groundLengthCm, groundFrontExtensionCm, cameras);
    }

    public SurroundCalibrationProfile(@NonNull String profileName,
                                      boolean isCustomCalibration,
                                      boolean useBuiltInMeshes,
                                      boolean hasExtrinsicAdjustments,
                                      int groundWidthCm,
                                      int groundLengthCm,
                                      int groundFrontExtensionCm,
                                      @NonNull Map<String, CameraCalibration> cameras) {
        this(profileName, isCustomCalibration, useBuiltInMeshes, hasExtrinsicAdjustments,
                DEFAULT_BIRD_CENTER_OFFSET_Y_CM,
                groundWidthCm, groundLengthCm, groundFrontExtensionCm, cameras);
    }

    public SurroundCalibrationProfile(@NonNull String profileName,
                                      boolean isCustomCalibration,
                                      boolean useBuiltInMeshes,
                                      boolean hasExtrinsicAdjustments,
                                      double birdCenterOffsetYCm,
                                      int groundWidthCm,
                                      int groundLengthCm,
                                      int groundFrontExtensionCm,
                                      @NonNull Map<String, CameraCalibration> cameras) {
        this.profileName = profileName;
        this.isCustomCalibration = isCustomCalibration;
        this.useBuiltInMeshes = useBuiltInMeshes;
        this.hasExtrinsicAdjustments = hasExtrinsicAdjustments;
        this.birdCenterOffsetYCm = Double.isFinite(birdCenterOffsetYCm)
                ? birdCenterOffsetYCm
                : DEFAULT_BIRD_CENTER_OFFSET_Y_CM;
        this.groundWidthCm = Math.max(160, Math.min(260, groundWidthCm));
        this.groundLengthCm = Math.max(420, Math.min(600, groundLengthCm));
        this.groundFrontExtensionCm = Math.max(0, Math.min(100, groundFrontExtensionCm));
        this.cameras = Collections.unmodifiableMap(new LinkedHashMap<>(cameras));
    }

    @Nullable
    public CameraCalibration getCamera(@NonNull String position) {
        return cameras.get(position);
    }

    public double getHalfWorldWidthCm() {
        return (groundWidthCm * 0.5) + BIRD_SIDE_MARGIN_CM;
    }

    public double getWorldMinYCm() {
        return -(groundLengthCm * 0.5) - BIRD_REAR_MARGIN_CM;
    }

    public double getWorldMaxYCm() {
        return (groundLengthCm * 0.5) + BIRD_FRONT_MARGIN_CM + groundFrontExtensionCm;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
