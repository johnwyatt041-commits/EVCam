# 项目进度

最后更新/核对：2026-11-23 09:26:36 +08:00（Asia/Hong_Kong）
维护：Antigravity（已下线 `composite(B)` 构建风味并锁定仅在 **`independent(A)`** 迭代；已完成**批次 A (`A1~A4`) + 批次 B (`B1~B3`) + 批次 C (`C1~C4`)** 离线实现与单 A 验证，工作区版本 `1.6.6-test-11230919`，未提交待合流打包实测）。执行规范：[PROGRESS_RULES.md](PROGRESS_RULES.md)。

## 当前快照

| 项目 | 当前状态 |
| --- | --- |
| 分支与提交 | `codex/e5-recording-startup-fix` 基线 `006ebb5`（`build25`）；**本地未提交批次 A + B + C (`A1~A4`, `B1~B3`, `C1~C4`)**（`1.6.6-test-11230919`） |
| 本地交付 | `outputs/` 顶层保留 **`build24`** + **`build25`**（`build23` 已归档至 `outputs/archive/`）；批次 A/B/C 仅做离线编译未另出交付包 |
| GitHub | `johnwyatt041-commits/EVCam` 最新 Pre-release：[`v1.6.6-test-11222113-build25`](https://github.com/johnwyatt041-commits/EVCam/releases/tag/v1.6.6-test-11222113-build25)（5 资产 SHA256 核验一致） |
| 离线与设备验证 | **批次 A+B+C 已过单 A 离线验证**（`.\tools\verify.ps1` `395` 测 `0` 败/错/跳 + Debug/Release + `:app:lintIndependentDebug` `0` errors / `2243` warnings）；待合流实测 |
| 实车状态 | 批次 A/B/C 全部离线完成待打包实车验证，并同步由用户关闭车机外部 D 挡脚本 |

## 当前结论与确认边界

- **批次 A/B/C 已离线完成，待实车验证**：详见 [录像与预览性能优化任务](tasks/optimize-recording-preview-perf.md)（批次 C 完成视频/照片回看后台异步扫盘与加载态、`PlaybackTimelineBuilder` 滤 `.`/`.trashed-`/`trashed-` 并内存 Set O(1) 判 `.lock`、`PlaybackThumbnailGuard`+`ExpandableVideoGroupAdapter` 每组仅加载 1 张已录主路代表缩略图且绝不显示未录视角+限流/LRU 负缓存、以及回看页与记录仪设置页安全手动清理不可恢复碎片双入口；通过 `395` 测与 Lint `0` errors）。
- **U 盘手动写入速度检测已离线完成**：详见 [U 盘速度检测任务](tasks/add-usb-speed-test.md)（通过 `FileChannel.force(true)` 测算均速与最低分块速度，评估直写或中转写入要求）。
- **多路录像启动、U 盘中转/遗留 `.pending`/动态清理、模式 A 断联与压制规则忙等 6 项问题已离线修复**：详见 [多路录制与系统可靠性修复](tasks/fix-recording-storage-modea-rule-reliability.md)（全 6 项均在源码核实存在）。
- **回放局域网分享“未检测到网卡”已离线修复**：详见 [回放分享网卡修复任务](tasks/fix-lan-share-nic-detection.md)。
- **主预览视图「左右 E」已离线完成**：详见 [新增视图 E 任务](tasks/add-preview-mode-e-sides.md)（左摄居左、右摄居右，实车稳态 `24.82fps`）。
- **补盲三模式输出、副屏坐标校正、单卡统一调校与飞书测试连接已离线完成**：详见 [补盲与飞书测试连接任务](tasks/fix-blindspot-screens-and-feishu-test.md)。
- **`build21` 已合并发布方案 B 修复与原厂 `avm_calib_res` 格式直导**：详见 [实车修复与 4.3 外参微调](tasks/fix-e5-realcar-360-guidance-mode-d-ui.md)。
- **用户规则与历史基线保持**：① **鱼眼矫正保持关闭**（`AppFeatures.FISHEYE_CORRECTION = false`）；② **ESP32 与浏览器投屏保持保留**（`AppFeatures.NETWORK_STREAMING = true`）；手机端 APVP `40007`、双层引导线、模式 D/E、四分栏透明度及 `.mp4.pending` 修复均保持。

## 下一步

1. 提交并打包单 A 版（`independent`）供银河 E5 实车验证批次 A+B+C，并请用户关闭车机外部 D 挡 `start_recording` 脚本。

## 最近更新（最多5条）

- 2026-11-23 09:26:36 +08:00 | 完成批次 C (`C1~C4`) 离线修复与单 A 版验证（`1.6.6-test-11230919`） | 回放异步扫盘加加载态、滤隐藏/回收站与内存 Set O(1) 判锁、每组 1 张已录主路代表图含限流负缓存、回看/设置安全手动清理碎片；`verify.ps1` `395` 测与 Lint `0` errors 通过 | [性能优化清单](tasks/optimize-recording-preview-perf.md)
- 2026-11-23 01:53:49 +08:00 | 完成批次 B (`B1~B3`) 离线修复与单 A 版验证（`1.6.6-test-11230150`） | 消 Mode A 双绑定/`200ms` 匿名重建/`onClosed` 错清，修 `-16` 延迟移除且不污染主通道不断录，加三保险 R 挡/AVM 避让与 Mode A `View.GONE`；`verify.ps1` `389` 测与 Lint `0` errors 通过 | [性能优化清单](tasks/optimize-recording-preview-perf.md)
- 2026-11-23 01:28:05 +08:00 | 完成批次 A (`A1~A4`) 离线修复与单 A 版验证（`1.6.6-test-11230123`） | 合入 `start_recording` 短路、切模式保留规则、VHAL `stopAsync` 静默、直连 `frames` 统计及 6 项新单测；重跑 `verify.ps1` `380` 测全过且 `:app:lintIndependentDebug` `0` errors，锁定 B/C 方案 | [性能优化清单](tasks/optimize-recording-preview-perf.md)
- 2026-11-23 01:05:26 +08:00 | 整理 `build25` 分批整改清单（A/B/C+D）并确认车机外部 D 挡脚本（`1.6.6-test-11222113`） | 核实 `M2`/`S2`/`S3`/`M1-ModeD` 有效（总加权 `19.23fps`，稳态 `24~25fps`），用户确认存在未关 D 挡外部脚本；拆分为批次 A/B/C+D | [性能优化清单](tasks/optimize-recording-preview-perf.md)
- 2026-11-22 21:40:18 +08:00 | 封包发布 build25 并推送 GitHub Pre-release（`1.6.6-test-11222113`） | 源码 `006ebb5` 打标 `v1.6.6-test-11222113-build25` 推送，单 A 版 APK/`mappings.zip` 等 5 资产远端 SHA256 核验一致、`build23` 归档，通过 `374` 测与 Lint `0` errors | [性能优化清单](tasks/optimize-recording-preview-perf.md)

## 归档与按需索引

- **归档全索引**：[2026-11 历史文档与任务全索引](archive/2026-11/INDEX.md)（`T01`–`T10`、`build01`–`build13`、早期报告均已收录）；APK 归档见 `outputs/archive/INDEX.md`
- **按需规范**：[需求](REQUIREMENTS.md) · [坑表](PITFALLS.md) · [协作](COLLABORATION.md) · [开发](DEVELOPMENT.md) · [本机资料](AI_HANDOFF.md)
