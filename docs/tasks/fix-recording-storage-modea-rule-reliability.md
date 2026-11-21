# 修复多路录制启动、U盘中转与清理、模式A断联及压制规则忙等

最后更新：2026-11-21 23:47:07 +08:00
最后核对：2026-11-21 23:47:07 +08:00
任务/执行者：fix-recording-storage-modea-rule-reliability / Antigravity
分支/工作树：codex/e5-recording-startup-fix / E:\GPT\EVCam
状态：执行中（已完成源码与双风味 `624` 单测、Debug/Release 构建与 Lint `0` errors，尚未提交与实车测试）
当前结论：6 项报告问题均在代码核查中确认存在并完成修复（`1.6.6-test-11212325`），离线编译、单测与双风味 Lint 全部通过；待银河 E5 实车验证多路录像、U 盘中转/断插恢复、启动 `.pending` 提示与模式 A 长时间预览稳定性。

## 根因核实与已落地改动

1. **问题 1（多路录像启动 watchdog 误取消与局部失败漏报）**：
   - [RecordingStartupTracker.java](../../app/src/main/java/com/kooo/evcam/camera/RecordingStartupTracker.java)、[MultiCameraManager.java](../../app/src/main/java/com/kooo/evcam/camera/MultiCameraManager.java)、[MainActivity.java](../../app/src/main/java/com/kooo/evcam/MainActivity.java)：按预期摄像头键集跟踪首帧写入与终态错误，不再因单路写入即取消整体 20s 看门狗或屏蔽后续各路报错；Codec 启动及重准备补齐 `setCallback` 并统计失败摄像头；部分失败停止坏路并向 UI 限频提示（10s 冷却），全失败才整体中止。单测：[RecordingStartupTrackerTest.java](../../app/src/test/java/com/kooo/evcam/camera/RecordingStartupTrackerTest.java)。
2. **问题 2（U 盘中转 1 小时盲删未落盘临时文件）**：
   - [FileTransferManager.java](../../app/src/main/java/com/kooo/evcam/FileTransferManager.java)：替换原仅按修改时间 >1h 盲删的 `cleanupExpiredTempFiles()`；改为核验真实可写目标目录中同名且大小匹配（`>= MIN_VALID_FILE_SIZE`）后才删，未落盘文件在目标可用时自动去重补传、目标不可用时继续保留；保护队列/传输中/`.pending` 活跃文件；跨盘拷贝改为 `.part` + `sync()` + 原子重命名，仅在严重低空间紧急淘汰时清理最旧未排队/非活跃缓存。
3. **问题 3（`.pending` 活跃与崩溃遗留混淆）**：
   - [ActiveRecordingRegistry.java](../../app/src/main/java/com/kooo/evcam/camera/ActiveRecordingRegistry.java)、[AsyncMuxerWriter.java](../../app/src/main/java/com/kooo/evcam/camera/AsyncMuxerWriter.java)、[VideoLockStore.java](../../app/src/main/java/com/kooo/evcam/playback/VideoLockStore.java)、[PlaybackTimelineBuilder.java](../../app/src/main/java/com/kooo/evcam/playback/PlaybackTimelineBuilder.java)、[PendingFileRecoveryManager.java](../../app/src/main/java/com/kooo/evcam/PendingFileRecoveryManager.java)、[MainActivity.java](../../app/src/main/java/com/kooo/evcam/MainActivity.java)：在进程内精确登记活跃 `Muxer` 正在写入的 `.mp4.pending` 路径并配合宽限窗口；启动异步扫描遗留 `.mp4.pending`（自动删除极小空壳、按 ISO BMFF Box 完整性 + `MediaExtractor` 尝试重命名恢复可读 MP4、不可恢复项弹窗提示一键清理），同时允许清理器回收孤立 `.pending`。单测：[PendingFileRecoveryAndCleanupTest.java](../../app/src/test/java/com/kooo/evcam/PendingFileRecoveryAndCleanupTest.java)。
4. **问题 4（分段完成空间检测、动态写入速率预留与 O(1) 性能优化）**：
   - [StorageSpaceGuard.java](../../app/src/main/java/com/kooo/evcam/StorageSpaceGuard.java)、[StorageCleanupManager.java](../../app/src/main/java/com/kooo/evcam/StorageCleanupManager.java)、[MultiCameraManager.java](../../app/src/main/java/com/kooo/evcam/camera/MultiCameraManager.java)：在三路录制模式的 `onSegmentSwitch` 触发后台低优先级合并检测（2s 防抖 + 15s 最小间隔）；基于滚动窗口实际写入速率（5~15 分钟缓冲下限）动态预留空间并同步守护 U 盘中转内部缓存；正常余量期通过 `StatFs` + 15 分钟目录大小缓存执行 O(1) 快速门控跳过全目录 `listFiles()`。单测：[StorageSpaceGuardTest.java](../../app/src/test/java/com/kooo/evcam/StorageSpaceGuardTest.java)、[StorageCleanupLockProtectionTest.java](../../app/src/test/java/com/kooo/evcam/StorageCleanupLockProtectionTest.java)。
5. **问题 5（模式 A 局部摄像头断联/卡住）**：
   - [SingleCamera.java](../../app/src/main/java/com/kooo/evcam/camera/SingleCamera.java)、[MultiCameraManager.java](../../app/src/main/java/com/kooo/evcam/camera/MultiCameraManager.java)、[SurroundGlRenderer.java](../../app/src/main/java/com/kooo/evcam/surround/SurroundGlRenderer.java)、[SurroundCameraWatchdog.java](../../app/src/main/java/com/kooo/evcam/surround/SurroundCameraWatchdog.java)、[SurroundModeAView.java](../../app/src/main/java/com/kooo/evcam/surround/SurroundModeAView.java)：修复关闭兜底回调遇 `isSessionClosing` 死等、`outputConfigs.isEmpty()` 漏清标志、`onDisconnected`/`onError`/`forceReopen` 残留会话标志、API 28 动态更新误用 `finalizeOutputConfigurations`、`onConfigured`/`onConfigureFailed` 异常断点及模式 A 两次重建竞争；Renderer 改用 `AtomicBoolean` + 独立 `SurroundOesCb` 线程与每路 OES 心跳时间戳；Mode A 新增 2s 看门狗按路重绑/重建（录像中永不 `forceReopen`）。单测：[SingleCameraLifecycleTest.java](../../app/src/test/java/com/kooo/evcam/camera/SingleCameraLifecycleTest.java)、[SurroundCameraWatchdogTest.java](../../app/src/test/java/com/kooo/evcam/surround/SurroundCameraWatchdogTest.java)。
6. **问题 6（被压制的到期延迟规则导致 20ms UI 忙等）**：
   - [VehicleRuleEngine.java](../../app/src/main/java/com/kooo/evcam/vehicle/rule/VehicleRuleEngine.java)、[VehicleAutomationCoordinator.java](../../app/src/main/java/com/kooo/evcam/vehicle/VehicleAutomationCoordinator.java)：在 `RuleRuntime` 增设 `suppressed` 状态，`arbitrateGroup` 标记被压制规则并在 `getNextDelayDueMs` 中跳过，避免状态未变化时返回 `0ms` 触发 20ms 主线程循环；重载规则后立即重调度计时器。单测：[VehicleRuleEngineTest.java](../../app/src/test/java/com/kooo/evcam/vehicle/rule/VehicleRuleEngineTest.java)。
7. **验证结果（2026-11-21）**：
   - **已通过（离线）**：`.\tools\verify.ps1` 双风味 `624` 项单测 `0` 失败/`0` 错误/`0` 跳过（`build-20261121-234347-398.log`），Debug 与 Release APK 构建通过；双风味 Lint `0` errors（`2220` warnings）。
   - **未执行**：尚未在银河 E5 实车验证多路并发异常、U 盘慢盘/热拔插中转恢复、遗留 `.pending` 启动恢复弹窗、满盘自动清理触发及模式 A 长时卡顿自愈效果。

## 最近记录

- 2026-11-21 23:41:10 +08:00 | 完成 6 项可靠性与性能修复及离线全量验证（`1.6.6-test-11212325`，未提交/未封包） | 全部 6 项问题均核实并修复，补充纯 Java 与 Robolectric-free 单元测试，双风味 `624` 测、`assembleDebug`/`assembleRelease` 与 Lint `0` errors 全过，待实车验证 | [StorageCleanupManager.java](../../app/src/main/java/com/kooo/evcam/StorageCleanupManager.java)

## 下一步

1. 在银河 E5 实车验证：单路断开/缺卡故障下多路录像提示与其余路续录、U 盘拔插/恢复补传、崩溃遗留 `.pending` 恢复弹窗、高码率分段写入动态清理与模式 A 长时间切换/单路断联自动恢复。
