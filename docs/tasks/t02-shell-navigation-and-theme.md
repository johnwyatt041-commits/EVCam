# 任务记录：T02 全局外壳 UI、SC 风格左栏与深浅色主题

- 最后更新：2026-11-16 02:50:25 +08:00（Asia/Hong_Kong）
- 最后核对：2026-11-16 02:50:25 +08:00（Asia/Hong_Kong）
- 任务/执行者：`t02-shell-navigation-and-theme` / Antigravity
- 分支/工作树：`codex/e5-recording-startup-fix` / `E:\GPT\EVCam`（`build02`基线 `72dc456`，SC UI 对齐优化 `9f443b6`）
- 状态：已完成基于 `build04` 的 SC 视觉/双栏设置优化提交 `9f443b6`（`1.6.6-test-11160243`，**尚未实车/未重打包 `build05`**）
- 当前结论：根据 `build04` 实机对比图完成 SC V3.0.56 中性黑曜石/纯白高反差色板、Switch 控件、窄左栏（顶部录制开关+记录仪/回看+左下设置/红字退出/版本）、双栏 Master-Detail 设置（去编号标题、`外观与交互` 双列卡片与胶囊选项及开门延时 3s–30s 持久化）及二级子页内嵌返回；离线 `248` 测、`Debug`/`Release` R8 与 `0` 新增 Lint 通过，**待打包/实车确认**。

## 实施摘要（`72dc456` 基线 + `9f443b6` SC 对齐）

1. **三态主题（默认跟随系统）+ SC 配色与零重建重涂**：
   - [AndroidManifest.xml](../../app/src/main/AndroidManifest.xml) 为 `.MainActivity` 配置 `configChanges` 并转发至 [UiThemeController.java](../../app/src/main/java/com/kooo/evcam/ui/UiThemeController.java) 与 [UiThemeRepainter.java](../../app/src/main/java/com/kooo/evcam/ui/UiThemeRepainter.java)；`9f443b6` 将深浅色替换为主调中性黑 `#0D0D0F`/`#1A1A1A` 与纯白 `#FFFFFF`/`#F5F5F5`，新增 `ui_switch_thumb`/`ui_switch_track` 与胶囊选项高亮。
2. **SC 窄左栏 + 双栏 Master-Detail 设置**：
   - [ui_app_shell.xml](../../app/src/main/res/layout/ui_app_shell.xml) 与 [AppShellController.java](../../app/src/main/java/com/kooo/evcam/ui/AppShellController.java) 改为顶部「开始/停止录制」+ 主导航「记录仪」「回看」（[PlaybackHubFragment.java](../../app/src/main/java/com/kooo/evcam/playback/PlaybackHubFragment.java)）+ 左下「设置」/红色「退出」/`V1.6.6`；非录制根页隐藏顶栏。
   - [SettingsCatalog.java](../../app/src/main/java/com/kooo/evcam/ui/SettingsCatalog.java) 与 [SettingsFragment.java](../../app/src/main/java/com/kooo/evcam/SettingsFragment.java) 实现左侧分类栏+右侧容器内嵌 7 类设置及二级子页返回，「补盲」「飞书远程」收归设置目录，保留 T01/T03/T04 主逻辑与双 HUD。
3. **`外观与交互` SC 双列卡片与开门退出延时**：
   - [AppearanceSettingsFragment.java](../../app/src/main/java/com/kooo/evcam/ui/AppearanceSettingsFragment.java) 与 [AppConfig.java](../../app/src/main/java/com/kooo/evcam/AppConfig.java) 采用两列卡片+胶囊按钮，新增 `KEY_DOOR_EXIT_DELAY_SECONDS`（默认 5 秒，支持 3/5/10/15/30 秒）；保留 T03/T04 全部控件 ID。

## 验证与交付状态（仅离线）

- **`9f443b6` 离线验证**：`tools/verify.ps1` 退出 0（`248` 项单测：Independent `124` + Composite `124`，`0` 失败/错误/跳过，含外观/延时新测试；Debug/Release R8 编译通过）；`:app:lintIndependentDebug --offline` 为 `19` 项既有错误（`0` 新增）、`1745` 警告。
- **历史 `build02` 产物**：`outputs/EVCam-E5-{A-Independent,B-Composite}-1.6.6-test-11152245-build02-release.apk`（`6,431,411`B，`apksigner verify` 通过）；最新 Release 包仍为 `build04`（含 `T01`–`T04`，**不含本次 `9f443b6` UI 改版**）。
- **待实车确认**：E5 `2560×1600` SC 深浅色反差、窄左栏与双栏设置触控、子页内嵌返回、录像中切换、`T03` 顶栏控制及 `T04` 小窗/双 HUD 共生。

## 最近记录（最多5条）

- 2026-11-16 02:50:25 +08:00 | T02 SC界面对齐(离线) | 基于 `build04` 提交 `9f443b6`（`1.6.6-test-11160243`）：重调 SC 深浅色板、窄左栏（补盲/飞书收归设置）、双栏 Master-Detail 设置与双列外观页；`248` 测+Release+0 新增 Lint 通过（未封装 `build05`/待实车） | [总览](../STATUS.md)
- 2026-11-15 23:00:38 +08:00 | build02存档 | 基于 `T02`（`72dc456` / `1.6.6-test-11152245`）打包 `build02` 双风味 Release APK、校验签名/SHA256/mappings 并推送 GitHub，转交新会话做 `T03`。| [总览](../STATUS.md)
- 2026-11-15 22:50:24 +08:00 | T02完成(离线) | 完成三态不重建主题、SC 4+2 左栏与回看合并、7 项精简设置目录与子页拆分（`72dc456`），188 测+Release+0 新增 Lint 通过。| [总览](../STATUS.md)
- 2026-11-15 22:06:00 +08:00 | T02定稿 | 用户拍板默认跟随系统主题、SC 式回看合并与精简设置目录；待新会话编码。| [总蓝图](../EVCAM_SMARTCAM_REFACTOR_PLAN.md)

## 下一步

1. 若准备上车测试新版 UI，可在 `9f443b6`（已含 `T01`–`T04` + SC UI 对齐）基础上打包发布 `build05`；否则可先直装离线编出的 `Debug`/`Release` APK 核验界面效果。
2. 实车时联合验收 `T01`–`T04` 与新版 UI 交互。
