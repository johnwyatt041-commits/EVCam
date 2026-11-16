# 项目进度

最后更新/核对：2026-11-17 00:16:00 +08:00（Asia/Hong_Kong）
维护：Antigravity（已完成 **`T09`（银河 E5 离线配置广场与 `13.` 系统权限诊断面板 `3c3c2ba`）** 并打包发布 **`build11`**；未干涉 `T10`）。执行规范：[PROGRESS_RULES.md](PROGRESS_RULES.md)。

## 当前快照

| 项目 | 当前状态 |
| --- | --- |
| 项目/设备 | E:\GPT\EVCam；银河E5，2560×1600 |
| 分支 | `codex/e5-recording-startup-fix`；源码 `3c3c2ba`，Tag `v1.6.6-test-11170004-build11` |
| 最新APK | **`build11`：`1.6.6-test-11170004`**（A `6,752,926` / B `6,769,310`B，累计 `T01`–`T09`，未实车） |
| APK源码 | `3c3c2ba`；`outputs/EVCam-E5-build11-1.6.6-test-11170004-manifest.json` 已关联 |
| 本地交付 | `outputs/EVCam-E5-{A-Independent,B-Composite}-1.6.6-test-11170004-build11-release.apk` 及 SHA/mappings/notes |
| GitHub | `johnwyatt041-commits/EVCam` 已推分支/Tag，并发布 `build11` Pre-release 全套 6 项资产 |
| 验证 | 离线通过：A/B 共 `386` 测（每版 `193`，0 失败/跳过）、Release R8、`apksigner`、`so` 及 `18` 项 Lint（0 新增，较基线 `19` 降 1） |
| 实车 | `build01`（`T01`）–`build11`（`T01`–`T09`）均**尚未上车实测**；A/Smartcam 四段短片仅静态/离线分析 |

## 当前结论

- **`build11`（`3c3c2ba`）完成 `T09` 银河 E5 离线配置广场与 `13.` 系统权限诊断面板**：[T09任务](tasks/t09-config-plaza-and-permissions.md) 落地 `configplaza/`（仅 `galaxy_e5`、6 大白名单分区、3 套内置 E5 预设、SAF/剪贴板导入导出、`AtomicFile` 应用前备份与一键回滚，彻底屏蔽云端与非 E5/凭据字段），并重构 `PermissionDiagnostics` + `PermissionSettingsFragment`（分级诊断、缺项申请、指南、ADB 授权与白名单还原）及修复 `AppOpsManager` `[NewApi]`。
- **`build01`–`build10` 主链路完整保留**：`T08`（[t08](tasks/t08-playback-and-lan-share.md) `13e2f59`）、`T07`（`0d2a56e`/`b9aeda1`）、`T06`（[t06](tasks/t06-vehicle-rules-and-signal-presets.md) `d40938c`）、`T05`（[t05](tasks/t05-video-pipeline-and-recorder-32-34.md) `ef3ea32`）、`T02`（`f2f1be6`）、`T04`（[t04](tasks/t04-floating-architecture-and-blindspot-fusion.md) `a6f4105`）、`T03`（`4706b6f`）、`T01`（`ea395a0`）、EVCC/ESP32 双 HUD 与飞书远程均未回退。
- 四段对比中 A 后台约 24.95fps；SC 约 24.5fps 但分格 20.5–22.6 次更新/秒。[报告](RECORDING_COMPARISON_20261115.md) · [3.0.56差异](SMARTCAM_V3.0.56_DIFF.md)
- 已保留 1–5 项确认优化；R8 Room 保留与无障碍保活见 [P013/P014](PITFALLS.md)。

## 下一步

1. 基于 `build11` 启动 **`T10`**（`11.` 飞书 P 档门控懒加载 + `14.` 私有 GitHub 手动更新与 `15.` 连贯性）；[蓝图](EVCAM_SMARTCAM_REFACTOR_PLAN.md)。
2. E5 实车安装 `build11` 联合验收 `T01`–`T09`（离线配置广场导入/备份回滚、权限逐项跳转/ADB、回看 Range 分享与双 HUD）。
3. 18 项既有 Lint 错误仍待后续专项整改（本轮已降 1 项）。

## 最近更新（最多5条）

- 2026-11-17 00:16:00 +08:00 | build11存档 | 完成 **`T09`**（`3c3c2ba` / `1.6.6-test-11170004`）并发布 `build11`：`12. 配置广场`（仅 E5 6 分区白名单、3 套离线预设、SAF/剪贴板导入导出与 `AtomicFile` 备份回滚）及 `13. 系统与权限`（`PermissionDiagnostics` 10 项分级诊断、缺项申请、ADB/白名单）接入设置目录；`386` 测、R8 与 `18` 项 Lint（降 1）通过（未实车） | [T09任务](tasks/t09-config-plaza-and-permissions.md)
- 2026-11-16 23:24:00 +08:00 | build10存档 | 完成 **`T08`**（`13e2f59` / `1.6.6-test-11162313`）并发布 `build10`：`LanShareService` + 60s 空闲关闭、HTTP `206/416` Range 与路径隔离、小时时间线 + `.lock` 保护、`StorageCleanupManager` 跳过锁定及 B 版象限放大；`366` 测、R8 与 0 新增 Lint 通过（未实车） | [T08任务](tasks/t08-playback-and-lan-share.md)
- 2026-11-16 20:42:00 +08:00 | build09存档 | **`T07` 方案 1 转角补充**（`0d2a56e` / `1.6.6-test-11162036`）并发布 `build09`：仅前台模式 A 开引导线时临时租用 VHAL/APVP 方向盘转角，守住 `T01`/`T06` 4 类后台边界并保留水印监听契约；`334` 测、R8 与 0 新增 Lint 通过（未实车） | [T07任务](tasks/t07-surround-360-and-mode-a.md)
- 2026-11-16 16:17:00 +08:00 | build08存档 | 完成 **`T07`**（`b9aeda1` / `1.6.6-test-11161613`）并打包发布 `build08`：按需 360 GL 模式 A、4.1 分视角/按键显隐、4.2 R 档/车速/P 档返桌、E5 内置标定/离线车模/动态线与 `4. 360 画面`；`324` 测、R8 与 0 新增 Lint 通过（未实车） | [T07任务](tasks/t07-surround-360-and-mode-a.md)
- 2026-11-16 15:12:00 +08:00 | build07存档 | 完成 **`T06`**（`d40938c` / `1.6.6-test-11161502`）并打包发布 `build07`：4 类白名单规则引擎+默认 2 规则、R/D 换挡弹出与 X 秒退后台、保摄像头恢复、`10.` E5 信号预设匹配与 T10 挡位去抖发布；`294` 测、R8 与 0 新增 Lint 通过（未实车） | [T06任务](tasks/t06-vehicle-rules-and-signal-presets.md)

## 按需索引

- [融合重构蓝图](EVCAM_SMARTCAM_REFACTOR_PLAN.md) · [需求](REQUIREMENTS.md) · [协作](COLLABORATION.md) · [坑](PITFALLS.md) · [新AI资料](AI_HANDOFF.md) · [Smartcam全功能](SMARTCAM_FEATURE_CATALOG.md) · [3.0.56差异](SMARTCAM_V3.0.56_DIFF.md)
- [E5启动分析](E5_STARTUP_FIX_20261114.md) · [A/B试录](RECORDING_AB_TEST.md) · [开发](DEVELOPMENT.md)
- 本轮证据：`work/ab-test-preoptimization/`；历史启动证据仍在`work/e5-startup/`，不默认全读。
- [整理前历史快照](archive/2026-11/STATUS_20261114_183403.md)；历史按需读取。
