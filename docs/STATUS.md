# 项目进度

最后更新/核对：2026-11-19 11:26:00 +08:00（Asia/Hong_Kong）
维护：Antigravity（已完成 `B/C→D` 切屏卡顿与 `P019` 修复源码提交 `b8c5773` / `build16` 封包导出，并完成过时历史项归档与索引）。执行规范：[PROGRESS_RULES.md](PROGRESS_RULES.md)。

## 当前快照

| 项目 | 当前状态 |
| --- | --- |
| 分支与提交 | `codex/e5-recording-startup-fix`；最新代码提交 `b8c5773`（Tag `v1.6.6-test-11190906-build16` / `1.6.6-test-11190906`，`versionCode = 83`） |
| 本地交付 | `outputs/` 顶层保留 **`build15`** + **`build16`**（12 文件）；`build14` 已归档至 `outputs/archive/`（索引 `outputs/archive/INDEX.md`） |
| GitHub | `johnwyatt041-commits/EVCam` 远端停留在 `111c1cb` / `v1.6.6-test-11190207-build15`（`build16` 仅本地提交、打标与封包，未推送） |
| 离线与模拟器验证 | `build16`（`1.6.6-test-11190906`）已复核 `verify.ps1` `480` 测 `0` 失败 `0` 跳过、双风味 `0` Lint error（`2076` warnings）、`apksigner`/`libvhal_decoder.so` 校验通过；`emulator-5556` 已于 `09:20` 实测 `B/C/D/A` 切换、快速连点、`P019` 设置页延后重绑与录制中切换无丢帧/崩溃 |
| 实车状态 | 银河 E5 实车四路 `B/C→D` 切换及包 A–E 全集（`build16`）均**尚未上车实测** |

## 当前结论与确认边界

- **包 E（`5` 车机端菜单/顶栏/设置放大 + 防溢出收口）已完成模拟器全验收**：以 5 档 `w`/`h` 限定符替代旧 `sw600dp*`，为顶栏/左栏/设置/回看按钮统一 `@dimen/ui_*`，并在「外观与交互」新增“菜单与按钮缩放（标准自适应 / 车机大号 1.16f）”原地热切；在 `1600×900` 与 `2560×1600`（`240`/`160dpi`）实测均无截断溢出与崩溃。
- **包 D（`3.1` 仅 D 档弹出 + `3.3` 水印四项独立开关去刹车）已完成离线与模拟器全验收**：`GearShiftPopupCoordinator` 仅在稳定切入 `Gear.DRIVE` 时触发；`3.3 显示` 拆分为时间/车速/档位/转向灯 4 项独立开关并彻底删除刹车字段。
- **包 C（补盲去动效 + 平铺选屏 + 2560×1600 可视化与分路裁切）已完成本地验证**：移除状态栏动效，顶置屏幕选择与总开关，新增 6 槽位 16:10 可视化拖拽及分方向旋转/四边裁切（保留 ESP32）。
- **用户规则拍板（2026-11-18 已确认）**：① **鱼眼矫正保持关闭**（`AppFeatures.FISHEYE_CORRECTION = false`）；② **ESP32 与浏览器投屏保持保留不动**（`AppFeatures.NETWORK_STREAMING = true`）。
- **`W1`（白色主题 · 精密镜头）图标已提交至 `904e500`**：已打入 `build14`–`build16`，详情见 [图标任务](tasks/app-icon-redesign.md)。

## 下一步

1. 安装 `outputs/` 中 **`build16`**（独立版 A / 共存版 B 先后覆盖，切勿并行）到银河 E5 实车按 [build13 验收清单](archive/2026-11/tasks/build12-e5-acceptance-and-lint-cleanup.md) 联测四路 `B/C→D` 切换及包 A–E 全部实车项；如需上传 GitHub，再推送 `build16` 分支与标签。

## 最近更新（最多5条）

- 2026-11-19 11:26:00 +08:00 | build16 封包提交与历史归档索引 | 提交 `B/C→D` + `P019` 修复至 `b8c5773`（Tag `v1.6.6-test-11190906-build16`）并导出 `build16` 6 项资产至 `outputs/`（`480` 测 `0` 失败、双风味 `0` Lint error、`apksigner`/`libvhal_decoder.so` 通过）；归档 `build14`、`11-17`/`11-18` 验证日志与 `workspace-cleanup` 任务，清理旧无风味 APK 与残留 `emulator*.log`，新增 [文档归档索引](archive/2026-11/INDEX.md) 与本地 `work/archive/INDEX.md` | [模式D卡顿修复](tasks/fix-mode-d-switch-stall.md) · [归档索引](archive/2026-11/INDEX.md)
- 2026-11-19 09:20:00 +08:00 | 修复 B/C 切 D 卡顿与 P019 后台切模式 | `SingleCamera` 按路拆分 `CamLifecycle-<id>` 单线程+代次守卫异步执行 `closeCamera()`/`openCamera()`，`MultiCameraManager.release()` 并行等待，`PreviewModeController` 延后后台页掩码，`MainActivity` 修复隐藏窗格无限轮询；`emulator-5556` 全流程实测通过（0 崩溃、0 丢帧） | [模式D卡顿修复](tasks/fix-mode-d-switch-stall.md) · [P019](PITFALLS.md)
- 2026-11-19 02:31:36 +08:00 | 默认车模替换、SAF 导入修复与 build15 发布 | 写入银河 E5 透明车模为默认资源并修 360 GL `GL_ONE` 预乘混合，补 `OpenDocument` 与弹窗提示解决无存储权限下按钮无反应（`111c1cb` / `build15`：`470` 测 `0` 失败、`0` Lint error） | [包 B 任务](tasks/fix-pack-b-preview-and-mode-d.md) · [P020](PITFALLS.md)
- 2026-11-19 01:16:00 +08:00 | build14 提交与封包导出 | 提交包 A–E 整合 `55cd486` 并导出 `build14`（`1.6.6-test-11190102`）A/B Release APK 至 `outputs/`（现已归档至 `outputs/archive/`） | [包 A](tasks/fix-pack-a-crashes-and-reverse-signal.md) · [包 D](tasks/fix-pack-d-recorder-settings-and-watermark.md)
- 2026-11-18 23:29:00 +08:00 | 包 E 实现与双分辨率验收 | 完成 `5` 档 `w`/`h` 缩放资源、原地“标准/车机大号(1.16f)”切换与防溢出（`1.6.6-test-11182319`，`468` 测 `0` 失败、`0` Lint error、`emulator-5556` 双分辨率通过） | [包 E 任务](tasks/fix-pack-e-headunit-ui-scaling.md)

## 归档与按需索引

- **归档全索引**：[2026-11 历史文档与任务全索引](archive/2026-11/INDEX.md)（`T01`–`T10`、`build01`–`build13`、早期报告均已收录）；APK 归档见 `outputs/archive/INDEX.md`
- **按需规范**：[需求](REQUIREMENTS.md) · [坑表](PITFALLS.md) · [协作](COLLABORATION.md) · [开发](DEVELOPMENT.md) · [本机资料](AI_HANDOFF.md)
