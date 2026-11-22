# 录像与预览性能优化

最后更新：2026-11-22 12:38:25 +08:00
最后核对：2026-11-22 12:38:25 +08:00
任务/执行者：optimize-recording-preview-perf / Antigravity
分支/工作树：codex/e5-recording-startup-fix / E:\GPT\EVCam
状态：第一批 9 项 + 第二批第 1 项轻量化已完成并准备封包 `build24`（`1.6.6-test-11221230`，离线 `650` 测与双风味构建/Lint `0` errors 通过；待银河 E5 实车验证）
当前结论：已完成第一批 9 项录像/预览优化及普通多窗预览轻量化（共 10 项，`1.6.6-test-11221230`），涵盖 GL 管线去同步与去冗余绑定、分段异步收尾、排空防空轮询、默认 HEVC 码率生效、水印零分配、中转降优、360 模式 A VBO/IBO+OES 合并限频+Shader 优化、隐藏计时器停刷及默认关闭窗格圆角裁剪；剩余 4 项架构/实车兼容项已列明验证判据待实车定性。

## 已修复清单（共 10 项）

### 录像链路（5 项）
1. **消除逐帧 GPU 管线同步与冗余 `eglMakeCurrent`**（[EglSurfaceEncoder.java](../../app/src/main/java/com/kooo/evcam/camera/EglSurfaceEncoder.java)、[MultiCameraCompositor.java](../../app/src/main/java/com/kooo/evcam/camera/MultiCameraCompositor.java)）：Release 构建跳过逐帧 `glGetError()`；按单线程上下文绑定状态跳过每帧重复 `eglMakeCurrent()`（异常/换 Surface/释放时重置重绑）。
2. **分段切换异步收尾零阻塞**（[CodecVideoRecorder.java](../../app/src/main/java/com/kooo/evcam/camera/CodecVideoRecorder.java)）：`rotateMuxerAtKeyFrame()` 立即建新 `AsyncMuxerWriter` 接管关键帧，旧 writer 移交 `segmentHandler` 后台 `stop()/release()` 后再校验并回调 `onSegmentSwitch`；`stopRecording()` 与 `release()` 均等待在途收尾落盘。
3. **消除 10ms 持续空轮询排空**（[CodecVideoRecorder.java](../../app/src/main/java/com/kooo/evcam/camera/CodecVideoRecorder.java)）：`drawFrame()` 提交帧后按 `drainWakeupPending` 合并唤醒排空；无输出时按在途帧数自适应退避（`10ms -> 15ms -> 30ms -> 120ms`）。
4. **修复默认 HEVC 路径忽略用户码率设置**（[CodecVideoRecorder.java](../../app/src/main/java/com/kooo/evcam/camera/CodecVideoRecorder.java)、[MultiCameraManager.java](../../app/src/main/java/com/kooo/evcam/camera/MultiCameraManager.java)）：新增 `resolveTargetBitrate()` 尊重用户低/标准/高码率（HEVC 按 55% 折算并钳制上下限），移除硬编码 `setQualityLevel(3)` 改为按码率等级映射。
5. **水印零分配与中转线程降优提效**（[VehicleStatusWatermark.java](../../app/src/main/java/com/kooo/evcam/camera/VehicleStatusWatermark.java)、[FileTransferManager.java](../../app/src/main/java/com/kooo/evcam/FileTransferManager.java)）：水印热路径改基本类型字段对比并复用 `int[4]` viewport 缓冲；中转线程降为 `THREAD_PRIORITY_BACKGROUND`，拷贝缓冲扩至 `256KB` 并去除每 `1MB` 循环 `flush()`（保留结尾 `fd.sync()`）。

### 预览链路（5 项）
6. **360 模式 A 网格升级 GPU VBO/IBO**（[SurroundGlRenderer.java](../../app/src/main/java/com/kooo/evcam/surround/SurroundGlRenderer.java)）：鸟瞰（`2.65MB`）、道路、四路轮位与车模网格全部驻留 GPU VBO/IBO，每帧 CPU→GPU 拷贝降为 `0`；上下文重建与退出时完整释放并解绑。
7. **360 四路 OES 回调合并限频**（[SurroundGlRenderer.java](../../app/src/main/java/com/kooo/evcam/surround/SurroundGlRenderer.java)）：新增 `33ms` 合并窗口节流（约 30fps 上限），消除四路错峰到达触发的 60~100Hz 冗余全屏重绘。
8. **360 Shader 减负与零逐帧查表**（[SurroundGlRenderer.java](../../app/src/main/java/com/kooo/evcam/surround/SurroundGlRenderer.java)）：`vBlendWeight <= 0.001` 跳过二次 OES 采样，颜色改 `mediump`（UV 保留 `highp`），多项式近似替代逐像素 3 通道 `pow(..., 0.92)`；缓存全部 GL location、4 路镜像参数与车模矩形。
9. **隐藏录制计时器停刷 UI**（[MainActivity.java](../../app/src/main/java/com/kooo/evcam/MainActivity.java)）：状态显示关闭时暂停每秒无效 `setText()` 与定时器，恢复显示时立即同步刷新。
10. **普通多窗预览轻量化（去圆角裁剪与防冗余合成）**（[AppConfig.java](../../app/src/main/java/com/kooo/evcam/AppConfig.java)、[AutoFitTextureView.java](../../app/src/main/java/com/kooo/evcam/AutoFitTextureView.java)、[EditablePreviewLayout.java](../../app/src/main/java/com/kooo/evcam/ui/EditablePreviewLayout.java)、[SurroundModeAView.java](../../app/src/main/java/com/kooo/evcam/surround/SurroundModeAView.java)、[PreviewModeController.java](../../app/src/main/java/com/kooo/evcam/ui/PreviewModeController.java)、[AppearanceSettingsFragment.java](../../app/src/main/java/com/kooo/evcam/ui/AppearanceSettingsFragment.java)）：新增默认关闭的「预览窗格圆角裁剪」开关（去 4 路 `TextureView` 16dp `clipToOutline` 及圆角 `ui_card` overdraw，同步 360 叠加框）、`onLayout` 仅尺寸变更且开圆角时 `invalidateOutline()`、非编辑态 `setWillNotDraw(true)`、显式 `TextureView.setOpaque(true)`。

## 待评估/待修复清单（架构级 / 需实车验证）

1. **普通预览彻底合一（深度架构，仅实车仍觉 UI 卡顿时考虑）**：将 4×`TextureView` 替换为单 `SurfaceView`/`GLSurfaceView` 统一四格渲染（需同步改造 `takePicture()` 脱离 `textureView.getBitmap()`）。
2. **A 版多路录制共享单个 GL 线程与 `EGLContext`**：合并 3~4 套独立 GL 上下文，需结合实车 `glAvgUs`/`glMaxUs` 与 GPU 负载判断是否值得改。
3. **Camera2 物理流减半 / 预览小尺寸流**：评估 E5 HAL 是否支持 `recordSurface` 共享预览 `OutputConfiguration` 或预览降尺寸（需结合实车 `captureFps`/`sensorMaxGapMs` 决定）。
4. **E5 硬件编码器实时参数 A/B 验证（遵守 `P004`）**：实车逐项验证 `KEY_MAX_B_FRAMES = 0`、`KEY_PRIORITY = 0`、`KEY_OPERATING_RATE` 的兼容性与延迟收益。
5. **无水印/无调色直连编码与 A/B 方案对照**：结合实车日志（`software`、`actualFps`、`glAvgUs`、`writerMaxUs`）决定是否推进直连或默认使用 B 版合成。

## 验证证据（2026-11-22 12:38:25 +08:00）

- **已通过（离线）**：新增 6 项单测（[RecordingBitrateTest.java](../../app/src/test/java/com/kooo/evcam/camera/RecordingBitrateTest.java)、[CodecDrainTest.java](../../app/src/test/java/com/kooo/evcam/camera/CodecDrainTest.java)、[VehicleStatusWatermarkTest.java](../../app/src/test/java/com/kooo/evcam/camera/VehicleStatusWatermarkTest.java)、[SurroundRenderThrottleTest.java](../../app/src/test/java/com/kooo/evcam/surround/SurroundRenderThrottleTest.java)、[SettingsCatalogAndAppearanceTest.java](../../app/src/test/java/com/kooo/evcam/ui/SettingsCatalogAndAppearanceTest.java)）；`tools/verify.ps1` 双风味 **`650` 测 `0` 失败/`0` 错误/`0` 跳过**（`build-20261122-123107-542.log`），`assembleDebug`/`assembleRelease` 全过，`lintIndependentDebug`/`lintCompositeDebug` 均为 **`0` errors**（`2227` warnings）。
- **未执行**：尚未在银河 E5 实车验证帧率、温度、360 模式 A 与多窗预览流畅度。

## 最近记录

- 2026-11-22 12:38:25 +08:00 | 完成普通多窗预览轻量化（共 10 项优化，`1.6.6-test-11221230`） | 新增默认关闭的「预览窗格圆角裁剪」开关、去 `clipToOutline`/overdraw 与冗余 `invalidateOutline()`、非编辑态 `setWillNotDraw(true)` 及 `setOpaque(true)`；双风味 `650` 测与构建/Lint `0` errors 全过，准备提交封包 `build24` | [EditablePreviewLayout.java](../../app/src/main/java/com/kooo/evcam/ui/EditablePreviewLayout.java)
- 2026-11-22 09:35:21 +08:00 | 完成第一批 9 项录像与预览性能优化（`1.6.6-test-11220924`） | 落地 GL 去同步/去冗余绑定、分段异步收尾、排空防空轮询、HEVC 码率生效、水印零分配、中转降优、360 VBO/IBO+OES 合并限频+Shader 优化及隐藏计时器停刷；双风味 `648` 测与构建/Lint `0` errors 全过 | [SurroundGlRenderer.java](../../app/src/main/java/com/kooo/evcam/surround/SurroundGlRenderer.java)

## 下一步

1. 提交推送源码、封包 `build24`，安装至银河 E5 实车采集会话日志，依据 2/3/4/5 验证指标决定后续是否需要深度架构改造。
