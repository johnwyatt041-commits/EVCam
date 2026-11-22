# 录像与预览性能优化

最后更新：2026-11-22 21:25:03 +08:00
最后核对：2026-11-22 21:25:03 +08:00
任务/执行者：optimize-recording-preview-perf / Antigravity
分支/工作树：codex/e5-recording-startup-fix / E:\GPT\EVCam
状态：已完成 **`S1~S4` + `M1~M2`** 单 A 版离线验证（`1.6.6-test-11222113`），待决提交封包上车实测
当前结论：`S1~S4` + `M1~M2` 通过 `374` 测、A 版 Debug/Release 及 Lint `0` errors（待实测）；`M2` 在无水印且无/恒等调色时直连 `MediaCodec` 输入面跳过 GL，保留开关与单路一次自动回退。

## 已修复（`build24` / `9f90c63` 已合入 10 项）

- **录像 1~5**：去 Release `glGetError()`/冗余 `eglMakeCurrent()`（[EglSurfaceEncoder.java](../../app/src/main/java/com/kooo/evcam/camera/EglSurfaceEncoder.java)、[MultiCameraCompositor.java](../../app/src/main/java/com/kooo/evcam/camera/MultiCameraCompositor.java)）；`segmentHandler` 异步分段、退避排空及 HEVC 码率修复（[CodecVideoRecorder.java](../../app/src/main/java/com/kooo/evcam/camera/CodecVideoRecorder.java)）；水印零分配与中转降优（[VehicleStatusWatermark.java](../../app/src/main/java/com/kooo/evcam/camera/VehicleStatusWatermark.java)、[FileTransferManager.java](../../app/src/main/java/com/kooo/evcam/FileTransferManager.java)）。
- **预览 6~10**：Mode A VBO/IBO、`33ms` 合并限频与 Shader 减负（[SurroundGlRenderer.java](../../app/src/main/java/com/kooo/evcam/surround/SurroundGlRenderer.java)）；隐藏停刷计时 UI（[MainActivity.java](../../app/src/main/java/com/kooo/evcam/MainActivity.java)）；去默认圆角与冗余 Outline/`setOpaque`（[EditablePreviewLayout.java](../../app/src/main/java/com/kooo/evcam/ui/EditablePreviewLayout.java)）。

## 优化清单（已离线完成 `S1~S4` + `M1~M2`）

### 第一组：`S1~S4`（小工作量，已完成）
- [x] **S1 悬浮窗防重排**：[RecordingFloatingService.java](../../app/src/main/java/com/kooo/evcam/service/RecordingFloatingService.java) 计时等宽固定宽高，[OverlayWindowParamsFactory.java](../../app/src/main/java/com/kooo/evcam/ui/OverlayWindowParamsFactory.java) 与 [FloatingWindowService.java](../../app/src/main/java/com/kooo/evcam/FloatingWindowService.java) 改轻量 2038。
- [x] **S2 VHAL 120s 重连消震荡**：[VhalSignalObserver.java](../../app/src/main/java/com/kooo/evcam/VhalSignalObserver.java) `ReconnectStateMachine` 仅真静默满 `120s` 重连且保留有效挡位。
- [x] **S3 起录并行异步化**：[CodecVideoRecorder.java](../../app/src/main/java/com/kooo/evcam/camera/CodecVideoRecorder.java) 幂等取消清理，[MultiCameraManager.java](../../app/src/main/java/com/kooo/evcam/camera/MultiCameraManager.java) 经 [CodecPrepareCoordinator.java](../../app/src/main/java/com/kooo/evcam/camera/CodecPrepareCoordinator.java) 并发准备。
- [x] **S4 补盲后台降频**：[BlindSpotService.java](../../app/src/main/java/com/kooo/evcam/BlindSpotService.java) 与 [BlindSpotCoordinator.java](../../app/src/main/java/com/kooo/evcam/blindspot/BlindSpotCoordinator.java) 仅需时轮询 AVM 并延迟预建主屏窗。

### 第二组：`M1~M2`（中等工作量，已完成）
- [x] **M1 `Mode D`/前后台预览隔离重建**：[RecordingPreviewSessionPolicy.java](../../app/src/main/java/com/kooo/evcam/camera/RecordingPreviewSessionPolicy.java)、[SingleCamera.java](../../app/src/main/java/com/kooo/evcam/camera/SingleCamera.java) 热切与动态 `OutputConfiguration` 隔离重建（守 `P002`），协同 [MultiCameraManager.java](../../app/src/main/java/com/kooo/evcam/camera/MultiCameraManager.java)、[PreviewModeController.java](../../app/src/main/java/com/kooo/evcam/ui/PreviewModeController.java) 与 [MainActivity.java](../../app/src/main/java/com/kooo/evcam/MainActivity.java)。
- [x] **M2 无水印无调色直连编码跳过 GL**：新增 [DirectEncodingPolicy.java](../../app/src/main/java/com/kooo/evcam/camera/DirectEncodingPolicy.java)；[CodecVideoRecorder.java](../../app/src/main/java/com/kooo/evcam/camera/CodecVideoRecorder.java) 直连编码器输入面，含 `preStartDrain`/首 IDR 门控、跨段单调 PTS 与 `path=direct|gl`；协同 [SingleCamera.java](../../app/src/main/java/com/kooo/evcam/camera/SingleCamera.java)、[MultiCameraManager.java](../../app/src/main/java/com/kooo/evcam/camera/MultiCameraManager.java)、[AppConfig.java](../../app/src/main/java/com/kooo/evcam/AppConfig.java)、[RecorderSettingsFragment.java](../../app/src/main/java/com/kooo/evcam/RecorderSettingsFragment.java) 保活跃会话稳态、配置开关与异常一次回退 GL。

### 第三组：`L1~L3`（大工作量，实测后再评）
- [ ] **L1 共享单 GL 线程**（仅日常开水印需优化时合并 3~4 路 `EGLContext`）。
- [ ] **L2 预览降尺寸 / 减物理流**（查前景 4 路对后台 3 路 `21.3~22.9fps` HAL 带宽瓶颈，守 `P004`/`P006`）。
- [ ] **L3 4×`TextureView` 合单 `GLSurfaceView`**（联动拍照与矩阵，仅 UI 仍卡再做）。

### 已排除项
- ~~E5 编码参数~~：`correctedPts=0`、`codec poll avgUs=0.17~0.36ms`；~~切 B/改写盘~~：`writerAvgUs=0.20~0.59ms`、`declaredMaxInstances=8`。

## 验证与证据（2026-11-22 21:25:03 +08:00）

- **离线（`1.6.6-test-11222113`）**：`.\tools\verify.ps1` `374` 测 `0` 失败/`0` 错误/`0` 跳过（`work/verification/build-20261122-211622-056.log`），A 版 Debug/Release 通过；`:app:lintIndependentDebug` `0` errors（`2226` warnings，`M2` Java 0 新增 warning，布局 2 条同风格 `HardcodedText`）。
- **实车基线（`build24` `E245` `mode_d`，141 路-分段）**：稳态 `24.7~24.88fps`，`Mode D` `id=0/3` 跌至 `19~21.9fps`；`S1~S4` 与 `M1~M2` 实车帧率、直连比例及闪屏待复测（开「Debug 日志转 INFO」）。

## 最近记录

- 2026-11-22 21:25:03 +08:00 | 完成 `M2` 直连编码跳过 GL、17 项单测与单 A 版验证（`1.6.6-test-11222113`） | 跳过 EGL/`SurfaceTexture` 直连编码器，含预排空/首 IDR 门控、可控开关、动态调优延后生效及单路自动回退 GL；`verify.ps1` `374` 测与 Lint `0` errors 通过 | [STATUS.md](../STATUS.md)
- 2026-11-22 20:41:35 +08:00 | 完成 `M1` 预览隔离重建与单 A 版验证（`1.6.6-test-11222037`） | 录制期改 RepeatingRequest 热切与动态 `OutputConfiguration` 隔离重建；`verify.ps1` `357` 测与 Lint `0` errors 通过 | [STATUS.md](../STATUS.md)
- 2026-11-22 20:06:06 +08:00 | 完成 `S1~S4` 优化与单 A 版验证（`1.6.6-test-11222001`） | 补 4 组单测，`verify.ps1` `342` 测与 Lint `0` errors 通过 | [STATUS.md](../STATUS.md)
- 2026-11-22 19:04:00 +08:00 | 拆分第二批优化清单（`S1~S4`/`M1~M2`/`L1~L3`） | 排除第 4/5 项，纳入后台闪屏嫌疑点与排查抓手 | [STATUS.md](../STATUS.md)
- 2026-11-22 18:22:00 +08:00 | 完成 `build24` E5 日志分析（`13:29–15:46`，3105 条） | 锁定 `Mode D`/前后台 `recreateSession` 与 3 路 `glAvgUs` 最高 `19.9ms` | [STATUS.md](../STATUS.md)

## 下一步

1. 待用户确认基于当前 **`S1~S4` + `M1~M2`**（`1.6.6-test-11222113`）提交代码、封包上车实测（开 `Debug 日志转 INFO`，对照 `path=direct` vs `path=gl` 与帧率）。
