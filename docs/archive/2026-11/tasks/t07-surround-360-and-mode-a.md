# 任务记录：T07 360 全景 + 分视角模式 A 与辅助设置（build08/build09）

- 最后更新：2026-11-16 20:42:00 +08:00（Asia/Hong_Kong）
- 最后核对：2026-11-16 20:42:00 +08:00（Asia/Hong_Kong）
- 任务/执行者：`t07-surround-360-and-mode-a` / Antigravity
- 分支/工作树：`codex/e5-recording-startup-fix` / `E:\GPT\EVCam`（源码 `0d2a56e`，Tag `v1.6.6-test-11162036-build09`）
- 状态：已完成代码、`build08` 与方案 1 转角补充 `build09` 双风味 Release(R8) 打包（离线通过，**未上车实测**）

## 实施摘要（仅限 T07，未改 T08–T10）

1. **按需 360 GL 上下文与 Surface 共享**：[SurroundModeAView.java](..\..\..\..\app\src\main\java\com\kooo\evcam\surround\SurroundModeAView.java) / [SurroundGlRenderer.java](..\..\..\..\app\src\main\java\com\kooo\evcam\surround\SurroundGlRenderer.java) 仅在模式 A 前台激活主预览时创建 `GLSurfaceView` 与 4 路 OES；[SingleCamera.java](..\..\..\..\app\src\main\java\com\kooo\evcam\camera\SingleCamera.java) 与 [MultiCameraManager.java](..\..\..\..\app\src\main\java\com\kooo\evcam\camera\MultiCameraManager.java) 共享输出；复用 [CameraMirrorPolicy.java](..\..\..\..\app\src\main\java\com\kooo\evcam\blindspot\CameraMirrorPolicy.java) 物理镜像并在 [PreviewModeController.java](..\..\..\..\app\src\main\java\com\kooo\evcam\ui\PreviewModeController.java) 联动 `suppress_on_360`。
2. **`4.1` 分视角与 `4.3` E5 校准/车模/引导线**：[SurroundViewMode.java](..\..\..\..\app\src\main\java\com\kooo\evcam\surround\SurroundViewMode.java)、[SurroundLayoutCalculator.java](..\..\..\..\app\src\main\java\com\kooo\evcam\surround\SurroundLayoutCalculator.java)、[SurroundCalibrationManager.java](..\..\..\..\app\src\main\java\com\kooo\evcam\surround\SurroundCalibrationManager.java)、[SurroundMeshBuilder.java](..\..\..\..\app\src\main\java\com\kooo\evcam\surround\SurroundMeshBuilder.java)、[SurroundGuidanceCalculator.java](..\..\..\..\app\src\main\java\com\kooo\evcam\surround\SurroundGuidanceCalculator.java)、[SurroundCarModelRepository.java](..\..\..\..\app\src\main\java\com\kooo\evcam\surround\SurroundCarModelRepository.java)；已彻底移除通用屏幕尺寸、外设、环境/前雷达提示字、领克 08 与云端地址。
3. **`4.2` R 档延时拉起/车速/P 档返桌与 `4. 360 画面`**：[ReversePreviewAutoCloseCoordinator.java](..\..\..\..\app\src\main\java\com\kooo\evcam\vehicle\ReversePreviewAutoCloseCoordinator.java)、[SurroundSettingsFragment.java](..\..\..\..\app\src\main\java\com\kooo\evcam\ui\SurroundSettingsFragment.java) 注册于 [SettingsCatalog.java](..\..\..\..\app\src\main\java\com\kooo\evcam\ui\SettingsCatalog.java)。
4. **方案 1 前台模式 A 按需方向盘转角数据源（`build09` `0d2a56e`）**：新增 [SurroundSteeringAngleSource.java](..\..\..\..\app\src\main\java\com\kooo\evcam\vehicle\SurroundSteeringAngleSource.java)，双协议兼容 VHAL `0x11600209`（40004）与 E5 APVP `PinionSteerAgGroupPinionSteerAg1`（40005，`Qf==3 && Timeout==0`）；[VhalSignalObserver.java](..\..\..\..\app\src\main\java\com\kooo\evcam\VhalSignalObserver.java) 分离附加多播槽避免冲突；[SurroundGlRenderer.java](..\..\..\..\app\src\main\java\com\kooo\evcam\surround\SurroundGlRenderer.java) 新增 `updateGuidanceAngle` 避免 View 重布局抖动。

## 与 T01/T06 信号排除边界的关系（供后续全面复审）

- **保留 T01/T06 原边界**：此前在 `T01`/`T06` 中排除高频率方向盘转角信号，是为了防止 [VehicleSignalCategory.java](..\..\..\..\app\src\main\java\com\kooo\evcam\vehicle\VehicleSignalCategory.java)、[VehicleSignalHub.java](..\..\..\..\app\src\main\java\com\kooo\evcam\vehicle\VehicleSignalHub.java)、`3.1` 规则引擎及 `10. 信号预设` 进行 48 路后台轮询与频繁唤醒。本次 **`build09` 未改动上述 4 类后台白名单**，`classifyPropertyId(0x11600209)` 仍返回 `null`。
- **方案 1 隔离范围**：仅在主界面**前台处于模式 A 且开启“动态转向引导线”**时由 [SurroundModeAView.java](..\..\..\..\app\src\main\java\com\kooo\evcam\surround\SurroundModeAView.java) 申请临时 `Lease`；切至模式 B/C、离开录制页、关引导线或 `onPause()` 退后台即刻释放连接并落盘末次有效角，实现**零后台唤醒开销**；同时保留 [RecordingVehicleSource.java](..\..\..\..\app\src\main\java\com\kooo\evcam\vehicle\RecordingVehicleSource.java) 原水印单测契约不变。

## 离线验证与 `build09` 交付（`0d2a56e`）

- **单元测试**：A/B 共 `334` 测（每版 `167`，`0` 失败/错误/跳过），含新增 [SurroundSteeringAngleSourceTest.java](..\..\..\..\app\src\test\java\com\kooo\evcam\vehicle\SurroundSteeringAngleSourceTest.java) `5` 测与既有 [RecordingVehicleSourceTest.java](..\..\..\..\app\src\test\java\com\kooo\evcam\vehicle\RecordingVehicleSourceTest.java) `3` 测。
- **Lint 与校验**：`:app:lintIndependentDebug --offline` 保持 `19` baseline 错误（`0` 新增、`1941` warnings）；Release R8 + `apksigner`（Cert `a40da8...1bf5dc`）+ `libvhal_decoder.so`（`8408`B，`243a92bc...`）通过；`build08`（`b9aeda1`）历史档案保留。
- **`build09` 交付物**：
  - `EVCam-E5-A-Independent-1.6.6-test-11162036-build09-release.apk`（`6,627,219`B，SHA256 `3d1742a2...d55044c`）
  - `EVCam-E5-B-Composite-1.6.6-test-11162036-build09-release.apk`（`6,643,603`B，SHA256 `9a6356b7...c7a7b35`）
  - `outputs/`: [manifest.json](..\..\..\..\outputs\archive\EVCam-E5-build09-1.6.6-test-11162036-manifest.json)、[notes.md](..\..\..\..\outputs\archive\EVCam-E5-build09-1.6.6-test-11162036-notes.md)、[SHA256.txt](..\..\..\..\outputs\archive\EVCam-E5-build09-1.6.6-test-11162036-SHA256.txt)、`mappings.zip`（`4,902,182`B）。

## 实车待验项与下一步

1. E5 实车验证模式 A 拼接贴合、4.2 返桌联动、`40005` APVP / `40004` VHAL 实车方向盘转角方向与延迟，以及切出模式 A 后零后台流驻留。
2. 基于 `build09` 启动 **`T08`**（回看重构与局域网浏览器分享）。
