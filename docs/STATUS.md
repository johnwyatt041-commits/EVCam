# 项目进度

最后更新/核对：2026-11-19 14:28:30 +08:00（Asia/Hong_Kong）
维护：Antigravity（已完成 `build18` / `06bcd22` 手机公共目录权限诊断与快败修复封包、提交与 GitHub Pre-release 发布）。执行规范：[PROGRESS_RULES.md](PROGRESS_RULES.md)。

## 当前快照

| 项目 | 当前状态 |
| --- | --- |
| 分支与提交 | fork `codex/e5-recording-startup-fix` 已同步至本工作树最新提交（代码 `06bcd22` / Tag `v1.6.6-test-11191414-build18` / `1.6.6-test-11191414`，`versionCode = 85`；未包含主工作区未提交的 4.3 改动） |
| 本地交付 | `outputs/` 顶层保留 **`build17`** + **`build18`**（12 文件）；`build16` 已归档至 `outputs/archive/`（索引 `outputs/archive/INDEX.md`） |
| GitHub | `johnwyatt041-commits/EVCam` 已同步分支 `codex/e5-recording-startup-fix`、标签 `build18` 并发布 Pre-release `v1.6.6-test-11191414-build18`（6 项资产 SHA256 核验一致） |
| 离线与设备验证 | `build18`（`1.6.6-test-11191414`）已通过 `verify.ps1` `504` 测 `0` 失败 `0` 跳过、双风味 `0` Lint error（`2076` warnings）、`apksigner`/`libvhal_decoder.so` 校验；`build18` 真机授权弹窗/开录与模拟器均**尚未安装实测** |
| 实车状态 | 银河 E5 实车四路硬件录制、`B/C→D` 切换及包 A–E 全集（`build18`）均**尚未上车实测** |

## 当前结论与确认边界

- **手机端“一直显示录像准备中”诊断与 `build18` 修复已落地**：Android 16 真机日志（`2211133C`，`1.6.6-test-11191152`）证实失败原因为缺少 `MANAGE_EXTERNAL_STORAGE` 写入 `DCIM/EVCam_Video/*.mp4.pending` 触发 `EPERM`，随后 `CodecVideoRecorder` 陷入 3s 重建循环至 20s 超时。已在保持 `.mp4.pending` 协议（[P015](PITFALLS.md)）前提下完成权限核心级诊断、实写探测、手动「去授权」引导、致命写入快败及 UI/远程收口。
- **包 E / 包 D / 包 C 既有基线保持完整**：在 `5df4dcb` 基础上增量修复，主工作区未完成的 4.3 改动已隔离保留、未打入本次提交与发布包。
- **用户规则拍板（2026-11-18 已确认）**：① **鱼眼矫正保持关闭**（`AppFeatures.FISHEYE_CORRECTION = false`）；② **ESP32 与浏览器投屏保持保留不动**（`AppFeatures.NETWORK_STREAMING = true`）。
- **`W1`（白色主题 · 精密镜头）图标已提交至 `904e500`**：已打入 `build14`–`build18`，详情见 [图标任务](tasks/app-icon-redesign.md)。

## 下一步

1. 在手机端安装 **`build18`** 实测未授/已授「所有文件访问权限」的弹窗提示与正常开录表现；再按需在银河 E5 实车联测独立版 A / 共存版 B（先后覆盖，切勿并行）。

## 最近更新（最多5条）

- 2026-11-19 14:28:30 +08:00 | 手机权限诊断/快败修复与 build18 发布 GitHub | 将 `ALL_FILES` 设为 Android 11+ 核心必需权限，新增 `.mp4.pending` 实写探测、手动“去授权”弹窗、致命存储错误快败与 `RemoteCommandHandler` 收口（`06bcd22` / `v1.6.6-test-11191414-build18`），`504` 测 `0` 失败、`0` Lint error，已归档 `build16` 并发布 GitHub Pre-release（6 项资产 SHA256 一致） | [权限启动修复](tasks/fix-storage-permission-startup.md)
- 2026-11-19 12:11:00 +08:00 | P018 回看销毁保护修复与 build17 发布 GitHub | 修复 `PlaybackImageMemory.clearViews()` 在 `Activity.isDestroyed()` 调 `Glide.with()` 闪退，加固全量 Glide 加载/清理与 `AppShellController.restore()` 根容器显隐恢复（`e946c50` / `v1.6.6-test-11191152-build17`），`488` 测 `0` 失败、`0` Lint error、`emulator-5556` 重建实测通过并发布 GitHub Pre-release（`build15` 归档） | [P018修复](tasks/fix-p018-playback-glide-destroy.md) · [P018](PITFALLS.md)
- 2026-11-19 11:26:00 +08:00 | build16 封包提交与历史归档索引 | 提交 `B/C→D` + `P019` 修复至 `b8c5773`（Tag `v1.6.6-test-11190906-build16`）并导出 `build16`，归档 `build14`、`11-17`/`11-18` 日志与 `workspace-cleanup` 任务 | [模式D卡顿修复](tasks/fix-mode-d-switch-stall.md) · [归档索引](archive/2026-11/INDEX.md)
- 2026-11-19 09:20:00 +08:00 | 修复 B/C 切 D 卡顿与 P019 后台切模式 | `SingleCamera` 按路拆分 `CamLifecycle-<id>` 异步执行 `closeCamera()`/`openCamera()`，`MultiCameraManager.release()` 并行等待，`PreviewModeController` 延后后台页掩码，`MainActivity` 修复隐藏窗格空轮询；`emulator-5556` 全流程通过 | [模式D卡顿修复](tasks/fix-mode-d-switch-stall.md) · [P019](PITFALLS.md)
- 2026-11-19 02:31:36 +08:00 | 默认车模替换、SAF 导入修复与 build15 发布 | 写入银河 E5 透明车模为默认资源并修 360 GL `GL_ONE` 预乘混合，补 `OpenDocument` 与弹窗提示（`111c1cb` / `build15`：`470` 测 `0` 失败、`0` Lint error） | [包 B 任务](tasks/fix-pack-b-preview-and-mode-d.md) · [P020](PITFALLS.md)

## 归档与按需索引

- **归档全索引**：[2026-11 历史文档与任务全索引](archive/2026-11/INDEX.md)（`T01`–`T10`、`build01`–`build13`、早期报告均已收录）；APK 归档见 `outputs/archive/INDEX.md`
- **按需规范**：[需求](REQUIREMENTS.md) · [坑表](PITFALLS.md) · [协作](COLLABORATION.md) · [开发](DEVELOPMENT.md) · [本机资料](AI_HANDOFF.md)
