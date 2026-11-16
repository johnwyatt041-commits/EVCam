# 项目进度

最后更新/核对：2026-11-16 23:24:00 +08:00（Asia/Hong_Kong）
维护：Antigravity（已完成 **`T08`（回看重构、`.lock` 保护、B 版合成象限放大与 HTTP Range 服务化局域网分享 `13e2f59`）** 并打包发布 **`build10`**；未干涉 `T09`–`T10`）。执行规范：[PROGRESS_RULES.md](PROGRESS_RULES.md)。

## 当前快照

| 项目 | 当前状态 |
| --- | --- |
| 项目/设备 | E:\GPT\EVCam；银河E5，2560×1600 |
| 分支 | `codex/e5-recording-startup-fix`；源码 `13e2f59`，Tag `v1.6.6-test-11162313-build10` |
| 最新APK | **`build10`：`1.6.6-test-11162313`**（A `6,680,359` / B `6,696,743`B，累计 `T01`–`T08`，未实车） |
| APK源码 | `13e2f59`；`outputs/EVCam-E5-build10-1.6.6-test-11162313-manifest.json` 已关联 |
| 本地交付 | `outputs/EVCam-E5-{A-Independent,B-Composite}-1.6.6-test-11162313-build10-release.apk` 及 SHA/mappings/notes |
| GitHub | `johnwyatt041-commits/EVCam` 已推分支/Tag，并发布 `build10` Pre-release 全套 6 项资产 |
| 验证 | 离线通过：A/B 共 `366` 测（每版 `183`，0 失败/跳过）、Release R8、`apksigner`、`so` 及 `19` baseline Lint（0 新增） |
| 实车 | `build01`（`T01`）–`build10`（`T01`–`T08`）均**尚未上车实测**；A/Smartcam 四段短片仅静态/离线分析 |

## 当前结论

- **`build10`（`13e2f59`）完成 `T08` 回看重构与安全 HTTP Range 局域网分享**：[T08任务](tasks/t08-playback-and-lan-share.md) 落地 `LanShareService` 前台分享 + 60s 自动停服、Session Token + 规范路径保护、`206`/`416` 单段 Range、`BoundedAsyncRunner` + 限流缩略图、小时时间线 + `.lock` 免清理保护及 B 版合成象限放大，并修复漏传 `POSITION_COMBINED` 缺陷。
- **`build01`–`build09` 主链路完整保留**：`T07` 模式 A 前台按需转角（`0d2a56e`）/ 360 GL（`b9aeda1`）、`T06`（[t06](tasks/t06-vehicle-rules-and-signal-presets.md) `d40938c`）、`T05`（[t05](tasks/t05-video-pipeline-and-recorder-32-34.md) `ef3ea32`）、`T02` SC UI（`f2f1be6`）、`T04`（[t04](tasks/t04-floating-architecture-and-blindspot-fusion.md) `a6f4105`）、`T03`（`4706b6f`）、`T01`（`ea395a0`）、EVCC/ESP32 双 HUD 与飞书远程均未回退。
- 四段对比中 A 后台约 24.95fps；SC 约 24.5fps 但分格 20.5–22.6 次更新/秒。[报告](RECORDING_COMPARISON_20261115.md) · [3.0.56差异](SMARTCAM_V3.0.56_DIFF.md)
- 已保留 1–5 项确认优化；R8 Room 保留与无障碍保活见 [P013/P014](PITFALLS.md)。

## 下一步

1. 基于 `build10` 启动 **`T09`**（银河 E5 离线配置广场与 `13.` 系统权限面板）；[蓝图](EVCAM_SMARTCAM_REFACTOR_PLAN.md)。
2. E5 实车安装 `build10` 联合验收 `T01`–`T08`（热点浏览器 `206` 拖动/下载、B 版象限放大、360 模式 A/转角、4 类规则与双 HUD）。
3. 19 项既有 Lint 错误仍待后续专项整改。

## 最近更新（最多5条）

- 2026-11-16 23:24:00 +08:00 | build10存档 | 完成 **`T08`**（`13e2f59` / `1.6.6-test-11162313`）并发布 `build10`：`LanShareService` + 60s 空闲关闭、HTTP `206/416` Range 与路径隔离、小时时间线 + `.lock` 保护、`StorageCleanupManager` 跳过锁定及 B 版象限放大；`366` 测、R8 与 0 新增 Lint 通过（未实车） | [T08任务](tasks/t08-playback-and-lan-share.md)
- 2026-11-16 20:42:00 +08:00 | build09存档 | **`T07` 方案 1 转角补充**（`0d2a56e` / `1.6.6-test-11162036`）并发布 `build09`：仅前台模式 A 开引导线时临时租用 VHAL/APVP 方向盘转角，守住 `T01`/`T06` 4 类后台边界并保留水印监听契约；`334` 测、R8 与 0 新增 Lint 通过（未实车） | [T07任务](tasks/t07-surround-360-and-mode-a.md)
- 2026-11-16 16:17:00 +08:00 | build08存档 | 完成 **`T07`**（`b9aeda1` / `1.6.6-test-11161613`）并打包发布 `build08`：按需 360 GL 模式 A、4.1 分视角/按键显隐、4.2 R 档/车速/P 档返桌、E5 内置标定/离线车模/动态线与 `4. 360 画面`；`324` 测、R8 与 0 新增 Lint 通过（未实车） | [T07任务](tasks/t07-surround-360-and-mode-a.md)
- 2026-11-16 15:12:00 +08:00 | build07存档 | 完成 **`T06`**（`d40938c` / `1.6.6-test-11161502`）并打包发布 `build07`：4 类白名单规则引擎+默认 2 规则、R/D 换挡弹出与 X 秒退后台、保摄像头恢复、`10.` E5 信号预设匹配与 T10 挡位去抖发布；`294` 测、R8 与 0 新增 Lint 通过（未实车） | [T06任务](tasks/t06-vehicle-rules-and-signal-presets.md)
- 2026-11-16 14:06:00 +08:00 | build06存档 | 完成 **`T05`**（`ef3ea32` / `1.6.6-test-11161400`）并打包发布 `build06`：方案 A 帧率、3.2 直通旁路调色裁剪、`T04` 镜像同步、3.3 四模块水印与 3.4 提示音；`266` 测、R8 与 0 新增 Lint 通过（未实车） | [T05任务](tasks/t05-video-pipeline-and-recorder-32-34.md)

## 按需索引

- [融合重构蓝图](EVCAM_SMARTCAM_REFACTOR_PLAN.md) · [需求](REQUIREMENTS.md) · [协作](COLLABORATION.md) · [坑](PITFALLS.md) · [新AI资料](AI_HANDOFF.md) · [Smartcam全功能](SMARTCAM_FEATURE_CATALOG.md) · [3.0.56差异](SMARTCAM_V3.0.56_DIFF.md)
- [E5启动分析](E5_STARTUP_FIX_20261114.md) · [A/B试录](RECORDING_AB_TEST.md) · [开发](DEVELOPMENT.md)
- 本轮证据：`work/ab-test-preoptimization/`；历史启动证据仍在`work/e5-startup/`，不默认全读。
- [整理前历史快照](archive/2026-11/STATUS_20261114_183403.md)；历史按需读取。
