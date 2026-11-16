# 项目进度

最后更新/核对：2026-11-16 08:35:18 +08:00（Asia/Hong_Kong）
维护：Antigravity（已完成 `T02` SC UI 对齐 `9f443b6` 及嵌套飞书/子页顶栏修复 `f2f1be6`，并打包发布增量检查点 **`build05`**；未干涉 `T05`–`T10`）。执行规范：[PROGRESS_RULES.md](PROGRESS_RULES.md)。

## 当前快照

| 项目 | 当前状态 |
| --- | --- |
| 项目/设备 | E:\GPT\EVCam；银河E5，2560×1600 |
| 分支 | `codex/e5-recording-startup-fix`；源码 `f2f1be6`，Tag `v1.6.6-test-11160830-build05` |
| 最新APK | **`build05`：`1.6.6-test-11160830`**（A `6,479,679` / B `6,496,063`B，累计 `T01`–`T04`+SC 对齐，未实车） |
| APK源码 | `f2f1be6`；`outputs/EVCam-E5-build05-1.6.6-test-11160830-manifest.json` 已关联 |
| 本地交付 | `outputs/EVCam-E5-{A-Independent,B-Composite}-1.6.6-test-11160830-build05-release.apk` 及 SHA/mappings/notes |
| GitHub | `johnwyatt041-commits/EVCam` 已推分支/Tag，并发布 `build05` Pre-release 全套 6 项资产 |
| 验证 | 离线通过：A/B 共 `248` 测（每版 `124`，0 失败/跳过）、Release R8、`apksigner`、`so` 及 `19` baseline Lint（0 新增） |
| 实车 | `build01`（`T01`）–`build05`（`T01`–`T04`+SC 对齐）均**尚未上车实测**；A/Smartcam 四段短片仅静态/离线分析 |

## 当前结论

- **`build05`（`f2f1be6`）完成 `T02` SC UI 对齐与修复并封版**：含 `9f443b6` SC 深浅色板/Switch、窄左栏（补盲与飞书收归设置）、双栏 Master-Detail 设置与双列 `外观与交互`；`f2f1be6` 修复内嵌 `FeishuFragment` 状态实时刷新（`SettingsFragment.getActiveDetailFragment()`）与 3 个补盲/推流子页 `@+id/toolbar`；详情见 [T02任务](tasks/t02-shell-navigation-and-theme.md)。
- **`build01`–`build04` 主链路完整保留**：`T04`（[t04](tasks/t04-floating-architecture-and-blindspot-fusion.md) `a6f4105`）、`T03`（[t03](tasks/t03-multi-mode-preview-and-dynamic-stream.md) `4706b6f`）、`T01`（[t01](tasks/t01-lifecycle-and-signal-foundation.md) `ea395a0`）与 EVCC/ESP32 双 HUD 均未回退。
- 四段录像中A后台行驶约24.95帧、开预览19.76帧；Smartcam文件约24.5帧但分格近重复，估计20.5–22.6次更新/秒。仅代表各自配置：A三路/Smartcam四路负载不等，同负载优劣与画质未定，B未测。[报告](RECORDING_COMPARISON_20261115.md) · [3.0.56差异](SMARTCAM_V3.0.56_DIFF.md)
- 已保留 1–5 项确认优化（精简SDK+R8、Glide限额、纹理时间戳复用、保留扫码/飞书/补盲/双HUD）；R8 Room 保留与无障碍保活见 [P013/P014](PITFALLS.md)。
- A 为三路独立文件；B 为 1280×800 三格合成（右下黑）；飞书四路独立；模拟器仅覆盖基础采集/水印/HTTP 扫码，不能替代 E5 实车、VHAL、双 HUD 与长期稳定性测试。

## 下一步

1. 基于 `build05` 启动 **`T05`**（视频管线、旁路着色与方案 A 帧率/水印/提示音）或 **`T06`**（车辆规则引擎）；[蓝图](EVCAM_SMARTCAM_REFACTOR_PLAN.md)。
2. E5 实车安装 `build05` 联合验收 `T01`–`T04`、新版 SC 视觉/双栏设置、`2032`/`2038` 不抢导航与双 HUD。
3. 19 项既有 Lint 错误仍待后续专项整改。

## 最近更新（最多5条）

- 2026-11-16 08:35:18 +08:00 | build05存档 | 打包发布 `build05`（`f2f1be6` / `1.6.6-test-11160830`）：含 `9f443b6` SC UI 对齐+内嵌飞书状态刷新与 3 子页 `#id/toolbar` 修复；`248` 测、R8 与 0 新增 Lint 通过（待实车） | [T02任务](tasks/t02-shell-navigation-and-theme.md)
- 2026-11-16 02:50:25 +08:00 | T02 SC界面对齐(离线) | 基于 `build04` 提交 `9f443b6`（`1.6.6-test-11160243`）：重调 SC 深浅色板与 Switch、窄左栏、双栏 Master-Detail 设置与双列外观页；`248` 测、R8 与 `0` 新增 Lint 通过 | [T02任务](tasks/t02-shell-navigation-and-theme.md)
- 2026-11-16 02:21:03 +08:00 | build04存档 | 实施并封装 `T04`（`a6f4105` / `1.6.6-test-11160215`）：三态小窗、`OverlayWindowParamsFactory`、双镜像解耦与主屏 360 暂停双 HUD 保活；`246` 测、R8 与 0 新增 Lint 通过（待实车） | [T04任务](tasks/t04-floating-architecture-and-blindspot-fusion.md)
- 2026-11-16 01:18:33 +08:00 | build03存档 | 基于 `T03`（`4706b6f` / `1.6.6-test-11160002`）打包 `build03` 双风味 Release APK、校验签名/SHA256/mappings 并推送 GitHub Pre-release（尚未实车），待新会话做 `T04` | [T03任务](tasks/t03-multi-mode-preview-and-dynamic-stream.md)
- 2026-11-16 00:07:46 +08:00 | T03完成(离线) | 实施 `T03` 模式 B/C 主预览、16dp 圆角分割、全屏角落快捷键与真·Surface 解绑停流（`4706b6f` / `1.6.6-test-11160002`）；`214` 项单测、Debug/Release 与 `0` 新增 Lint 通过（待实车） | [T03任务](tasks/t03-multi-mode-preview-and-dynamic-stream.md)

## 按需索引

- [融合重构蓝图](EVCAM_SMARTCAM_REFACTOR_PLAN.md) · [需求](REQUIREMENTS.md) · [协作](COLLABORATION.md) · [坑](PITFALLS.md) · [新AI资料](AI_HANDOFF.md) · [Smartcam全功能](SMARTCAM_FEATURE_CATALOG.md) · [3.0.56差异](SMARTCAM_V3.0.56_DIFF.md)
- [E5启动分析](E5_STARTUP_FIX_20261114.md) · [A/B试录](RECORDING_AB_TEST.md) · [开发](DEVELOPMENT.md)
- 本轮证据：`work/ab-test-preoptimization/`；历史启动证据仍在`work/e5-startup/`，不默认全读。
- [整理前历史快照](archive/2026-11/STATUS_20261114_183403.md)；历史按需读取。
