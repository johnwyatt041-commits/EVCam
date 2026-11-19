# 记录仪界面 B/C 切 D 卡顿修复（含 P019 后台切模式）

最后更新：2026-11-19 11:24:00 +08:00
最后核对：2026-11-19 11:24:00 +08:00
任务/执行者：fix-mode-d-switch-stall / Antigravity
分支/工作树：codex/e5-recording-startup-fix / E:\GPT\EVCam
状态：已完成离线验证、build16 封包与 2 摄模拟器验收（待实车四路验证）
当前结论：已修复记录仪与设置页从 `B/C` 切到 `D/A` 触发闲置相机在 UI 主线程同步 `closeCamera()`（含 HAL teardown 与持有 `reconnectLock` 的 `join(2000)`）导致的卡顿问题，同步落实 `P019` 后台页延后掩码及隐藏窗格 `applySlotMatrixTransform()` 防空轮询。当前本地工作区（`1.6.6-test-11190906`）已通过 `.\tools\verify.ps1`（`480` 测 `0` 失败 `0` 跳过）、双风味 `Debug/Release` 构建、`Lint` `0` errors（`2076` warnings），以及 2 摄 `emulator-5556` 空闲 `B/C/D/A` 切换+连点、`P019` 设置页 `360 画面 -> 4.1` 切换延后重绑、17s 双路录制期间 `B/C↔D` 切换全部实测（0 崩溃、0 `ERROR_CAMERA_IN_USE`、0 `Choreographer` 丢帧，仅模拟器默认 `EPERM` 需授权 `MANAGE_EXTERNAL_STORAGE`）；银河 E5 实车 4 路硬件切换尚未上车实测。

## 根因与修改清单

1. **主线程同步关闭/打开相机（[SingleCamera.java](../../app/src/main/java/com/kooo/evcam/camera/SingleCamera.java)）**：
   - 新增每路独立单线程生命周期队列 `CamLifecycle-<cameraId>` 与 `lifecycleGeneration`/`activeOpenGeneration` 代次守卫：
   - `closeCamera()` 改为在轻锁下原子摘除并清空 `captureSession`、`cameraDevice`、`previewSurface`、`backgroundThread/Handler` 及外部 Surface 引用后立即返回，把阻塞的 `session.close()`、`device.close()` 与 `stopBackgroundThreadInstance()` 放到 `CamLifecycle-*` 异步执行，消除 UI 主线程 `join(2000)` 与 HAL 阻塞（B→D 双路、C→D 单路、2 摄模拟器仅 D/A 关右摄）。
   - `openCamera()` 在首次打开时同步缓存 `cameraCharacteristics` 与 `previewSize`（满足 `startRecording()` 同步读尺寸契约），把 `getCameraIdList()` 与 `cameraManager.openCamera()` 调度到同一 `CamLifecycle-*` 队列执行，严格保证“先关后开”且不阻塞主线程。
   - `buildStateCallback(openGen)`、`scheduleReconnect()`、`forceReopen()` 与 `createCameraPreviewSession()` 均绑定代次校验，防止延迟回调复用旧实例或复活已关闭相机；新增 `closeCameraAndAwait(timeoutMs)` / `awaitPendingLifecycle(timeoutMs)`。
2. **并行销毁收尾（[MultiCameraManager.java](../../app/src/main/java/com/kooo/evcam/camera/MultiCameraManager.java)）**：
   - `closeAllCameras()` 保持异步不阻塞 `onPause()`/后台释放；`release()` 改用 `closeAllCamerasAndAwait(2500L)` 先并行触发各路关闭再统一等待并停用生命周期线程池。
3. **后台设置页延后掩码与隐藏窗格变换防轮询（[PreviewModeController.java](../../app/src/main/java/com/kooo/evcam/ui/PreviewModeController.java)、[MainActivity.java](../../app/src/main/java/com/kooo/evcam/MainActivity.java)）**：
   - 落实 `P019`：`PreviewModeController` 新增 `shouldApplyVisibilityMaskImmediately(hostEligible)`，非记录仪页或后台修改模式时延后下发相机可见掩码及 `onRebindVisibleTextureViews`，待回到记录仪页（`setSurroundHostEligible(true)`）再统一应用。
   - 修复 `MainActivity.applySlotMatrixTransform()` 在 Mode C/D 隐藏窗格 `width/height == 0` 时无限 `postDelayed(..., 80)` 空轮询问题（仅可见窗格且上限 15 次重试）。
4. **单元测试**：新增 [SingleCameraLifecycleTest.java](../../app/src/test/java/com/kooo/evcam/camera/SingleCameraLifecycleTest.java)（5 项：慢设备关机不阻塞主线程、快关快开 FIFO 串行、失效 `onOpened` 立即关闭拒收、`closeCameraAndAwait`、多路 B→D 主线程不阻塞）与 [PreviewModeControllerTest.java](../../app/src/test/java/com/kooo/evcam/ui/PreviewModeControllerTest.java) 掩码延后测试。

## 最近记录

- 2026-11-19 11:24:00 +08:00 | 复核离线测试与 Lint 准备封包 `build16` | 复跑 `.\tools\verify.ps1` 通过（`480` 测 `0` 失败 `0` 跳过，日志 `work/verification/build-20261119-112302-548.log`），双风味 Lint `0` errors（`2076` warnings，日志 `work/verification/lint-20261119-1124.log`），`apksigner` 与 `libvhal_decoder.so` 校验通过 | [build.gradle.kts](../../app/build.gradle.kts)
- 2026-11-19 09:20:00 +08:00 | 完成全量验证与 2 摄模拟器验收 | `1.6.6-test-11190906` 通过 `.\tools\verify.ps1`（`480` 测 `0` 失败 `0` 跳过、`build-20261119-091455-935.log`）、双风味 `Lint` `0` errors（`2076` warnings）、Debug/Release 编译；`emulator-5556` 验证空闲 `B/C/D/A` 切换、快速 `B↔D` 连点、`P019` 设置页切换不触相机直到返回记录仪、以及 17s 录制中 `B/C↔D` 切换（0 崩溃、0 `ERROR_CAMERA_IN_USE`、0 `Choreographer` 丢帧），并已关机清场 `work/tmp/mode-d-switch-stall/` | [SingleCamera.java](../../app/src/main/java/com/kooo/evcam/camera/SingleCamera.java) · [PreviewModeController.java](../../app/src/main/java/com/kooo/evcam/ui/PreviewModeController.java)
- 2026-11-19 09:07:30 +08:00 | 完成源码修复与定向单测 | `SingleCameraLifecycleTest`（5 测）+ `PreviewModeControllerTest`（6 测）全部通过 | [SingleCameraLifecycleTest.java](../../app/src/test/java/com/kooo/evcam/camera/SingleCameraLifecycleTest.java)

## 下一步

1. 安装 `outputs/` 中 **`build16`**（独立版 A / 共存版 B 先后覆盖，切勿并行），在银河 E5 实车验证四路相机同时关闭/重开（`B/C→D`、急速连点、录制中切换与转向联动）。
