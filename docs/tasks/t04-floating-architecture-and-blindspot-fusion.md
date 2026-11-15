# T04：悬浮小窗架构与补盲择优融合（build04）

最后更新/核对：2026-11-16 02:21:03 +08:00（Asia/Hong_Kong）
执行：Antigravity（分支 `codex/e5-recording-startup-fix`；源码提交 `a6f4105`，Tag `v1.6.6-test-11160215-build04`）

## 目标与确认边界

- **工作流模式**：遵循 T04 核源码后用户拍板定稿——默认 `PreviewPresentation.SIDEBAR`（保留 T02/T03 侧边栏与多模式），主屏悬浮层优先无障碍叠加层（`2032`）并在解绑/异常时自动降级 `2038`；「显示 360 时暂停补盲」仅暂停主屏补盲弹窗，**保留 EVCC 副屏与 ESP32 Wi-Fi 双 HUD 转向灯联动**。
- **范围约束**：未修改 `T05`–`T10`，保留 `T01`–`T03` 主链路、双 HUD 与飞书目录。

## 已实现改动（均已合入 `a6f4105`）

1. **三态预览呈现与独立小窗（4.2 / SC `Lqw2` 优化）**
   - `PreviewPresentation`（`SIDEBAR` / `FULLSCREEN` / `WINDOW`）、顶栏「小窗」开关及 `AppearanceSettingsFragment` 呈现模式/无障碍叠加层开关。
   - 新增 `StandalonePreviewWindow`：支持 16:9 拖动/缩放、点击切换主副视角、`openCameraDeferred()` 切回自动重绑流及销毁通知闲置关流保护；进入全屏应用或返回 `MainActivity` 时自动收起。
2. **不抢导航焦点叠加层策略（`OverlayWindowParamsFactory`）**
   - 统一主屏浮窗 `WindowManager.LayoutParams`（`FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL | FLAG_ALT_FOCUSABLE_IM`）；启用并绑定 `KeepAliveAccessibilityService` 时优先用 `2032` 叠加层，`onUnbind` / `BadTokenException` 自动降级或安全卸载到 `2038`。
   - 副屏 `EvccDashcastDisplayResolver` 严格使用 `DisplayContext + TYPE_APPLICATION_OVERLAY (2038)`，绝不混入无障碍类型。
3. **物理/输出双镜像解耦（`MirrorTarget` + `CameraMirrorPolicy` + SC `Lq01`）**
   - 解耦传感器物理镜像 `phys` 与 4 路输出端镜像 `out`（`MAIN_PREVIEW`、`BLIND_SPOT`、`SECONDARY_HUD`、`NETWORK_STREAM`），统一生效公式 `effective = phys ^ out`。
   - 覆盖 `PreviewTransformHelper`、`BlindSpotCorrection`、`FullscreenPreviewDialog`、`StreamGlEncoder`（零 FBO `uPhysMirror`/`uOutMirror`）、`MjpegStreamManager` 与 `BlindSpotCorrectionFragment` 实时联动。
4. **补盲协调器与 360/AVM 主屏暂停融合**
   - 新增 `BlindSpotCoordinator`（统一分发与左右槽位复用）及 `BlindSpotSuppressionController`（`blind_spot_suppress_on_360` 默认开启 + `BlindSpotService.setInternal360Active` 给 `T07` 预留）。
   - AVM/360 避让期间保持 `getActiveBlindSpotCamera()` 与 EVCC/ESP32 转向灯状态活跃，避让解除且未超时自动恢复主屏补盲。

## 离线验证与 `build04` 交付清单

| 项目 | 验证结果 / 数值（2026-11-16 02:20 +08:00，均为离线核验） |
| --- | --- |
| `tools\verify.ps1` | 通过；JVM `246` 项（A `123` + B `123`，`0` 失败/跳过/错误，含 T04 新增 `16×2` 测） |
| Lint 基线 | `:app:lintIndependentDebug --offline` 保持 `19` 项既有错误（`0` 新增）、`1750` 警告 |
| A-Independent APK | `6,472,395` 字节；SHA256 `e8845d1836887af69c46a6b97e008e760fda24cc704fde3c3bd5ed043c31673a` |
| B-Composite APK | `6,488,779` 字节；SHA256 `81c583877d257f1267a8bef93bfdb6914ef46275398a2748544a30d6c67015e2` |
| R8 Mappings ZIP | `5,593,769` 字节；SHA256 `2ff547c300b08dde833e3fe39961007aa3da3915137358b2b5ef01af1cc71881` |
| 签名与原生库 | `apksigner verify` 通过（Cert `a40da8...1bf5dc`）；`arm64-v8a/libvhal_decoder.so` `8,408`B（`243a92...8e666`） |

## 实车待验与遗留边界（未在 E5 车机实测）

- 主屏导航运行期无障碍 `2032` vs `2038` 焦点栈与 Z-order 实际表现待上车验证。
- 物理/输出双镜像（主预览、主屏补盲、EVCC 副屏、ESP32 MJPEG）实车画面方向与辅助线对齐待实测。
- 360/AVM 避让期间 EVCC/ESP32 联动保活、`StandalonePreviewWindow` 拖拽缩放及 `T01`–`T03` 联调待实车确认。
