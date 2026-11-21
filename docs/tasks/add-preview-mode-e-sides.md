# 新增主预览视图「左右 E」（左右双摄并排）

最后更新：2026-11-21 13:07:21 +08:00
最后核对：2026-11-22 07:34:13 +08:00
任务/执行者：add-preview-mode-e-sides / Antigravity
分支/工作树：codex/e5-recording-startup-fix / E:\GPT\EVCam
状态：已提交推送源码（随 `215753f` 推送 `johnwyatt041-commits/EVCam`，尚未单独封包与实车测试）
当前结论：已完成主预览视图 `MODE_E`（「左右 E」：左框左摄 + 右框右摄，默认 50%/50% 并排、按需释放前/后摄主预览流）全链路接入及单测/构建/Lint 验证（`1.6.6-test-11211258`），待银河 E5 实车验证四路切流与布局观感。

## 已完成实现与验证边界

1. **核心模型与模式枚举**：
   - [AppConfig.java](../../app/src/main/java/com/kooo/evcam/AppConfig.java)、[PreviewMode.java](../../app/src/main/java/com/kooo/evcam/ui/PreviewMode.java)：新增 `PREVIEW_MODE_E = "mode_e"` 与 `PreviewMode.MODE_E("mode_e", "左右 E")`，支持模式级独立 `fill/zoom/pan` 槽位裁剪存储。
   - [PreviewLayoutModel.java](../../app/src/main/java/com/kooo/evcam/ui/PreviewLayoutModel.java)、[EditablePreviewLayout.java](../../app/src/main/java/com/kooo/evcam/ui/EditablePreviewLayout.java)、[PreviewModeController.java](../../app/src/main/java/com/kooo/evcam/ui/PreviewModeController.java)：新增 `PRESET_MODE_E_SIDES` 与 `computeEffectiveModeE()`（默认左 `0..0.5` / 右 `0.5..1`，强制隐藏 `front`/`back` 并碰撞防重叠，保存时同步隐藏配对槽矩形，编辑保存/恢复独立至 `e_saved` / `e_pane_` / `e_order_`）。
2. **顶栏、编辑器、设置页与配置广场**：
   - [ui_app_shell.xml](../../app/src/main/res/layout/ui_app_shell.xml)、[AppShellController.java](../../app/src/main/java/com/kooo/evcam/ui/AppShellController.java)、[PreviewEditorController.java](../../app/src/main/java/com/kooo/evcam/ui/PreviewEditorController.java)：顶栏新增 `ui_mode_e`（「左右 E」）并支持 UI 缩放与主题高亮；编辑器在 `MODE_E` 隐藏前/后开关仅保留左/右及恢复左右等分预设。
   - [fragment_appearance_settings.xml](../../app/src/main/res/layout/fragment_appearance_settings.xml)、[AppearanceSettingsFragment.java](../../app/src/main/java/com/kooo/evcam/ui/AppearanceSettingsFragment.java)、[fragment_surround_settings.xml](../../app/src/main/res/layout/fragment_surround_settings.xml)、[SurroundSettingsFragment.java](../../app/src/main/java/com/kooo/evcam/ui/SurroundSettingsFragment.java)：外观默认模式与 360 画面 4.1 模式均接入 `MODE_E`（`左右 E · 双侧` / `左右双摄 (模式 E)`）。
   - [E5ConfigSchema.java](../../app/src/main/java/com/kooo/evcam/configplaza/E5ConfigSchema.java)、[AndroidE5ConfigTarget.java](../../app/src/main/java/com/kooo/evcam/configplaza/AndroidE5ConfigTarget.java)：白名单 `preview_mode` 允许 `mode_e` 并新增 `mode_e_panes` 校验与读写映射。
3. **验证结果（2026-11-21）**：
   - **已通过（离线）**：新增/扩充 [PreviewLayoutModelTest.java](../../app/src/test/java/com/kooo/evcam/ui/PreviewLayoutModelTest.java)、[PreviewModeControllerTest.java](../../app/src/test/java/com/kooo/evcam/ui/PreviewModeControllerTest.java)、[SettingsCatalogAndAppearanceTest.java](../../app/src/test/java/com/kooo/evcam/ui/SettingsCatalogAndAppearanceTest.java)、[E5ConfigPlazaTest.java](../../app/src/test/java/com/kooo/evcam/configplaza/E5ConfigPlazaTest.java)；`verify.ps1` 双风味 `556` 测 `0` 失败/`0` 跳过（`build-20261121-130002-319.log`），Debug/Release APK 编译通过，双风味 Lint `0` errors（`2216` warnings）。
   - **未执行**：尚未在银河 E5 实车四路硬件上实测「左右 E」切换、编辑拖动/缩放保存与前/后流释放表现。

## 最近记录

- 2026-11-21 13:07:21 +08:00 | 完成视图「左右 E」全链路接入与离线验证（`1.6.6-test-11211258`） | 新增 `MODE_E`（左摄在左+右摄在右、前/后隐藏释放）、顶栏/编辑器/设置页/配置广场支持与单测；`verify.ps1` 双风味 `556` 测 0 失败、Debug/Release 编译与双风味 Lint `0` errors 通过，待实车测试 | [PreviewLayoutModel.java](../../app/src/main/java/com/kooo/evcam/ui/PreviewLayoutModel.java)

## 下一步

1. 在银河 E5 实车验证主界面切至「左右 E」时左右画面分布、布局拖拽/填充裁剪及切回 A/B/C/D 的相机流切换。
