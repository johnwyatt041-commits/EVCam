# 项目进度

最后更新/核对：2026-11-16 14:06:00 +08:00（Asia/Hong_Kong）
维护：Antigravity（已完成 **`T05`（视频管线优化、方案 A 帧率与 `3.2`–`3.4` 画面/水印/提示音 `ef3ea32`）** 并打包发布 **`build06`**；未干涉 `T06`–`T10`）。执行规范：[PROGRESS_RULES.md](PROGRESS_RULES.md)。

## 当前快照

| 项目 | 当前状态 |
| --- | --- |
| 项目/设备 | E:\GPT\EVCam；银河E5，2560×1600 |
| 分支 | `codex/e5-recording-startup-fix`；源码 `ef3ea32`，Tag `v1.6.6-test-11161400-build06` |
| 最新APK | **`build06`：`1.6.6-test-11161400`**（A `6,516,731` / B `6,533,115`B，累计 `T01`–`T05`，未实车） |
| APK源码 | `ef3ea32`；`outputs/EVCam-E5-build06-1.6.6-test-11161400-manifest.json` 已关联 |
| 本地交付 | `outputs/EVCam-E5-{A-Independent,B-Composite}-1.6.6-test-11161400-build06-release.apk` 及 SHA/mappings/notes |
| GitHub | `johnwyatt041-commits/EVCam` 已推分支/Tag，并发布 `build06` Pre-release 全套 6 项资产 |
| 验证 | 离线通过：A/B 共 `266` 测（每版 `133`，0 失败/跳过）、Release R8、`apksigner`、`so` 及 `19` baseline Lint（0 新增） |
| 实车 | `build01`（`T01`）–`build06`（`T01`–`T05`）均**尚未上车实测**；A/Smartcam 四段短片仅静态/离线分析 |

## 当前结论

- **`build06`（`ef3ea32`）完成 `T05`（方案 A + 零 FBO 旁路 + `3.2`–`3.4`）**：拒绝 SC 6 槽 FBO 与 `Lf9` 20ms 网格；`3.2 画面` 总开关关闭或全默认时走零开销直通 Shader，开启时单遍内联调色/裁剪/镜像（与 `T04` 双镜像、EVCC/ESP32 双 HUD 联动），`setSourceSize` 隔离缩放与 Camera2 尺寸协商；升级 [VehicleStatusWatermark](tasks/t05-video-pipeline-and-recorder-32-34.md) 四模块/四角落/双样式合并纹理与 [RecordingSoundPlayer](tasks/t05-video-pipeline-and-recorder-32-34.md) 开始/结束提示音。
- **`build01`–`build05` 主链路完整保留**：`T02` SC UI（`f2f1be6`）、`T04`（[t04](tasks/t04-floating-architecture-and-blindspot-fusion.md) `a6f4105`）、`T03`（[t03](tasks/t03-multi-mode-preview-and-dynamic-stream.md) `4706b6f`）、`T01`（[t01](tasks/t01-lifecycle-and-signal-foundation.md) `ea395a0`）、EVCC/ESP32 双 HUD 与飞书远程均未回退。
- 四段对比中 A 后台约 24.95fps；SC 约 24.5fps 但分格 20.5–22.6 次更新/秒。[报告](RECORDING_COMPARISON_20261115.md) · [3.0.56差异](SMARTCAM_V3.0.56_DIFF.md)
- 已保留 1–5 项确认优化；R8 Room 保留与无障碍保活见 [P013/P014](PITFALLS.md)。

## 下一步

1. 基于 `build06` 启动 **`T06`**（车辆状态规则引擎、R/D 换挡弹出退回及信号预设 `3.1`+`10.`）；[蓝图](EVCAM_SMARTCAM_REFACTOR_PLAN.md)。
2. E5 实车安装 `build06` 联合验收 `T01`–`T05`（直通旁路帧率、四模块水印、提示音、SC UI 与双 HUD）。
3. 19 项既有 Lint 错误仍待后续专项整改。

## 最近更新（最多5条）

- 2026-11-16 14:06:00 +08:00 | build06存档 | 完成 **`T05`**（`ef3ea32` / `1.6.6-test-11161400`）并打包发布 `build06`：方案 A 帧率、3.2 直通旁路调色裁剪、`T04` 镜像同步、3.3 四模块水印与 3.4 提示音；`266` 测、R8 与 0 新增 Lint 通过（未实车） | [T05任务](tasks/t05-video-pipeline-and-recorder-32-34.md)
- 2026-11-16 08:35:18 +08:00 | build05存档 | 打包发布 `build05`（`f2f1be6` / `1.6.6-test-11160830`）：含 `9f443b6` SC UI 对齐+内嵌飞书状态刷新与 3 子页 `#id/toolbar` 修复；`248` 测、R8 与 0 新增 Lint 通过（未实车） | [T02任务](tasks/t02-shell-navigation-and-theme.md)
- 2026-11-16 02:50:25 +08:00 | T02 SC界面对齐(离线) | 基于 `build04` 提交 `9f443b6`：重调 SC 深浅色板、窄左栏、双栏 Master-Detail 设置与双列外观页；`248` 测、R8 与 0 新增 Lint 通过 | [T02任务](tasks/t02-shell-navigation-and-theme.md)
- 2026-11-16 02:21:03 +08:00 | build04存档 | 实施并封装 `T04`（`a6f4105` / `1.6.6-test-11160215`）：三态小窗、`OverlayWindowParamsFactory`、双镜像解耦与主屏 360 暂停双 HUD 保活；`246` 测、R8 与 0 新增 Lint 通过（未实车） | [T04任务](tasks/t04-floating-architecture-and-blindspot-fusion.md)
- 2026-11-16 01:18:33 +08:00 | build03存档 | 基于 `T03`（`4706b6f` / `1.6.6-test-11160002`）打包 `build03` 双风味 Release APK 并推送 GitHub Pre-release（尚未实车） | [T03任务](tasks/t03-multi-mode-preview-and-dynamic-stream.md)

## 按需索引

- [融合重构蓝图](EVCAM_SMARTCAM_REFACTOR_PLAN.md) · [需求](REQUIREMENTS.md) · [协作](COLLABORATION.md) · [坑](PITFALLS.md) · [新AI资料](AI_HANDOFF.md) · [Smartcam全功能](SMARTCAM_FEATURE_CATALOG.md) · [3.0.56差异](SMARTCAM_V3.0.56_DIFF.md)
- [E5启动分析](E5_STARTUP_FIX_20261114.md) · [A/B试录](RECORDING_AB_TEST.md) · [开发](DEVELOPMENT.md)
- 本轮证据：`work/ab-test-preoptimization/`；历史启动证据仍在`work/e5-startup/`，不默认全读。
- [整理前历史快照](archive/2026-11/STATUS_20261114_183403.md)；历史按需读取。
