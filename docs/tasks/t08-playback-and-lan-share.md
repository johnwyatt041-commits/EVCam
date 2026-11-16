# 任务记录：T08 回看重构与局域网浏览器分享（build10）

- 最后更新：2026-11-16 23:24:00 +08:00（Asia/Hong_Kong）
- 最后核对：2026-11-16 23:24:00 +08:00（Asia/Hong_Kong）
- 任务/执行者：`t08-playback-and-lan-share` / Antigravity
- 分支/工作树：`codex/e5-recording-startup-fix` / `E:\GPT\EVCam`（源码 `13e2f59`，Tag `v1.6.6-test-11162313-build10`）
- 状态：已完成代码、`build10` 双风味 Release(R8) 打包与自检（离线通过，**未上车实测**）

## 实施摘要（仅限 T08，未改 T09–T10，保留 T01–T07 主链路）

1. **回看时间线、`.lock` 保护与 B 版单视角放大**：
   - 新增 [PlaybackTimelineBuilder.java](../../app/src/main/java/com/kooo/evcam/playback/PlaybackTimelineBuilder.java)、[VideoLockStore.java](../../app/src/main/java/com/kooo/evcam/playback/VideoLockStore.java)、[PlaybackListViewHolders.java](../../app/src/main/java/com/kooo/evcam/playback/PlaybackListViewHolders.java) 与 [PlaybackThemeAndShareHelper.java](../../app/src/main/java/com/kooo/evcam/playback/PlaybackThemeAndShareHelper.java)：重构日期+小时（`HH:00 · N 段/组`）列表、`全部/今日/已锁定` 筛选、单线程懒加载时长、`320×200` Glide 降采样与 `.pending` 录制中标牌；横屏列表栏扩至 `280dp`，全回看及弹窗接入 `ThemeAware` 与 `Theme_Cam_MaterialAlertDialog`；修复 `VideoGroup.getShareableFiles()` 漏收 `POSITION_COMBINED` 问题；[StorageCleanupManager.java](../../app/src/main/java/com/kooo/evcam/StorageCleanupManager.java) 自动与强清跳过 `.lock` 媒体与 `.pending` 并清孤儿 `.lock`。
   - 新增 [CompositeQuadrantMath.java](../../app/src/main/java/com/kooo/evcam/playback/CompositeQuadrantMath.java) 与 [CompositeQuadrantTextureController.java](../../app/src/main/java/com/kooo/evcam/playback/CompositeQuadrantTextureController.java)：B 版 `_combined.mp4` 启用 `TextureView` 单解码器 `2×2`/单路象限放大切换，A 版保留 [MultiVideoPlayerManager.java](../../app/src/main/java/com/kooo/evcam/playback/MultiVideoPlayerManager.java) 四路同步架构不变。
2. **局域网 HTTP Range 服务化分享与生命周期收尾**：
   - 新增 [LanShareService.java](../../app/src/main/java/com/kooo/evcam/transfer/LanShareService.java)（`dataSync` 前台服务与 `AppExitCoordinator` 退出停服）、[HttpRangeParser.java](../../app/src/main/java/com/kooo/evcam/transfer/HttpRangeParser.java)、[BoundedRafInputStream.java](../../app/src/main/java/com/kooo/evcam/transfer/BoundedRafInputStream.java)、[LanPathGuard.java](../../app/src/main/java/com/kooo/evcam/transfer/LanPathGuard.java)、[LanNetworkSelector.java](../../app/src/main/java/com/kooo/evcam/transfer/LanNetworkSelector.java)、[LanIdleWatchdog.java](../../app/src/main/java/com/kooo/evcam/transfer/LanIdleWatchdog.java)、[BoundedAsyncRunner.java](../../app/src/main/java/com/kooo/evcam/transfer/BoundedAsyncRunner.java) 与 [LanWebAssetHtml.java](../../app/src/main/java/com/kooo/evcam/transfer/LanWebAssetHtml.java)，改造 [FileTransferServer.java](../../app/src/main/java/com/kooo/evcam/transfer/FileTransferServer.java)、[QrTransferDialog.java](../../app/src/main/java/com/kooo/evcam/transfer/QrTransferDialog.java) 与 [PlaybackHubFragment.java](../../app/src/main/java/com/kooo/evcam/playback/PlaybackHubFragment.java)。
   - 支持全库浏览器分享与精选扫码、16 位 Token 前缀隔离、规范路径/防符号链接、HTTP `206`/`416` Seek、`Semaphore(2)` `/t/` 与 `/info/` 限流、RFC1918 网卡排序重建及 `60s` 空闲自动关停。

## 离线验证与 `build10` 交付（`13e2f59`）

- **单元测试**：A/B 共 `366` 测（每版 `183`，`0` 失败/错误/跳过），含 `HttpRangeParserTest`(4)、`LanPathGuardAndNetworkTest`(3)、`LanIdleWatchdogTest`(1)、`FileTransferServerLoopbackTest`(2)、`PlaybackTimelineAndLockTest`(3)、`CompositeQuadrantMathTest`(2)、`StorageCleanupLockProtectionTest`(1) 及原有全部回看/录制测试。
- **Lint 与校验**：`:app:lintIndependentDebug --offline` 保持 `19` baseline 错误（`0` 新增、`1988` warnings）；Release R8 + `apksigner`（Cert `a40da8...1bf5dc`）+ `libvhal_decoder.so`（`8408`B，`243a92bc...`）通过。
- **`build10` 交付物**：
  - `EVCam-E5-A-Independent-1.6.6-test-11162313-build10-release.apk`（`6,680,359`B，SHA256 `057ad1...8411`）
  - `EVCam-E5-B-Composite-1.6.6-test-11162313-build10-release.apk`（`6,696,743`B，SHA256 `c483f0...cccf`）
  - `outputs/`: [manifest.json](../../outputs/EVCam-E5-build10-1.6.6-test-11162313-manifest.json)、[notes.md](../../outputs/EVCam-E5-build10-1.6.6-test-11162313-notes.md)、[SHA256.txt](../../outputs/EVCam-E5-build10-1.6.6-test-11162313-SHA256.txt)、`mappings.zip`（`12,374,916`B）。

## 实车待验项与下一步

1. E5 实车验证热点/Wi-Fi 下手机浏览器在线拖动 `<video>`（`206`）、下载保存、B 版象限放大方位映射及 `LanShareService` 通知行为。
2. 下一步可基于 `build10` 启动 **`T09`**（银河 E5 离线配置广场与 `13.` 系统权限面板）或先联合实车验收 `T01`–`T08`。
