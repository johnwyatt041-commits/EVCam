# T06：车辆状态规则引擎与信号预设匹配（3.1 + 10.）

最后更新：2026-11-16 15:12:00 +08:00（Asia/Hong_Kong）
负责人：Antigravity
状态：已完成离线编码、单测、Lint 校验与 `build07` 打包（源码 `d40938c` / 版本 `1.6.6-test-11161502`，**未实车验证**）
目标范围：严格执行 [融合重构蓝图](../EVCAM_SMARTCAM_REFACTOR_PLAN.md) **T06**（`3.1` 车辆触发规则 / 保摄像头前台恢复 / R-D 换挡自动弹出记录仪并在 X 秒后自动退后台 / 向 `T10` 导出标准挡位订阅状态 + `10. 信号预设与匹配`）；保留 `T01`–`T05`、双 HUD（EVCC+ESP32）与飞书能力不被破坏；未触碰 `T07`–`T10`。

## 1. 背景与约束对齐

- 严禁照搬 Smartcam V3.0.56 的 48 路全局信号后台轮询（`Ler3`）、`Ljs2` 通用配置及 `领克08` / `smartcam.clqya.com` 云端预设接口。
- 严格收窄在 [VehicleSignalCategory](../../app/src/main/java/com/kooo/evcam/vehicle/VehicleSignalCategory.java) 与 [VehicleSignalHub](../../app/src/main/java/com/kooo/evcam/vehicle/VehicleSignalHub.java) 的 **4 类白名单核心信号**：档位（`GEAR`）、车速（`SPEED`）、四车门（`DOORS`）与转向灯（`TURN_SIGNALS`）。

## 2. 本轮实现要点（`d40938c`）

1. **4 类白名单规则引擎（`com.kooo.evcam.vehicle.rule.*`）**
   - [VehicleRuleSignal](../../app/src/main/java/com/kooo/evcam/vehicle/rule/VehicleRuleSignal.java) 仅暴露 `GEAR` / `SPEED` / `DOOR_FL/FR/RL/RR/ANY_DOOR` / `TURN_SIGNAL`；当底层为 `UNKNOWN` 或 `<0` 时返回 `null`（不匹配任何规则），防止断连或未就绪时 `!= P` 误触发。
   - [VehicleTriggerRule](../../app/src/main/java/com/kooo/evcam/vehicle/rule/VehicleTriggerRule.java) 内置 Smartcam `Lgk1.k()` 默认 2 条规则：`default_door_stop`（`DOOR_FL == 1 -> EXIT_APP`）与 `default_gear_start`（`GEAR != 4 -> START`），采用无反射 Gson `JsonObject`/`JsonArray` 序列化保障 R8 安全，且空数组 `"[]"` 保留用户显式清空语义。
   - [VehicleRuleEngine](../../app/src/main/java/com/kooo/evcam/vehicle/rule/VehicleRuleEngine.java) 实现多条件 `AND`/`OR`、可持续秒数防抖（`delaySeconds`）、边沿触发锁存及停录/退出规则优先压制 `START` 的仲裁语义。
2. **R/D 换挡自动弹出与 X 秒退后台（[GearShiftPopupCoordinator](../../app/src/main/java/com/kooo/evcam/vehicle/GearShiftPopupCoordinator.java)）**
   - `250ms` 挡位去抖；首次接入及断连恢复 `UNKNOWN -> valid` 仅建立基线不抢前台。
   - 当 UI 已处于前台且非自动弹出会话时保持原位、不会在倒计时结束后把用户踢回后台；自动弹出期间如用户切换导航页、切小窗/全屏或进入布局编辑，立即解除自动退后台接管。
   - 支持配置 `3/5/10/15/30` 秒（默认 `5` 秒），倒计时结束仅调用退后台（保留录像与前台服务保活）。
3. **3.1 保摄像头有门槛恢复与 `confirm_door_exit` 衔接（[CameraRecoveryCoordinator](../../app/src/main/java/com/kooo/evcam/vehicle/CameraRecoveryCoordinator.java)、[VehicleAutomationCoordinator](../../app/src/main/java/com/kooo/evcam/vehicle/VehicleAutomationCoordinator.java)）**
   - 接入 `keep_camera_connected` 到 [MainActivity](../../app/src/main/java/com/kooo/evcam/MainActivity.java) `onPause`（`STOP_RELEASE`/`EXIT_APP` 与 `user_exited=true` 优先释放）。
   - `CameraForegroundService` 以 5s 周期驱动 `CameraRecoveryCoordinator`：后台先执行 `5s × 3` 重连；若仍断线且开启前台拉起恢复，须同时满足 **亮屏 + 60 秒冷却 + 最多 3 次**，恢复成功后自动退回后台，3 次耗尽后收敛停录防止循环抢焦点。
   - 规则触发 `EXIT_APP`：若 UI 已在后台则静默停录释放且保留服务监听（不设 `user_exited=true`）；若在前台且过 5 秒启动保护窗口，按 Settings 1 `confirm_door_exit` 决定弹出 `door_exit_delay_seconds` 倒计时确认弹窗或直接退后台。
4. **向 T10 导出的标准挡位订阅契约（[VehicleGearStatePublisher](../../app/src/main/java/com/kooo/evcam/vehicle/VehicleGearStatePublisher.java)）**
   - 通过 `VehicleSignalHub.getInstance().getGearStatePublisher()` 提供 `300ms` 去抖后的 `GearState`（`isParkConfirmed()` / `isDrivingConfirmed()` / `addListener`），信号变为 `UNKNOWN` 时立即取消确认，供后续 `T10` 飞书 P 挡门控直接订阅。
5. **`3.1` 规则页扩展与 `10. 信号预设与匹配`（[VehicleSignalPresetFragment](../../app/src/main/java/com/kooo/evcam/ui/VehicleSignalPresetFragment.java)）**
   - 在 [SettingsCatalog](../../app/src/main/java/com/kooo/evcam/ui/SettingsCatalog.java) / [SettingsFragment](../../app/src/main/java/com/kooo/evcam/SettingsFragment.java) 接入第 8 项 `10. 信号预设与匹配`：E5 本地信号源切换、异步连接检测与快照拉取、实时 4 类信号及去抖挡位诊断面板、4 类 VHAL Property ID 自定义与恢复默认映射（[VehicleSignalCategory](../../app/src/main/java/com/kooo/evcam/vehicle/VehicleSignalCategory.java)）。
   - 在 [RecorderSettingsFragment](../../app/src/main/java/com/kooo/evcam/RecorderSettingsFragment.java) `3.1 录制` 增补保摄像头、前台拉起恢复、R/D 弹出及秒数选择器、规则列表与增删改/恢复默认弹窗。

## 3. 离线验证与构建证据（`build07`）

- **JVM 单元测试（`tools\verify.ps1`）**：总计 **`294`** 项（Independent `147` + Composite `147`，新增 `VehicleRuleEngineTest`、`VehicleGearStatePublisherTest`、`GearShiftPopupCoordinatorTest`、`CameraRecoveryCoordinatorTest` 及 `SettingsCatalogAndAppearanceTest` T06 持久化用例），`0` 失败、`0` 错误、`0` 跳过。
- **构建与 R8**：`assembleDebug`、`assembleRelease` 双风味（R8 开启）全部通过。
- **Lint 校验**：`:app:lintIndependentDebug --offline` 保持 **`19`** 项既有基线错误（**`0` 新增错误**）、`1861` 项警告。
- **签名与原生库检查**：`apksigner verify` 通过（证书 SHA256 `a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc`）；A/B 两版 Release APK 均内置 `lib/arm64-v8a/libvhal_decoder.so`（`8408`B，SHA256 `243a92bc97108c5591df8363c65789a69cb33179d0266f49d6535b3c0bf8e666`）。
- **交付清单（`outputs/`，6 项完整）**：
  - `EVCam-E5-A-Independent-1.6.6-test-11161502-build07-release.apk`（`6554591`B，sha256 `58bb81ee62218a595f3d042b3d99b1f3255b09a6ce44f2e4accd178ae4a8c504`）
  - `EVCam-E5-B-Composite-1.6.6-test-11161502-build07-release.apk`（`6570975`B，sha256 `6c84072b7d13546275478c9063c73658dab82d2815e3ecb146dec9ab1a7a2c7d`）
  - `EVCam-E5-build07-1.6.6-test-11161502-mappings.zip`（`4805546`B，sha256 `3a578d978b7f3617b300e92b63ee34e7af713f359735a5117abcd4feefecfa4b`）
  - `EVCam-E5-build07-1.6.6-test-11161502-SHA256.txt` / `-manifest.json` / `-notes.md`

## 4. 待实车确认项

- 银河 E5 实车在 `10. 信号预设与匹配` 中的 VHAL gRPC 实时 4 类信号读取、挡位/车门/车速/转向灯数值匹配与自定义 Property ID 兼容性。
- R/D 换挡自动弹出记录仪并在 X 秒后自动退后台、交互取消接管及 `confirm_door_exit` 开门倒计时确认弹窗的车机焦点行为。
- 后台录制或开启 `keep_camera_connected` 时摄像头掉线 `5s × 3` 重连与有门槛前台拉起恢复的实车策略表现，以及 `T01`–`T06` 整体联调验收。
