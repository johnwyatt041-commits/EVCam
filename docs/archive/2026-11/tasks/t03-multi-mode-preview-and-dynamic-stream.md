# 任务记录：T03 多模式主预览框架与动态关流（模式 B+C）

- 最后更新：2026-11-16 01:18:33 +08:00（Asia/Hong_Kong）
- 最后核对：2026-11-16 01:18:33 +08:00（Asia/Hong_Kong）
- 任务/执行者：`t03-multi-mode-preview-and-dynamic-stream` / Antigravity
- 分支/工作树：`codex/e5-recording-startup-fix` / `E:\GPT\EVCam`（源码 `4706b6f`，Tag `v1.6.6-test-11160002-build03`）
- 状态：已完成 `build03` 打包与 GitHub 发布（**尚未实车测试**）
- 当前结论：已将 `T03` 封存为记录点 `build03`（`1.6.6-test-11160002`：A `6,451,763` 字节 / B `6,468,147` 字节），完成模式 B/C、16dp 圆角分割、全屏角落控制、真·Surface 解绑停流与 `T07` 容器预留，签名与 `libvhal_decoder.so` 校验通过；准备新会话启动 `T04`。

## 实施摘要（源码提交 `4706b6f`）

1. **多模式布局模型与统一圆角分割**：
   - 升级 [PreviewLayoutModel.java](..\..\..\..\app\src\main\java\com\kooo\evcam\ui\PreviewLayoutModel.java) 与 [EditablePreviewLayout.java](..\..\..\..\app\src\main\java\com\kooo\evcam\ui\EditablePreviewLayout.java)：模式 B 采用首列 `front`/`back` 上下（各 `40%×50%`）+ 2/3 列 `left`/`right`（各 `30%×100%`），隐藏任意格时按比例智能重排并保底至少保留 1 路；模式 C 采用左侧主路 `64%` + 右侧 `left`/`right` 各 `18%`；统一 `16dp` 圆角 `ViewOutlineProvider` 与 `12dp` 间距，独立持久化 `b_*`/`c_*`/`b_vis_*`/`c_vis_*` 并兼容旧 T02 布局迁移。
   - 扩展 [ui_preview_editor.xml](..\..\..\..\app\src\main\res\layout\ui_preview_editor.xml) 与 [PreviewEditorController.java](..\..\..\..\app\src\main\java\com\kooo\evcam\ui\PreviewEditorController.java)：新增 `前/后/左/右` 显隐芯片与按模式预设弹窗；摄像头角标迁至 `top|end` 避免与左上角控制条重叠。
2. **模式控制器、挡位联动、全屏外壳与设置接入**：
   - 新增 [PreviewMode.java](..\..\..\..\app\src\main\java\com\kooo\evcam\ui\PreviewMode.java)、[PreviewModeHost.java](..\..\..\..\app\src\main\java\com\kooo\evcam\ui\PreviewModeHost.java)、[PreviewModeController.java](..\..\..\..\app\src\main\java\com\kooo\evcam\ui\PreviewModeController.java)、[preview_shortcut_pill.xml](..\..\..\..\app\src\main\res\drawable\preview_shortcut_pill.xml)，并在 [activity_main.xml](..\..\..\..\app\src\main\res\layout\activity_main.xml)、[activity_main_e5_multi.xml](..\..\..\..\app\src\main\res\layout\activity_main_e5_multi.xml) 中以 `@id/ui_preview_mode_host` 包裹画布，预留 `ui_mode_a_surround_container`（`GONE`）与 `ui_photo_staging_container`。
   - 模式 C 接入 `VehicleSignalHub` `200ms` 挡位防抖（`REVERSE` 切后，其余切前，支持当次挡位手动覆盖 `前/后`）；[AppShellController.java](..\..\..\..\app\src\main\java\com\kooo\evcam\ui\AppShellController.java) 与 [ui_app_shell.xml](..\..\..\..\app\src\main\res\layout\ui_app_shell.xml) 增加 `B`/`C`/全屏按钮与返回键退出全屏；[AppearanceSettingsFragment.java](..\..\..\..\app\src\main\java\com\kooo\evcam\ui\AppearanceSettingsFragment.java) 与 [AppConfig.java](..\..\..\..\app\src\main\java\com\kooo\evcam\AppConfig.java) 接入默认模式、全屏角落导航策略（`auto`/`always`/`hidden`）与视图快捷键显隐配置；[UiThemeController.java](..\..\..\..\app\src\main\java\com\kooo\evcam\ui\UiThemeController.java) 在原位切主题时同步刷新上述控件。
3. **动态 Surface 解绑、隐藏停流与离屏拍照**：
   - 改造 [SingleCamera.java](..\..\..\..\app\src\main\java\com\kooo\evcam\camera\SingleCamera.java) 与 [MultiCameraManager.java](..\..\..\..\app\src\main\java\com\kooo\evcam\camera\MultiCameraManager.java)：区分 `isMainPreviewEnabled()` 与次级流（含 `streamSurface` 回退），`switchToPreviewOnlyMode()` 通过 `collectActiveNonRecordSurfaces()` 保留仍需输出的预览目标；主预览隐藏且无录制/副流/离线拍照租约时解绑输出 Surface 并 `closeIfUnused()` 关闭 Camera2 会话；[MainActivity.java](..\..\..\..\app\src\main\java\com\kooo\evcam\MainActivity.java) 改为单键动态初始化等待并按新尺寸重算预览矩阵。

## 验证与 `build03` 交付（仅离线）

- **离线验证**：`tools/verify.ps1` 退出 0（`work/verification/build-20261116-000639-272.log`；`214` 项 JVM 单测：Independent `107` + Composite `107`，0 失败/错误/跳过；Debug + Release R8 编包通过）；`:app:lintIndependentDebug` 为 `19` 项既有错误（`0` 新增，1714 警告）；`apksigner verify` 签名 `a40da80a...bf5dc` 与 `libvhal_decoder.so`（`243a92bc...e666`）校验通过。
- **`build03` 产物**：`outputs/EVCam-E5-{A-Independent,B-Composite}-1.6.6-test-11160002-build03-release.apk`、`-manifest.json`、`-mappings.zip`、`-notes.md`、`-SHA256.txt`。
- **待实车确认**：E5 车机真实 Camera2 隐藏停流/重开稳定性、模式 C `200ms` 挡位防抖与倒车响应延迟、全屏与左上角胶囊触控、隐藏摄像头时离线拍照暂存及双 HUD/11 布局兼容性。

## 最近记录（最多5条）

- 2026-11-16 01:18:33 +08:00 | build03存档 | 基于 `T03`（`4706b6f` / `1.6.6-test-11160002`）打包 `build03` 双风味 Release APK、校验签名/SHA256/mappings 并推送 GitHub，转交新会话做 `T04`。| [总览](..\..\..\STATUS.md)
- 2026-11-16 00:07:46 +08:00 | T03完成(离线) | 完成模式 B/C、16dp 圆角分割、全屏角落快捷键与真·Surface 解绑停流（`4706b6f`），214 测 + Debug/Release + 0 新增 Lint 通过 | [总览](..\..\..\STATUS.md)

## 下一步

1. 在新会话启动授权任务包 **`T04`（悬浮小窗架构与补盲择优融合（含 HUD））**。
2. 实车时使用 `build03` 安装包联合验收 `T01`–`T03` 待实车项。
