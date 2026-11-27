# 项目进度

最后更新/核对：2026-11-28 02:07:30 +08:00（Asia/Hong_Kong）
维护：Antigravity（已下线 `composite(B)` 构建风味并锁定仅在 **`independent(A)`** 迭代；最新正式封包为 `build28` / `1.6.6-test-11260052`；正在封包「6. 广播中心」`build29` / `1.6.6-test-11280205` / `versionCode = 89`）。执行规范：[PROGRESS_RULES.md](PROGRESS_RULES.md)。

## 当前快照

| 项目 | 当前状态 |
| --- | --- |
| 分支与提交 | `codex/e5-recording-startup-fix`（正在提交并封包「6. 广播中心」`1.6.6-test-11280205` / `build29`） |
| 本地交付 | `outputs/` 顶层保留 **`build27`** + **`build28`**（`build29` 封包中） |
| GitHub | `johnwyatt041-commits/EVCam` 最新 Pre-release：[`v1.6.6-test-11260052-build28`](https://github.com/johnwyatt041-commits/EVCam/releases/tag/v1.6.6-test-11260052-build28)（5 资产远端 SHA256 核验一致） |
| 离线与设备验证 | **当前工作区已过单 A 离线验证**（`.\tools\verify.ps1` `410` 测 `0` 败/错/跳 + Debug/Release + `:app:lintIndependentDebug` `0` errors / `2263` warnings）；待实车实测 |
| 实车状态 | 待在银河 E5 实车验证原车 360 闪屏修复、按钮广播与广播中心快捷键绑定及批次 A/B/C；回放 Wi-Fi 分享确认 `wlan0 (192.168.14.146)` 需手动开热点才出现，待确认后续方案 |

## 当前结论与确认边界

- **设置页「6. 广播中心」已离线完成（`1.6.6-test-11280205`，`versionCode = 89`）**：详见 [广播中心任务](tasks/add-broadcast-center-ui.md)（收纳 13 个外部控制广播与 `ACTION_KEEP_ALIVE` 共 14 项，支持分类筛选、关键词检索、一键复制包名/Action/完整配置/ADB 命令/全部清单与本机测试，通过 `410` 测、Lint `0` errors 与 APK 签名资产校验）。

- **原车 360/AVM 显示闪屏修复已发布 `build28` GitHub Pre-release（`1.6.6-test-11260052` / `f8c1ac3`，待实车验证）**：详见 [原车 360 闪屏修复任务](tasks/fix-native-360-avm-screen-flicker.md)（补齐无障碍窗口实时推送、后台持续 AVM 巡检、`FloatingWindowService` 立即卸载替代 `GONE`、修 450ms/1000ms 竞态与 OEM 多包名识别、`StandalonePreviewWindow` 倒挡/360 避让；APK `EVCam-E5-A-Independent-1.6.6-test-11260052-build28-release.apk` 与 5 资产远端 SHA256 核验一致，通过 `407` 测与 Lint `0` errors）。
- **外部显式广播快捷控制已发布 `build27` GitHub Pre-release（`1.6.6-test-11231552` / `0bb9b04`），待实车验证**：详见 [外部广播快捷控制任务](tasks/add-external-broadcast-control.md)（13 个显式广播，包名 `com.kooo.evcam`，零常驻后台占用；APK `EVCam-E5-A-Independent-1.6.6-test-11231552-build27-release.apk` 与 5 资产远端 SHA256 核验一致，通过 `405` 测与 Lint `0` errors）。
- **`build26`（批次 A/B/C）已封包发布并推送 GitHub Pre-release，待实车验证**：详见 [录像与预览性能优化任务](tasks/optimize-recording-preview-perf.md)（源码 `2d99f75` 打标 `v1.6.6-test-11230919-build26`，单 A 版 `5` 资产远端 SHA256 一致；通过 `395` 测与 Lint `0` errors）。
- **U 盘手动写入速度检测已离线完成**：详见 [U 盘速度检测任务](tasks/add-usb-speed-test.md)（通过 `FileChannel.force(true)` 测算均速与最低分块速度，评估直写或中转写入要求）。
- **多路录像启动、U 盘中转/遗留 `.pending`/动态清理、模式 A 断联与压制规则忙等 6 项问题已离线修复**：详见 [多路录制与系统可靠性修复](tasks/fix-recording-storage-modea-rule-reliability.md)（全 6 项均在源码核实存在）。
- **回放局域网分享“未检测到网卡”已离线修复（确认 `wlan0` 为手动热点网卡，待选定后续方案）**：详见 [回放分享网卡修复任务](tasks/fix-lan-share-nic-detection.md)（实车确认开热点后检出 `wlan0 · Wi-Fi (192.168.14.146)` 并起服 `18765`；静态核对 SmartCam V3.0.56 同样不自动开系统热点，待确认不开热点时 SC 表现及是否加自动复扫/UI 优化或实验性 `LocalOnlyHotspot`）。
- **主预览视图「左右 E」已离线完成**：详见 [新增视图 E 任务](tasks/add-preview-mode-e-sides.md)（左摄居左、右摄居右，实车稳态 `24.82fps`）。
- **补盲三模式输出、副屏坐标校正、单卡统一调校与飞书测试连接已离线完成**：详见 [补盲与飞书测试连接任务](tasks/fix-blindspot-screens-and-feishu-test.md)。
- **`build21` 已合并发布方案 B 修复与原厂 `avm_calib_res` 格式直导**：详见 [实车修复与 4.3 外参微调](tasks/fix-e5-realcar-360-guidance-mode-d-ui.md)。
- **用户规则与历史基线保持**：① **鱼眼矫正保持关闭**（`AppFeatures.FISHEYE_CORRECTION = false`）；② **ESP32 与浏览器投屏保持保留**（`AppFeatures.NETWORK_STREAMING = true`）；手机端 APVP `40007`、双层引导线、模式 D/E、四分栏透明度及 `.mp4.pending` 修复均保持。

## 下一步

1. 在银河 E5 实车验证原车 360 闪屏修复（`1.6.6-test-11260052`）、按钮广播与批次 A+B+C，并同步关闭车机外部 D 挡 `start_recording` 脚本。
2. **[待办]** 回放 Wi-Fi 分享实车反馈跟进：已确认 `wlan0 (192.168.14.146)` 为手动开启车机热点后才出现的 SoftAP 网卡；待用户确认不开热点时 SC 是否也无网卡，并决定是否增加网络状态自动复扫/下拉箭头优化及实验性 `LocalOnlyHotspot`。

## 最近更新（最多5条）

- 2026-11-28 02:07:30 +08:00 | 完成设置页「6. 广播中心」源码定版与验证（`1.6.6-test-11280205` / `versionCode = 89`） | 新增 `ExternalBroadcastCatalog` 与 `BroadcastCenterFragment`（14 项内置广播筛选/搜索/复制与本机测试），`verify.ps1` `410` 测、Lint `0` errors 与 APK 签名资产校验通过 | [广播中心任务](tasks/add-broadcast-center-ui.md)
- 2026-11-26 20:22:00 +08:00 | 记录回放分享需手动开热点的实车反馈与 SC 核对 | 确认 `wlan0 (192.168.14.146)` 为车机 SoftAP 网口；核对 SmartCam V3.0.56 同样不自动开系统热点，待用户确认后续方案与不开热点日志/对比 | [回放分享网卡修复](tasks/fix-lan-share-nic-detection.md)
- 2026-11-26 01:08:30 +08:00 | 封包发布 build28 并推送 GitHub Pre-release（`1.6.6-test-11260052`） | 源码 `f8c1ac3` 打标 `v1.6.6-test-11260052-build28` 推送，单 A 版 `5` 资产远端 SHA256 核验一致、`build26` 归档，通过 `407` 测与 Lint `0` errors | [360闪屏修复](tasks/fix-native-360-avm-screen-flicker.md)
- 2026-11-23 16:14:36 +08:00 | 封包发布 build27 并推送 GitHub Pre-release（`1.6.6-test-11231552`） | 源码 `0bb9b04` 打标 `v1.6.6-test-11231552-build27` 推送，单 A 版 `5` 资产远端 SHA256 核验一致、`build25` 归档，通过 `405` 测与 Lint `0` errors | [外部广播控制](tasks/add-external-broadcast-control.md)
- 2026-11-23 10:39:05 +08:00 | 封包发布 build26 并推送 GitHub Pre-release（`1.6.6-test-11230919`） | 源码 `2d99f75` 打标 `v1.6.6-test-11230919-build26` 推送，单 A 版 `5` 资产远端 SHA256 一致、`build24` 归档，通过 `395` 测与 Lint `0` errors | [性能优化清单](tasks/optimize-recording-preview-perf.md)

## 归档与按需索引

- **归档全索引**：[2026-11 历史文档与任务全索引](archive/2026-11/INDEX.md)（`T01`–`T10`、`build01`–`build13`、早期报告均已收录）；APK 归档见 `outputs/archive/INDEX.md`
- **按需规范**：[需求](REQUIREMENTS.md) · [坑表](PITFALLS.md) · [协作](COLLABORATION.md) · [开发](DEVELOPMENT.md) · [本机资料](AI_HANDOFF.md)
