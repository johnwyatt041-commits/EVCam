# 任务记录：T02 全局外壳 UI、SC 风格左栏与深浅色主题

- 最后更新：2026-11-15 22:50:24 +08:00（Asia/Hong_Kong）
- 最后核对：2026-11-15 22:50:24 +08:00（Asia/Hong_Kong）
- 任务/执行者：`t02-shell-navigation-and-theme` / Antigravity
- 分支/工作树：`codex/e5-recording-startup-fix` / `E:\GPT\EVCam`（代码 `72dc456`，`versionName = 1.6.6-test-11152245`）
- 状态：已完成编码与离线验证（**待实车确认**）
- 当前结论：已按定稿完成 `T02` 代码重构与单元测试，离线 `tools/verify.ps1`、双风味 Debug/Release 编译与 `:app:lintIndependentDebug` 均通过（0 新增 Lint）；`T03`–`T10` 未越权修改。

## 实施摘要（源码提交 `72dc456`）

1. **三态主题（默认跟随系统）+ 零重建原地切换**：
   - [AndroidManifest.xml](../../app/src/main/AndroidManifest.xml) 为 `.MainActivity` 配置 `android:configChanges="uiMode|orientation|screenSize|screenLayout|smallestScreenSize"`，并在 [MainActivity.java](../../app/src/main/java/com/kooo/evcam/MainActivity.java) 通过 `onConfigurationChanged` 转发至 [UiThemeController.java](../../app/src/main/java/com/kooo/evcam/ui/UiThemeController.java) 与 [UiThemeRepainter.java](../../app/src/main/java/com/kooo/evcam/ui/UiThemeRepainter.java)。
   - 支持 `FOLLOW_SYSTEM`（全新安装默认）/ `DARK` / `LIGHT` 且向下兼容旧 `ui_preferences.light_theme`；切主题原地重涂视图树、系统栏及 `ThemeAware` 页面，不调用 `recreate()`、录制中不断录。
2. **SC 风格左栏（4+2）与外壳清理**：
   - [ui_app_shell.xml](../../app/src/main/res/layout/ui_app_shell.xml)、[AppShellController.java](../../app/src/main/java/com/kooo/evcam/ui/AppShellController.java) 及 `11` 个主布局 / `4` 个自定义按键布局移除 `DrawerLayout`、`NavigationView`、`btn_exit`、顶栏主题按钮，删除 `navigation_menu.xml`、`nav_header.xml`、`preview_ids.xml`。
   - 左栏保留 **`记录仪`**（默认）/**`回看`**（合并入口 [PlaybackHubFragment.java](../../app/src/main/java/com/kooo/evcam/playback/PlaybackHubFragment.java)，顶部切 `视频 / 照片` 两个既有列表）/**`补盲`**/**`飞书`**，左下角固定 **`设置`** 与 **`退出`**；`checkFirstLaunch()` 不再强跳设置页。
3. **精简设置目录与拆页**：
   - [SettingsCatalog.java](../../app/src/main/java/com/kooo/evcam/ui/SettingsCatalog.java) 与 [SettingsFragment.java](../../app/src/main/java/com/kooo/evcam/SettingsFragment.java) 剔除 `屏幕尺寸`、`4.`/`7.`/`8.`/`9.` 及未实装项，仅列 `7` 个可进项（`1.外观与交互`、`3.记录仪`、`5.补盲`、`11.飞书远程`、`13.系统与权限`、`14.软件更新`、`15.Debug日志`）。
   - 新建 [AppearanceSettingsFragment.java](../../app/src/main/java/com/kooo/evcam/ui/AppearanceSettingsFragment.java)（主题三态、默认启动页、`confirm_door_exit` 持久化预留待 `T06` 接入信号），并拆出 [RecorderSettingsFragment.java](../../app/src/main/java/com/kooo/evcam/RecorderSettingsFragment.java)、[UpdateSettingsFragment.java](../../app/src/main/java/com/kooo/evcam/UpdateSettingsFragment.java)、[DebugLogSettingsFragment.java](../../app/src/main/java/com/kooo/evcam/DebugLogSettingsFragment.java)。

## 验证结果与待实车确认项

- **离线已通过**（全部在 `72dc456` / `1.6.6-test-11152245` 上实跑）：`tools/verify.ps1` 退出 0（`188` 项单测：Independent `94` + Composite `94`，含 [UiThemeControllerTest.java](../../app/src/test/java/com/kooo/evcam/ui/UiThemeControllerTest.java) `6` 项与 [SettingsCatalogAndAppearanceTest.java](../../app/src/test/java/com/kooo/evcam/ui/SettingsCatalogAndAppearanceTest.java) `4` 项，0 失败 / 0 跳过；双风味 Debug 与 Release R8 编译通过）；`:app:lintIndependentDebug` 为 `19` 项既有错误（**0 新增**，1672 warnings）。
- **待实车确认**（未上车实测）：E5 `2560×1600` 车机随系统日夜切换原地重涂效果；录制中切换深浅色是否零掉帧不断流；默认启动页、合并回看 `视频/照片` 切换、左下角设置/退出热区触控、两套补盲/自定义多按键布局（1cam/2cam/4cam/e5_multi 等）与双 HUD 正常共存。

## 最近记录（最多5条）

- 2026-11-15 22:50:24 +08:00 | T02完成(离线) | 完成三态不重建主题、SC 4+2 左栏与回看合并、7 项精简设置目录与子页拆分（`72dc456` / `1.6.6-test-11152245`），`tools/verify.ps1`（188 测）+ Release + 0 新增 Lint 通过，待实车。| [总览](../STATUS.md)
- 2026-11-15 22:06:00 +08:00 | T02定稿 | 用户拍板：全新安装默认跟随系统主题、左栏按 SC 合并「回看（视频/照片切换）」、设置目录仅展示已实装分类；待新会话编码。| [总蓝图](../EVCAM_SMARTCAM_REFACTOR_PLAN.md)
- 2026-11-15 21:43:00 +08:00 | 源码核查 | 完成 SC `Lfk3;`/`Lsi;`/`Lo34;`/`Lqs3;`/`manifest_xmltree.txt` 与 EC 外壳/主题/设置源码对照。| [总览](../STATUS.md)

## 下一步

1. 在总览挑选下一授权任务包（如 `T03` 多模式主预览/动态关流，或 `T04` 悬浮窗与补盲融合）。
2. 待上车实测时按上面「待实车确认」清单联合验证 `T01` + `T02`。
