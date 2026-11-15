# 任务记录：T02 全局外壳 UI、SC 风格左栏与深浅色主题

- 最后更新：2026-11-15 23:00:38 +08:00（Asia/Hong_Kong）
- 最后核对：2026-11-15 23:00:38 +08:00（Asia/Hong_Kong）
- 任务/执行者：`t02-shell-navigation-and-theme` / Antigravity
- 分支/工作树：`codex/e5-recording-startup-fix` / `E:\GPT\EVCam`（源码 `72dc456`，Tag `v1.6.6-test-11152245-build02`）
- 状态：已完成 `build02` 打包并准备 GitHub 发布（**尚未实车测试**）
- 当前结论：已封存 `T02` 成果为记录点 `build02`（`1.6.6-test-11152245`，双风味 Release APK 各 `6,431,411` 字节），离线单测 188 项、R8、0 新增 Lint、签名与 `libvhal_decoder.so` 均校验通过；`T03` 留待下一新会话启动。

## 实施摘要（源码提交 `72dc456`）

1. **三态主题（默认跟随系统）+ 零重建原地切换**：
   - [AndroidManifest.xml](../../app/src/main/AndroidManifest.xml) 为 `.MainActivity` 配置 `android:configChanges="uiMode|orientation|screenSize|screenLayout|smallestScreenSize"`，并在 [MainActivity.java](../../app/src/main/java/com/kooo/evcam/MainActivity.java) 转发至 [UiThemeController.java](../../app/src/main/java/com/kooo/evcam/ui/UiThemeController.java) 与 [UiThemeRepainter.java](../../app/src/main/java/com/kooo/evcam/ui/UiThemeRepainter.java)。
   - 支持 `FOLLOW_SYSTEM`（全新默认）/ `DARK` / `LIGHT` 并兼容旧 `ui_preferences.light_theme`；原地重涂窗口、系统栏及 `ThemeAware` 页面，不重建 `Activity`。
2. **SC 风格左栏（4+2）与外壳清理**：
   - [ui_app_shell.xml](../../app/src/main/res/layout/ui_app_shell.xml)、[AppShellController.java](../../app/src/main/java/com/kooo/evcam/ui/AppShellController.java) 及 `11` 主布局 / `4` 自定义按键布局移除 `DrawerLayout`/`NavigationView`/`btn_exit`/顶栏主题按钮，删废弃资源；左栏保留 `记录仪`/`回看`（[PlaybackHubFragment.java](../../app/src/main/java/com/kooo/evcam/playback/PlaybackHubFragment.java) 顶部切 `视频/照片`）/`补盲`/`飞书`，左下角固定 `设置`/`退出`；首启不再强跳设置。
3. **精简设置目录与拆页**：
   - [SettingsCatalog.java](../../app/src/main/java/com/kooo/evcam/ui/SettingsCatalog.java) 与 [SettingsFragment.java](../../app/src/main/java/com/kooo/evcam/SettingsFragment.java) 仅展示 7 个已实装项（`1.`/`3.`/`5.`/`11.`/`13.`/`14.`/`15.`）；新建 [AppearanceSettingsFragment.java](../../app/src/main/java/com/kooo/evcam/ui/AppearanceSettingsFragment.java)（含 `confirm_door_exit` 预留），拆出 [RecorderSettingsFragment.java](../../app/src/main/java/com/kooo/evcam/RecorderSettingsFragment.java)、[UpdateSettingsFragment.java](../../app/src/main/java/com/kooo/evcam/UpdateSettingsFragment.java)、[DebugLogSettingsFragment.java](../../app/src/main/java/com/kooo/evcam/DebugLogSettingsFragment.java)。

## 验证与 `build02` 交付（仅离线）

- **离线验证**：`tools/verify.ps1` 退出 0（`188` 项单测：Independent `94` + Composite `94`，0 失败/跳过）；`:app:lintIndependentDebug` 为 `19` 项既有错误（0 新增）；`apksigner verify` 签名 `a40da80a...bf5dc` 与 `libvhal_decoder.so` 校验通过。
- **`build02` 产物**：`outputs/EVCam-E5-{A-Independent,B-Composite}-1.6.6-test-11152245-build02-release.apk`、`-manifest.json`、`-mappings.zip`、`-notes.md`、`-SHA256.txt`。
- **待实车确认**：E5 `2560×1600` 日夜切换原地重涂、录像中切主题、左栏 `4+2`/合并回看/设置触控、默认启动页、自定义多按键布局及双 HUD 共生（联合 `T01` 一并实测）。

## 最近记录（最多5条）

- 2026-11-15 23:00:38 +08:00 | build02存档 | 基于 `T02`（`72dc456` / `1.6.6-test-11152245`）打包 `build02` 双风味 Release APK、校验签名/SHA256/mappings 并推送 GitHub，转交新会话做 `T03`。| [总览](../STATUS.md)
- 2026-11-15 22:50:24 +08:00 | T02完成(离线) | 完成三态不重建主题、SC 4+2 左栏与回看合并、7 项精简设置目录与子页拆分（`72dc456`），188 测+Release+0 新增 Lint 通过。| [总览](../STATUS.md)
- 2026-11-15 22:06:00 +08:00 | T02定稿 | 用户拍板默认跟随系统主题、SC 式回看合并与精简设置目录；待新会话编码。| [总蓝图](../EVCAM_SMARTCAM_REFACTOR_PLAN.md)

## 下一步

1. 在新会话启动授权任务包 **`T03`（多模式主预览框架与动态关流：模式 B+C）**。
2. 实车时使用 `build02` 安装包联合验收 `T01` + `T02` 待实车项。
