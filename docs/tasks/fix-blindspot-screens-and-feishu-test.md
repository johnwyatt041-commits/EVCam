# 补盲主副屏模式/倒置坐标/统一画面调整重构 & 飞书测试连接

最后更新：2026-11-21 03:45:31 +08:00
最后核对：2026-11-22 07:34:13 +08:00
任务/执行者：fix-blindspot-screens-and-feishu-test / Antigravity
分支/工作树：codex/e5-recording-startup-fix / E:\GPT\EVCam
状态：已提交推送源码（随 `215753f` 推送 `johnwyatt041-commits/EVCam`，尚未单独封包与实车测试）
当前结论：已完成补盲输出三选一（仅主屏/仅副屏/主副屏同时）、副屏视觉方向坐标正反映射修复、单卡「先选屏幕再选摄像头+四路同步」统一画面调整与飞书「测试连接」按钮实现（`1.6.6-test-11210334`），待 E5 实车验证副屏/EVCC 投屏与实网测试飞书连接。

## 已完成实现与验证边界

1. **补盲输出模式（仅主屏 / 仅副屏 / 主副屏同时）**：
   - [BlindSpotCoordinator.java](../../app/src/main/java/com/kooo/evcam/blindspot/BlindSpotCoordinator.java)、[AppConfig.java](../../app/src/main/java/com/kooo/evcam/AppConfig.java)、[BlindSpotService.java](../../app/src/main/java/com/kooo/evcam/BlindSpotService.java)：新增 `main_blind_spot_enabled` 与三模式切换；选「仅副屏」时不弹出主屏独立/临时补盲窗，360/AVM 抑制退出也不误弹主屏，避免误用 Display `0` 叠画到主屏。同步注册至 [E5ConfigSchema.java](../../app/src/main/java/com/kooo/evcam/configplaza/E5ConfigSchema.java) 与 [AndroidE5ConfigTarget.java](../../app/src/main/java/com/kooo/evcam/configplaza/AndroidE5ConfigTarget.java)。
2. **修复副屏调整坐标反向 & 统一画面调整入口**：
   - [BlindSpotPlacement.java](../../app/src/main/java/com/kooo/evcam/blindspot/BlindSpotPlacement.java)、[BlindSpotCorrection.java](../../app/src/main/java/com/kooo/evcam/BlindSpotCorrection.java)、[BlindSpotVisualEditorView.java](../../app/src/main/java/com/kooo/evcam/blindspot/BlindSpotVisualEditorView.java)：新增 `0°/90°/180°/270°`「视觉坐标 ↔ WindowManager 逻辑坐标」正反映射，副屏根视图保持 `0°` 并将硬件方向旋转合并至 `TextureView` 矩阵末尾；修复 `180°` 倒置副屏往右/下挪动反向问题，同时兼容旧逻辑坐标无损迁移。
   - [fragment_secondary_display_settings.xml](../../app/src/main/res/layout/fragment_secondary_display_settings.xml)、[BlindSpotSettingsFragment.java](../../app/src/main/java/com/kooo/evcam/BlindSpotSettingsFragment.java)、[BlindSpotFloatingWindowView.java](../../app/src/main/java/com/kooo/evcam/BlindSpotFloatingWindowView.java)、[MainFloatingWindowView.java](../../app/src/main/java/com/kooo/evcam/MainFloatingWindowView.java)：移除重复的「分方向画面矫正」卡片，在「画面位置与画面调整」单卡中实现「先选屏幕 → 再选摄像头」独立调节位置/旋转/四边裁切/镜像，并新增「四路位置同步」勾选框；保留 ESP32 MJPEG 投屏入口与关闭鱼眼。
3. **飞书远程「测试连接」按钮**：
   - 新增 [FeishuConnectionTester.java](../../app/src/main/java/com/kooo/evcam/feishu/FeishuConnectionTester.java) 并接入 [fragment_feishu.xml](../../app/src/main/res/layout/fragment_feishu.xml) / [FeishuFragment.java](../../app/src/main/java/com/kooo/evcam/FeishuFragment.java)：直接校验当前输入框凭据（`tenant_access_token` + `/callback/ws/endpoint`），提示中文逗号白名单误填，不写入缓存 Token、不受 P 档门控限制且退出页面自动取消请求。
4. **验证结果（2026-11-21）**：
   - **已通过（离线）**：[BlindSpotCoordinatorAndSuppressionTest.java](../../app/src/test/java/com/kooo/evcam/blindspot/BlindSpotCoordinatorAndSuppressionTest.java)、[BlindSpotPlacementAndCorrectionTest.java](../../app/src/test/java/com/kooo/evcam/blindspot/BlindSpotPlacementAndCorrectionTest.java)、[FeishuConnectionTesterTest.java](../../app/src/test/java/com/kooo/evcam/feishu/FeishuConnectionTesterTest.java) 等全量双风味 `550` 测 `0` 失败/`0` 跳过（`build-20261121-033424-898.log`），双风味 Debug/Release 编译通过，双风味 Lint `0` errors（`2213` warnings）。
   - **未执行**：尚未在银河 E5 实车或物理副屏/EVCC/飞书外网环境实测。

## 最近记录

- 2026-11-21 03:45:31 +08:00 | 完成补盲多屏/倒置坐标/统一调校重构与飞书测试连接（`1.6.6-test-11210334`） | 完成源码改造与单测新增，`verify.ps1` 双风味 `550` 测 `0` 失败、Debug/Release 构建与 Lint `0` errors 通过；待实车副屏/EVCC 与飞书联调 | [BlindSpotSettingsFragment.java](../../app/src/main/java/com/kooo/evcam/BlindSpotSettingsFragment.java)

## 下一步

1. 在银河 E5 实车测试「仅副屏 / 主副屏同时」、180° 副屏拖动方向、分路四路同步与飞书「测试连接」实网校验。
