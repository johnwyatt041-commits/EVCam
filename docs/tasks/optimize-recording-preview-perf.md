# 录像与预览性能优化

最后更新：2026-11-23 10:39:05 +08:00
最后核对：2026-11-23 10:39:05 +08:00
任务/执行者：optimize-recording-preview-perf / Antigravity
分支/工作树：codex/e5-recording-startup-fix / E:\GPT\EVCam
状态：已完成 **批次 A (`A1–A4`)**、**批次 B (`B1–B3`)** 与 **批次 C (`C1–C4`)** 合流提交与 **`build26`** GitHub Pre-release 发布（[`v1.6.6-test-11230919-build26`](https://github.com/johnwyatt041-commits/EVCam/releases/tag/v1.6.6-test-11230919-build26) / 源码 `2d99f75`，`395` 测与 Lint `0` errors；待实车验证）
当前结论：批次 A/B/C 已合流提交 `2d99f75` 并打包发布单 A 版 `build26`（`1.6.6-test-11230919`），通过 `395` 测与 Lint `0` errors（`2243` warnings）及 5 资产远端 SHA256 校验；待银河 E5 实车复测并由用户关闭外部 D 挡脚本。

## 已修复（`build24` / `9f90c63` 已合入 10 项）

- **录像 1~5**：去 Release `glGetError()`/冗余 `eglMakeCurrent()`；异步分段/退避与 HEVC 码率修复；水印零分配与中转降优。
- **预览 6~10**：Mode A VBO/IBO、`33ms` 合并限频与 Shader 减负；隐藏停刷计时；去默认圆角与冗余 Outline/`setOpaque`。

## 分批整改清单（A/B/C 已离线完成；D 后置）

- [x] **批次 A（`1.6.6-test-11230123`）**：**A1** `shouldShortCircuitStartRecordingIntent` 短路；**A2** `VehicleTriggerRule`/`VehicleRuleEngine` `setRules` 保留未变规则状态；**A3** `VhalSignalObserver` `stop`/`stopAsync` 静默退出；**A4** `CodecVideoRecorder` `direct` 路径统计编码输出帧数。
- [x] **批次 B（`1.6.6-test-11230150`）**：**B1** `SurroundModeAView` `suppressRebindInSurfacesReadyCallback`+`250L` 释放与 `SingleCamera` 会话去重/删 `200ms` 匿名重建/校验 `closingSession`；**B2** 延迟 `60ms`/`80ms` 移除 `-16` 且不断录不弃主；**B3** [OverlayAvoidancePolicy.java](../../app/src/main/java/com/kooo/evcam/service/OverlayAvoidancePolicy.java)+[AvmForegroundDetector.java](../../app/src/main/java/com/kooo/evcam/service/AvmForegroundDetector.java) 三保险倒挡/AVM 避让与 Mode A `View.GONE`。
- [x] **批次 C（`1.6.6-test-11230919`）**：**C1** [PlaybackFragmentNew.java](../../app/src/main/java/com/kooo/evcam/playback/PlaybackFragmentNew.java)+[PhotoPlaybackFragmentNew.java](../../app/src/main/java/com/kooo/evcam/playback/PhotoPlaybackFragmentNew.java) 异步扫盘+代次防抖+加载态（全 8 布局同步 `list_loading_view`）；**C2** [PlaybackTimelineBuilder.java](../../app/src/main/java/com/kooo/evcam/playback/PlaybackTimelineBuilder.java)+[VideoLockStore.java](../../app/src/main/java/com/kooo/evcam/playback/VideoLockStore.java) 滤 `.`/`.trashed-`/`trashed-` 并内存 Set O(1) 判锁；**C3** [PlaybackThumbnailGuard.java](../../app/src/main/java/com/kooo/evcam/playback/PlaybackThumbnailGuard.java)+[ExpandableVideoGroupAdapter.java](../../app/src/main/java/com/kooo/evcam/playback/ExpandableVideoGroupAdapter.java)+[PlaybackListViewHolders.java](../../app/src/main/java/com/kooo/evcam/playback/PlaybackListViewHolders.java)+[VideoGroup.java](../../app/src/main/java/com/kooo/evcam/playback/VideoGroup.java) 每组 1 张已录主路代表图（不显示未录视角，并发=2/快滑暂停/LRU 负缓存）；**C4** [PendingFileRecoveryManager.java](../../app/src/main/java/com/kooo/evcam/PendingFileRecoveryManager.java)+[FileTransferManager.java](../../app/src/main/java/com/kooo/evcam/FileTransferManager.java)+[FragmentCleanupCoordinator.java](../../app/src/main/java/com/kooo/evcam/FragmentCleanupCoordinator.java) 接入 [PlaybackHubFragment.java](../../app/src/main/java/com/kooo/evcam/playback/PlaybackHubFragment.java)/[RecorderSettingsFragment.java](../../app/src/main/java/com/kooo/evcam/RecorderSettingsFragment.java) 安全手动清理碎片。
- [ ] **批次 D（外部/后置）**：用户关闭车机 D 挡外部 `start_recording` 脚本；**L2**（预览降尺寸）留作 A+B+C 实测后按需评估，`L1`/`L3` 暂不做。

## 验证与证据（2026-11-23 10:39:05 +08:00）

- **批次 C / `build26` 离线验证（`1.6.6-test-11230919`）**：新增 `6` 测（[PlaybackThumbnailGuardTest.java](../../app/src/test/java/com/kooo/evcam/playback/PlaybackThumbnailGuardTest.java) `3` 测、[PlaybackTimelineAndLockTest.java](../../app/src/test/java/com/kooo/evcam/playback/PlaybackTimelineAndLockTest.java) +`2` 测、[PendingFileRecoveryAndCleanupTest.java](../../app/src/test/java/com/kooo/evcam/PendingFileRecoveryAndCleanupTest.java) +`1` 测）；`.\tools\verify.ps1` `395` 测全过（`0` 败/错/跳，`work/verification/build-20261123-092103-954.log`），单 A Debug/Release 通过，`:app:lintIndependentDebug` `0` errors（`2243` warnings）。
- **批次 A/B 验证**：批次 A `380` 测、批次 B `389` 测（`build-20261123-015021-869.log`）与 Lint `0` errors 通过。
- **实车基线（`build25` / `006ebb5`）**：`work/optimize-recording-preview-perf/evcam_log_20261123_001841.txt`（总加权 `19.23fps`，稳态 `24.0~24.82`）。

## 最近记录

- 2026-11-23 10:39:05 +08:00 | 封包发布 `build26` 至 GitHub Pre-release（`2d99f75` / `1.6.6-test-11230919`） | 单 A 版 `5` 资产远端 SHA256 核验一致、`build24` 归档，含批次 A/B/C 修复与 `395` 测及 Lint `0` errors | [notes](../../outputs/archive/EVCam-E5-build26-1.6.6-test-11230919-notes.md)
- 2026-11-23 09:26:36 +08:00 | 完成批次 C (`C1~C4`) 离线修复与单 A 版验证（`1.6.6-test-11230919`） | 异步扫盘加载态、滤隐藏/回收站与内存 Set O(1) 判锁、每组 1 张已录主路缩略图含限流负缓存、双入口安全清理碎片；`verify.ps1` `395` 测与 Lint `0` errors 通过 | [STATUS.md](../STATUS.md)
- 2026-11-23 01:53:49 +08:00 | 完成批次 B (`B1~B3`) 离线修复与单 A 版验证（`1.6.6-test-11230150`） | 修 Mode A 双绑定/`200ms` 重建/`onClosed` 错清、`-16` 延迟移除不断录、三保险 R 挡/AVM 避让；`verify.ps1` `389` 测与 Lint `0` errors 通过 | [STATUS.md](../STATUS.md)
- 2026-11-23 01:28:05 +08:00 | 完成批次 A (`A1~A4`) 离线修复与单 A 版验证（`1.6.6-test-11230123`） | 修 `start_recording` 短路、规则保留、VHAL `stopAsync` 静默、直连帧数日志；`verify.ps1` `380` 测与 Lint `0` errors 通过 | [STATUS.md](../STATUS.md)
- 2026-11-23 01:05:26 +08:00 | 整理 `build25` 分批整改清单（A/B/C+D）并确认车机外部 D 挡脚本（`1.6.6-test-11222113`） | 核实存在未关 D 挡外部 `start_recording` 脚本，拆分批次 A/B/C+D | [STATUS.md](../STATUS.md)

## 下一步

1. 在银河 E5 实车安装 `build26` 验证批次 A+B+C（用户同步关闭外部 D 挡脚本），实测后再评估 **L2**。
