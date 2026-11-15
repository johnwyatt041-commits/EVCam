# 任务：T01 精简架构底座、退出机制与补盲遗留清理（t01-lifecycle-and-signal-foundation）

最后更新：2026-11-15 21:34:30 +08:00（Asia/Hong_Kong）
最后核对：2026-11-15 21:34:30 +08:00
任务/执行者：t01-lifecycle-and-signal-foundation / Antigravity
分支/工作树：`codex/e5-recording-startup-fix` / `E:\GPT\EVCam`（串行执行）
状态：等待实测（已完成首个记录点 `build01` 打包并推送 GitHub，未上车测试）

## 当前结论

`T01` 源码（`ea395a0`，`1.6.6-test-11152059`）已封装为记录点 **`build01`**（`outputs/EVCam-E5-{A-Independent,B-Composite}-1.6.6-test-11152059-build01-release.apk`、SHA256、manifest 与 R8 映射）并发布 GitHub Prerelease `v1.6.6-test-11152059-build01`；当前仅完成离线校验，**尚未实车测试**。

## 本轮改动清单

1. **统一单进程退出双状态与协调器**：新增 [AppLifecycleState.java](../../app/src/main/java/com/kooo/evcam/lifecycle/AppLifecycleState.java)、[AppExitCoordinator.java](../../app/src/main/java/com/kooo/evcam/lifecycle/AppExitCoordinator.java)、[BackgroundCapabilityController.java](../../app/src/main/java/com/kooo/evcam/lifecycle/BackgroundCapabilityController.java)。
   - `user_exited=true` 同步落盘并按方案 A 禁用 `BootReceiver` 组件及运行时门禁，同时在 `KeepAliveProvider` / `BootReceiver` / `KeepAliveReceiver` / `KeepAliveWorker` / `KeepAliveManager` / `KeepAliveAccessibilityService` / `TransparentBootActivity` / `CameraForegroundService` / `BlindSpotService` / `FloatingWindowService` / `RecordingFloatingService` / `RemoteServiceManager` 全部拦截自重启与开机唤醒，直至用户手工打开 `MainActivity` 恢复。
   - `ui_exited=true` 仅藏界面，保活后台服务并禁止 `CameraForegroundService` 自动拉回 `MainActivity`；重构 `MainActivity.exitApp()`、`moveTaskToBack` 及 `onDestroy()`，远程确认退出保留 `600ms` 回执窗口再断飞书与杀进程；预留 `ensureFeishuLazyStarted` 供 `T10` 接入。
2. **补盲遗留清理与统一无动画显隐**：物理删除 `BlindSpotDisclaimerDialogFragment`、`dialog_blind_spot_disclaimer.xml`、`blind_spot_disclaimer.md` 及对应偏好与设置开关；清理 `BlindSpotFloatingWindowView`、`MainFloatingWindowView`、`BlindSpotSettingsFragment` 与 `AppConfig` 中悬浮窗开关闭动效逻辑，抽离 [FloatingWindowDisplayHelper.java](../../app/src/main/java/com/kooo/evcam/FloatingWindowDisplayHelper.java)（保留首帧等待防黑屏）。
3. **车辆信号收窄为 4 类核心白名单**：新增 [VehicleSignalCategory.java](../../app/src/main/java/com/kooo/evcam/vehicle/VehicleSignalCategory.java)、[VehicleSignalSnapshot.java](../../app/src/main/java/com/kooo/evcam/vehicle/VehicleSignalSnapshot.java)、[VhalProtoBatchReader.java](../../app/src/main/java/com/kooo/evcam/vehicle/VhalProtoBatchReader.java)、[CoreVehicleSignalDecoder.java](../../app/src/main/java/com/kooo/evcam/vehicle/CoreVehicleSignalDecoder.java)、[VehicleSignalHub.java](../../app/src/main/java/com/kooo/evcam/vehicle/VehicleSignalHub.java)，复用 wire reader 到 `VehicleStatusDecoder`；`VhalSignalObserver` 与 `CarSignalManagerObserver` 仅向 Hub 输出档位、车速、四车门、转向灯并在断连时复位。

## 验证与证据（离线）

| 检查项 | 结果 | 证据 / 备注 |
| --- | --- | --- |
| `tools/verify.ps1` | 通过（`174` tests，`0` 失败/错误/跳过） | A/B 各 `87` 项（含新增 `AppLifecycleStateTest` 6 项、`VehicleSignalCategoryAndHubTest` 2 项），Debug 与 Release R8 均编过；日志 `work/verification/build-20261115-210705-946.log` |
| `:app:lintIndependentDebug` | `19` 项既有错误，`0` 新增错误 | `1668` 条警告（较 `1675` 降 7 条）；`19` 项错误均属原 HEAD 基线；详见 `work/verification/t01-gradle.log` |
| `build01` 打包与校验 | 完成（**尚未实车测试**） | 标签 `v1.6.6-test-11152059-build01`（源码 `ea395a0`）；双风味 Release APK（各 `6,447,343` 字节）、SHA256、R8 mappings 与 GitHub Pre-release 见 `outputs/EVCam-E5-build01-1.6.6-test-11152059-manifest.json` |
| 实车待验项 | **待实车确认** | E5 彻底退出后重启拦截、无障碍/TIME_TICK 防复活、`CarSignalManager`/VHAL 四类真实总线字节对齐与远程飞书 600ms 退出回执 |

## 最近记录（最多5条）

- 2026-11-15 21:35:00 +08:00 | build01 存档 | 打包并校验 `1.6.6-test-11152059` 双风味 Release APK，推送分支与标签 `v1.6.6-test-11152059-build01` 并发布 GitHub Pre-release（尚未实车测试）。| [总览](../STATUS.md)
- 2026-11-15 21:08:30 +08:00 | T01 收尾 | 完成生命周期/退出协调器、补盲免责与动效清理、4 类车辆信号 Hub；174 项单测与 Debug/Release R8 通过，待实车。| [总蓝图](../EVCAM_SMARTCAM_REFACTOR_PLAN.md)
- 2026-11-15 20:49:00 +08:00 | 方案确认 | 用户拍板 Option A（彻底退出禁 `BootReceiver`）、双悬浮窗均去动效、同步清停 `BlindSpotService`/`RecordingFloatingService`。| [总览](../STATUS.md)

## 下一步

已完成 `build01` 打包与 GitHub 发布（未实车）；当前转入 `T02` 源码核查，先输出 SC 底层不足与 `T02` 优化方案再改代码。
