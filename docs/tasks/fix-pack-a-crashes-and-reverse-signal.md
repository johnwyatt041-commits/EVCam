# 包 A 修复：回放闪退、360 模式 A 闪退与倒车 VHAL 信号

最后更新：2026-11-18 13:10:00 +08:00
最后核对：2026-11-18 13:10:00 +08:00
任务/执行者：fix-pack-a-crashes-and-reverse-signal / Antigravity
分支/工作树：codex/e5-recording-startup-fix / E:\GPT\EVCam
状态：等待实测
当前结论：包 A（`2.1` / `1.2` / `1.4`）代码、单元测试（`440` 测通过）与 `emulator-5556`（0 路/2 路模拟相机）全链路界面切换验收已完成且零崩溃；`1.4` 实车 VHAL `127.0.0.1:40004` 倒车流待银河 E5 上车实测。

## 任务范围（包 A 红线）

1. `2.1` 开启预览后进回放（视频/照片）闪退
2. `1.2` 开启预览后进 `360 A` 闪退（仅修稳定性与出图，不改排版）
3. `1.4` 车上未检测到倒车信号（`VehicleAutomationCoordinator` / `VhalProtoBatchReader` / `CoreVehicleSignalDecoder` / 诊断日志与单测）

禁止扩改：主预览布局/模式 D（包 B）、补盲重构（包 C）、设置开关/水印（包 D）、全局缩放（包 E）；保持鱼眼关闭、ESP32/浏览器投屏不变，保持 `HEAD` (`7d03610` / `904e500`) 既有 `W1` 与目录整理改动完好。

## 修复与验证边界

- **`2.1` 回放闪退（已修复并经模拟器验证）**：`layout-sw600dp` / `layout-port` / `layout-sw600dp-port` 下视频与照片回放布局补齐 `filter_bar`/`btn_filter_all`/`btn_filter_today`/`btn_filter_locked`/`btn_lock_toggle` 与 `texture_single`；`CompositeQuadrantTextureController`、`PlaybackFragmentNew`、`PhotoPlaybackFragmentNew` 补 `@Nullable` 与判空防御。
- **`1.2` 360 A 闪退（已修复并经 0/2 路模拟器验证）**：`SurroundGlRenderer` 移出构造期 `requestRenderSafe()`，仅在 `glView.setRenderer()` + `markRendererAttached()` 后触发并增加着色器/纹理清理防御；`PreviewModeController` 进入 `MODE_A` 时先保留常规输出直到底层 GL 建表完成，退出先恢复普通预览流再 `deactivateModeA` 销毁旧 GL 上下文；`SingleCamera` 增加 `surroundPreviewSurface` 不变跳过重加与 `activePreviewConfig` 幂等保护，消除二次进 A 时的 `Surface is already added!` 重建警告。
- **`1.4` 倒车 VHAL 信号（离线单测与二进制协议验证；待实车验证）**：反编译确认 E5 `libvhal_decoder.so` 与 Smartcam `3.0.56` `libvhal_proto_parser.so` 的 `field 5` 均为 `sint32` ZigZag（保持 `VhalProtoBatchReader` 不改）；修复 `CoreVehicleSignalDecoder` 删除 `GEAR` 的 `area == 0` 限制并隔离 `0x11400400`/`0x11400401` 状态防止互相覆盖、`VehicleSignalSnapshot.Gear.fromVhalValue(<0)` 返回 `UNKNOWN`、`VehicleAutomationCoordinator` 在未开补盲时按规则/随档位预览/倒车预览独立申请 VHAL 租约、`MainActivity` 冷启动同步与 `SurroundSettingsFragment` 触发保活，并补充 `VhalSignalObserver` 节流原始批次/挡位诊断日志（见 [P016](../PITFALLS.md)、[P017](../PITFALLS.md)）。

## 最近记录

- 2026-11-18 13:10:00 +08:00 | 收尾与全量复验 | 补齐 `SingleCamera` `surroundPreviewSurface` 防重加后重新验证：`tools\verify.ps1` 通过（双风味 `440` 测 `0` 失败、`assembleDebug`/`assembleRelease` 通过），`1.6.6-test-11181300` 在 2 路 `emulator-5556` 完成“开预览→360 A→B→C→360 A→B→视频/照片回看→返回”零崩溃、无重加告警；已停模拟器并清空 `work/tmp/fix-pack-a-crashes-and-reverse-signal/` | `work/verification/build-20261118-130502-085.log`
- 2026-11-18 12:46:00 +08:00 | 完成包 A 三项源码修复与单测 | 修复多分辨率回放布局/NPE、`SurroundGlRenderer`/`PreviewModeController` 生命周期与 `1.4` VHAL 挡位解码/租约问题，新增 `CoreVehicleSignalDecoderAndLeaseTest` 与 `PreviewModeControllerTest` | `app/build.gradle.kts` (`1.6.6-test-11181300`)
- 2026-11-18 12:12:00 +08:00 | 模拟器抓现行崩溃与协议交叉验证 | 5556 抓得 `2.1`（`CompositeQuadrantTextureController.<init>` `TextureView` 空指针）与 `1.2`（`SurroundGlRenderer.<init>` `mGLThread.requestRender()` 空指针）；反编译确认 `field 5` 为 `sint32` ZigZag 及 Smartcam `areaFilter=null` 读取逻辑 | [P016](../PITFALLS.md) · [P017](../PITFALLS.md)
- 2026-11-18 12:04:00 +08:00 | 初始化与源码定位 | 建立包 A 任务文档与排查边界 | [fix-pack-a-crashes-and-reverse-signal.md](fix-pack-a-crashes-and-reverse-signal.md)

## 下一步

1. 在银河 E5 实车安装最新构建，实测“开预览进回放”“开预览切 `360 A`（4 路）”，并挂 R/P/N/D 档抓取 `VhalSignalObserver` / `VehicleAutoCoord` 节流日志确认 `1.4` 倒车信号已识别。
