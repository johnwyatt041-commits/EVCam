# 包 C：补盲去动效、设置平铺与 2560×1600 可视化/分路调整

最后更新：2026-11-18 18:48:14 +08:00
最后核对：2026-11-18 18:48:14 +08:00
任务/执行者：fix-pack-c-blindspot-refactor / Antigravity
分支/工作树：codex/e5-recording-startup-fix（串行合入，基于 7d03610 + 包 A/B 未提交工作区）/ E:\GPT\EVCam
状态：本地单元测试、Lint 与 emulator-5556 双分辨率验收已通过，待银河 E5 实车 EVCC/VHAL 实测
当前结论：已完成 `4.1` 去状态栏动效与 `4.2` 补盲设置平铺、2560×1600 可视化拖拽及分路旋转/四边裁切重构；保留 ESP32/浏览器投屏（`NETWORK_STREAMING = true`）与关闭鱼眼（`FISHEYE_CORRECTION = false`）。

## 已完成实现与修复要点

1. **`4.1` 移除补盲状态栏动效**：
   - 删除 `BlindSpotStatusBarView.java`、`ColorPickerView.java` 及浮窗/副屏 XML 中的 `blind_spot_status_bar`；同步清理 `BlindSpotFloatingWindowView`、`MainFloatingWindowView`、`BlindSpotService`、`AppConfig`、`E5ConfigSchema`、`AndroidE5ConfigTarget` 与 `e5_low_power_smooth.json` 中状态栏样式/颜色/透明度配置（顺带修复浮窗 `updateWindowLayoutSafely()` 递归调用缺陷）。
2. **`4.2` 补盲设置平铺、2560×1600 可视化拖拽与分路矫正**：
   - 新增纯 Java 模型 [BlindSpotPlacement.java](../../app/src/main/java/com/kooo/evcam/blindspot/BlindSpotPlacement.java)、[BlindSpotCorrectionMath.java](../../app/src/main/java/com/kooo/evcam/blindspot/BlindSpotCorrectionMath.java)、画布 [BlindSpotVisualEditorView.java](../../app/src/main/java/com/kooo/evcam/blindspot/BlindSpotVisualEditorView.java)、裁切预览 [BlindSpotCropPreviewView.java](../../app/src/main/java/com/kooo/evcam/blindspot/BlindSpotCropPreviewView.java) 及屏幕/胶囊工具类。
   - 重构 `BlindSpotSettingsFragment` 与 `fragment_secondary_display_settings.xml`：顶部直置全局总开关与屏幕选择（主屏 / 副屏 / EVCC 仪表），紧跟触发方案与消失延迟，下方平铺 6 槽位（`前/后/左/右/雨夜左/雨夜右`）16:10 拖拽缩放画布、`左右使用同一位置`、多分辨率数值 `X/Y/W/H` 微调，并同步作用于主屏独立/复用补盲弹窗与穿透位置预览；修复返回栈 `onViewStateRestored` 覆盖 `blind_spot_correction_enabled` 的状态恢复问题。
   - 重构 `BlindSpotCorrectionFragment` 与 `BlindSpotCorrection.java`：每路支持独立 `0°–359°` 等比防切变旋转与 `上/下/左/右` 百分比四边裁切（不影响记录仪预览和录像，无鱼眼），保留物理/输出端解耦镜像与 `MjpegStreamAdjustFragment`（ESP32/浏览器投屏）。

## 关联单测与验收边界

- JVM 单测：[BlindSpotPlacementAndCorrectionTest.java](../../app/src/test/java/com/kooo/evcam/blindspot/BlindSpotPlacementAndCorrectionTest.java)（6 项新测）+ 原有全量双风味共 460 项单测 `0` 失败，双风味 Lint `0` errors。
- `emulator-5556`（剔除 `arm64-v8a` 重签临时测试副本，已在结束前删除且停止模拟器）：`1600×900` 与 `2560×1600`、深/浅色主题重绘、画布 16:10 (`744×465`) 拖拽缩放、`左右同位` 与 4 边裁切持久化、位置浮窗预览及零崩溃验证通过；实车 EVCC 物理屏 / VHAL 信号 / 四路实体视频流待实车联测。

## 最近记录

- 2026-11-18 18:48:14 +08:00 | 包 C 完成并验证 | 完成 `4.1` 去动效与 `4.2` 补盲扁平设置、2560×1600 拖拽、分方向旋转/四边裁切与 `AppConfig`/浮窗联动（`1.6.6-test-11181843`，保留 ESP32 不启鱼眼） | [fix-pack-c-blindspot-refactor.md](fix-pack-c-blindspot-refactor.md)
- 2026-11-18 15:40:00 +08:00 | 建立包 C 规格 | 落盘 `4.1` 去动效与 `4.2` 补盲设置平铺、2560×1600 可视化编辑器、分路调整规格，并确认保留 ESP32/浏览器投屏不变 | [fix-pack-c-blindspot-refactor.md](fix-pack-c-blindspot-refactor.md)

## 下一步

1. 继续推进 [包 D（设置与水印）](fix-pack-d-recorder-settings-and-watermark.md) 与 [包 E（车机缩放收口）](fix-pack-e-headunit-ui-scaling.md)。
2. 择机在银河 E5 实车验证 VHAL 转向灯联动、EVCC 仪表副屏、四边裁切实景帧画面及独立/复用弹窗切换。
