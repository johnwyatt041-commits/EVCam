# P018 回看销毁保护与重建容器恢复修复

最后更新：2026-11-19 12:07:00 +08:00
最后核对：2026-11-19 12:07:00 +08:00
任务/执行者：fix-p018-playback-glide-destroy / Antigravity
分支/工作树：codex/e5-recording-startup-fix / E:\GPT\EVCam
状态：已完成离线验证、build17 封包与 emulator-5556 销毁重建实测（待实车验收）
当前结论：已修复 `PlaybackImageMemory.clearViews()` 在 `Activity.isDestroyed()` 时调用 `Glide.with()` 闪退（`P018`），同步加固 `playback/` 与相册适配器 Glide 加载/清理及 `AppShellController.restore()` 根容器显隐恢复；`1.6.6-test-11191152`（`versionCode = 84`）通过 `verify.ps1` `488` 测 `0` 失败、双风味 `0` Lint error 及 `emulator-5556` 分辨率/DPI 重建实测（`PID` 不变、0 崩溃）。

## 修复清单

1. **Glide 销毁态守卫与统一清理（[PlaybackImageMemory.java](../../app/src/main/java/com/kooo/evcam/playback/PlaybackImageMemory.java)）**：
   - 新增 `findActivity(Context)`（深度上限 16 + 环路防御）、`canClear(Context)`（`activity == null || !activity.isDestroyed()`）、`canLoad(Context)`（`!isDestroyed() && !isFinishing()`）与 `clearImageView(ImageView)`。
   - `clearViews(View)` 与 `clearImageView(ImageView)` 在宿主 `Activity` 已销毁时跳过 `Glide.with(...).clear(...)` 并捕获竞态 `IllegalArgumentException`，同时仍执行 `setImageDrawable(null)`；`clear(Context)` 判空保护。
2. **全量调用点与重建恢复加固**：
   - [PlaybackListViewHolders.java](../../app/src/main/java/com/kooo/evcam/playback/PlaybackListViewHolders.java)、[PhotoPlaybackFragmentNew.java](../../app/src/main/java/com/kooo/evcam/playback/PhotoPlaybackFragmentNew.java)、[PlaybackFragmentNew.java](../../app/src/main/java/com/kooo/evcam/playback/PlaybackFragmentNew.java)、[PlaybackHubFragment.java](../../app/src/main/java/com/kooo/evcam/playback/PlaybackHubFragment.java)、[PhotoGroupAdapter.java](../../app/src/main/java/com/kooo/evcam/playback/PhotoGroupAdapter.java)、[VideoGroupAdapter.java](../../app/src/main/java/com/kooo/evcam/playback/VideoGroupAdapter.java)、[PhotoAdapter.java](../../app/src/main/java/com/kooo/evcam/PhotoAdapter.java)、[VideoAdapter.java](../../app/src/main/java/com/kooo/evcam/VideoAdapter.java) 接入 `canLoad`/`clearImageView`/`clear(getContext())`。
   - [AppShellController.java](../../app/src/main/java/com/kooo/evcam/ui/AppShellController.java)：抽出 `applyRootContainerVisibility()`，修复 `restore(destination)` 重建后 `fragment_container` 保持 `GONE` 的问题。
3. **单元测试**：新增 [PlaybackImageMemoryTest.java](../../app/src/test/java/com/kooo/evcam/playback/PlaybackImageMemoryTest.java)（4 项用例）。

## 最近记录

- 2026-11-19 12:07:00 +08:00 | 完成 `emulator-5556` `wm size`/`density` 重建实测与清场 | `1.6.6-test-11191152`（剔除 `arm64-v8a` 的 Release 测试副本）实测在回看照片/视频页及切回记录仪隐藏 `PlaybackHubFragment` 后执行 `2560x1600@240` ↔ `reset`，`MainActivity` 窗口重建后 `PID=2966` 全程存活、`AndroidRuntime:E` 为 `0`、回看容器正常保留；已关闭模拟器与 `5039` 并清空 `work/tmp/fix-p018-playback-glide-destroy/` | [PlaybackImageMemory.java](../../app/src/main/java/com/kooo/evcam/playback/PlaybackImageMemory.java)
- 2026-11-19 11:59:00 +08:00 | 离线构建与双风味 Lint 通过 | `.\tools\verify.ps1` 通过（`488` 测 `0` 失败 `0` 跳过，日志 `work/verification/build-20261119-115902-723.log`），双风味 `Lint` `0` errors（`2076` warnings，日志 `work/verification/lint-20261119-1153.log`） | [PlaybackImageMemoryTest.java](../../app/src/test/java/com/kooo/evcam/playback/PlaybackImageMemoryTest.java)

## 下一步

1. 随 **`build17`** 一并同步至 `johnwyatt041-commits/EVCam` 并发布 Pre-release，待银河 E5 实车按总清单联合验收。
