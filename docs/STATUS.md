# 项目进度

最后更新/核对：2026-11-19 12:11:00 +08:00（Asia/Hong_Kong）
维护：Antigravity（已完成 `P018` 回看销毁保护与重建恢复修复 `e946c50` / `build17` 封包、提交与 GitHub Pre-release 发布）。执行规范：[PROGRESS_RULES.md](PROGRESS_RULES.md)。

## 当前快照

| 项目 | 当前状态 |
| --- | --- |
| 分支与提交 | `codex/e5-recording-startup-fix`；最新代码提交 `e946c50`（Tag `v1.6.6-test-11191152-build17` / `1.6.6-test-11191152`，`versionCode = 84`） |
| 本地交付 | `outputs/` 顶层保留 **`build16`** + **`build17`**（12 文件）；`build15` 已归档至 `outputs/archive/`（索引 `outputs/archive/INDEX.md`） |
| GitHub | `johnwyatt041-commits/EVCam` 已同步分支 `codex/e5-recording-startup-fix`、标签 `build16`/`build17` 并发布 Pre-release `v1.6.6-test-11191152-build17`（6 项资产 SHA256 核验一致） |
| 离线与模拟器验证 | `build17`（`1.6.6-test-11191152`）已通过 `verify.ps1` `488` 测 `0` 失败 `0` 跳过、双风味 `0` Lint error（`2076` warnings）、`apksigner`/`libvhal_decoder.so` 校验及 `emulator-5556` `2560x1600@240`↔`reset` 重建实测（`PID=2966` 存活、`0` 崩溃） |
| 实车状态 | 银河 E5 实车四路 `B/C→D` 切换及包 A–E 全集（`build17`）均**尚未上车实测** |

## 当前结论与确认边界

- **包 E（`5` 车机端菜单/顶栏/设置放大 + 防溢出收口）已完成模拟器全验收**：以 5 档 `w`/`h` 限定符替代旧 `sw600dp*`，为顶栏/左栏/设置/回看按钮统一 `@dimen/ui_*`，并在「外观与交互」新增“菜单与按钮缩放（标准自适应 / 车机大号 1.16f）”原地热切；在 `1600×900` 与 `2560×1600`（`240`/`160dpi`）实测均无截断溢出与崩溃。
- **包 D（`3.1` 仅 D 档弹出 + `3.3` 水印四项独立开关去刹车）已完成离线与模拟器全验收**：`GearShiftPopupCoordinator` 仅在稳定切入 `Gear.DRIVE` 时触发；`3.3 显示` 拆分为时间/车速/档位/转向灯 4 项独立开关并彻底删除刹车字段。
- **包 C（补盲去动效 + 平铺选屏 + 2560×1600 可视化与分路裁切）已完成本地验证**：移除状态栏动效，顶置屏幕选择与总开关，新增 6 槽位 16:10 可视化拖拽及分方向旋转/四边裁切（保留 ESP32）。
- **用户规则拍板（2026-11-18 已确认）**：① **鱼眼矫正保持关闭**（`AppFeatures.FISHEYE_CORRECTION = false`）；② **ESP32 与浏览器投屏保持保留不动**（`AppFeatures.NETWORK_STREAMING = true`）。
- **`W1`（白色主题 · 精密镜头）图标已提交至 `904e500`**：已打入 `build14`–`build17`，详情见 [图标任务](tasks/app-icon-redesign.md)。

## 下一步

1. 安装 **`build17`**（独立版 A / 共存版 B 先后覆盖，切勿并行）到银河 E5 实车按 [build13 验收清单](archive/2026-11/tasks/build12-e5-acceptance-and-lint-cleanup.md) 联测四路 `B/C→D` 切换及包 A–E 全部实车项。

## 最近更新（最多5条）

- 2026-11-19 12:11:00 +08:00 | P018 回看销毁保护修复与 build17 发布 GitHub | 修复 `PlaybackImageMemory.clearViews()` 在 `Activity.isDestroyed()` 调 `Glide.with()` 闪退，加固全量 Glide 加载/清理与 `AppShellController.restore()` 根容器显隐（`e946c50` / `v1.6.6-test-11191152-build17`），`488` 测 `0` 失败、`0` Lint error、`emulator-5556` 重建实测通过并发布 GitHub Pre-release（`build15` 归档） | [P018修复](tasks/fix-p018-playback-glide-destroy.md) · [P018](PITFALLS.md)
- 2026-11-19 11:26:00 +08:00 | build16 封包提交与历史归档索引 | 提交 `B/C→D` + `P019` 修复至 `b8c5773`（Tag `v1.6.6-test-11190906-build16`）并导出 `build16`，归档 `build14`、`11-17`/`11-18` 日志与 `workspace-cleanup` 任务，新增 [文档归档索引](archive/2026-11/INDEX.md) 与本地 `work/archive/INDEX.md` | [模式D卡顿修复](tasks/fix-mode-d-switch-stall.md) · [归档索引](archive/2026-11/INDEX.md)
- 2026-11-19 09:20:00 +08:00 | 修复 B/C 切 D 卡顿与 P019 后台切模式 | `SingleCamera` 按路拆分 `CamLifecycle-<id>` 异步执行 `closeCamera()`/`openCamera()`，`MultiCameraManager.release()` 并行等待，`PreviewModeController` 延后后台页掩码，`MainActivity` 修复隐藏窗格空轮询；`emulator-5556` 全流程通过 | [模式D卡顿修复](tasks/fix-mode-d-switch-stall.md) · [P019](PITFALLS.md)
- 2026-11-19 02:31:36 +08:00 | 默认车模替换、SAF 导入修复与 build15 发布 | 写入银河 E5 透明车模为默认资源并修 360 GL `GL_ONE` 预乘混合，补 `OpenDocument` 与弹窗提示（`111c1cb` / `build15`：`470` 测 `0` 失败、`0` Lint error） | [包 B 任务](tasks/fix-pack-b-preview-and-mode-d.md) · [P020](PITFALLS.md)
- 2026-11-19 01:16:00 +08:00 | build14 提交与封包导出 | 提交包 A–E 整合 `55cd486` 并导出 `build14`（`1.6.6-test-11190102`）A/B Release APK 至 `outputs/`（现已归档至 `outputs/archive/`） | [包 A](tasks/fix-pack-a-crashes-and-reverse-signal.md) · [包 D](tasks/fix-pack-d-recorder-settings-and-watermark.md)

## 归档与按需索引

- **归档全索引**：[2026-11 历史文档与任务全索引](archive/2026-11/INDEX.md)（`T01`–`T10`、`build01`–`build13`、早期报告均已收录）；APK 归档见 `outputs/archive/INDEX.md`
- **按需规范**：[需求](REQUIREMENTS.md) · [坑表](PITFALLS.md) · [协作](COLLABORATION.md) · [开发](DEVELOPMENT.md) · [本机资料](AI_HANDOFF.md)
