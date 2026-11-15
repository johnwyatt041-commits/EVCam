# 任务记录：T02 全局外壳 UI、SC 风格左栏与深浅色主题

- 最后更新：2026-11-15 22:06:00 +08:00（Asia/Hong_Kong）
- 最后核对：2026-11-15 22:06:00 +08:00（Asia/Hong_Kong）
- 任务/执行者：`t02-shell-navigation-and-theme` / Antigravity（方案核查与定稿）
- 分支/工作树：`codex/e5-recording-startup-fix` / `E:\GPT\EVCam`（基线 `build01`：`ea395a0` / Tag `v1.6.6-test-11152059-build01`）
- 状态：方案已定稿 / 待新会话编码
- 当前结论：已完成 SC V3.0.56 与 EC 外壳/主题/设置源码核查，用户已拍板 3 项设计细节，新会话可直接启动 `T02` 编码（禁止顺手改 `T03`–`T10`）。

## 核查事实（SC 底层缺陷 vs EC 现状）

1. **SC 重建隐患与生态包袱**：SC（`Lsi;`、`Lyh;`、`Lfk3;`、`Lo34;`、`manifest_xmltree.txt`）`MainActivity` 未声明 `uiMode` `configChanges`，靠双进程 `:core` 保活兜底主题切换重建，且混入 `主页/投票`、`屏幕尺寸`、`外设`、`行程/数据卡片/喊话`。
2. **EC 现行痛点**：单进程下 [UiThemeController.java](../../app/src/main/java/com/kooo/evcam/ui/UiThemeController.java) 仅支持深/浅两态且会销毁重建 `MainActivity`（录像时被迫延后）；[ui_app_shell.xml](../../app/src/main/res/layout/ui_app_shell.xml) 左下角缺少 `设置/退出`、顶栏多余 `ui_theme` 按钮、[MainActivity.java](../../app/src/main/java/com/kooo/evcam/MainActivity.java) 残余锁定 `DrawerLayout/NavigationView`；[SettingsFragment.java](../../app/src/main/java/com/kooo/evcam/SettingsFragment.java) 2414 行单体混堆。

## 用户定稿的 `T02` 实施方案（新会话直接按此开发）

1. **三态主题（默认跟随系统）+ 零重建切换**：
   - 主题模式支持 `FOLLOW_SYSTEM`（**全新安装默认**）/ `DARK` / `LIGHT`，向下兼容迁移旧 `ui_preferences.light_theme`。
   - [AndroidManifest.xml](../../app/src/main/AndroidManifest.xml) 为 `.MainActivity` 增加 `android:configChanges="uiMode|orientation|screenSize|screenLayout|smallestScreenSize"`，并在 `onConfigurationChanged` + `UiThemeController` 解析目标 `Configuration(uiMode)` 下的 `colors.xml` / `values-night/colors.xml` 原地重涂窗口树与系统栏，录像中零重建、不断录。
2. **SC 风格左栏（4+2 结构）与左下角入口**：
   - 主导航去 `主页`/`外设`/投票，保留 **`记录仪`**（默认页）、**`回看`**（合并入口，顶部切 `视频 / 照片` 两个既有列表，不丢照片功能）、**`补盲`**、**`飞书`**（后续 `T10` 再收进设置 `11.` + P 档门控）。
   - **左下角固定区**：放置 **`设置`** 与 **`退出`**（统一复用 `AppExitCoordinator` 弹窗）；移除顶栏 `ui_theme` 按钮、录制栏冗余 `btn_exit` 与冗余 `DrawerLayout/NavigationView` 抽屉菜单；首次启动不再强跳设置页。
3. **设置分类精简与 `1. 外观与交互` 子页**：
   - 剔除 `屏幕尺寸`、`4. 外设`、`7. 行程与充电`、`8. 数据卡片`、`9. 车外喊话` 及未实现空壳；`T02` 分类列表**只展示已实装可进项**：`1. 外观与交互`、`3. 记录仪`、`5. 补盲`、`11. 飞书远程`、`13. 系统与权限`、`14. 软件更新`、`15. Debug 日志`。
   - 新建 `1. 外观与交互`（`AppearanceSettingsFragment`）：主题模式（跟随系统/深色/浅色）、默认启动页（记录仪/回看/设置）、`confirm_door_exit` 开门退出开关与持久化预留（车门总线触发留待 `T06` 接入 `VehicleSignalHub`）。

## 最近记录（最多5条）

- 2026-11-15 22:06:00 +08:00 | T02 定稿 | 用户拍板：全新安装默认跟随系统主题、左栏按 SC 合并「回看（视频/照片切换）」、设置目录仅展示已实装分类；待新会话编码。| [总蓝图](../EVCAM_SMARTCAM_REFACTOR_PLAN.md)
- 2026-11-15 21:43:00 +08:00 | 源码核查 | 完成 SC `Lfk3;`/`Lsi;`/`Lo34;`/`Lqs3;`/`manifest_xmltree.txt` 与 EC 外壳/主题/设置源码对照。| [总览](../STATUS.md)

## 下一步

1. 新会话读取 `AGENTS.md`、[总览](../STATUS.md)、[总蓝图](../EVCAM_SMARTCAM_REFACTOR_PLAN.md) 与本文档，**无需重探需求**，直接实施 `T02` 代码（更新 `app/build.gradle.kts` `versionName`）。
2. 编码后补充 UI/主题状态单元测、跑通 `tools/verify.ps1` + Lint（0 新增错误）+ 四件套文档同步 + `check_docs.py`。
