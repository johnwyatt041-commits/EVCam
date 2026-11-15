package com.kooo.evcam.blindspot;

/**
 * 物理镜像与输出镜像解耦策略。
 *
 * 物理镜像（physicalH / physicalV）：描述摄像头传感器硬件/安装方向翻转，同一路摄像头的所有输出共用；
 * 输出镜像（outputH / outputV）：描述特定输出目标（主预览 / 主屏补盲 / 副屏 HUD / 网络推流）的独立观看偏好。
 * 等效镜像（无额外 90/270° 交叉轴影响时的纯轴向组合）为 eff = phys ^ out；
 * 在带旋转矩阵管线中，物理镜像于旋转前（Buffer 空间）生效，输出镜像于旋转后（屏幕显示空间）生效，
 * 确保任意挂载旋转角下仍保持物理正确性与输出左右对称一致性。
 */
public final class CameraMirrorPolicy {

    /** 解析后的四向镜像参数快照。 */
    public static final class ResolvedMirror {
        public final boolean physicalH;
        public final boolean physicalV;
        public final boolean outputH;
        public final boolean outputV;
        public final boolean effectiveH;
        public final boolean effectiveV;

        public ResolvedMirror(boolean physicalH, boolean physicalV, boolean outputH, boolean outputV) {
            this.physicalH = physicalH;
            this.physicalV = physicalV;
            this.outputH = outputH;
            this.outputV = outputV;
            this.effectiveH = physicalH ^ outputH;
            this.effectiveV = physicalV ^ outputV;
        }
    }

    /** 配置访问接口，便于在纯 JVM 单元测试与 AppConfig 间解耦。 */
    public interface Store {
        boolean isPhysicalMirrorH(String cameraPosition);
        boolean isPhysicalMirrorV(String cameraPosition);
        boolean isOutputMirrorH(MirrorTarget target, String cameraPosition);
        boolean isOutputMirrorV(MirrorTarget target, String cameraPosition);
    }

    private CameraMirrorPolicy() {
    }

    public static ResolvedMirror resolve(Store store, MirrorTarget target, String cameraPosition) {
        if (store == null || cameraPosition == null) {
            return new ResolvedMirror(false, false, false, false);
        }
        MirrorTarget safeTarget = target != null ? target : MirrorTarget.BLIND_SPOT;
        boolean physH = store.isPhysicalMirrorH(cameraPosition);
        boolean physV = store.isPhysicalMirrorV(cameraPosition);
        boolean outH = store.isOutputMirrorH(safeTarget, cameraPosition);
        boolean outV = store.isOutputMirrorV(safeTarget, cameraPosition);
        return new ResolvedMirror(physH, physV, outH, outV);
    }

    public static ResolvedMirror combine(boolean physicalH, boolean physicalV, boolean outputH, boolean outputV) {
        return new ResolvedMirror(physicalH, physicalV, outputH, outputV);
    }

    /**
     * 主预览未显式配置时，保留历史默认习惯：仅后摄（"back"）默认输出水平镜像开启，其余默认关闭。
     */
    public static boolean defaultOutputMirrorH(MirrorTarget target, String cameraPosition) {
        return target == MirrorTarget.MAIN_PREVIEW && "back".equals(cameraPosition);
    }
}
