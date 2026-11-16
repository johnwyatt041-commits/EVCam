# 项目进度

最后更新/核对：2026-11-16 15:12:00 +08:00（Asia/Hong_Kong）
维护：Antigravity（已完成 **`T06`（车辆状态规则引擎、R/D 换挡弹出退回、保摄像头恢复与 `10.` 信号预设 `d40938c`）** 并打包发布 **`build07`**；未干涉 `T07`–`T10`）。执行规范：[PROGRESS_RULES.md](PROGRESS_RULES.md)。

## 当前快照

| 项目 | 当前状态 |
| --- | --- |
| 项目/设备 | E:\GPT\EVCam；银河E5，2560×1600 |
| 分支 | `codex/e5-recording-startup-fix`；源码 `d40938c`，Tag `v1.6.6-test-11161502-build07` |
| 最新APK | **`build07`：`1.6.6-test-11161502`**（A `6,554,591` / B `6,570,975`B，累计 `T01`–`T06`，未实车） |
| APK源码 | `d40938c`；`outputs/EVCam-E5-build07-1.6.6-test-11161502-manifest.json` 已关联 |
| 本地交付 | `outputs/EVCam-E5-{A-Independent,B-Composite}-1.6.6-test-11161502-build07-release.apk` 及 SHA/mappings/notes |
| GitHub | `johnwyatt041-commits/EVCam` 已推分支/Tag，并发布 `build07` Pre-release 全套 6 项资产 |
| 验证 | 离线通过：A/B 共 `294` 测（每版 `147`，0 失败/跳过）、Release R8、`apksigner`、`so` 及 `19` baseline Lint（0 新增） |
| 实车 | `build01`（`T01`）–`build07`（`T01`–`T06`）均**尚未上车实测**；A/Smartcam 四段短片仅静态/离线分析 |

## 当前结论

- **`build07`（`d40938c`）完成 `T06`（`3.1` 规则/恢复/换挡弹出 + `10.` 信号预设与匹配）**：规则收窄至 `VehicleSignalHub` 4 类信号并默认启用 `default_door_stop`/`default_gear_start`；新增 250ms 去抖 R/D 自动弹出与 X 秒退后台（交互接管取消）；接入 `keep_camera_connected` 与 5s×3/60s 冷却前台恢复、T02 `confirm_door_exit` 倒计时弹窗，并向 `T10` 导出 300ms 去抖 [VehicleGearStatePublisher](tasks/t06-vehicle-rules-and-signal-presets.md)。
- **`build01`–`build06` 主链路完整保留**：`T05`（[t05](tasks/t05-video-pipeline-and-recorder-32-34.md) `ef3ea32`）、`T02` SC UI（`f2f1be6`）、`T04`（[t04](tasks/t04-floating-architecture-and-blindspot-fusion.md) `a6f4105`）、`T03`（`4706b6f`）、`T01`（`ea395a0`）、EVCC/ESP32 双 HUD 与飞书远程均未回退。
- 四段对比中 A 后台约 24.95fps；SC 约 24.5fps 但分格 20.5–22.6 次更新/秒。[报告](RECORDING_COMPARISON_20261115.md) · [3.0.56差异](SMARTCAM_V3.0.56_DIFF.md)
- 已保留 1–5 项确认优化；R8 Room 保留与无障碍保活见 [P013/P014](PITFALLS.md)。

## 下一步

1. 基于 `build07` 启动 **`T07`**（360 全景 + 分视角模式 A 与 `4.1`–`4.3` 辅助设置）；[蓝图](EVCAM_SMARTCAM_REFACTOR_PLAN.md)。
2. E5 实车安装 `build07` 联合验收 `T01`–`T06`（4 类规则、`10.` 诊断、R/D 换挡弹出退回、开门退出确认、直通旁路帧率与双 HUD）。
3. 19 项既有 Lint 错误仍待后续专项整改。

## 最近更新（最多5条）

- 2026-11-16 15:12:00 +08:00 | build07存档 | 完成 **`T06`**（`d40938c` / `1.6.6-test-11161502`）并打包发布 `build07`：4 类白名单规则引擎+默认 2 规则、R/D 换挡弹出与 X 秒退后台、保摄像头恢复、`10.` E5 信号预设匹配与 T10 挡位去抖发布；`294` 测、R8 与 0 新增 Lint 通过（未实车） | [T06任务](tasks/t06-vehicle-rules-and-signal-presets.md)
- 2026-11-16 14:06:00 +08:00 | build06存档 | 完成 **`T05`**（`ef3ea32` / `1.6.6-test-11161400`）并打包发布 `build06`：方案 A 帧率、3.2 直通旁路调色裁剪、`T04` 镜像同步、3.3 四模块水印与 3.4 提示音；`266` 测、R8 与 0 新增 Lint 通过（未实车） | [T05任务](tasks/t05-video-pipeline-and-recorder-32-34.md)
- 2026-11-16 08:35:18 +08:00 | build05存档 | 打包发布 `build05`（`f2f1be6` / `1.6.6-test-11160830`）：含 `9f443b6` SC UI 对齐+内嵌飞书状态刷新与 3 子页 `#id/toolbar` 修复；`248` 测、R8 与 0 新增 Lint 通过（未实车） | [T02任务](tasks/t02-shell-navigation-and-theme.md)
- 2026-11-16 02:50:25 +08:00 | T02 SC界面对齐(离线) | 基于 `build04` 提交 `9f443b6`：重调 SC 深浅色板、窄左栏、双栏 Master-Detail 设置与双列外观页；`248` 测、R8 与 0 新增 Lint 通过 | [T02任务](tasks/t02-shell-navigation-and-theme.md)
- 2026-11-16 02:21:03 +08:00 | build04存档 | 实施并封装 `T04`（`a6f4105` / `1.6.6-test-11160215`）：三态小窗、`OverlayWindowParamsFactory`、双镜像解耦与主屏 360 暂停双 HUD 保活；`246` 测、R8 与 0 新增 Lint 通过（未实车） | [T04任务](tasks/t04-floating-architecture-and-blindspot-fusion.md)

## 按需索引

- [融合重构蓝图](EVCAM_SMARTCAM_REFACTOR_PLAN.md) · [需求](REQUIREMENTS.md) · [协作](COLLABORATION.md) · [坑](PITFALLS.md) · [新AI资料](AI_HANDOFF.md) · [Smartcam全功能](SMARTCAM_FEATURE_CATALOG.md) · [3.0.56差异](SMARTCAM_V3.0.56_DIFF.md)
- [E5启动分析](E5_STARTUP_FIX_20261114.md) · [A/B试录](RECORDING_AB_TEST.md) · [开发](DEVELOPMENT.md)
- 本轮证据：`work/ab-test-preoptimization/`；历史启动证据仍在`work/e5-startup/`，不默认全读。
- [整理前历史快照](archive/2026-11/STATUS_20261114_183403.md)；历史按需读取。
