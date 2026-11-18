# T10 飞书 P 档门控懒加载、私有 GitHub 手动更新与 Debug 日志连贯性校验

- **状态**: 已完成 `build12` 离线开发、单测与双风味打包（待 E5 实车验证）
- **最后核对**: `2026-11-17 01:49:00 +08:00`
- **关联构建**: `1.6.6-test-11170138`（`versionCode = 81`，源码提交 `29fe92b`，Tag `v1.6.6-test-11170138-build12`）
- **基线范围**: 基于 `build11`（`3c3c2ba`）仅实施 [重构总方案](../EVCAM_SMARTCAM_REFACTOR_PLAN.md) T10，保留 T06 档位发布契约、EVCC/ESP32 双 HUD 与 T09 配置广场白名单。

## 实装内容

1. **11. 飞书远程 P 档门控与零后台初始化**
   - [FeishuConfig.java](..\..\..\..\app\src\main\java\com\kooo\evcam\feishu\FeishuConfig.java) 新增总开关 `feishu_enabled`（保留老用户 `isConfigured && auto_start` 映射）、`p_gear_only`（默认 `true`）及静态无副作用判定 `isFeatureActive()`。
   - 新增纯 Java 状态机 [FeishuGearGateController.java](..\..\..\..\app\src\main\java\com\kooo\evcam\feishu\FeishuGearGateController.java)，订阅 T06 `VehicleSignalHub.getGearStatePublisher()`：冷启动 `UNKNOWN` 不建连，进 P 档防抖 `2.0s` 懒加载，切离 P 档 `0.5s` 彻底断连，`UNKNOWN` 超 `3.0s` 兜底断开，禁用/`user_exited` `0ms` 即停；并按需申请/释放 `RecordingVehicleSource.Lease`。
   - [FeishuBotManager.java](..\..\..\..\app\src\main\java\com\kooo\evcam\feishu\FeishuBotManager.java) 与 [FeishuApiClient.java](..\..\..\..\app\src\main\java\com\kooo\evcam\feishu\FeishuApiClient.java) 增加代际令牌 `activeGeneration`、`reconnectRunnable`/`connectThread` 追踪及完整连接池/请求取消清理。
   - 收敛 [RemoteServiceManager.java](..\..\..\..\app\src\main\java\com\kooo\evcam\RemoteServiceManager.java)、[RemoteCommandDispatcher.java](..\..\..\..\app\src\main\java\com\kooo\evcam\remote\RemoteCommandDispatcher.java)、[CameraForegroundService.java](..\..\..\..\app\src\main\java\com\kooo\evcam\CameraForegroundService.java)、[MainActivity.java](..\..\..\..\app\src\main\java\com\kooo\evcam\MainActivity.java)、[BackgroundCapabilityController.java](..\..\..\..\app\src\main\java\com\kooo\evcam\lifecycle\BackgroundCapabilityController.java) 与 [AppExitCoordinator.java](..\..\..\..\app\src\main\java\com\kooo\evcam\lifecycle\AppExitCoordinator.java)，未配置/禁用时纯后台录像零远程初始化；同步更新 [FeishuFragment.java](..\..\..\..\app\src\main\java\com\kooo\evcam\FeishuFragment.java)。
2. **14. 私有 GitHub 手动检查更新与加密 PAT**
   - 新增 `com.kooo.evcam.update`（`AppVersionComparator`、`GitHubReleaseParser`、`GitHubHostPolicy`、`PatCipher`、`AndroidKeyStorePatCipher`、`GitHubUpdateConfig`、`GitHubReleaseClient`），默认指向 `johnwyatt041-commits/EVCam`（手动触发，零后台轮询）；`AndroidKeyStore` AES-256-GCM 密文存入独立 `github_update_secrets`（拒绝降级明文）。
   - 下载走 API Asset URL（`Accept: application/octet-stream`）并验证跨主机 302 剥离 `Authorization`，按 A/B 风味精确选包并核验 ZIP 魔数与 SHA-256；[AndroidManifest.xml](..\..\..\..\app\src\main\AndroidManifest.xml) 补齐 `REQUEST_INSTALL_PACKAGES`，支持 [UpdateSettingsFragment.java](..\..\..\..\app\src\main\java\com\kooo\evcam\UpdateSettingsFragment.java) 系统安装、ADB 安装与历史版本浏览，去除 [AppConfig.java](..\..\..\..\app\src\main\java\com\kooo\evcam\AppConfig.java) 原硬编码默认更新域名。
3. **15. Debug 日志连贯性校验**
   - 新增 [SessionLogStore.java](..\..\..\..\app\src\main\java\com\kooo\evcam\SessionLogStore.java) 接管会话持久化、增量批量/定时落盘、超限截尾与一致性自检；[EVCamApplication.java](..\..\..\..\app\src\main\java\com\kooo\evcam\EVCamApplication.java) 早期调 `AppLog.init(this)`。
   - 修复 [AppLog.java](..\..\..\..\app\src\main\java\com\kooo\evcam\AppLog.java) `isDebugToInfoEnabled()` 误触发重复轮换和崩溃处理器叠套的缺陷；移除硬编码上游 `suyunkai.top` Gotify 上传；[DebugLogSettingsFragment.java](..\..\..\..\app\src\main\java\com\kooo\evcam\DebugLogSettingsFragment.java) 与 [LogcatViewerActivity.java](..\..\..\..\app\src\main\java\com\kooo\evcam\LogcatViewerActivity.java) 全中文化并提供本地导出、实时 Logcat 与连贯性自检。

## 离线验证与待验缺口

- **已实测**：`tools\verify.ps1` 离线通过 `414` 项单测（`207` × 2，含 `FeishuGearGateControllerTest`、`GitHubUpdateTest`、`SessionLogStoreTest`）；`:app:lintIndependentDebug` `18 errors`（与 `build11` 持平，T10 0 新增）；A/B 双风味 Release APK 已通过 `apksigner` 校验且含 `libvhal_decoder.so`。
- **待实测**：E5 实车 P/D/R 挂档飞书建连/断连时机、真实私有库 PAT 检查更新与 AndroidKeyStore 实车下载安装流程。
