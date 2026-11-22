# 新增 U 盘手动写入速度检测

最后更新：2026-11-22 08:55:30 +08:00
最后核对：2026-11-22 08:55:30 +08:00
任务/执行者：add-usb-speed-test / Antigravity
分支/工作树：codex/e5-recording-startup-fix / E:\GPT\EVCam
状态：已提交推送源码（`1.6.6-test-11220818`，尚未单独封包 Release 与实车测试）
当前结论：已在「记录仪 → 存储位置」下方新增「U盘写入速度检测」卡片，支持手动点击触发基于 `FileChannel.force(true)` 真实落盘同步的持续写速测算，并结合当前启用路数、分辨率、帧率与码率等级自动判定是否满足直接写入 / 中转写入要求，已随当前分支提交并推送至 `johnwyatt041-commits/EVCam`（`1.6.6-test-11220818`）。

## 已落地改动

1. **真实落盘测速与需求评估工具**（[UsbSpeedTester.java](../../app/src/main/java/com/kooo/evcam/UsbSpeedTester.java)）：
   - 按当前启用录制路数、实时/默认分辨率、画质缩放、目标帧率、码率等级与 A/B 合成模式估算总码率及建议写入速率：中转写入最低 `max(4.0 MB/s, 2×标称总码率)`，直写安全线 `max(10.0 MB/s, 3×标称总码率)`。
   - 在 `<U盘>/DCIM/.evcam_speed_test_*.tmp` 写入不可压缩伪随机数据（目标 `64 MB`、`2 MB/块`、`FileChannel.force(true)` 强制刷盘，低可用空间保护至少留 `32 MB` 余量，慢盘 `10s/15s` 软硬超时上限，结束后与取消时 `finally` 清理临时文件）。
   - 结合均速与最低分块写速输出分级结论：`MEETS_DIRECT`（支持直写/中转）、`RELAY_ONLY`（仅建议开启中转写入，含严重掉速波动降级）、`INSUFFICIENT`（持续写速不达标）及权限/只读/空间不足诊断。
2. **设置页交互与安全保护**（[fragment_recorder_settings.xml](../../app/src/main/res/layout/fragment_recorder_settings.xml)、[RecorderSettingsFragment.java](../../app/src/main/java/com/kooo/evcam/RecorderSettingsFragment.java)、[SettingsCatalog.java](../../app/src/main/java/com/kooo/evcam/ui/SettingsCatalog.java)）：
   - 在「中转写入模式」卡片下方新增「U盘写入速度检测」卡片，实时展示当前录制配置需求，点击「开始测速」后台异步测速并实时刷新进度与结果色标；若当前正在无中转直写 U 盘录像则先二次确认；退出页面在 `onDestroyView()` 自动取消测速并删临时文件；同步修复手动设置 U 盘路径后立即清缓存刷新。
3. **验证与版本（2026-11-22）**：
   - **已通过（离线）**：新增 [UsbSpeedTesterTest.java](../../app/src/test/java/com/kooo/evcam/UsbSpeedTesterTest.java)（`7` 项单测）；`tools/verify.ps1` 双风味 `638` 测 `0` 失败/`0` 跳过（`build-20261122-082035-915.log`），Debug/Release APK 编译通过，`lintIndependentDebug` 与 `lintCompositeDebug` 均为 `0` errors（`2225` warnings）。
   - **未执行**：尚未在银河 E5 实车插 U 盘验证不同规格 U 盘测速与实录对照。

## 最近记录

- 2026-11-22 08:55:30 +08:00 | 提交并推送 U 盘手动写入速度检测至 GitHub fork（`1.6.6-test-11220818`） | 新增 `UsbSpeedTester`、设置页「U盘写入速度检测」卡片与 `UsbSpeedTesterTest`（7测），提交并推送至 `johnwyatt041-commits/EVCam` `codex/e5-recording-startup-fix`；`verify.ps1` 双风味 `638` 测与 Lint `0` errors 全过，待封包与实车插 U 盘实测 | [UsbSpeedTester.java](../../app/src/main/java/com/kooo/evcam/UsbSpeedTester.java)

## 下一步

1. 在银河 E5 实车插入 U 盘，点击「设置 → 记录仪 → U盘写入速度检测 → 开始测速」，核对测速耗时、速度判定与直写/中转写入实际录制表现。
