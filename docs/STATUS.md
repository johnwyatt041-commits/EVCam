# 项目进度

最后更新/核对：2026-11-17 16:16:18 +08:00（Asia/Hong_Kong）
维护：Antigravity（已完成 **18 项历史 Lint 清零与 `MainActivity` 3 处接收器加固** 并发布 **`build13`**；**尚未上车实测**）。执行规范：[PROGRESS_RULES.md](PROGRESS_RULES.md)。

## 当前快照

| 项目 | 当前状态 |
| --- | --- |
| 项目/设备 | E:\GPT\EVCam；银河E5，2560×1600 |
| 分支 | `codex/e5-recording-startup-fix`；`8ef5b36`（Tag `v1.6.6-test-11171608-build13`，`versionCode = 82`） |
| 最新APK | **`build13`：`1.6.6-test-11171608`**（A `6,791,918` / B `6,808,302`B，累计 `T01`–`T10` + 0 Lint，未实车） |
| APK源码 | `8ef5b36`；`outputs/EVCam-E5-build13-1.6.6-test-11171608-manifest.json` 已关联 |
| 本地交付 | `outputs/EVCam-E5-{A-Independent,B-Composite}-1.6.6-test-11171608-build13-release.apk` 及 SHA/mappings/notes |
| GitHub | `johnwyatt041-commits/EVCam` 已推分支/Tag，并发布 `build13` Pre-release 全套 6 项资产 |
| 验证 | 离线通过：A/B 共 `432` 测（每版 `216`，0 失败/跳过）、双风味 Lint **`0 errors`**（0 屏蔽/基线）、Release R8、`apksigner` 及 `so` |
| 实车 | `build01`（`T01`）–`build13`（`T01`–`T10` + Lint 清零）均**尚未上车实测**；四段短片仅静态/离线分析 |

## 当前结论

- **`build13`（`8ef5b36` / `versionCode = 82`）清零全部 18 项历史 Lint 并加固接收器**：[验收与Lint任务](tasks/build12-e5-acceptance-and-lint-cleanup.md) 落地 `Vibrator` 移除、`MainBackNavigationRouter` 与 `OnBackPressedDispatcher`（`MainActivity`/`QrTransferDialog`/`TransparentBootActivity`）、`values-v31` Splash 拆分、8 处 `ContextCompat.registerReceiver`、`ACTION_UPDATE_SIZE` 包名限定与 `app:tint`，双风味 Lint **`0 errors`**。
- **`build12`（`29fe92b`）完成 `T10` 融合收官三项**：[T10任务](tasks/t10-feishu-pgear-github-update-and-log-coherence.md) 落地 `FeishuGearGateController`（总开关懒加载、进 P 2s/出 P 0.5s/UNKNOWN 3s 防抖、`FeishuBotManager` 清理定时器、纯后台零远程初始化）、`update/`（私有 GitHub 手动检查、`AndroidKeyStore` AES-GCM PAT、A/B 校验）及 `SessionLogStore`。
- 四段对比中 A 后台约 24.95fps；SC 约 24.5fps 但分格 20.5–22.6 次更新/秒。[报告](RECORDING_COMPARISON_20261115.md) · [3.0.56差异](SMARTCAM_V3.0.56_DIFF.md)
- 已保留 1–5 项确认优化；R8 Room 保留与无障碍保活见 [P013/P014](PITFALLS.md)。

## 下一步

1. 在银河 E5 实车安装 `build13`（A/B 先后覆盖），按 P0→P1→P2 清单验收 `T01`–`T10`（含三路录制/双 HUD/VHAL/飞书 P 档/预测返回）并回传日志与原片。

## 最近更新（最多5条）

- 2026-11-17 16:16:18 +08:00 | build13存档 | 清零全部 18 项 Lint（0 屏蔽）+ `MainActivity` 3 处 `ContextCompat.registerReceiver` 并补 9 项单测，递进 `versionCode = 82`（`8ef5b36` / `1.6.6-test-11171608`）打包发布 `build13`；`432` 测、双风味 `0` Lint error、R8/`apksigner`/`so` 通过（未实车） | [验收与Lint任务](tasks/build12-e5-acceptance-and-lint-cleanup.md)
- 2026-11-17 01:49:00 +08:00 | build12存档 | 完成 **`T10`**（`29fe92b` / `1.6.6-test-11170138`）并打包发布 `build12`：`11.` 飞书 P 档门控懒加载+彻底断连+零后台初始化、`14.` 私有 GitHub 更新+Keystore 加密 PAT+A/B 资产校验、`15.` `SessionLogStore` 连贯性修复与去 Gotify；`414` 测、R8 与 `18` 项 Lint 0 新增（未实车） | [T10任务](tasks/t10-feishu-pgear-github-update-and-log-coherence.md)
- 2026-11-17 00:16:00 +08:00 | build11存档 | 完成 **`T09`**（`3c3c2ba` / `1.6.6-test-11170004`）并发布 `build11`：`12. 配置广场`（仅 E5 6 分区白名单、3 套离线预设、SAF/剪贴板导入导出与 `AtomicFile` 备份回滚）及 `13. 系统与权限`（`PermissionDiagnostics` 10 项分级诊断、缺项申请、ADB/白名单）接入设置目录；`386` 测、R8 与 `18` 项 Lint（降 1）通过（未实车） | [T09任务](tasks/t09-config-plaza-and-permissions.md)
- 2026-11-16 23:24:00 +08:00 | build10存档 | 完成 **`T08`**（`13e2f59` / `1.6.6-test-11162313`）并发布 `build10`：`LanShareService` + 60s 空闲关闭、HTTP `206/416` Range 与路径隔离、小时时间线 + `.lock` 保护、`StorageCleanupManager` 跳过锁定及 B 版象限放大；`366` 测、R8 与 0 新增 Lint 通过（未实车） | [T08任务](tasks/t08-playback-and-lan-share.md)
- 2026-11-16 20:42:00 +08:00 | build09存档 | **`T07` 方案 1 转角补充**（`0d2a56e` / `1.6.6-test-11162036`）并发布 `build09`：仅前台模式 A 开引导线时临时租用 VHAL/APVP 方向盘转角，守住 `T01`/`T06` 4 类后台边界并保留水印监听契约；`334` 测、R8 与 0 新增 Lint 通过（未实车） | [T07任务](tasks/t07-surround-360-and-mode-a.md)

## 按需索引

- [融合重构蓝图](EVCAM_SMARTCAM_REFACTOR_PLAN.md) · [需求](REQUIREMENTS.md) · [协作](COLLABORATION.md) · [坑](PITFALLS.md) · [新AI资料](AI_HANDOFF.md) · [Smartcam全功能](SMARTCAM_FEATURE_CATALOG.md) · [3.0.56差异](SMARTCAM_V3.0.56_DIFF.md)
- [E5启动分析](E5_STARTUP_FIX_20261114.md) · [A/B试录](RECORDING_AB_TEST.md) · [开发](DEVELOPMENT.md)
- 本轮证据：`work/ab-test-preoptimization/`；历史启动证据仍在`work/e5-startup/`，不默认全读。
- [整理前历史快照](archive/2026-11/STATUS_20261114_183403.md)；历史按需读取。
