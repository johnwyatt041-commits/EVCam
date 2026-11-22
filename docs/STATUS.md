# 项目进度

最后更新/核对：2026-11-22 21:40:18 +08:00（Asia/Hong_Kong）
维护：Antigravity（已下线 `composite(B)` 构建风味并锁定仅在 **`independent(A)`** 迭代；已完成 [`build25`](tasks/optimize-recording-preview-perf.md) 源码提交 `006ebb5`、标签 `v1.6.6-test-11222113-build25`、单 A 封包与 GitHub Pre-release 上传核验，版本 `1.6.6-test-11222113`）。执行规范：[PROGRESS_RULES.md](PROGRESS_RULES.md)。

## 当前快照

| 项目 | 当前状态 |
| --- | --- |
| 分支与提交 | `codex/e5-recording-startup-fix` 已推送 `006ebb5`；最新 tag 为 **`v1.6.6-test-11222113-build25`**（合入单 A 版收敛 + **`S1~S4` + `M1~M2`**） |
| 本地交付 | `outputs/` 顶层保留 **`build24`** + **`build25`**（`build23` 已归档至 `outputs/archive/`）；仅产出 A 版 5 资产 |
| GitHub | `johnwyatt041-commits/EVCam` 最新 Pre-release：[`v1.6.6-test-11222113-build25`](https://github.com/johnwyatt041-commits/EVCam/releases/tag/v1.6.6-test-11222113-build25)（5 资产 SHA256 核验一致） |
| 离线与设备验证 | **已通过单 A 版验证**（`verify.ps1` `374` 测 `0` 失败/`0` 错误/`0` 跳过 + A 版 Debug/Release + `:app:lintIndependentDebug` `0` errors + `apksigner` v2）；**`build24` 实车日志已核实**，`build25` 待实车复测 |
| 实车状态 | **`build25`**（`independent(A)`，`S1~S4` + `M1~M2`）已上 GitHub 待安装上车实测，另待测 U 盘测速、`build22` 补盲/网卡/飞书 |

## 当前结论与确认边界

- **10 项录像与预览性能优化已离线完成**：详见 [录像与预览性能优化任务](tasks/optimize-recording-preview-perf.md)（录像侧：去逐帧 `glGetError()` 与冗余 `eglMakeCurrent()`、分段异步收尾、自适应退避防 10ms 空轮询、修复 HEVC 忽略用户码率、水印零分配与中转降优；预览侧：360 模式 A 网格迁 VBO/IBO、4 路 OES 33ms 合并限频、Shader 跳过非接缝双采样与缓存 Location/Mirror、隐藏计时器停刷 UI、普通多窗预览默认去圆角裁剪与防冗余 Outline/overdraw）。
- **U 盘手动写入速度检测已离线完成**：详见 [U 盘速度检测任务](tasks/add-usb-speed-test.md)（在「记录仪 → 存储位置」下方新增「U盘写入速度检测」卡片，通过 `FileChannel.force(true)` 真实落盘测算均速与最低分块速度，结合当前路数/分辨率/帧率/码率等级与 A/B 合成模式评估是否满足直写或中转写入要求）。

- **多路录像启动、U 盘中转/遗留 `.pending`/动态清理、模式 A 断联与压制规则忙等 6 项问题已离线修复**：详见 [多路录制与系统可靠性修复](tasks/fix-recording-storage-modea-rule-reliability.md)（全 6 项均在源码核实存在：`RecordingStartupTracker` 全路启动跟踪、`FileTransferManager` 落盘核验后删+`.part`原子拷贝、`ActiveRecordingRegistry`+`PendingFileRecoveryManager` 区分活跃与遗留 `.pending`、`StorageSpaceGuard` 分段 O(1) 门控与写入速率动态预留、`SingleCamera`/`SurroundGlRenderer`/`SurroundCameraWatchdog` 修复模式 A 会话死锁与断联自愈、`VehicleRuleEngine` 消除被压制延迟规则 20ms 忙等）。
- **回放局域网分享“未检测到网卡”已离线修复**：详见 [回放分享网卡修复任务](tasks/fix-lan-share-nic-detection.md)（对照 SmartCam V3.0.56 `Laq1`/`Lvp1`/`Lxn0`，改为 `contains` 匹配与逐接口容错，增加 `ConnectivityManager`/`WifiManager` 兜底、通配符绑定、异步扫描、「热点设置」与诊断摘要）。
- **主预览视图「左右 E」已离线完成**：详见 [新增视图 E 任务](tasks/add-preview-mode-e-sides.md)（左摄居左、右摄居右默认 50%/50% 并排，隐藏释放前/后主预览流，全链路接入顶栏、布局编辑、外观/360 设置与配置广场）。
- **补盲三模式输出、副屏坐标校正、单卡统一调校与飞书测试连接已离线完成**：详见 [补盲与飞书测试连接任务](tasks/fix-blindspot-screens-and-feishu-test.md)（`仅主屏/仅副屏/主副屏同时`、`0/90/180/270°` 视觉与逻辑坐标正反映射、单卡「先选屏幕再选摄像头+四路同步」、去除重复「分方向画面矫正」卡片，及飞书两步连接诊断）。
- **`build21` 已合并发布方案 B 修复与原厂 `avm_calib_res` 格式直导**：详见 [实车修复与 4.3 外参微调](tasks/fix-e5-realcar-360-guidance-mode-d-ui.md)（`190×462` 车框、零跳变重投影、`Scale`+辅助开关及 `Table.db`+`.param` 原厂直导）。
- **用户规则与历史基线保持**：① **鱼眼矫正保持关闭**（`AppFeatures.FISHEYE_CORRECTION = false`）；② **ESP32 与浏览器投屏保持保留**（`AppFeatures.NETWORK_STREAMING = true`）；手机端 APVP `40007`、双层引导线、模式 D/E、四分栏透明度及 `.mp4.pending` 修复均保持。

## 下一步

1. 安装 **`build25`**（[`v1.6.6-test-11222113-build25`](https://github.com/johnwyatt041-commits/EVCam/releases/tag/v1.6.6-test-11222113-build25)）上银河 E5 实测并开启 `Debug 日志转 INFO`，重点采集：`path=direct` vs `path=gl` 实测帧率、`Mode D`/退后台切换流畅度、悬浮按钮与后台闪屏表现，再按需评估 `L1~L3`。

## 最近更新（最多5条）

- 2026-11-22 21:40:18 +08:00 | 封包发布 build25 并推送 GitHub Pre-release（`1.6.6-test-11222113`） | 源码 `006ebb5` 打标 `v1.6.6-test-11222113-build25` 推送，单 A 版 APK/`mappings.zip` 等 5 资产远端 SHA256 一致、`build23` 归档，通过 `374` 测与 Lint `0` errors，待实车测试 | [性能优化清单](tasks/optimize-recording-preview-perf.md)
- 2026-11-22 21:25:03 +08:00 | 完成 `optimize-recording-preview-perf` 第二组 `M2` 无水印无调色直连编码跳过 GL 与单 A 版离线验证（`1.6.6-test-11222113`） | 新增 `DirectEncodingPolicy` 与设置页可控开关，无水印且无/恒等调色时直连 `MediaCodec` 输入面并保留 `preStartDrain`/首 IDR 门控、跨段/S3/M1 契约与单路一次自动回退 GL；`verify.ps1` `374` 测全过且 `:app:lintIndependentDebug` `0` errors | [性能优化清单](tasks/optimize-recording-preview-perf.md)
- 2026-11-22 20:41:35 +08:00 | 完成 `optimize-recording-preview-perf` 第二组 `M1` 预览隔离重建与单 A 版离线验证（`1.6.6-test-11222037`） | 新增 `RecordingPreviewSessionPolicy`，录制期切 Mode D/前后台/`TextureView` 销毁与 `Mode A` 改走 RepeatingRequest 热切/动态 `OutputConfiguration` 隔离整路重建；`verify.ps1` `357` 测全过且 `:app:lintIndependentDebug` `0` errors | [性能优化清单](tasks/optimize-recording-preview-perf.md)
- 2026-11-22 20:06:06 +08:00 | 完成 `optimize-recording-preview-perf` 第一组 `S1~S4` 优化与单 A 版离线验证（`1.6.6-test-11222001`） | 悬浮窗防重排与轻量参数、VHAL 120s 静默重连消震荡、起录并发异步准备与取消清理、`BlindSpotService` 按需轮询/预创建落地；`verify.ps1` `342` 测全过且 `:app:lintIndependentDebug` `0` errors | [性能优化清单](tasks/optimize-recording-preview-perf.md)
- 2026-11-22 19:20:00 +08:00 | 正式下线 B 版（`composite`）并锁定全项目仅更新 A 版（`1.6.6-test-11221918`） | `app/build.gradle.kts` 移除 `composite` flavor、`verify.ps1` 收敛为 `Independent` 单风味测试/构建，并在 `AGENTS.md`/`START_HERE.md`/`DEVELOPMENT.md`/`REQUIREMENTS.md`/`ARCHITECTURE.md` 固化规则禁止再编发 B 版 | [AGENTS.md](../AGENTS.md)

## 归档与按需索引

- **归档全索引**：[2026-11 历史文档与任务全索引](archive/2026-11/INDEX.md)（`T01`–`T10`、`build01`–`build13`、早期报告均已收录）；APK 归档见 `outputs/archive/INDEX.md`
- **按需规范**：[需求](REQUIREMENTS.md) · [坑表](PITFALLS.md) · [协作](COLLABORATION.md) · [开发](DEVELOPMENT.md) · [本机资料](AI_HANDOFF.md)
