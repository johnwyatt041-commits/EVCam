# 项目进度

最后更新/核对：2026-11-16 20:42:00 +08:00（Asia/Hong_Kong）
维护：Antigravity（已完成 **`T07`（360 全景模式 A + `4.1`–`4.3` 辅助设置 `b9aeda1` + 方案 1 前台按需方向盘转角 `0d2a56e`）** 并打包发布 **`build09`**；未干涉 `T08`–`T10`）。执行规范：[PROGRESS_RULES.md](PROGRESS_RULES.md)。

## 当前快照

| 项目 | 当前状态 |
| --- | --- |
| 项目/设备 | E:\GPT\EVCam；银河E5，2560×1600 |
| 分支 | `codex/e5-recording-startup-fix`；源码 `0d2a56e`，Tag `v1.6.6-test-11162036-build09` |
| 最新APK | **`build09`：`1.6.6-test-11162036`**（A `6,627,219` / B `6,643,603`B，累计 `T01`–`T07`，未实车） |
| APK源码 | `0d2a56e`；`outputs/EVCam-E5-build09-1.6.6-test-11162036-manifest.json` 已关联 |
| 本地交付 | `outputs/EVCam-E5-{A-Independent,B-Composite}-1.6.6-test-11162036-build09-release.apk` 及 SHA/mappings/notes |
| GitHub | `johnwyatt041-commits/EVCam` 已推分支/Tag，并发布 `build09` Pre-release 全套 6 项资产 |
| 验证 | 离线通过：A/B 共 `334` 测（每版 `167`，0 失败/跳过）、Release R8、`apksigner`、`so` 及 `19` baseline Lint（0 新增） |
| 实车 | `build01`（`T01`）–`build09`（`T01`–`T07`）均**尚未上车实测**；A/Smartcam 四段短片仅静态/离线分析 |

## 当前结论

- **`build09`（`0d2a56e`）补充方案 1 前台按需方向盘转角，并守住 `T01`/`T06` 四类信号边界**：[SurroundSteeringAngleSource](tasks/t07-surround-360-and-mode-a.md) 仅在模式 A 前台且开启引导线时临时租用 VHAL `0x11600209` 与 E5 APVP `PinionSteerAgGroupPinionSteerAg1`（`40005`），退出/关预览即刻断开；`VehicleSignalCategory`/`VehicleSignalHub`/3.1 规则仍严格限定 `GEAR`/`SPEED`/`DOORS`/`TURN_SIGNALS`。
- **`build01`–`build08` 主链路完整保留**：`T07` 模式 A GL/校准/离线车模（`b9aeda1`）、`T06`（[t06](tasks/t06-vehicle-rules-and-signal-presets.md) `d40938c`）、`T05`（[t05](tasks/t05-video-pipeline-and-recorder-32-34.md) `ef3ea32`）、`T02` SC UI（`f2f1be6`）、`T04`（[t04](tasks/t04-floating-architecture-and-blindspot-fusion.md) `a6f4105`）、`T03`（`4706b6f`）、`T01`（`ea395a0`）、EVCC/ESP32 双 HUD 与飞书远程均未回退。
- 四段对比中 A 后台约 24.95fps；SC 约 24.5fps 但分格 20.5–22.6 次更新/秒。[报告](RECORDING_COMPARISON_20261115.md) · [3.0.56差异](SMARTCAM_V3.0.56_DIFF.md)
- 已保留 1–5 项确认优化；R8 Room 保留与无障碍保活见 [P013/P014](PITFALLS.md)。

## 下一步

1. 基于 `build09` 启动 **`T08`**（回看重构与局域网浏览器分享，主界面 `3.`）；[蓝图](EVCAM_SMARTCAM_REFACTOR_PLAN.md)。
2. E5 实车安装 `build09` 联合验收 `T01`–`T07`（360 模式 A 拼接/转角引导线/4.2 返桌、4 类规则与 `10.` 诊断、直通旁路与双 HUD）。
3. 19 项既有 Lint 错误仍待后续专项整改。

## 最近更新（最多5条）

- 2026-11-16 20:42:00 +08:00 | build09存档 | **`T07` 方案 1 转角补充**（`0d2a56e` / `1.6.6-test-11162036`）并发布 `build09`：仅前台模式 A 开引导线时临时租用 VHAL/APVP 方向盘转角，守住 `T01`/`T06` 4 类后台边界并保留水印监听契约；`334` 测、R8 与 0 新增 Lint 通过（未实车） | [T07任务](tasks/t07-surround-360-and-mode-a.md)
- 2026-11-16 16:17:00 +08:00 | build08存档 | 完成 **`T07`**（`b9aeda1` / `1.6.6-test-11161613`）并打包发布 `build08`：按需 360 GL 模式 A、4.1 分视角/按键显隐、4.2 R 档/车速/P 档返桌、E5 内置标定/离线车模/动态线与 `4. 360 画面`；`324` 测、R8 与 0 新增 Lint 通过（未实车） | [T07任务](tasks/t07-surround-360-and-mode-a.md)
- 2026-11-16 15:12:00 +08:00 | build07存档 | 完成 **`T06`**（`d40938c` / `1.6.6-test-11161502`）并打包发布 `build07`：4 类白名单规则引擎+默认 2 规则、R/D 换挡弹出与 X 秒退后台、保摄像头恢复、`10.` E5 信号预设匹配与 T10 挡位去抖发布；`294` 测、R8 与 0 新增 Lint 通过（未实车） | [T06任务](tasks/t06-vehicle-rules-and-signal-presets.md)
- 2026-11-16 14:06:00 +08:00 | build06存档 | 完成 **`T05`**（`ef3ea32` / `1.6.6-test-11161400`）并打包发布 `build06`：方案 A 帧率、3.2 直通旁路调色裁剪、`T04` 镜像同步、3.3 四模块水印与 3.4 提示音；`266` 测、R8 与 0 新增 Lint 通过（未实车） | [T05任务](tasks/t05-video-pipeline-and-recorder-32-34.md)
- 2026-11-16 08:35:18 +08:00 | build05存档 | 打包发布 `build05`（`f2f1be6` / `1.6.6-test-11160830`）：含 `9f443b6` SC UI 对齐+内嵌飞书状态刷新与 3 子页 `#id/toolbar` 修复；`248` 测、R8 与 0 新增 Lint 通过（未实车） | [T02任务](tasks/t02-shell-navigation-and-theme.md)

## 按需索引

- [融合重构蓝图](EVCAM_SMARTCAM_REFACTOR_PLAN.md) · [需求](REQUIREMENTS.md) · [协作](COLLABORATION.md) · [坑](PITFALLS.md) · [新AI资料](AI_HANDOFF.md) · [Smartcam全功能](SMARTCAM_FEATURE_CATALOG.md) · [3.0.56差异](SMARTCAM_V3.0.56_DIFF.md)
- [E5启动分析](E5_STARTUP_FIX_20261114.md) · [A/B试录](RECORDING_AB_TEST.md) · [开发](DEVELOPMENT.md)
- 本轮证据：`work/ab-test-preoptimization/`；历史启动证据仍在`work/e5-startup/`，不默认全读。
- [整理前历史快照](archive/2026-11/STATUS_20261114_183403.md)；历史按需读取。
