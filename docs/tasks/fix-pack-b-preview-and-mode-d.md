# 包 B：主预览布局 + 模式 D + 360 A 排版精调

最后更新：2026-11-19 02:35:00 +08:00
最后核对：2026-11-19 02:35:00 +08:00
任务/执行者：fix-pack-b-preview-and-mode-d / Antigravity
分支/工作树：codex/e5-recording-startup-fix / E:\GPT\EVCam
版本：`1.6.6-test-11190207`（源码 `111c1cb`，已封包 **`build15`** 并发布 GitHub Pre-release）
状态：已完成代码与单测/Lint/双分辨率模拟器回归（待上车四路+方向盘转角实测）
当前结论：包 B 需求（`1.1`~`1.7`）已全部落地并通过双风味 `448` 项 JVM 单测（0 失败）、双风味 Lint（0 errors）、Debug/Release APK 编译与 `emulator-5556`（2 路模拟相机）`2560×1600` & `1600×900` 全流程视觉/logcat 验证；模拟器 2 路与静态转角限制外的四路实车相机拼接与实车方向盘转角流（`SurroundSteeringAngleSource` APVP `40005` / AOSP `0x11600209`）待上车验收。

## 改动清单

- **死锁与 `360 A → 分栏 B/C/D` 修复（`1.2`）**：
  - `SingleCamera.java`：`startBackgroundThread()` 加锁复用存活线程，`stopBackgroundThread()` 提前清理 `backgroundThread`/`backgroundHandler` 引用并跳过同线程 `join()`，消除退出 `360 A` 时 `closeIfUnused()` 引发的 `2000ms` 自锁阻塞与线程清空竞态。
  - `PreviewModeController.java`：退出 `360 A` 切往常显模式时先展示 `EditablePreviewLayout` 并下发可见掩码，再经 `previewLayout.post(...)` 待 `TextureView` 挂载可用后异步 `deactivateModeA()`，彻底消除黑/白屏闪断；关闭主预览时停在 `modeAContainer` 引导卡而不回退误渲染 `分栏 B`。
- **`360 A` 排版与鸟瞰视野精调（`1.2`）**：
  - `SurroundCalibrationProfile.java` & `SurroundLayoutCalculator.java`：保留 `AppConfig` 原配置数值不变，新增车周 stitches 边距（左右 `125cm` / 后 `170cm` / 前 `185cm`）以完整容纳 E5 车模及 `175cm` 动态引导线；鸟瞰宽度按视场比匹配至 `30%~39%`，右下中央轮控卡收窄为 `104~168px` 让出两侧轮位画幅。
  - `SurroundModeAView.java`：增大顶部 `[前视][后视][随档位]` 与折叠 `展开轮位` 触控高度至 `38dp` 并提升描边对比度；中控卡 4 键改用等权重自适应高度并统一补齐 `btnWheelCollapse`（「收起轮位」）白色文字与深色底片。
- **去除画布重复胶囊 & 统一画内开关语义（`1.1`）**：
  - `activity_main.xml` & `activity_main_e5_multi.xml`：移除悬浮画布 `ui_shortcut_view_switch_group`（重复 `A/B/C/前/后/全屏` 胶囊），仅保留受 `corner_nav_mode` 控制的 `ui_shortcut_corner_nav_group`（「← 返回 / 设置」）。
  - `AppearanceSettingsFragment.java` / `SurroundSettingsFragment.java`（及对应布局）：外观与 360 开关双向同步读写 `view_shortcuts_enabled` 与 `surround_overlay_buttons_visible`，直接控制 `360 A` 内置按钮及 `MODE_C`/`MODE_D` 左（主）画框内底右角「`前视 ⇄ 后视`」按钮显隐。
- **编辑模式零压缩浮层 + 碰撞换位 + 画框填充裁剪（`1.3`, `1.5`）**：
  - `activity_main.xml` & `activity_main_e5_multi.xml`：用外层 `FrameLayout(0dp, weight=1)` 同时容纳 `ui_preview_mode_host(match_parent)` 与悬浮 `ui_preview_editor_host(elevation=10dp)`，编辑态与常态预览画布高度 1:1 完全一致（实测 `[176,266][2530,1576]` 零压缩）。
  - `PreviewLayoutModel.java`、`EditablePreviewLayout.java`、`PreviewEditorController.java`、`ui_preview_editor.xml`：支持拖拽碰撞自动换位让位（`movePaneWithCollision`/`resizePaneWithCollision`）、编辑工具栏「收起/展开」，以及「框体排版 / 画框填充」双页签（支持单指平移、双指及 `+/-` 缩放、`铺满裁剪 / 完整居中` 切换、重置、取消全量回退、保存按 `(modeKey, cameraKey)` 持久化至 `AppConfig`）。
  - `PreviewTransformHelper.java` & `MainActivity.java`：新增 `buildSlotMatrix(...)` 统一处理 `0/90/180/270` 旋转、先物理后输出双镜像、`CENTER_CROP` / `FIT_CENTER` 比例矫正及自定义缩放平移；默认全模式开启 `fillContainer=true` 并监听布局尺寸变化自动重算矩阵，消除模式 C 两侧黑边；预览关态自动隐藏未接入占位提示避免文字重叠。
- **接入「模式 D」与取消 C/D 挡位自动切换（`1.6`, `1.7`）**：
  - 全链路接入 `PreviewMode.MODE_D`（`AppConfig`、`PreviewMode`、`PreviewLayoutModel`、`ui_app_shell.xml`、`AppShellController`、`E5ConfigSchema`/`AndroidE5ConfigTarget`、`AppearanceSettingsFragment`、`SurroundSettingsFragment`）；顶栏将 `ui_edit_layout` 移至模式组左侧，保证切换 `360 A` 时各模式按键横坐标固定。
  - `PreviewModeController.java` & `VehicleAutomationCoordinator.java`：取消 `MODE_C` 及 `MODE_D` R/D 挡位前/后自动切换（`VehicleAutomationCoordinator` 不再为 `MODE_C` 持 VHAL 租约）；`MODE_D` 左框默认前视并支持手动切前/后，右框通过 `SurroundSteeringAngleSource` 前台按需租约配合 `1.2°` 死区迟滞按左打左、右打右动态切换 `LEFT`/`RIGHT` 并基于 `PreviewDemand` 按需启停流。

## 验收结果

- **离线构建与单测**：
  - `.\tools\verify.ps1`：`448` Tests，`0` Failures，`0` Errors，`0` Skipped（新增/更新 `PreviewLayoutModelTest`、`PreviewModeControllerTest`、`SettingsCatalogAndAppearanceTest`）。
  - `./gradlew.bat :app:lintIndependentDebug :app:lintCompositeDebug`：双风味均通过（`0 errors`）。
  - `python tools/check_docs.py`：已通过。
- **模拟器 `emulator-5556`（2 路模拟相机 `0/1`，不改默认 AVD `config.ini`）已验证项**：
  - `2560×1600` & `1600×900` 顶栏无折行裁剪、`360 A` 切换时按钮横坐标无跳动。
  - `360 A` 鸟瞰车模边距与引导线协调、轮位卡 4 键高对比完整显示、「收起/展开轮位」与主预览关态引导卡片正常；`360 A → 分栏 B/C/D` 无黑屏且 logcat 零崩溃、零 `InterruptedException`。
  - 编辑态 `ui_preview_mode_host` 高度 1:1 无压缩、跨窗拖拽碰撞自动换位零重叠、画框填充缩放/裁剪取消可逆、外观/360 开关双向同步控制画内按钮、浅色/深色原地主题刷新、全屏左上仅留返回/设置、回看切换返回正常。
  - 注：回看进后台态后在进程内直接 `adb shell wm size reset` 触发 `Activity` 重建时，包 A `PlaybackImageMemory.clearViews()` 会触发一次 `Glide` 销毁态异常（属包 A `playback/` 范围，本次守红线未改；正常同分辨率或重起实例无此问题），已在 `PITFALLS.md` 登记 `P018`。
- **待上车实测项（明确未在模拟器完成）**：
  - 银河 E5 实车 4 路物理摄像头下的 `MODE_A` 地面拼接、左右轮视角、`MODE_C`/`MODE_D` 四路满帧切换与 `CENTER_CROP` 实景观感。
  - 实车 APVP 端口 `40005` / VHAL 实时方向盘转角信号驱动 `MODE_D` 右侧 `LEFT ↔ RIGHT` 切换与 `1.2°` 死区防抖表现。
  - 测试尾清理完成：`emulator-5556` 已关闭，`work/tmp/fix-pack-b-preview-and-mode-d/` 已删除，`work/avd/EVCamUI.avd/config.ini` 保持零改动。

## 最近更新

- 2026-11-19 02:27:29 +08:00 | 写入用户车模默认图并修复本地导入无响应 | 内置 `drawable-nodpi/e5_car_model_default.png`（504×1024）并修 `SurroundGlRenderer` `GL_ONE` 混合；补 SAF `OpenDocument` 与弹窗反馈解决无存储权限/Toast 抑制下按钮“点不了”问题（`1.6.6-test-11190207`：双风味 `470` 测 0 失败、0 Lint error、`emulator-5556` 360 A/SAF/Download 导入全通过） | [P020](../PITFALLS.md)
- 2026-11-19 01:35:22 +08:00 | 模拟器初测反馈排查（4.1 切 A/D 卡顿 & 车模图来源） | 核实设置 4.1 切 A/D 卡顿为主线程 `closeCamera()`（`P019`）；默认车模纯离线绘制，上车不联网加载 | [P019](../PITFALLS.md)
- 2026-11-18 16:48:00 +08:00 | 完成包 B 实现与模拟器全项验收 | 完成 `1.1`~`1.7`（`MODE_D`、去重胶囊、画内裁剪、碰撞换位、`360 A` 比例及切回不黑屏），`1.6.6-test-11181639` 通过 `448` 单测、双风味 `0` Lint error 与 `emulator-5556` 双分辨率实测 | [fix-pack-b-preview-and-mode-d.md](fix-pack-b-preview-and-mode-d.md)
- 2026-11-18 15:40:00 +08:00 | 补全模式 D 与包 B 规格 | 补全 `1.1`~`1.7` 与模式 D 规格并同步附图 | [fix-pack-b-preview-and-mode-d.md](fix-pack-b-preview-and-mode-d.md)
- 2026-11-18 15:33:46 +08:00 | 基线取证 | 完成 `emulator-5556` 现状截图与 `SingleCamera` 阻塞根因定位 | [fix-pack-b-preview-and-mode-d.md](fix-pack-b-preview-and-mode-d.md)

## 下一步

1. 决定是否将 `1.6.6-test-11190207` 提交并封包更新 `outputs/` 后上车实测（并确认是否优化 `P019`）。
