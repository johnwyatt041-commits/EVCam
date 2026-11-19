# 4.3 实时标定与视野调校预览

最后更新：2026-11-19 16:10:00 +08:00
最后核对：2026-11-19 16:10:00 +08:00
任务/执行者：surround-calibration-live-preview / Antigravity
分支/工作树：codex/e5-recording-startup-fix / E:\GPT\EVCam
状态：已随 `build19` 提交发布（源码 `9d2dc5c`，tag `v1.6.6-test-11191602-build19`；离线 `512` 测与双风味构建/Lint 通过，待实车实测）
当前结论：在「360 画面 → 4.3」新增「实时预览调校」二级双栏子页，调节标定方案、地面宽高/延伸、引导线与车模可下一帧实时生效；退出/切换/退后台即按自身 Surface 引用解绑并销毁 GL 预览与闲置相机，不影响正在进行的录像。已与 `build18`（`3258fe0`）及星舰7默认标定合流提交 `9d2dc5c`（`1.6.6-test-11191602`，`versionCode = 86`），通过双风味 `512` 测、Debug/Release 打包及 `0` Lint error，并发布至 GitHub Pre-release `v1.6.6-test-11191602-build19`（6 份资产 SHA256 回读一致），尚未上车实测。

## 改动清单

1. **实时双栏调校子页与共用导入助手**：
   - [fragment_surround_settings.xml](../../app/src/main/res/layout/fragment_surround_settings.xml)、[SurroundSettingsFragment.java](../../app/src/main/java/com/kooo/evcam/ui/SurroundSettingsFragment.java)：4.3 卡片新增「实时预览调校」按钮打开子页，并将本地校准/PNG 车模弹窗与 SAF 导入抽取到 [SurroundAssetImportHelper.java](../../app/src/main/java/com/kooo/evcam/ui/SurroundAssetImportHelper.java)。
   - [fragment_surround_calibration_live.xml](../../app/src/main/res/layout/fragment_surround_calibration_live.xml)、[SurroundCalibrationLiveFragment.java](../../app/src/main/java/com/kooo/evcam/ui/SurroundCalibrationLiveFragment.java)：左栏实时预览（支持「鸟瞰大图 / 完整模式 A」），右栏支持内置/本地校准切换、地面宽度/长度/车头延伸滑块与步进微调、引导线与车模切换及恢复默认。
2. **按需预览容器与精确退场回收**：
   - [SurroundCalibrationPreviewView.java](../../app/src/main/java/com/kooo/evcam/surround/SurroundCalibrationPreviewView.java)：进入 `onStart()` 按需创建 GL 上下文与 4 路 OES Surface；`onStop()` / `onDestroyView()` / `onDetachedFromWindow()` 立即停止渲染、退订转向/档位监听并释放资源。
   - [SingleCamera.java](../../app/src/main/java/com/kooo/evcam/camera/SingleCamera.java)、[MultiCameraManager.java](../../app/src/main/java/com/kooo/evcam/camera/MultiCameraManager.java)：新增 `clearSurroundPreviewSurfaceIfMatching()`，仅解绑调校页自身持有的 Surface 引用（不误清切回主界面模式 A 新建的预览），并触发 `closeIfUnused()` 自动关闭临时打开的前摄或闲置相机。
   - [SurroundLivePreviewHost.java](../../app/src/main/java/com/kooo/evcam/ui/SurroundLivePreviewHost.java)、[MainActivity.java](../../app/src/main/java/com/kooo/evcam/MainActivity.java)：提供权限校验与按需获取 `MultiCameraManager` 接口（未初始化时通过 `CameraManagerHolder.getOrInit` 获取并保持关闭主预览）。
3. **纯鸟瞰视口与滑块拖动缓存优化**：
   - [SurroundLayoutCalculator.java](../../app/src/main/java/com/kooo/evcam/surround/SurroundLayoutCalculator.java)：新增 `calculateBirdOnlyBox()` 按地面物理宽高比居中缩放鸟瞰区。
   - [SurroundGlRenderer.java](../../app/src/main/java/com/kooo/evcam/surround/SurroundGlRenderer.java)：支持 `birdOnlyPreview` 与 `markStopping()` 防竞态保护；基于车模/校准文件时间戳签名去重并按各自参数键缓存引导线与网格，拖动滑块仅重算地面网格，避免重复解码 PNG 或读盘 JSON。
4. **单元测试**：[SurroundLayoutCalculatorTest.java](../../app/src/test/java/com/kooo/evcam/surround/SurroundLayoutCalculatorTest.java)、[SingleCameraResourceTest.java](../../app/src/test/java/com/kooo/evcam/camera/SingleCameraResourceTest.java) 各新增 1 项验证用例。

## 最近记录

- 2026-11-19 16:10:00 +08:00 | 随 `build19` 封包提交并发布 GitHub Pre-release | 源码 `9d2dc5c` 打标 `v1.6.6-test-11191602-build19` 推送远端 fork，GitHub Pre-release 6 份交付资产（含 `A-Independent`/`B-Composite` Release APK 与 `mappings.zip`）大小与 SHA256 核验一致，待实车实测 | [星舰7默认标定](default-xingjian7-calibration.md)
- 2026-11-19 16:06:00 +08:00 | 合流 `build18` 与星舰7默认标定准备 `build19` | 与 `3258fe0`（`build18`）及 `default-xingjian7-calibration` 无冲突合流至 `1.6.6-test-11191602`（`versionCode = 86`），通过 `verify.ps1` `512` 测（`0` 失败）与双风味 `0` Lint error（待实车验证） | [星舰7默认标定](default-xingjian7-calibration.md)
- 2026-11-19 13:12:02 +08:00 | 完成实现、构建、全量单测与双风味 Lint | 双风味 `492` 测 `0` 失败 `0` 跳过、`assembleDebug` & `assembleRelease` 通过、双风味 `lint` `0` error（`2110` warnings）；`verify.ps1` 在 PS 5.1 因 `javac` stderr note 中断，已改用 `cmd /c` 全量验证并清理残余日志 | [SurroundCalibrationLiveFragment.java](../../app/src/main/java/com/kooo/evcam/ui/SurroundCalibrationLiveFragment.java)

## 下一步

1. 实车安装新包验证在日常三路录像/主预览关闭/模式 A 切换下进入与退出「4.3 实时预览调校」的画面联动、四路帧率与前摄自动关闭表现。
