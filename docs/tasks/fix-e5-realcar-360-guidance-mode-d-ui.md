# 修复银河 E5 实车 360 引导线、规则联动、模式 D 与轮位按钮及统一编辑调校

最后更新：2026-11-21 02:59:13 +08:00
最后核对：2026-11-21 02:59:13 +08:00
任务/执行者：fix-e5-realcar-360-guidance-mode-d-ui / Antigravity
分支/工作树：codex/e5-recording-startup-fix / E:\GPT\EVCam
状态：等待实测（`build21` 已发布，待 E5 实车验证）
当前结论：已完成「方案 B + 原厂 `avm_calib_res` 导入」（`1.6.6-test-11210240`，`3869f7f`）封包 `build21` 与 fork Pre-release [`v1.6.6-test-11210240-build21`](https://github.com/johnwyatt041-commits/EVCam/releases/tag/v1.6.6-test-11210240-build21)，双风味 `534` 测、构建、Lint `0` errors 与 6 资产 SHA256 一致；尚未上实车验证。

## 根因与改动清单

1. **方向盘转角信号与引导线开关/规则联动**：
   - `SurroundSteeringAngleSource`：目录端口迁至 `APVP_DEBUG_PORT = 40007`，解析嵌套 `TransferInfo` 扫全 3 路信号，支持 ID-only 帧与连接初读 `readSignal`，取消 3.5 秒 `Qf`/`Timeout` 静默淘汰。
   - `RuleAction` / `VehicleRuleEngine` / `VehicleAutomationCoordinator` / `MainActivity`：新增 `ActionGroup` 分离 `RECORDING`/`GUIDANCE` 与 `ENABLE_GUIDANCE`/`DISABLE_GUIDANCE` 独立仲裁；设置中心「外观与交互」及 360 设置同步首页开关并直达规则引擎。
2. **图 2 风格金黄轨迹带与鸟瞰几何修复**：
   - `SurroundGuidanceCalculator` / `SurroundGlRenderer`：修复 `angleFromPivot` 符号反折，世界坐标展宽并叠加倒车前外摆扫掠弧；道路视图去中间横线改为 4 条纯两侧金黄粗/细轨带，死区 `0.25f`，手选「后视」同步鸟瞰向后弧线。
3. **模式 D 左右视切换与转向灯联动**：
   - `AppConfig` / `EditablePreviewLayout` / `PreviewModeController` / `SurroundSettingsFragment`：右屏新增「随转向灯/随方向盘/手动切换」与「左侧 ⇄ 右侧」胶囊条，`TURN_SIGNAL` 订阅 `VehicleSignalHub` 与按需租约并防双闪抖动。
4. **360 轮位卡片美化与关按键消隙**：
   - `SurroundLayoutCalculator` / `SurroundModeAView`：轮位卡宽上限 `208`、自适应字号、海湾蓝+深灰蓝配色与分隔线；关闭画内按键时 `SurroundLayoutCalculator`/`SurroundGlRenderer` 同步收拢中央卡位消除黑洞空隙。
5. **顶栏 360「编辑布局」与全四分栏统一按键底色透明度**：
   - `AppShellController` / `ui_preview_editor.xml` / `PreviewEditorController` / `MainActivity`：顶栏对 `360 A` 开放「编辑布局」，模式 A 提供宽度/透明度双滑条，B/C/D 提供「按键透明度」页复用同一全局 `preview_overlay_bg_alpha_percent` 与取消回滚。
6. **4.3 四路外参微调、分槽保存与 JSON 导出**：
   - [SurroundExtrinsicAdjustment.java](../../app/src/main/java/com/kooo/evcam/surround/SurroundExtrinsicAdjustment.java)、[SurroundCalibrationProfile.java](../../app/src/main/java/com/kooo/evcam/surround/SurroundCalibrationProfile.java)、[SurroundCalibrationManager.java](../../app/src/main/java/com/kooo/evcam/surround/SurroundCalibrationManager.java)、[AppConfig.java](../../app/src/main/java/com/kooo/evcam/AppConfig.java)：内置（星舰7）与本地导入两槽独立存读，切换不覆盖；仅导入新文件时经 [SurroundAssetImportHelper.java](../../app/src/main/java/com/kooo/evcam/ui/SurroundAssetImportHelper.java) 重置本地槽。支持导出 `vehicle_centered: true` 的标准 1.3 `calibration.json` 往返无漂移。
   - [SurroundGlRenderer.java](../../app/src/main/java/com/kooo/evcam/surround/SurroundGlRenderer.java)、[fragment_surround_calibration_live.xml](../../app/src/main/res/layout/fragment_surround_calibration_live.xml)、[SurroundCalibrationLiveFragment.java](../../app/src/main/java/com/kooo/evcam/ui/SurroundCalibrationLiveFragment.java)：未微调保持零回退静态 `bird.mesh`，非零微调原位重投影鸟瞰（保留 `front/rear_wheels.mesh`），`4.3` 页提供前/后/左/右切换、常用与折叠高级参数及 SAF/本地降级导出。
7. **版本与单测**：`app/build.gradle.kts` 升至 `1.6.6-test-11210240`；补齐并更新 [SurroundCalibrationAndGuidanceTest.java](../../app/src/test/java/com/kooo/evcam/surround/SurroundCalibrationAndGuidanceTest.java)。
8. **方案 B（E5 车框/零跳变/焦距缩放+辅助开关）与原厂 `avm_calib_res` 导入**：
   - 修正 `E5_CAR_*` 物理轮廓为 `190×462 cm` 及车模贴图框 `226×464 cm`（[SurroundCalibrationProfile.java](../../app/src/main/java/com/kooo/evcam/surround/SurroundCalibrationProfile.java)）；[SurroundMeshBuilder.java](../../app/src/main/java/com/kooo/evcam/surround/SurroundMeshBuilder.java) 新增 `reprojectBirdMesh` 沿用工厂 `bird.mesh` 接缝拓扑原位重投影消跳变、`SurroundExtrinsicAdjustment` 新增焦距 `scale`（`70%–130%`）与 `Δcx/Δcy/Δk1`、`SurroundGuidanceCalculator` 与 `4.3` 界面新增 `250 cm`「参考网格/隐藏车模/单路独显」。
   - 新增 [SurroundFactoryCalibrationParser.java](../../app/src/main/java/com/kooo/evcam/surround/SurroundFactoryCalibrationParser.java)，支持 `Table.db`（392B）+ 704B 1.3 `.param` + 可选轮位 `.db` / `config.ini`（`bottom_det`）校验转换，并与 [SurroundCalibrationManager.java](../../app/src/main/java/com/kooo/evcam/surround/SurroundCalibrationManager.java) / [SurroundAssetImportHelper.java](../../app/src/main/java/com/kooo/evcam/ui/SurroundAssetImportHelper.java) 打通 `Download` 扫描、`.zip` 与 SAF `OpenDocumentTree` 原厂目录直导。

## 最近记录

- 2026-11-21 02:59:13 +08:00 | 提交 `3869f7f`、封包 `build21` 并发布 GitHub Pre-release | 推 `codex/e5-recording-startup-fix` 与 `v1.6.6-test-11210240-build21`，A/B（各 `7,922,152` B，`f54562fb...`/`533104f9...`）与 `mappings.zip`（`13,234,412` B）等 6 资产远端 SHA256 一致、`build19` 归档，离线 `534` 测/Lint `0` errors，待 E5 实车测试 | [SurroundFactoryCalibrationParser.java](../../app/src/main/java/com/kooo/evcam/surround/SurroundFactoryCalibrationParser.java)
- 2026-11-21 01:49:06 +08:00 | 记录 `build20` 实车 360 错位现象并核对原厂标定/车模影响（纯文档，未改源码） | 用户确认实车主要为车头/车尾与两侧对不上、比例怪异、缝隙明显（车模也为星舰7）；核实车模 PNG 不参与投影但代码 `E5_CAR_*` 达 `213×534 cm` 导致视觉比例与盲区/接缝失真，获取 E5 原厂标定（`avm_calib_res`）可治本且当前仅支持 JSON/ZIP 导入 | [SurroundCalibrationProfile.java](../../app/src/main/java/com/kooo/evcam/surround/SurroundCalibrationProfile.java#L19-L23)
- 2026-11-20 00:43:30 +08:00 | 封包 `build20` 并发布 GitHub Pre-release | 提交 `4836da2`、标签 `v1.6.6-test-11192353-build20` 推送至 `johnwyatt041-commits/EVCam`，完成 `outputs/` 封包（A `7,887,580` B、`c0af422a...`；B `7,903,964` B、`07d96119...`；`mappings.zip` `13,169,763` B、`b1be5a6f...`）及 `build18` 归档，GitHub 6 资产大小与 SHA256 回读一致（待实车复测）
- 2026-11-20 00:01:30 +08:00 | 新增 4.3 四路外参微调、分槽保存与 JSON 导出并完成全量离线验证（`1.6.6-test-11192353`） | `verify.ps1` 双风味 `530` 测 `0` 失败 `0` 跳过、`assembleDebug`/`assembleRelease` 成功（`build-20261119-235447-318.log`）；双风味 Lint `0` errors（`2162` warnings）；`check_docs.py` 通过 | [SurroundExtrinsicAdjustment.java](../../app/src/main/java/com/kooo/evcam/surround/SurroundExtrinsicAdjustment.java)
- 2026-11-19 23:04:30 +08:00 | 新增 360 引导线宽度调节、全四分栏「编辑布局」统一按键底色透明度与消隙（`1.6.6-test-11192257`） | `verify.ps1` `524` 测 `0` 失败、构建成功、Lint `0` errors | [PreviewEditorController.java](../../app/src/main/java/com/kooo/evcam/ui/PreviewEditorController.java)

## 下一步

1. 安装 `build21`（`1.6.6-test-11210240`）上银河 E5 实车使用 4.3「参考网格/隐藏车模/单路独显/焦距缩放 Scale」调校验证，或直接导入提取到的 E5 原厂 `avm_calib_res` 标定目录。
