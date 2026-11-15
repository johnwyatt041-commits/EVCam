# 项目进度

最后更新/核对：2026-11-16 02:21:03 +08:00（Asia/Hong_Kong）
维护：Antigravity（`build04` 已封存推送 GitHub 待实车；下一步可启动 `T05` 或 `T06`）。执行规范：[PROGRESS_RULES.md](PROGRESS_RULES.md)。

## 当前快照

| 项目 | 当前状态 |
| --- | --- |
| 项目/设备 | E:\GPT\EVCam；银河E5，2560×1600 |
| 分支 | `codex/e5-recording-startup-fix`；源码 `a6f4105`（`T04`），Tag `v1.6.6-test-11160215-build04` |
| 最新APK | **`build04`：`1.6.6-test-11160215`**（A `6,472,395` / B `6,488,779` 字节，同签名同包名，**尚未实车**） |
| APK源码 | `T04` 源码 `a6f4105`；`outputs/EVCam-E5-build04-1.6.6-test-11160215-manifest.json` 已关联 |
| 本地交付 | `outputs/EVCam-E5-{A-Independent,B-Composite}-1.6.6-test-11160215-build04-release.apk` 及 SHA/mappings/notes |
| GitHub | 已推送分支、Tag 与 [`v1.6.6-test-11160215-build04`](https://github.com/johnwyatt041-commits/EVCam/releases/tag/v1.6.6-test-11160215-build04) Pre-release；`build01`–`build03` 保留 |
| 验证 | A/B 共 246 项（每版 123 项）JVM、Debug/Release R8、`apksigner verify` 通过；Lint 保持 19 项既有错误（0 新增） |
| 实车 | `build01`（`T01`）–`build04`（`T04`）均**尚未上车实测**；A/Smartcam 四段短片仅静态/离线分析 |

## 当前结论

- **`build04` 记录点已封存（尚未实车）**：完成 `T04`（`PreviewPresentation` 三态含 `StandalonePreviewWindow` 独立小窗、`OverlayWindowParamsFactory` 不抢焦点 `2032->2038`、`MirrorTarget` + `CameraMirrorPolicy` 物理/输出双镜像解耦、`BlindSpotCoordinator` + 主屏 360 暂停但双 HUD 保活）并打包 A/B Release APK；详情见 [T04/build04任务](tasks/t04-floating-architecture-and-blindspot-fusion.md)。
- **`build01`–`build03` 基线保留**：`build03`（`T03` `4706b6f`）多模式主预览与真·Surface 解绑停流（[T03任务](tasks/t03-multi-mode-preview-and-dynamic-stream.md)）；`build02`（`T02` `72dc456`）三态主题与左栏（[T02任务](tasks/t02-shell-navigation-and-theme.md)）；`build01`（`T01` `ea395a0`）退出防复活与 4 类信号（[T01任务](tasks/t01-lifecycle-and-signal-foundation.md)）。
- 四段录像中A后台行驶约24.95帧、开预览19.76帧；Smartcam文件约24.5帧但分格近重复，估计20.5–22.6次更新/秒。仅代表各自配置：A三路/Smartcam四路负载不等，同负载优劣与画质未定，B未测。[报告](RECORDING_COMPARISON_20261115.md) · [3.0.56差异](SMARTCAM_V3.0.56_DIFF.md)
- 已保留 1–5 项确认优化（精简SDK+R8、Glide限额、纹理时间戳复用、保留扫码/飞书/补盲/双HUD）；R8 Room 保留与无障碍保活见 [P013/P014](PITFALLS.md)。
- A 为三路独立文件；B 为 1280×800 三格合成（右下黑）；飞书四路独立；模拟器仅覆盖基础采集/水印/HTTP 扫码，不能替代 E5 实车、VHAL、双 HUD 与长期稳定性测试。

## 下一步

1. 在新会话按用户指示启动 **`T05`（视频管线优化、调色/水印/提示音与方案 A 直通旁路）** 或 **`T06`（车辆状态规则引擎与信号预设匹配）**；完成后按约定新增 `build05` 并推送 GitHub；[蓝图](EVCAM_SMARTCAM_REFACTOR_PLAN.md)。
2. 实车使用 `build04` 联合验收 `T01`–`T04`：重点核对 `2032`/`2038` 不抢导航、双镜像四端方向一致性、360 避让期间双 HUD 转向联动与 `StandalonePreviewWindow` 交互。
3. 19 项既有 Lint 错误仍待后续专项整改。

## 最近更新（最多5条）

- 2026-11-16 02:21:03 +08:00 | build04存档 | 实施并封装 `T04`（`a6f4105` / `1.6.6-test-11160215`）：三态小窗、`OverlayWindowParamsFactory`、双镜像解耦与主屏 360 暂停双 HUD 保活；`246` 测、R8 与 0 新增 Lint 通过（待实车） | [T04任务](tasks/t04-floating-architecture-and-blindspot-fusion.md)
- 2026-11-16 01:18:33 +08:00 | build03存档 | 基于 `T03`（`4706b6f` / `1.6.6-test-11160002`）打包 `build03` 双风味 Release APK、校验签名/SHA256/mappings 并推送 GitHub Pre-release（尚未实车），待新会话做 `T04` | [T03任务](tasks/t03-multi-mode-preview-and-dynamic-stream.md)
- 2026-11-16 00:07:46 +08:00 | T03完成(离线) | 实施 `T03` 模式 B/C 主预览、16dp 圆角分割、全屏角落快捷键与真·Surface 解绑停流（`4706b6f` / `1.6.6-test-11160002`）；`214` 项单测、Debug/Release 与 `0` 新增 Lint 通过（待实车） | [T03任务](tasks/t03-multi-mode-preview-and-dynamic-stream.md)
- 2026-11-15 23:00:38 +08:00 | build02存档 | 基于 `T02`（`72dc456` / `1.6.6-test-11152245`）打包 `build02` 双风味 Release APK、校验签名/SHA256/mappings 并推送 GitHub Pre-release（尚未实车），待新会话做 `T03` | [T02任务](tasks/t02-shell-navigation-and-theme.md)
- 2026-11-15 22:50:24 +08:00 | T02完成(离线) | 实施 `T02` 三态不重建主题、SC 4+2 左栏/回看合并、7 项精简设置目录与子页拆分（`72dc456`）；`tools/verify.ps1`（188 测）+ Release R8 + 0 新增 Lint 通过 | [T02任务](tasks/t02-shell-navigation-and-theme.md)

## 按需索引

- [融合重构蓝图](EVCAM_SMARTCAM_REFACTOR_PLAN.md) · [需求](REQUIREMENTS.md) · [协作](COLLABORATION.md) · [坑](PITFALLS.md) · [新AI资料](AI_HANDOFF.md) · [Smartcam全功能](SMARTCAM_FEATURE_CATALOG.md) · [3.0.56差异](SMARTCAM_V3.0.56_DIFF.md)
- [E5启动分析](E5_STARTUP_FIX_20261114.md) · [A/B试录](RECORDING_AB_TEST.md) · [开发](DEVELOPMENT.md)
- 本轮证据：`work/ab-test-preoptimization/`；历史启动证据仍在`work/e5-startup/`，不默认全读。
- [整理前历史快照](archive/2026-11/STATUS_20261114_183403.md)；历史按需读取。
