# 任务记录：T02 全局外壳 UI、SC 风格左栏与深浅色主题

- 最后更新：2026-11-16 08:35:18 +08:00（Asia/Hong_Kong）
- 最后核对：2026-11-16 08:35:18 +08:00（Asia/Hong_Kong）
- 任务/执行者：`t02-shell-navigation-and-theme` / Antigravity
- 分支/工作树：`codex/e5-recording-startup-fix` / `E:\GPT\EVCam`（`build02` `72dc456`；SC 对齐 `9f443b6`；`build05` 修复与封版 `f2f1be6`）
- 状态：已完成基于 `build04` 的 SC UI 对齐、嵌套子页修复并打包发布 **`build05`**（`1.6.6-test-11160830`，离线通过，**未实车**）
- 当前结论：在 `9f443b6` SC 配色/窄左栏/双栏 Master-Detail 设置与双列外观页基础上，`f2f1be6` 修复了双栏设置下飞书状态实时刷新（`SettingsFragment.getActiveDetailFragment()`）及补盲/推流二级子页 `#id/toolbar` 背景边距统一；离线 `248` 测、R8 Release、`apksigner`/`libvhal_decoder.so` 与 `0` 新增 Lint 通过，**未上车实测**。

## 实施摘要（`72dc456` + `9f443b6` + `f2f1be6`）

1. **三态主题（默认跟随系统）+ SC 配色与零重建重涂**：
   - [AndroidManifest.xml](..\..\..\..\app\src\main\AndroidManifest.xml) 配置 `configChanges` 并转发至 [UiThemeController.java](..\..\..\..\app\src\main\java\com\kooo\evcam\ui\UiThemeController.java) 与 [UiThemeRepainter.java](..\..\..\..\app\src\main\java\com\kooo\evcam\ui\UiThemeRepainter.java)；`9f443b6` 对齐 SC 黑曜石 `#0D0D0F`/`#1A1A1A` 与纯白 `#FFFFFF`/`#F5F5F5` 高反差色板及 `ui_switch_*`/胶囊控件。
2. **SC 窄左栏 + 双栏 Master-Detail 设置 + 嵌套状态修复**：
   - [ui_app_shell.xml](..\..\..\..\app\src\main\res\layout\ui_app_shell.xml) 与 [AppShellController.java](..\..\..\..\app\src\main\java\com\kooo\evcam\ui\AppShellController.java) 收敛为顶部「开始/停止录制」+「记录仪」「回看」（[PlaybackHubFragment.java](..\..\..\..\app\src\main\java\com\kooo\evcam\playback\PlaybackHubFragment.java)）+ 左下「设置」/红字「退出」/`V1.6.6`；非录制根页隐藏顶栏。
   - [SettingsCatalog.java](..\..\..\..\app\src\main\java\com\kooo\evcam\ui\SettingsCatalog.java) 与 [SettingsFragment.java](..\..\..\..\app\src\main\java\com\kooo\evcam\SettingsFragment.java) 实现左侧分类+右侧内嵌双栏与二级子页返回；`f2f1be6` 新增 `getActiveDetailFragment()` 供 [MainActivity.java](..\..\..\..\app\src\main\java\com\kooo\evcam\MainActivity.java) `updateFeishuFragmentUI()` 穿透刷新内嵌 `FeishuFragment`，并补齐 [fragment_blind_spot_correction.xml](..\..\..\..\app\src\main\res\layout\fragment_blind_spot_correction.xml)、[fragment_secondary_blind_spot_adjust.xml](..\..\..\..\app\src\main\res\layout\fragment_secondary_blind_spot_adjust.xml)、[fragment_mjpeg_stream_adjust.xml](..\..\..\..\app\src\main\res\layout\fragment_mjpeg_stream_adjust.xml) 的 `@+id/toolbar`。
3. **`外观与交互` SC 双列卡片与开门退出延时**：
   - [AppearanceSettingsFragment.java](..\..\..\..\app\src\main\java\com\kooo\evcam\ui\AppearanceSettingsFragment.java) 与 [AppConfig.java](..\..\..\..\app\src\main\java\com\kooo\evcam\AppConfig.java) 采用两列卡片+胶囊按钮并持久化 `KEY_DOOR_EXIT_DELAY_SECONDS`（默认 5 秒，支持 3/5/10/15/30 秒）。

## 验证与交付状态（仅离线）

- **单测/编译/Lint（`f2f1be6`）**：`tools/verify.ps1` 退出 0（`248` 项单测：Independent `124` + Composite `124`，`0` 失败/错误/跳过；Debug/Release R8 编译通过）；`:app:lintIndependentDebug --offline` 保持 `19` 项既有错误（`0` 新增）、`1745` 警告。
- **`build05` 归档（`1.6.6-test-11160830`，Tag `v1.6.6-test-11160830-build05`）**：
  - `EVCam-E5-A-Independent-1.6.6-test-11160830-build05-release.apk`（`6,479,679`B，SHA256 `27df5bc0...dc886d2f`）
  - `EVCam-E5-B-Composite-1.6.6-test-11160830-build05-release.apk`（`6,496,063`B，SHA256 `cb824a67...c2d38aa850`）
  - `EVCam-E5-build05-1.6.6-test-11160830-mappings.zip`（`5,600,248`B，SHA256 `59bcd3df...03b3c6`）+ [manifest](..\..\..\..\outputs\archive\EVCam-E5-build05-1.6.6-test-11160830-manifest.json)、[notes](..\..\..\..\outputs\archive\EVCam-E5-build05-1.6.6-test-11160830-notes.md)、[SHA256](..\..\..\..\outputs\archive\EVCam-E5-build05-1.6.6-test-11160830-SHA256.txt)；两包 `apksigner verify` 通过，`libvhal_decoder.so`（`8408`B，`243a92bc...`）完整。
- **待实车确认**：E5 `2560×1600` SC 深浅色反差、窄左栏与双栏设置触控、子页内嵌返回、内嵌飞书状态即时刷新、`T03` 顶栏控制与 `T04` 小窗/双 HUD 联动。

## 最近记录（最多5条）

- 2026-11-16 08:35:18 +08:00 | build05存档 | 合并 `9f443b6` SC 对齐与 `f2f1be6`（双栏内嵌飞书状态刷新+3 补盲子页 `#id/toolbar`），通过 `248` 测/R8/0 新增 Lint/签名与 so 校验，打包发布 `build05` Pre-release（未实车） | [总览](..\..\..\STATUS.md)
- 2026-11-16 02:50:25 +08:00 | T02 SC界面对齐(离线) | 基于 `build04` 提交 `9f443b6`（`1.6.6-test-11160243`）：重调 SC 深浅色板、窄左栏（补盲/飞书收归设置）、双栏 Master-Detail 设置与双列外观页；`248` 测+Release+0 新增 Lint 通过 | [总览](..\..\..\STATUS.md)
- 2026-11-15 23:00:38 +08:00 | build02存档 | 基于 `T02`（`72dc456` / `1.6.6-test-11152245`）打包 `build02` 双风味 Release APK、校验签名/SHA256/mappings 并推送 GitHub，转交新会话做 `T03`。| [总览](..\..\..\STATUS.md)
- 2026-11-15 22:50:24 +08:00 | T02完成(离线) | 完成三态不重建主题、SC 4+2 左栏与回看合并、7 项精简设置目录与子页拆分（`72dc456`），188 测+Release+0 新增 Lint 通过。| [总览](..\..\..\STATUS.md)
- 2026-11-15 22:06:00 +08:00 | T02定稿 | 用户拍板默认跟随系统主题、SC 式回看合并与精简设置目录；待新会话编码。| [总蓝图](../EVCAM_SMARTCAM_REFACTOR_PLAN.md)

## 下一步

1. 基于 `build05` 基线开启下一任务包 **`T05`**（视频管线、按需着色/旁路与方案 A 帧率/水印/提示音）或 **`T06`**（车辆规则引擎与 R/D 延时退回）。
2. E5 实车安装 `build05` 并联合验收 `T01`–`T04`、新版 SC 视觉/双栏设置与双 HUD。
