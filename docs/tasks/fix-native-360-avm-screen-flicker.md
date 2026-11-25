# 修复原车 360 / 全景影像显示时屏幕闪烁

最后更新：2026-11-26 01:08:30 +08:00
最后核对：2026-11-26 01:08:30 +08:00
任务/执行者：fix-native-360-avm-screen-flicker / Antigravity
分支/工作树：codex/e5-recording-startup-fix / E:\GPT\EVCam
状态：等待实测（已完成离线 407 测 + 单 A 构建/Lint + `build28` 封包并发布 GitHub Pre-release）
当前结论：已完成原生 360/AVM 避让全链路修复（`1.6.6-test-11260052` / `versionCode = 88` / `f8c1ac3`）并发布 GitHub Pre-release [`v1.6.6-test-11260052-build28`](https://github.com/johnwyatt041-commits/EVCam/releases/tag/v1.6.6-test-11260052-build28)，解决后台开原车 360 不触发避让、450ms/1000ms 竞态与自身包名短路、`FloatingWindowService` 仅设 `GONE` 未卸载窗口、OEM 多包名 AVM 识别及独立预览窗无避让 5 项根因；5 资产远端 SHA256 核验一致，`build26` 已归档，待实车测试。

## 根因与已落地改动

1. **实时窗口推送与防竞态**：
   - [accessibility_service_config.xml](../../app/src/main/res/xml/accessibility_service_config.xml)：`notificationTimeout` 由 `1000` 降至 `100ms`。
   - [KeepAliveAccessibilityService.java](../../app/src/main/java/com/kooo/evcam/KeepAliveAccessibilityService.java)：新增 `ForegroundWindowListener` 实时推送并在窗口切换时打印 `Foreground window changed` 调试日志。
   - [AvmForegroundDetector.java](../../app/src/main/java/com/kooo/evcam/service/AvmForegroundDetector.java)：修复 `SELF_PACKAGE`（`com.kooo.evcam`）与 `SYSTEM_UI_PACKAGE` 提前 `return false` 跳过 `UsageStatsManager` 的竞态，新增 `onAccessibilityWindowEvent()`。
2. **OEM 360/AVM 多包名识别与持续巡检策略**：
   - [OverlayAvoidancePolicy.java](../../app/src/main/java/com/kooo/evcam/service/OverlayAvoidancePolicy.java)：默认目标兼容 Geely/ECARX/Flyme/LynkCo/Zeekr/Smart 的 `avm`/`panoram`/`surround`/`360`/`.apa` 变体及 SystemUI AVM 弹窗（严格排除普通 SystemUI 与 `com.kooo.evcam`），新增 `shouldWatchAvmForeground()` 支持后台常驻可见期持续巡检。
3. **立即卸载与全主屏浮窗联动**：
   - [OverlayWindowParamsFactory.java](../../app/src/main/java/com/kooo/evcam/ui/OverlayWindowParamsFactory.java)：`AttachedOverlay.detach()` 优先 `removeViewImmediate()` 消除残留帧。
   - [FloatingWindowService.java](../../app/src/main/java/com/kooo/evcam/FloatingWindowService.java)：避让隐藏改为从 `WindowManager` 卸载并停帧刷新，接入实时窗口监听与持续 `1s` AVM 巡检。
   - [RecordingFloatingService.java](../../app/src/main/java/com/kooo/evcam/service/RecordingFloatingService.java)：接入实时窗口监听并将后台显示期纳入持续 `1s` AVM 巡检。
   - [BlindSpotService.java](../../app/src/main/java/com/kooo/evcam/BlindSpotService.java)：`isActivityInForeground` 统一复用 `AvmForegroundDetector` 并接入实时窗口推送立即隐藏主屏补盲（副屏 HUD 与 ESP32 保持联动）。
   - [StandalonePreviewWindow.java](../../app/src/main/java/com/kooo/evcam/ui/StandalonePreviewWindow.java)：接入倒挡与原车 360 避让自动关闭。

## 验证与清理（2026-11-26）

- **已通过（离线与发布核验）**：[OverlayAvoidancePolicyTest.java](../../app/src/test/java/com/kooo/evcam/service/OverlayAvoidancePolicyTest.java) 新增 OEM 变体识别、`shouldWatchAvmForeground` 与 `AvmForegroundDetector` 事件流测试；`.\tools\verify.ps1` `407` 测全过（`0` 败/错/跳，`work/verification/build-20261126-005147-518.log`），单 A Debug/Release 构建通过，`:app:lintIndependentDebug` `0` errors（`2244` warnings），Release APK（`8,058,700` B，SHA256 `79041e2910833a819d0caf3fdf8909ef072ef7284501bf0a05f9b16a05686fda`）通过 V2 签名与原生/标定资产核验；源码 `f8c1ac3` 与附注标签 `v1.6.6-test-11260052-build28` 已推至 `johnwyatt041-commits/EVCam`，GitHub Pre-release 已发布且 5 个资产远端大小与 `sha256:` 摘要核验一致。
- **未执行 / 临时文件**：尚未在银河 E5 实车验证原生 360 界面叠加表现；发布临时目录 `work/tmp/fix-native-360-avm-screen-flicker/` 已清理，无残留后台进程。

## 最近记录

- 2026-11-26 01:08:30 +08:00 | 封包发布 build28 并推送 GitHub Pre-release（`1.6.6-test-11260052`） | 源码 `f8c1ac3` 打标 `v1.6.6-test-11260052-build28` 推送 `johnwyatt041-commits/EVCam`，单 A 版 `5` 资产远端 SHA256 核验一致、`build26` 已归档至 `outputs/archive/`，临时目录已清理 | [STATUS.md](../STATUS.md)
- 2026-11-26 00:57:10 +08:00 | 完成原车 360/AVM 显示闪屏修复并过离线验证（`1.6.6-test-11260052` / `versionCode = 88`） | 补齐实时窗口推送、后台持续巡检、浮窗立即卸载、OEM 多包名识别与独立预览避让，`verify.ps1` `407` 测、Lint `0` errors 与 APK 签名资产校验通过，准备封包发布 `build28` | [OverlayAvoidancePolicy.java](../../app/src/main/java/com/kooo/evcam/service/OverlayAvoidancePolicy.java)

## 下一步

1. 在银河 E5 实车验证：从桌面/导航/EVCam 前台通过方向盘按键、低速触发、雷达或倒挡触发原车 360 时不再闪屏；若仍偶发且使用了未覆盖的原车 360 包名，导出日志核对 `Foreground window changed`。
