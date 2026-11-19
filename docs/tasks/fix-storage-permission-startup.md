# 手机公共目录写入权限诊断与录像启动快速失败修复

最后更新：2026-11-19 14:28:30 +08:00
最后核对：2026-11-19 14:28:30 +08:00
任务/执行者：fix-storage-permission-startup / Antigravity
分支/工作树：codex/fix-storage-permission-startup / E:\GPT\EVCam-worktrees\fix-storage-permission-startup（基于 `5df4dcb` / `build17`，未合入主工作区未提交的 4.3 改动）
状态：已完成提交、封包 build18 与 GitHub Pre-release 发布（待手机与实车实测）
当前结论：手机端（Android 16 / `2211133C`，`1.6.6-test-11191152`）一直停在“准备录制…”的根因是未授 `MANAGE_EXTERNAL_STORAGE`，导致 `AsyncMuxerWriter.FileSink.start()` 写入 `DCIM/EVCam_Video/*.mp4.pending` 报 `EPERM (Operation not permitted)` 后陷入 3s 编码器重建循环直至 20s 看门狗超时；已在保留现有 `.mp4.pending` 文件协议的前提下完成全链路修复（`1.6.6-test-11191414`，`versionCode = 85`）。

## 修复清单

1. **权限分级与诊断口径修正（[PermissionDiagnostics.java](../../app/src/main/java/com/kooo/evcam/PermissionDiagnostics.java)、[RecorderSettingsFragment.java](../../app/src/main/java/com/kooo/evcam/RecorderSettingsFragment.java)）**：
   - 将 Android 11+（`API >= 30`）`ItemId.ALL_FILES` 由 `OPTIONAL` 提升为 `Priority.CORE`，明确提示未授权将导致公共录像目录与 U 盘无法写入。
2. **`.mp4.pending` 实写探测与启动前置检查（[RecordingStorageAccess.java](../../app/src/main/java/com/kooo/evcam/RecordingStorageAccess.java)、[StorageHelper.java](../../app/src/main/java/com/kooo/evcam/StorageHelper.java)、[MainActivity.java](../../app/src/main/java/com/kooo/evcam/MainActivity.java)）**：
   - 保留 `DCIM/EVCam_Video/*.mp4.pending` 写入协议（遵循 [P015](../PITFALLS.md)），开录前通过临时 `.evcam_write_probe_*.mp4.pending` 验证真实可写性；手动点击缺 `ALL_FILES` 弹窗直达 `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`“去授权”，自动/后台/远程路径限频 Toast 且不回传虚假成功提示。
3. **致命写入快败、UI 与远程收口（[CodecVideoRecorder.java](../../app/src/main/java/com/kooo/evcam/camera/CodecVideoRecorder.java)、[VideoRecorder.java](../../app/src/main/java/com/kooo/evcam/camera/VideoRecorder.java)、[MultiCameraManager.java](../../app/src/main/java/com/kooo/evcam/camera/MultiCameraManager.java)、[RemoteCommandHandler.java](../../app/src/main/java/com/kooo/evcam/remote/handler/RemoteCommandHandler.java)、[RemoteCommandDispatcher.java](../../app/src/main/java/com/kooo/evcam/remote/RemoteCommandDispatcher.java)）**：
   - 首次写入前命中 `EPERM`/`EACCES`/`EROFS`/`ENOSPC`/`SecurityException` 立即置 `fatalWriteError` 并取消健康检查/重建重试；`MultiCameraManager` 在 MediaRecorder/Codec/Composite 三条回调、全摄启动失败、`MediaRecorder` 启动看门狗及非自动模式重建上限处统一快速收口；`RemoteCommandHandler` 在启动致命失败时同步清理远程录制态并回复报错。
4. **验证脚本与回归测试（[verify.ps1](../../tools/verify.ps1)、[RecordingStorageAccessTest.java](../../app/src/test/java/com/kooo/evcam/RecordingStorageAccessTest.java)、[PermissionDiagnosticsTest.java](../../app/src/test/java/com/kooo/evcam/PermissionDiagnosticsTest.java)、[CodecRecoveryStopTest.java](../../app/src/test/java/com/kooo/evcam/camera/CodecRecoveryStopTest.java)、[RemoteRecordingHandoffTest.java](../../app/src/test/java/com/kooo/evcam/remote/handler/RemoteRecordingHandoffTest.java)）**：
   - 修复冷工作树下 `verify.ps1` 将注解处理器 stderr `注:` 误判中断问题；新增 8 项单测，双风味离线通过 `504` 测（`0` 失败/`0` 错误/`0` 跳过）及 `0` Lint error。

## 最近记录

- 2026-11-19 14:28:30 +08:00 | 推送 fork 并发布 GitHub Pre-release (`build18`) | 已将 `06bcd22` 与文档提交推至 `johnwyatt041-commits/EVCam` `codex/e5-recording-startup-fix` 并推送标签 `v1.6.6-test-11191414-build18`，发布 Pre-release 并核验 6 项资产字节数与 SHA256 一致，同步清理 `work/tmp/fix-storage-permission-startup/`（`https://github.com/johnwyatt041-commits/EVCam/releases/tag/v1.6.6-test-11191414-build18`） | [STATUS](../STATUS.md)
- 2026-11-19 14:24:00 +08:00 | 完成隔离修复、504 测与双风味 Lint 清零 | 离线 `verify.ps1`（`E:\GPT\EVCam\work\verification\build-20261119-142140-251.log`）通过 `504` 测（A/B 各 `62` 套 `252` 测）；双风味 Lint（`E:\GPT\EVCam\work\verification\lint-20261119-1422.log`）均为 `0` errors（`2076` warnings）；两版 Release APK 均通过 `apksigner verify` 并包含 `lib/arm64-v8a/libvhal_decoder.so` | [STATUS](../STATUS.md)

## 下一步

1. 下载 GitHub Pre-release 或本地 `outputs/` 中 **`build18`**，先在手机端实测未授权弹窗“去授权”与授权后录像写入；再按需在银河 E5 实车联测独立版 A / 共存版 B（先后覆盖，切勿并行）。
