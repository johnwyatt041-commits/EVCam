# 包 D：记录仪设置修正（仅 D 档弹出 + 水印独立开关去刹车）

最后更新：2026-11-19 01:02:00 +08:00
最后核对：2026-11-19 01:02:00 +08:00
任务/执行者：fix-pack-d-recorder-settings-and-watermark / Antigravity
分支/工作树：codex/e5-recording-startup-fix（串行合入，基于 7d03610 + 包 A/B/C/E 工作区）/ E:\GPT\EVCam
状态：离线构建、468 项 JVM 单测、双风味 Lint（0 errors）与 emulator-5556 双分辨率 UI 验收已通过（待银河 E5 实车验证）
当前结论：已完成 `3.1` 仅前进档（D 档）自动弹出与 `3.3` 水印四项（时间/车速/档位/转向灯）独立开关及去除刹车字段；保持 `FISHEYE_CORRECTION = false` 与 `NETWORK_STREAMING = true` 不变，并在 `emulator-5556` 通过 `1600×900` 与 `2560×1600` 全项 UI 与边界复测。

## 已完成实现与修复要点

1. **`3.1` 自动弹出记录仪改为仅前进档（D 档），不含倒车档（R 档）**：
   - 修改 [GearShiftPopupCoordinator.java](../../app/src/main/java/com/kooo/evcam/vehicle/GearShiftPopupCoordinator.java)：将 `isTargetRd` 收敛为 `isTargetDrive`（仅稳定切入 `Gear.DRIVE` 触发，移除 `Gear.REVERSE` 触发）。
   - 保留 `rd_shift_auto_popup_enabled` / `rd_shift_popup_seconds` 偏好与 ConfigPlaza 键名兼容，同步更新 `RecorderSettingsFragment`、`fragment_recorder_settings.xml`、`AppConfig`、`MainActivity`、`VehicleAutomationCoordinator`、`WakeUpHelper`、`PermissionDiagnostics` 与 `e5_surround_reverse_enhanced.json` 的说明与日志文案；更新 [GearShiftPopupCoordinatorTest.java](../../app/src/test/java/com/kooo/evcam/vehicle/GearShiftPopupCoordinatorTest.java) 验证 R 档不触发、D 档正常弹出与自动退后台。
2. **`3.3` 显示水印独立开关 & 移除不可检测的刹车**：
   - 修改 [VehicleStatusWatermark.java](../../app/src/main/java/com/kooo/evcam/camera/VehicleStatusWatermark.java)：`WatermarkOptions` 拆出独立 `showGear` 与 `showTurnSignal`（连同 `showDateTime`、`showSpeed`），任意车况子项开启时保活 `RecordingVehicleSource` 租约，`formatSecondLine()` 与 `buildCacheKey()` 彻底删除 `刹车` 字样及 `safeStatus.brake` 拼接；同步更新 `CodecVideoRecorder`。
   - 修改 [AppConfig.java](../../app/src/main/java/com/kooo/evcam/AppConfig.java)、`RecorderSettingsFragment` 与 `fragment_recorder_settings.xml`：取消“车辆状态水印”主门控与绑定的 `cb_watermark_gear_turn`，在 `3.3 显示` 中提供 **时间**、**车速**、**档位**、**转向灯** 4 个独立开关（加自定义文字与位置/样式），并实现旧版 `vehicle_status_watermark` / `watermark_gear_turn_enabled` 到新独立开关的平滑兼容迁移，同步更新 `E5ConfigSchema`、`AndroidE5ConfigTarget` 与 3 份 `e5-presets/*.json`。
   - 更新 [VehicleStatusWatermarkTest.java](../../app/src/test/java/com/kooo/evcam/camera/VehicleStatusWatermarkTest.java)、[VehicleWatermarkDisabledTest.java](../../app/src/test/java/com/kooo/evcam/camera/VehicleWatermarkDisabledTest.java) 与 `E5ConfigPlazaTest`，新增无刹车断言、独立开关缓存键、旧版偏好与预设迁移单测。
3. **验证与边界**：`.\tools\verify.ps1` 通过（双风味 `468` 测 `0` 失败 `0` 跳过、`assembleDebug`/`assembleRelease` 成功），双风味 Lint `0` errors，`AndroidTest` 编译通过；`emulator-5556`（剔除 `arm64-v8a` 重签 Release 测试包，已停机并清空 `work/tmp/fix-pack-d-recorder-settings-and-watermark/`）在 `1600×900`（`160`/`240dpi`）与 `2560×1600`（`240`/`160dpi`）+ 标准/大号缩放 + 深/浅色主题下验证通过：无溢出重叠、无“刹车”或“R/D 弹出”遗留文案、D 档弹出折叠/5 档时长/添加规则弹窗/4 项水印独立切换/自定义文字 40 字上限/位置与样式重启动持久化/配置广场同步及零崩溃均已确认；E5 实车 VHAL D 档弹出与实景视频水印待实车验证。

## 最近记录

- 2026-11-19 01:02:00 +08:00 | emulator-5556 双分辨率复测通过并清理 | 在 `emulator-5556` 完成 `1600×900` 与 `2560×1600`（`160`/`240dpi`、标准/大号缩放、深/浅色）下「记录仪 → 录制/显示」UI 溢出与边界实测并关机清理临时目录 | [fix-pack-d-recorder-settings-and-watermark.md](fix-pack-d-recorder-settings-and-watermark.md)
- 2026-11-18 19:31:03 +08:00 | 包 D 实现与离线验证 | 完成 `3.1` 仅 D 档弹出与 `3.3` 四项独立水印开关及去刹车（`1.6.6-test-11181919`），`verify.ps1` `466` 测 0 失败、双风味 Lint `0` errors（待实车/模拟器实测） | [fix-pack-d-recorder-settings-and-watermark.md](fix-pack-d-recorder-settings-and-watermark.md)
- 2026-11-18 15:40:00 +08:00 | 建立包 D 规格 | 确认去掉 `3.2` 鱼眼矫正后整理形成轻量包 D（`3.1` 仅 D 档弹出 + `3.3` 时间/车速/档位/转向灯独立开关去刹车） | [fix-pack-d-recorder-settings-and-watermark.md](fix-pack-d-recorder-settings-and-watermark.md)

## 下一步

1. 随包 A–E 合入提交并打包导出最新 A/B Release APK 至 `outputs/`。
2. 在银河 E5 实车验证包 D（D 档自动弹出、R 档不弹出、时间/车速/档位/转向灯独立水印无刹车字段）。
