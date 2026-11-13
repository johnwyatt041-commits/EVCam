package com.kooo.evcam.camera;

import com.kooo.evcam.BuildConfig;
import java.util.List;

/** Build-time architecture selection. Remote four-camera capture retains independent files. */
public final class RecordingExperiment {
    private RecordingExperiment() {}
    public static boolean compositeDaily(List<String> cameras) {
        return BuildConfig.SMARTCAM_COMPOSITE && cameras.size() > 1 && !cameras.contains("front");
    }
    public static String description() {
        return BuildConfig.SMARTCAM_COMPOSITE
                ? "B 版：日常三路合成单文件，H.264；分辨率受硬件限制时会缩小。飞书四路仍分别保存。请先停止录像再覆盖安装另一版。"
                : "A 版：沿用 EVCam 多路独立文件；已整改写盘与分段流程。请先停止录像再覆盖安装另一版。";
    }
}
