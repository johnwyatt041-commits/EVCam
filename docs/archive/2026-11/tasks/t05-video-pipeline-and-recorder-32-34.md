# 任务记录：T05 视频管线优化、调色/水印/提示音（build06）

- 最后更新：2026-11-16 14:06:00 +08:00（Asia/Hong_Kong）
- 最后核对：2026-11-16 14:06:00 +08:00（Asia/Hong_Kong）
- 任务/执行者：`t05-video-pipeline-and-recorder-32-34` / Antigravity
- 分支/工作树：`codex/e5-recording-startup-fix` / `E:\GPT\EVCam`（源码 `ef3ea32`，Tag `v1.6.6-test-11161400-build06`）
- 状态：已完成代码、单元测试、`build06` 双风味 Release(R8) 打包（离线通过，**未上车实测**）

## 实施摘要（仅限 T05，未改 T06–T10）

1. **零 FBO 直通旁路与单遍按需着色（拒绝 SC 6 槽 FBO 与 `Lf9` 20ms 网格）**：
   - 新增 [ImageAdjustmentConfig.java](..\..\..\..\app\src\main\java\com\kooo\evcam\camera\ImageAdjustmentConfig.java)，强化 `FrameTimestampClock` 硬件捕获时间戳驱动；`3.2 画面` 总开关关闭或默认参数时，[EglSurfaceEncoder.java](..\..\..\..\app\src\main\java\com\kooo\evcam\camera\EglSurfaceEncoder.java) 与 [MultiCameraCompositor.java](..\..\..\..\app\src\main\java\com\kooo\evcam\camera\MultiCameraCompositor.java) 直接走零开销直通 OES Shader。
   - 开启时单遍内联完成亮度/对比度/饱和度、分镜头边缘裁剪（`0-20%`）及镜像；[CodecVideoRecorder.java](..\..\..\..\app\src\main\java\com\kooo\evcam\camera\CodecVideoRecorder.java) 新增 `setSourceSize` 保持 Camera2 原生尺寸协商，画质缩放仅作用于 GL/MediaCodec 偶数对齐输出。
   - 分镜头水平镜像接入 `T04` `phys_mirror_h_<pos>`，同步主预览、补盲、EVCC 副屏、ESP32 `MjpegStreamManager` 与录像。
2. **已定方案 A 帧率与 4 路录制开关隔离**：
   - 保留日常 `前/后/左/右` 4 路独立录制开关，与 `T03` 预览显隐/停流及飞书四路隔离；[AppConfig.java](..\..\..\..\app\src\main\java\com\kooo\evcam\AppConfig.java) 与 [SingleCamera.java](..\..\..\..\app\src\main\java\com\kooo\evcam\camera\SingleCamera.java) 实现录像目标帧率（自动 25fps / 15/20/25/30）与非录制预览限帧。
3. **`3.3 显示` 四模块单纹理水印 & `3.4 提示音`**：
   - 升级 [VehicleStatusWatermark.java](..\..\..\..\app\src\main\java\com\kooo\evcam\camera\VehicleStatusWatermark.java) 为单张按秒+状态版本号 `texSubImage2D` 刷新的合并层，支持日期时间、自定义文字（<=40字）、车速、档位/刹车/转向（无信号显示 `未知`）、4 角落位置及卡片/阴影样式，关闭即释放零残留。
   - 新增 [RecordingSoundPlayer.java](..\..\..\..\app\src\main\java\com\kooo\evcam\camera\RecordingSoundPlayer.java) 并接入 [MainActivity.java](..\..\..\..\app\src\main\java\com\kooo\evcam\MainActivity.java)，支持开始/结束独立配置（不播放/系统提示音/`Download` 或系统选取 <=20MB 音频）。
   - 在 [fragment_recorder_settings.xml](..\..\..\..\app\src\main\res\layout\fragment_recorder_settings.xml) 与 [RecorderSettingsFragment.java](..\..\..\..\app\src\main\java\com\kooo\evcam\RecorderSettingsFragment.java) 接入 `录制 / 画面 / 显示 / 提示音` 顶部切换标签。

## 离线验证与 `build06` 交付

- **单元测试（`ef3ea32`）**：`tools/verify.ps1` 通过，`266` 项 JVM 单测（A `133` + B `133`，`0` 失败/错误/跳过），含新增 [ImageAdjustmentConfigTest.java](..\..\..\..\app\src\test\java\com\kooo\evcam\camera\ImageAdjustmentConfigTest.java) 与 [VehicleStatusWatermarkTest.java](..\..\..\..\app\src\test\java\com\kooo\evcam\camera\VehicleStatusWatermarkTest.java)。
- **编译与 Lint**：Debug + Release(R8) 通过；`:app:lintIndependentDebug --offline` 保持 `19` 项既有错误（`0` 新增）、`1797` 警告。
- **签名与原生库**：A/B Release APK `apksigner verify` v2 通过（Cert `a40da8...1bf5dc`），均含 `arm64-v8a/libvhal_decoder.so`（`8408`B，`243a92bc...`）。
- **`build06` 交付物**：
  - `EVCam-E5-A-Independent-1.6.6-test-11161400-build06-release.apk`（`6,516,731`B，SHA256 `09cd5c40...bd3811b`）
  - `EVCam-E5-B-Composite-1.6.6-test-11161400-build06-release.apk`（`6,533,115`B，SHA256 `a2577108...fbaed52`）
  - [mappings.zip](..\..\..\..\outputs\archive\EVCam-E5-build06-1.6.6-test-11161400-mappings.zip) 配套文件：[manifest.json](..\..\..\..\outputs\archive\EVCam-E5-build06-1.6.6-test-11161400-manifest.json)、[notes.md](..\..\..\..\outputs\archive\EVCam-E5-build06-1.6.6-test-11161400-notes.md)、[SHA256.txt](..\..\..\..\outputs\archive\EVCam-E5-build06-1.6.6-test-11161400-SHA256.txt)。

## 实车待验项（未上车实测）

- E5 实车行车录制真实帧率、CPU/温度表现及 `3.2 画面` 直通旁路 vs 调色/画质缩放 HAL 协商实测。
- `3.3` 四模块水印与 VHAL 实时档位/车速/刹车/转向联动，以及 `3.4` 提示音在车机音频通道中的实车播放表现。
- 联合验收 `T01`–`T05`、`build05` SC UI、双 HUD 与双镜像对齐。

## 下一步

1. 基于 `build06` 在新会话启动 **`T06`**（车辆状态规则引擎与信号预设匹配 `3.1`+`10.`）或安装 `build06` 上车联合实测。
