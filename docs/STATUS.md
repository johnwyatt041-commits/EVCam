# 项目进度

最后更新/核对：2026-11-15 23:00:38 +08:00（Asia/Hong_Kong）
维护：Codex / Antigravity（`build02` 已封存待实车；下一步新会话启动 `T03`）。执行规范：[PROGRESS_RULES.md](PROGRESS_RULES.md)。

## 当前快照

| 项目 | 当前状态 |
| --- | --- |
| 项目/设备 | E:\GPT\EVCam；银河E5，2560×1600 |
| 分支 | `codex/e5-recording-startup-fix`；源码 `72dc456`（`T02`），Tag `v1.6.6-test-11152245-build02` |
| 最新APK | **`build02`：`1.6.6-test-11152245`**（A/B 同签名同包名，`6,431,411` 字节，**尚未实车**） |
| APK源码 | `T02` 源码 `72dc456`；`outputs/EVCam-E5-build02-1.6.6-test-11152245-manifest.json` 已关联 |
| 本地交付 | `outputs/EVCam-E5-{A-Independent,B-Composite}-1.6.6-test-11152245-build02-release.apk` 及 SHA/mappings/notes |
| GitHub | 已推送分支、Tag 与 [`v1.6.6-test-11152245-build02`](https://github.com/johnwyatt041-commits/EVCam/releases/tag/v1.6.6-test-11152245-build02) Pre-release；`build01` 保留 |
| 验证 | A/B 共 188 项(每版 94 项)JVM、Debug/Release R8、`apksigner verify` 通过；Lint 保持 19 项既有错误(0 新增) |
| 实车 | `build01`（`T01`）与 `build02`（`T02`）均**尚未上车实测**；A/Smartcam 四段短片仅静态/离线分析 |

## 当前结论

- **`build02` 记录点已封存（尚未实车）**：将 `T02`（三态零重建跟随系统主题、SC 4+2 左栏与回看合并、精简 7 项设置目录与子页拆分）打包为 A/B 两版 Release APK，校验签名与 `libvhal_decoder.so` 一致后推送 GitHub；详情见 [T02/build02任务](tasks/t02-shell-navigation-and-theme.md)。
- **`build01` 历史基线**：含 `T01` 退出双状态、Option A 自启拦截、补盲弹窗/动效清理与 4 类车辆信号 Hub，详情见 [T01/build01任务](tasks/t01-lifecycle-and-signal-foundation.md)。
- 四段录像中A后台行驶约24.95帧、开预览19.76帧；Smartcam文件约24.5帧但分格近重复，估计20.5–22.6次更新/秒。仅代表各自配置：A三路/Smartcam四路负载不等，同负载优劣与画质未定，B未测。[报告](RECORDING_COMPARISON_20261115.md) · [3.0.56差异](SMARTCAM_V3.0.56_DIFF.md)
- 已保留 1–5 项确认优化（精简SDK+R8、Glide限额、纹理时间戳复用、保留扫码/飞书/补盲/双HUD）；R8 Room 保留与无障碍保活见 [P013/P014](PITFALLS.md)。
- A 为三路独立文件；B 为 1280×800 三格合成（右下黑）；飞书四路独立；模拟器仅覆盖基础采集/水印/HTTP 扫码，不能替代 E5 实车、VHAL、双 HUD 与长期稳定性测试。

## 下一步

1. 在新会话按用户指示启动 **`T03`（多模式主预览框架与动态关流：模式 B+C）**；[蓝图](EVCAM_SMARTCAM_REFACTOR_PLAN.md)。
2. 实车使用 `build02` 联合确认：`T01` 退出防复活与 4 类信号、`T02` 三态不重建主题/录像中切换/4+2 左栏/合并回看/设置目录，以及 A/B 同场景帧率与 PSS 对照。
3. 确认原车 HUD 真实路径与后续在 `T04` 落地双镜像解耦；19 项既有 Lint 错误仍待独立整改。

## 最近更新（最多5条）

- 2026-11-15 23:00:38 +08:00 | build02存档 | 基于 `T02`（`72dc456` / `1.6.6-test-11152245`）打包 `build02` 双风味 Release APK、校验签名/SHA256/mappings 并推送 GitHub Pre-release（尚未实车），待新会话做 `T03` | [T02任务](tasks/t02-shell-navigation-and-theme.md)
- 2026-11-15 22:50:24 +08:00 | T02完成(离线) | 实施 `T02` 三态不重建主题、SC 4+2 左栏/回看合并、7 项精简设置目录与子页拆分（`72dc456`）；`tools/verify.ps1`（188 测）+ Release R8 + 0 新增 Lint 通过 | [T02任务](tasks/t02-shell-navigation-and-theme.md)
- 2026-11-15 22:06:00 +08:00 | T02定稿 | 完成 SC V3.0.56 与 EC 外壳/主题/设置核查，用户拍板「默认跟随系统 / SC 式回看合并入口 / 仅列已实装设置分类」 | [T02任务](tasks/t02-shell-navigation-and-theme.md)
- 2026-11-15 21:36:00 +08:00 | build01存档 | 基于 `T01`（`1.6.6-test-11152059`）打包 `build01` 双风味 Release APK、校验 SHA256/R8 映射并推送 GitHub 标签与 Pre-release（尚未实车） | [T01任务](tasks/t01-lifecycle-and-signal-foundation.md)
- 2026-11-15 21:08:30 +08:00 | T01完成(离线) | 收敛单进程双退出状态与 Option A 重启拦截、清理补盲弹窗/动效、落盘 4 类车辆信号 Hub；174 项单测、Debug/Release R8 与 0 新增 Lint 通过 | [T01任务](tasks/t01-lifecycle-and-signal-foundation.md)

## 按需索引

- [融合重构蓝图](EVCAM_SMARTCAM_REFACTOR_PLAN.md) · [需求](REQUIREMENTS.md) · [协作](COLLABORATION.md) · [坑](PITFALLS.md) · [新AI资料](AI_HANDOFF.md) · [Smartcam全功能](SMARTCAM_FEATURE_CATALOG.md) · [3.0.56差异](SMARTCAM_V3.0.56_DIFF.md)
- [E5启动分析](E5_STARTUP_FIX_20261114.md) · [A/B试录](RECORDING_AB_TEST.md) · [开发](DEVELOPMENT.md)
- 本轮证据：`work/ab-test-preoptimization/`；历史启动证据仍在`work/e5-startup/`，不默认全读。
- [整理前历史快照](archive/2026-11/STATUS_20261114_183403.md)；历史按需读取。
