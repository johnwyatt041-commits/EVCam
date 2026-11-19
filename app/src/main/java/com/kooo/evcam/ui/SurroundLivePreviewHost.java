package com.kooo.evcam.ui;

import androidx.annotation.Nullable;

import com.kooo.evcam.camera.MultiCameraManager;

/**
 * 为「4.3 实时标定与视野调校」子页提供受控的 MultiCameraManager 获取接口，
 * 避免直接依赖具体 Activity 类型，并在无相机权限时返回 null。
 */
public interface SurroundLivePreviewHost {
    @Nullable
    MultiCameraManager acquireCameraManagerForSurroundCalibration();
}
