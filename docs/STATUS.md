# 项目进度

最后更新/核对：2026-11-16 00:07:46 +08:00（Asia/Hong_Kong）
维护：Codex / Antigravity（`T03` 源码 `4706b6f` 已完成离线验证待实车；最新已封存包仍为 `build02`）。执行规范：[PROGRESS_RULES.md](PROGRESS_RULES.md)。

## 当前快照

| 项目 | 当前状态 |
| --- | --- |
| 项目/设备 | E:\GPT\EVCam；银河E5，2560×1600 |
| 分支 | `codex/e5-recording-startup-fix`；最新源码 `4706b6f`（`T03`），`versionName` `1.6.6-test-11160002` |
| 最新APK | 已封存发布仍为 **`build02`：`1.6.6-test-11152245`**（Tag `v1.6.6-test-11152245-build02`，**尚未实车**）；`T03` 已过 Release 构建未封存 `build03` |
| APK源码 | `build02` 对应 `T02` `72dc456`（`outputs/EVCam-E5-build02-1.6.6-test-11152245-manifest.json`） |
| 本地交付 | `outputs/EVCam-E5-{A-Independent,B-Composite}-1.6.6-test-11152245-build02-release.apk` 及 SHA/mappings/notes |
| GitHub | `build02` 已推送 Tag 与 [`v1.6.6-test-11152245-build02`](https://github.com/johnwyatt041-commits/EVCam/releases/tag/v1.6.6-test-11152245-build02)；`T03` 本地已提交待按需推送/封存 |
| 验证 | `T03` 离线：A/B 共 `214` 项（每版 `107` 项）JVM、Debug/Release R8 与 0 新增 Lint（保持 19 项既有错误）通过 |
| 实车 | `build01`（`T01`）、`build02`（`T02`）与 `T03` 源码均**尚未上车实测**；A/Smartcam 四段短片仅静态/离线分析 |

## 当前结论

- **`T03` 多模式主预览与动态关流已完成离线落地（`4706b6f`，尚未实车）**：完成模式 B（3 列智能重排）与模式 C（D 前/R 后 + 侧栏两路大屏）、`16dp` 圆角分割、全屏角落快捷键与真·Surface 解绑停流，并预留 `T07` 模式 A 容器；详情见 [T03任务](tasks/t03-multi-mode-preview-and-dynamic-stream.md)。
- **`build02` / `build01` 已封存基线**：`build02`（`T02` `72dc456`）含三态零重建主题、SC 4+2 左栏/合并回看与 7 项精简设置（[T02任务](tasks/t02-shell-navigation-and-theme.md)）；`build01`（`T01`）含退出双状态与 4 类信号 Hub（[T01任务](tasks/t01-lifecycle-and-signal-foundation.md)）。
- 四段录像中A后台行驶约24.95帧、开预览19.76帧；Smartcam文件约24.5帧但分格近重复，估计20.5–22.6次更新/秒。仅代表各自配置：A三路/Smartcam四路负载不等，同负载优劣与画质未定，B未测。[报告](RECORDING_COMPARISON_20261115.md) · [3.0.56差异](SMARTCAM_V3.0.56_DIFF.md)
- 已保留 1–5 项确认优化（精简SDK+R8、Glide限额、纹理时间戳复用、保留扫码/飞书/补盲/双HUD）；R8 Room 保留与无障碍保活见 [P013/P014](PITFALLS.md)。
- A 为三路独立文件；B 为 1280×800 三格合成（右下黑）；飞书四路独立；模拟器仅覆盖基础采集/水印/HTTP 扫码，不能替代 E5 实车、VHAL、双 HUD 与长期稳定性测试。

## 下一步

1. 按需将 `T03`（`4706b6f`）打包推送为 `build03` 上车对照，或在离线侧继续推进授权任务包 **`T04`（双 HUD 解耦与扩展能力）** / **`T05`**；[蓝图](EVCAM_SMARTCAM_REFACTOR_PLAN.md)。
2. 实车重点联合验证：`T01` 退出防复活、`T02` 主题与 4+2 左栏、`T03` 模式 B/C 重排、隐藏停流重开、200ms 挡位切换与全屏快捷键。
3. 确认原车 HUD 真实路径与在 `T04` 落地双镜像解耦；19 项既有 Lint 错误仍待独立整改。

## 最近更新（最多5条）

- 2026-11-16 00:07:46 +08:00 | T03完成(离线) | 实施 `T03` 模式 B/C 主预览、16dp 圆角分割、全屏角落快捷键与真·Surface 解绑停流（`4706b6f` / `1.6.6-test-11160002`）；`214` 项单测、Debug/Release 与 `0` 新增 Lint 通过（待实车） | [T03任务](tasks/t03-multi-mode-preview-and-dynamic-stream.md)
- 2026-11-15 23:00:38 +08:00 | build02存档 | 基于 `T02`（`72dc456` / `1.6.6-test-11152245`）打包 `build02` 双风味 Release APK、校验签名/SHA256/mappings 并推送 GitHub Pre-release（尚未实车），待新会话做 `T03` | [T02任务](tasks/t02-shell-navigation-and-theme.md)
- 2026-11-15 22:50:24 +08:00 | T02完成(离线) | 实施 `T02` 三态不重建主题、SC 4+2 左栏/回看合并、7 项精简设置目录与子页拆分（`72dc456`）；`tools/verify.ps1`（188 测）+ Release R8 + 0 新增 Lint 通过 | [T02任务](tasks/t02-shell-navigation-and-theme.md)
- 2026-11-15 22:06:00 +08:00 | T02定稿 | 完成 SC V3.0.56 与 EC 外壳/主题/设置核查，用户拍板「默认跟随系统 / SC 式回看合并入口 / 仅列已实装设置分类」 | [T02任务](tasks/t02-shell-navigation-and-theme.md)
- 2026-11-15 21:36:00 +08:00 | build01存档 | 基于 `T01`（`1.6.6-test-11152059`）打包 `build01` 双风味 Release APK、校验 SHA256/R8 映射并推送 GitHub 标签与 Pre-release（尚未实车） | [T01任务](tasks/t01-lifecycle-and-signal-foundation.md)

## 按需索引

- [融合重构蓝图](EVCAM_SMARTCAM_REFACTOR_PLAN.md) · [需求](REQUIREMENTS.md) · [协作](COLLABORATION.md) · [坑](PITFALLS.md) · [新AI资料](AI_HANDOFF.md) · [Smartcam全功能](SMARTCAM_FEATURE_CATALOG.md) · [3.0.56差异](SMARTCAM_V3.0.56_DIFF.md)
- [E5启动分析](E5_STARTUP_FIX_20261114.md) · [A/B试录](RECORDING_AB_TEST.md) · [开发](DEVELOPMENT.md)
- 本轮证据：`work/ab-test-preoptimization/`；历史启动证据仍在`work/e5-startup/`，不默认全读。
- [整理前历史快照](archive/2026-11/STATUS_20261114_183403.md)；历史按需读取。
