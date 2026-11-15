# 项目进度

最后更新/核对：2026-11-15 22:06:00 +08:00（Asia/Hong_Kong）
维护：Codex / Antigravity（`build01` 已封存；`T02` 方案已定稿待新会话编码）。执行规范：[PROGRESS_RULES.md](PROGRESS_RULES.md)。

## 当前快照

| 项目 | 当前状态 |
| --- | --- |
| 项目/设备 | E:\GPT\EVCam；银河E5，2560×1600 |
| 分支 | `codex/e5-recording-startup-fix`；源码已提交 `ea395a0`（Tag `v1.6.6-test-11152059-build01`） |
| 最新APK | **`build01`：`1.6.6-test-11152059`**（A/B 同签名同包名，未实车） |
| APK源码 | `T01` 源码 `ea395a0`；`outputs/EVCam-E5-build01-1.6.6-test-11152059-manifest.json` 已关联 |
| 本地交付 | `outputs/EVCam-E5-{A-Independent,B-Composite}-1.6.6-test-11152059-build01-release.apk` 及 SHA/mappings/notes |
| GitHub | 已推送分支与 [`v1.6.6-test-11152059-build01`](https://github.com/johnwyatt041-commits/EVCam/releases/tag/v1.6.6-test-11152059-build01) 记录点；旧 `11141103` 保留 |
| 验证 | A/B 共174项(每版87项)JVM、Debug/Release R8 通过；完整Lint保持19项既有错误(0新增)；**`build01` 尚未实车** |
| 实车 | `build01`（含 `T01`）尚未上车实测；A/Smartcam四段短片仅静态/离线分析 |

## 当前结论

- **`build01` 记录点已封存（尚未实车）**：将 `T01` 成果打包为 A/B 两版 Release APK 校验签名与原库一致后同步推送 GitHub，作为 `T02` 前基线；其余详情见 [T01/build01任务](tasks/t01-lifecycle-and-signal-foundation.md)。

- 四段录像中A后台行驶约24.95帧、开预览19.76帧；Smartcam文件约24.5帧但分格近重复，估计20.5–22.6次更新/秒。仅代表各自配置：A三路/Smartcam四路负载不等，同负载优劣与画质未定，B未测。Smartcam有Surface销毁解绑主预览机制，后台瓶颈未锁定。[报告](RECORDING_COMPARISON_20261115.md)
- 已完成 Smartcam V3.0.56 深度静态剖析（未改 app/outputs）：`Lkh.d` 退界面仅清 UI 任务，主进程仍可留驻或由 `BootReceiver`/15m `KeepAliveWorker` 唤醒跑 `SmartcamApplication.d()`（`y93`/VHAL 24 信号/15s WSS），**并非平时只剩 `:core` 单进程**；360/补盲/定位/LAN 分享多为按需开销，重头仍在 6 槽 `2560×1600` RGBA FBO（`~93.75 MiB`）与 `~10.9 Mbps` H.264 编码。后台分格更新偏慢对应 `Lr51<=25`、`Lwz.a` 变帧率 AE、`Lo13` 40ms 相位漂移+复用旧 OES 纹理、`Lf9` 20ms 刚性丢帧及跨 EGL `glFlush()`；直接二开已混淆 APK 风险高，建议保留 EVCam 仅移植 `FRAGMENTED` MP4、`.part` 恢复与配额机制。[3.0.56差异](SMARTCAM_V3.0.56_DIFF.md)
- 用户已确认本次失败为车机权限不足；仅撤销`2082be7`存储改造，恢复11150014及原已确认优化。0220候选保留但标撤回，不作为当前交付；[撤回记录](tasks/recording-storage-eperm.md)。
- 已实施确认的1–5项优化：物理删停用能力/SDK+R8，Glide限额及退场释放，方案3-稳妥时间水印复用/纹理原地更新；保留扫码、飞书、补盲及双HUD。
- R8首轮冷启动暴露Room反射构造器被裁，已精确保留并通过A/B冷启动。保留无障碍保活心跳及HUD共享旧配置键；见[P013/P014](PITFALLS.md)。
- A为日常三路独立文件；B仍1280×800三格合成，每格约640×400、右下黑；飞书四路仍独立。合成双GL本轮无改动。
- 模拟器验证Camera2首写、分段/合成、两种水印、扫码HTTP及飞书封面；录制中清图片缓存不阻断分段。不能替代E5硬件、HEVC、HUD/VHAL、联网飞书及长期测试。

## 下一步

1. 在新会话直接按已定稿方案实施 **`T02`**（默认跟随系统零重建主题、SC 风格左栏 4+2 布局与回看合并、仅展示已实装设置分类+`1.外观与交互`）；[T02任务](tasks/t02-shell-navigation-and-theme.md) · [蓝图](EVCAM_SMARTCAM_REFACTOR_PLAN.md)。
2. 待实车确认：`T01` 彻底退出后重启拦截与无障碍防复活、VHAL/CarSignalManager 四类信号映射、远程退出回执，以及 A/B 同场景帧率与 PSS 对照；[原优化任务](tasks/ab-test-preoptimization.md)。
3. 确认原车 HUD 真实路径，后续在 `T04` 保留双 HUD 并叠加补盲镜像解耦；19 项既有 Lint 错误仍待独立整改。

## 最近更新（最多5条）

- 2026-11-15 22:06:00 +08:00 | T02定稿 | 完成 SC V3.0.56 与 EC 外壳/主题/设置核查，用户拍板「默认跟随系统 / SC 式回看合并入口 / 仅列已实装设置分类」，待新会话实施编码 | [T02任务](tasks/t02-shell-navigation-and-theme.md)
- 2026-11-15 21:36:00 +08:00 | build01存档 | 基于 `T01`（`1.6.6-test-11152059`）打包 `build01` 双风味 Release APK、校验 SHA256/R8 映射并推送 GitHub 标签与 Pre-release（尚未实车测试），转入 `T02` 源码核查 | [T01任务](tasks/t01-lifecycle-and-signal-foundation.md)
- 2026-11-15 21:08:30 +08:00 | T01完成(离线) | 收敛单进程双退出状态与 Option A 重启拦截、清理补盲弹窗/动效、落盘 4 类车辆信号 Hub；174 项单测、Debug/Release R8 与 0 新增 Lint 通过，待实车 | [T01任务](tasks/t01-lifecycle-and-signal-foundation.md)
- 2026-11-15 20:10:42 +08:00 | 规划定稿 | 用户定稿「帧率方案 A」及「飞书若启用仅在 P 档加载、关闭/其他档位彻底断连」规则；更新 `T01`–`T10` 蓝图与任务文档，未改 `app/`/`outputs/` | [蓝图](EVCAM_SMARTCAM_REFACTOR_PLAN.md) · [任务](tasks/evcam-smartcam-refactor-plan.md)
- 2026-11-15 04:28:00 +08:00 | 竞品剖析 | 核实 V3.0.56 退界面仍双进程保活、后台有效帧率/分格跳顿根因（`Lr51`/`Lwz.a`/`Lo13`/`Lf9`/6 槽 FBO）及优化与架构建议（未动态实测，未改 app/outputs） | [任务](tasks/smartcam-v3.0.56-diff.md)

## 按需索引

- [融合重构蓝图](EVCAM_SMARTCAM_REFACTOR_PLAN.md) · [需求](REQUIREMENTS.md) · [协作](COLLABORATION.md) · [坑](PITFALLS.md) · [新AI资料](AI_HANDOFF.md) · [Smartcam全功能](SMARTCAM_FEATURE_CATALOG.md) · [3.0.56差异](SMARTCAM_V3.0.56_DIFF.md)
- [E5启动分析](E5_STARTUP_FIX_20261114.md) · [A/B试录](RECORDING_AB_TEST.md) · [开发](DEVELOPMENT.md)
- 本轮证据：`work/ab-test-preoptimization/`；历史启动证据仍在`work/e5-startup/`，不默认全读。
- [整理前历史快照](archive/2026-11/STATUS_20261114_183403.md)；历史按需读取。
