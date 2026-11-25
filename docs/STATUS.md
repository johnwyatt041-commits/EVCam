# 项目进度

最后更新/核对：2026-11-26 01:08:30 +08:00（Asia/Hong_Kong）
维护：Antigravity（已下线 `composite(B)` 构建风味并锁定仅在 **`independent(A)`** 迭代；已提交并封包发布**原车 360/AVM 显示闪屏修复** `build28` / `1.6.6-test-11260052` / `versionCode = 88`）。执行规范：[PROGRESS_RULES.md](PROGRESS_RULES.md)。

## 当前快照

| 项目 | 当前状态 |
| --- | --- |
| 分支与提交 | `codex/e5-recording-startup-fix` 最新源码 `f8c1ac3`（**原车 360/AVM 闪屏修复** `1.6.6-test-11260052` / `build28`，含本发布记录提交） |
| 本地交付 | `outputs/` 顶层保留 **`build27`** + **`build28`**（已归档 `build26` 至 `outputs/archive/`） |
| GitHub | `johnwyatt041-commits/EVCam` 最新 Pre-release：[`v1.6.6-test-11260052-build28`](https://github.com/johnwyatt041-commits/EVCam/releases/tag/v1.6.6-test-11260052-build28)（5 资产远端 SHA256 核验一致） |
| 离线与设备验证 | **当前工作区已过单 A 离线验证**（`.\tools\verify.ps1` `407` 测 `0` 败/错/跳 + Debug/Release + `:app:lintIndependentDebug` `0` errors / `2244` warnings）；待实车实测 |
| 实车状态 | 待在银河 E5 实车验证原车 360 闪屏修复、按钮广播与批次 A/B/C，并顺便抓回放 Wi-Fi 分享网卡日志 |

## 当前结论与确认边界

- **原车 360/AVM 显示闪屏修复已发布 `build28` GitHub Pre-release（`1.6.6-test-11260052` / `f8c1ac3`，待实车验证）**：详见 [原车 360 闪屏修复任务](tasks/fix-native-360-avm-screen-flicker.md)（补齐无障碍窗口实时推送、后台持续 AVM 巡检、`FloatingWindowService` 立即卸载替代 `GONE`、修 450ms/1000ms 竞态与 OEM 多包名识别、`StandalonePreviewWindow` 倒挡/360 避让；APK `EVCam-E5-A-Independent-1.6.6-test-11260052-build28-release.apk` 与 5 资产远端 SHA256 核验一致，通过 `407` 测与 Lint `0` errors）。
- **外部显式广播快捷控制已发布 `build27` GitHub Pre-release（`1.6.6-test-11231552` / `0bb9b04`），待实车验证**：详见 [外部广播快捷控制任务](tasks/add-external-broadcast-control.md)（13 个显式广播，包名 `com.kooo.evcam`，零常驻后台占用；APK `EVCam-E5-A-Independent-1.6.6-test-11231552-build27-release.apk` 与 5 资产远端 SHA256 核验一致，通过 `405` 测与 Lint `0` errors）。
- **`build26`（批次 A/B/C）已封包发布并推送 GitHub Pre-release，待实车验证**：详见 [录像与预览性能优化任务](tasks/optimize-recording-preview-perf.md)（源码 `2d99f75` 打标 `v1.6.6-test-11230919-build26`，单 A 版 `5` 资产远端 SHA256 一致；通过 `395` 测与 Lint `0` errors）。
- **U 盘手动写入速度检测已离线完成**：详见 [U 盘速度检测任务](tasks/add-usb-speed-test.md)（通过 `FileChannel.force(true)` 测算均速与最低分块速度，评估直写或中转写入要求）。
- **多路录像启动、U 盘中转/遗留 `.pending`/动态清理、模式 A 断联与压制规则忙等 6 项问题已离线修复**：详见 [多路录制与系统可靠性修复](tasks/fix-recording-storage-modea-rule-reliability.md)（全 6 项均在源码核实存在）。
- **回放局域网分享“未检测到网卡”已离线修复（待补实车网卡对齐）**：详见 [回放分享网卡修复任务](tasks/fix-lan-share-nic-detection.md)。
- **主预览视图「左右 E」已离线完成**：详见 [新增视图 E 任务](tasks/add-preview-mode-e-sides.md)（左摄居左、右摄居右，实车稳态 `24.82fps`）。
- **补盲三模式输出、副屏坐标校正、单卡统一调校与飞书测试连接已离线完成**：详见 [补盲与飞书测试连接任务](tasks/fix-blindspot-screens-and-feishu-test.md)。
- **`build21` 已合并发布方案 B 修复与原厂 `avm_calib_res` 格式直导**：详见 [实车修复与 4.3 外参微调](tasks/fix-e5-realcar-360-guidance-mode-d-ui.md)。
- **用户规则与历史基线保持**：① **鱼眼矫正保持关闭**（`AppFeatures.FISHEYE_CORRECTION = false`）；② **ESP32 与浏览器投屏保持保留**（`AppFeatures.NETWORK_STREAMING = true`）；手机端 APVP `40007`、双层引导线、模式 D/E、四分栏透明度及 `.mp4.pending` 修复均保持。

## 下一步

1. 在银河 E5 实车验证原车 360 闪屏修复（`1.6.6-test-11260052`）、按钮广播与批次 A+B+C，并同步关闭车机外部 D 挡 `start_recording` 脚本。
2. **[待办]** 回放 Wi-Fi 分享不开热点查齐网卡：在实车不开热点下对比 SC「分享网卡」与 EVCam 下拉网卡及导出日志中的 `LAN NIC scan`，补齐网卡识别/默认排序并优化下拉箭头与诊断展示。

## 最近更新（最多5条）

- 2026-11-26 01:08:30 +08:00 | 封包发布 build28 并推送 GitHub Pre-release（`1.6.6-test-11260052`） | 源码 `f8c1ac3` 打标 `v1.6.6-test-11260052-build28` 推送，单 A 版 `5` 资产远端 SHA256 核验一致、`build26` 归档，通过 `407` 测与 Lint `0` errors | [360闪屏修复](tasks/fix-native-360-avm-screen-flicker.md)
- 2026-11-23 16:14:36 +08:00 | 封包发布 build27 并推送 GitHub Pre-release（`1.6.6-test-11231552`） | 源码 `0bb9b04` 打标 `v1.6.6-test-11231552-build27` 推送，单 A 版 `5` 资产远端 SHA256 核验一致、`build25` 归档，通过 `405` 测与 Lint `0` errors | [外部广播控制](tasks/add-external-broadcast-control.md)
- 2026-11-23 10:39:05 +08:00 | 封包发布 build26 并推送 GitHub Pre-release（`1.6.6-test-11230919`） | 源码 `2d99f75` 打标 `v1.6.6-test-11230919-build26` 推送，单 A 版 `5` 资产远端 SHA256 一致、`build24` 归档，通过 `395` 测与 Lint `0` errors | [性能优化清单](tasks/optimize-recording-preview-perf.md)
- 2026-11-23 09:26:36 +08:00 | 完成批次 C (`C1~C4`) 离线修复与单 A 版验证（`1.6.6-test-11230919`） | 回放异步扫盘加加载态、滤隐藏/回收站与内存 Set O(1) 判锁、每组 1 张已录主路代表图含限流负缓存、回看/设置安全手动清理碎片；`verify.ps1` `395` 测与 Lint `0` errors 通过 | [性能优化清单](tasks/optimize-recording-preview-perf.md)
- 2026-11-23 01:53:49 +08:00 | 完成批次 B (`B1~B3`) 离线修复与单 A 版验证（`1.6.6-test-11230150`） | 消 Mode A 双绑定/`200ms` 匿名重建/`onClosed` 错清，修 `-16` 延迟移除且不污染主通道不断录，加三保险 R 挡/AVM 避让与 Mode A `View.GONE`；`verify.ps1` `389` 测与 Lint `0` errors 通过 | [性能优化清单](tasks/optimize-recording-preview-perf.md)

## 归档与按需索引

- **归档全索引**：[2026-11 历史文档与任务全索引](archive/2026-11/INDEX.md)（`T01`–`T10`、`build01`–`build13`、早期报告均已收录）；APK 归档见 `outputs/archive/INDEX.md`
- **按需规范**：[需求](REQUIREMENTS.md) · [坑表](PITFALLS.md) · [协作](COLLABORATION.md) · [开发](DEVELOPMENT.md) · [本机资料](AI_HANDOFF.md)
