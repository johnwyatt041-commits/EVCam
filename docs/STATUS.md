# 项目进度

最后更新/核对：2026-11-19 01:11:00 +08:00（Asia/Hong_Kong）
维护：Antigravity（已完成包 A/B/C/D/E 全部代码、单测、`0` Lint error 与 `emulator-5556` 双分辨率验收，准备提交整合代码并导出 `build14` 至 `outputs/`，已发布 GitHub 基线仍为 **`build13`**）。执行规范：[PROGRESS_RULES.md](PROGRESS_RULES.md)。

## 当前快照

| 项目 | 当前状态 |
| --- | --- |
| 分支与提交 | `codex/e5-recording-startup-fix`；`HEAD` 为 `7d03610`（含 `904e500` `W1`），工作区含待提交包 A–E 改动（`1.6.6-test-11190102` / `versionCode = 83`，未推送） |
| 已发布 APK | **`build13`：`1.6.6-test-11171608`**（A `6,791,918` / B `6,808,302` B）；`1.6.6-test-11190102` 即将封包为 `build14` 入 `outputs/` |
| 本地交付 | `outputs/` 顶层留 `build12` + `build13`（12 文件 / 50.28 MB）；早期 135 项已归档至 `outputs/archive/` |
| GitHub | `johnwyatt041-commits/EVCam` 远端最新仍为 `build13` Pre-release（`8ef5b36`）；本地修改尚未推送 |
| 离线与模拟器验证 | `tools\verify.ps1` 通过（双风味 `468` 测 `0` 失败、`assembleDebug`/`assembleRelease` 成功）；双风味 Lint `0 errors`（`2076` warnings）；`emulator-5556` 已全覆盖包 A–E（含包 D `1600×900` & `2560×1600` 双缩放深浅主题） |
| 实车状态 | `build01`–`build13`、`W1`、包 A（VHAL 倒车流）、包 B（四路拼接/方向盘转角）、包 C（EVCC/转向灯实流）、包 D（D 档弹出/独立水印）与包 E（车机实屏 `wm density` 与触控体感）均**尚未上车实测** |

## 当前结论与确认边界

- **包 E（`5` 车机端菜单/顶栏/设置放大 + 防溢出收口）已完成模拟器全验收**：以 5 档 `w`/`h` 限定符替代旧 `sw600dp*`，为顶栏/左栏/设置/回看按钮统一 `@dimen/ui_*`，并在「外观与交互」新增“菜单与按钮缩放（标准自适应 / 车机大号 1.16f）”原地热切；修复回看筛选折行与窄卡胶囊裁剪，在 `1600×900` 与 `2560×1600`（`240`/`160dpi`）实测均无截断溢出与崩溃。
- **包 D（`3.1` 仅 D 档弹出 + `3.3` 水印四项独立开关去刹车）已完成离线与模拟器全验收**：`GearShiftPopupCoordinator` 仅在稳定切入 `Gear.DRIVE` 时触发；`3.3 显示` 将时间、车速、档位、转向灯拆分为 4 项独立开关并彻底删除刹车字段，并在 `emulator-5556`（`1600×900` / `2560×1600`）验证边界与持久化。
- **包 C（补盲去动效 + 平铺选屏 + 2560×1600 可视化与分路裁切）已完成本地验证**：移除状态栏动效，顶置屏幕选择与总开关，新增 6 槽位 16:10 可视化拖拽及分方向旋转/四边裁切（保留 ESP32）。
- **用户规则拍板（2026-11-18 已确认）**：① **鱼眼矫正保持关闭**（`AppFeatures.FISHEYE_CORRECTION = false`）；② **ESP32 与浏览器投屏保持保留不动**（`AppFeatures.NETWORK_STREAMING = true`）。
- **`W1`（白色主题 · 精密镜头）图标已提交至 `904e500`（暂未打包发布）**：详情见 [图标任务](tasks/app-icon-redesign.md)。

## 下一步

1. 审阅并提交 `codex/e5-recording-startup-fix` 上累计的包 A–E 改动，按需封包出包并同步推送待推提交（含 `W1`）。
2. 在银河 E5 实车按 [build13 验收清单](archive/2026-11/tasks/build12-e5-acceptance-and-lint-cleanup.md) 联测包 A–E 全部实车项（含 `2560×1600` 实屏缩放与两档触控体感）。

## 最近更新（最多5条）

- 2026-11-19 01:08:00 +08:00 | 包 D 模拟器复测与整包验收 | 在 `emulator-5556` 完成包 D `1600×900` & `2560×1600`（标准/大号、深/浅色）UI 与边界实测并清场；`1.6.6-test-11190102`（`versionCode = 83`）通过 `verify.ps1` `468` 测 `0` 失败、双风味 `0` Lint error 及 `check_docs.py` | [包 D 任务](tasks/fix-pack-d-recorder-settings-and-watermark.md)
- 2026-11-18 23:29:00 +08:00 | 包 E 实现与双分辨率验收 | 完成 `5` 档 `w`/`h` 缩放资源、原地“标准/车机大号(1.16f)”切换、外观/补盲/配置广场防溢出与回看单行筛选（`1.6.6-test-11182319`），`468` 测 `0` 失败、双风味 `0` Lint error 及 `emulator-5556` `1600×900` + `2560×1600` 全通过 | [包 E 任务](tasks/fix-pack-e-headunit-ui-scaling.md)
- 2026-11-18 18:48:14 +08:00 | 包 C 实现与模拟器验收 | 完成 `4.1`/`4.2`（去状态栏动效、屏幕前置与平铺、2560×1600 可视化拖拽、分路旋转/四边裁切、保留 ESP32），`460` 测 `0` 失败、双风味 `0` Lint error 及 `emulator-5556` `1600×900`/`2560×1600`+深浅主题通过 | [包 C 任务](tasks/fix-pack-c-blindspot-refactor.md)
- 2026-11-18 16:48:00 +08:00 | 包 B 实现与模拟器验收 | 完成 `1.1`~`1.7`（`MODE_D`、去重胶囊、画内缩放裁剪、碰撞让位、`360 A` 视场/对比度、`SingleCamera` 同线程阻塞修复），`448` 测 0 失败、双风味 `0` Lint error 及 `emulator-5556` `2560×1600`/`1600×900` 全通过（4 路/实车方向盘待实测） | [包 B 任务](tasks/fix-pack-b-preview-and-mode-d.md)
- 2026-11-18 13:10:00 +08:00 | 包 A 修复与模拟器验证 | 完成 `2.1`（多分辨率回放布局/判空）、`1.2`（`360 A` GLThread/Surface 时序）与 `1.4`（VHAL 挡位解耦与租约、`440` 测 `0` 失败 + `emulator-5556` 零崩溃，实车 VHAL 待测） | [包 A 任务](tasks/fix-pack-a-crashes-and-reverse-signal.md)

## 13 个 Build 与归档按需索引

- **Build 全集任务记录（`build01`–`build13`）**：[build13 零 Lint 与实车清单](archive/2026-11/tasks/build12-e5-acceptance-and-lint-cleanup.md) · [T01](archive/2026-11/tasks/t01-lifecycle-and-signal-foundation.md) · [T02](archive/2026-11/tasks/t02-shell-navigation-and-theme.md) · [T03](archive/2026-11/tasks/t03-multi-mode-preview-and-dynamic-stream.md) · [T04](archive/2026-11/tasks/t04-floating-architecture-and-blindspot-fusion.md) · [T05](archive/2026-11/tasks/t05-video-pipeline-and-recorder-32-34.md) · [T06](archive/2026-11/tasks/t06-vehicle-rules-and-signal-presets.md) · [T07](archive/2026-11/tasks/t07-surround-360-and-mode-a.md) · [T08](archive/2026-11/tasks/t08-playback-and-lan-share.md) · [T09](archive/2026-11/tasks/t09-config-plaza-and-permissions.md) · [T10](archive/2026-11/tasks/t10-feishu-pgear-github-update-and-log-coherence.md)
- **阶段蓝图与专题归档**：[重构蓝图](archive/2026-11/EVCAM_SMARTCAM_REFACTOR_PLAN.md) · [Smartcam 3.0.40 全功能](archive/2026-11/SMARTCAM_FEATURE_CATALOG.md) · [3.0.56 差异（按需）](SMARTCAM_V3.0.56_DIFF.md) · [历史快照（2026-11-14）](archive/2026-11/STATUS_20261114_183403.md)
- **按需规范**：[需求](REQUIREMENTS.md) · [坑表](PITFALLS.md) · [协作](COLLABORATION.md) · [开发](DEVELOPMENT.md) · [本机资料](AI_HANDOFF.md)
