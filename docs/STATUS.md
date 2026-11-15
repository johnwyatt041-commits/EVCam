# 项目进度

最后更新/核对：2026-11-16 02:50:25 +08:00（Asia/Hong_Kong）
维护：Antigravity（已完成基于 `build04` 的 `T02` SC 界面/双栏设置优化提交 `9f443b6`，尚未打包 `build05`；待用户决定打包测试或继续 `T05`/`T06`）。执行规范：[PROGRESS_RULES.md](PROGRESS_RULES.md)。

## 当前快照

| 项目 | 当前状态 |
| --- | --- |
| 项目/设备 | E:\GPT\EVCam；银河E5，2560×1600 |
| 分支 | `codex/e5-recording-startup-fix`；最新源码 `9f443b6`（`1.6.6-test-11160243`），已发 Tag 基线 `v1.6.6-test-11160215-build04` |
| 最新APK | **`build04`：`1.6.6-test-11160215`**（`a6f4105`，A `6,472,395` / B `6,488,779`B，**不含 `9f443b6` UI 改版**，尚未实车） |
| APK源码 | `T04` 源码 `a6f4105`；`outputs/EVCam-E5-build04-1.6.6-test-11160215-manifest.json` 已关联 |
| 本地交付 | `outputs/EVCam-E5-{A-Independent,B-Composite}-1.6.6-test-11160215-build04-release.apk` 及 SHA/mappings/notes |
| GitHub | 已推送 `build04` Pre-release；最新 UI 改版 `9f443b6` 为本地提交，待用户决定是否打包 `build05`/推送 |
| 验证 | `9f443b6` 离线通过：A/B 共 `248` 测（每版 `124`，0 失败/跳过）、Debug/Release R8 与 `19` baseline Lint（0 新增） |
| 实车 | `build01`（`T01`）–`build04`（`T04`）及 `9f443b6` 改版均**尚未上车实测**；A/Smartcam 四段短片仅静态/离线分析 |

## 当前结论

- **`9f443b6` 完成 `T02` SC UI/双栏设置对齐（基于 `build04`，未干涉 `T01`/`T03`/`T04`）**：按用户提供的 SC V3.0.56 深浅色截图重调中性黑曜石/纯白高反差色板与 Switch；左栏收敛为顶部「开始/停止录制」+「记录仪」「回看」+左下「设置」「退出」与版本；非录制根页隐藏顶栏；[SettingsFragment](../app/src/main/java/com/kooo/evcam/SettingsFragment.java) 改造成左侧分类+右侧内嵌双栏及二级子页返回，将补盲与飞书收归设置，`外观与交互` 改为两列卡片/胶囊选项并支持 3s–30s 开门延时持久化；详情见 [T02任务](tasks/t02-shell-navigation-and-theme.md)。
- **`build01`–`build04` 基线保留**：`build04`（`T04` `a6f4105`）三态小窗与双镜像/补盲融合（[T04任务](tasks/t04-floating-architecture-and-blindspot-fusion.md)）；`build03`（`T03` `4706b6f`）多模式主预览与动态关流（[T03任务](tasks/t03-multi-mode-preview-and-dynamic-stream.md)）；`build01`（`T01` `ea395a0`）退出防复活与 4 类信号（[T01任务](tasks/t01-lifecycle-and-signal-foundation.md)）。
- 四段录像中A后台行驶约24.95帧、开预览19.76帧；Smartcam文件约24.5帧但分格近重复，估计20.5–22.6次更新/秒。仅代表各自配置：A三路/Smartcam四路负载不等，同负载优劣与画质未定，B未测。[报告](RECORDING_COMPARISON_20261115.md) · [3.0.56差异](SMARTCAM_V3.0.56_DIFF.md)
- 已保留 1–5 项确认优化（精简SDK+R8、Glide限额、纹理时间戳复用、保留扫码/飞书/补盲/双HUD）；R8 Room 保留与无障碍保活见 [P013/P014](PITFALLS.md)。
- A 为三路独立文件；B 为 1280×800 三格合成（右下黑）；飞书四路独立；模拟器仅覆盖基础采集/水印/HTTP 扫码，不能替代 E5 实车、VHAL、双 HUD 与长期稳定性测试。

## 下一步

1. 按用户确认：直接试用本地新编 Debug/Release APK，或在 `9f443b6` 上打包发布 `build05` 推送 GitHub，再进入 **`T05`** 或 **`T06`**；[蓝图](EVCAM_SMARTCAM_REFACTOR_PLAN.md)。
2. 实车联合验收 `T01`–`T04` 与新版 SC UI/双栏设置、`2032`/`2038` 不抢导航及双 HUD 联动。
3. 19 项既有 Lint 错误仍待后续专项整改。

## 最近更新（最多5条）

- 2026-11-16 02:50:25 +08:00 | T02 SC界面对齐(离线) | 基于 `build04` 提交 `9f443b6`（`1.6.6-test-11160243`）：重调 SC 深浅色板与 Switch、窄左栏（补盲/飞书收归设置）、双栏 Master-Detail 设置与双列外观页；`248` 测、R8 与 `0` 新增 Lint 通过（未封装 `build05`/待实车） | [T02任务](tasks/t02-shell-navigation-and-theme.md)
- 2026-11-16 02:21:03 +08:00 | build04存档 | 实施并封装 `T04`（`a6f4105` / `1.6.6-test-11160215`）：三态小窗、`OverlayWindowParamsFactory`、双镜像解耦与主屏 360 暂停双 HUD 保活；`246` 测、R8 与 0 新增 Lint 通过（待实车） | [T04任务](tasks/t04-floating-architecture-and-blindspot-fusion.md)
- 2026-11-16 01:18:33 +08:00 | build03存档 | 基于 `T03`（`4706b6f` / `1.6.6-test-11160002`）打包 `build03` 双风味 Release APK、校验签名/SHA256/mappings 并推送 GitHub Pre-release（尚未实车），待新会话做 `T04` | [T03任务](tasks/t03-multi-mode-preview-and-dynamic-stream.md)
- 2026-11-16 00:07:46 +08:00 | T03完成(离线) | 实施 `T03` 模式 B/C 主预览、16dp 圆角分割、全屏角落快捷键与真·Surface 解绑停流（`4706b6f` / `1.6.6-test-11160002`）；`214` 项单测、Debug/Release 与 `0` 新增 Lint 通过（待实车） | [T03任务](tasks/t03-multi-mode-preview-and-dynamic-stream.md)
- 2026-11-15 23:00:38 +08:00 | build02存档 | 基于 `T02`（`72dc456` / `1.6.6-test-11152245`）打包 `build02` 双风味 Release APK、校验签名/SHA256/mappings 并推送 GitHub Pre-release（尚未实车），待新会话做 `T03` | [T02任务](tasks/t02-shell-navigation-and-theme.md)

## 按需索引

- [融合重构蓝图](EVCAM_SMARTCAM_REFACTOR_PLAN.md) · [需求](REQUIREMENTS.md) · [协作](COLLABORATION.md) · [坑](PITFALLS.md) · [新AI资料](AI_HANDOFF.md) · [Smartcam全功能](SMARTCAM_FEATURE_CATALOG.md) · [3.0.56差异](SMARTCAM_V3.0.56_DIFF.md)
- [E5启动分析](E5_STARTUP_FIX_20261114.md) · [A/B试录](RECORDING_AB_TEST.md) · [开发](DEVELOPMENT.md)
- 本轮证据：`work/ab-test-preoptimization/`；历史启动证据仍在`work/e5-startup/`，不默认全读。
- [整理前历史快照](archive/2026-11/STATUS_20261114_183403.md)；历史按需读取。
