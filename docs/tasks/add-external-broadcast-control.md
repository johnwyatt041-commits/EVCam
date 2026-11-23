# 外部显式广播快捷控制入口

最后更新：2026-11-23 16:04:43 +08:00
最后核对：2026-11-23 16:04:43 +08:00
任务/执行者：add-external-broadcast-control / Antigravity
分支/工作树：codex/e5-recording-startup-fix / E:\GPT\EVCam
状态：等待实测（已完成源码提交 `0bb9b04` 与本地单 A 版 `build27` 封包；暂未推送 GitHub Pre-release）
当前结论：已完成 13 个外部显式广播快捷入口（目标包名 `com.kooo.evcam`，静态 `BroadcastReceiver` 零后台常驻占用），源码提交 `0bb9b04` 并本地封包 `build27`（`1.6.6-test-11231552`），离线 `405` 测与 `:app:lintIndependentDebug` `0` errors 通过；尚未在银河 E5 实车验证。

## 实现要点

- **外部广播契约与路由**：新增 [ExternalCommandContract.java](../../app/src/main/java/com/kooo/evcam/external/ExternalCommandContract.java)、[ExternalCommandRouter.java](../../app/src/main/java/com/kooo/evcam/external/ExternalCommandRouter.java) 与 [ExternalCommandReceiver.java](../../app/src/main/java/com/kooo/evcam/external/ExternalCommandReceiver.java)，在 [AndroidManifest.xml](../../app/src/main/AndroidManifest.xml) 静态注册 `exported="true"` 接收器。
- **13 个 Action（均需指定包名 `com.kooo.evcam`）**：
  - 前后台：`TOGGLE_FOREGROUND`（按当前前台状态切换）、`MOVE_TO_FOREGROUND`、`MOVE_TO_BACKGROUND`（`400ms` 防抖）。
  - 录像与抓拍：`TOGGLE_RECORDING`、`START_RECORDING`（沿用已在录短路）、`STOP_RECORDING`、`TAKE_PHOTO`（后台触发先唤醒抓拍再自动退后台，`1000ms` 防抖）。
  - 主视图直达：`OPEN_PREVIEW_MODE_A`~`OPEN_PREVIEW_MODE_E`（在后台自动拉起并切入对应视图与主预览；若已在前台记录仪页且主预览开启并处于该视图，再按一次退回后台）与 `SWITCH_PREVIEW_MODE`（可选 `mode` / `toggle_background` Extra，默认按 `A→B→C→D→E` 循环）。
- **防冲突与生命周期联动**：[EVCamApplication.java](../../app/src/main/java/com/kooo/evcam/EVCamApplication.java) 增加 `sResumedUiActivity` 跟踪（忽略 `TransparentBootActivity`）；[RecordingFloatingService.java](../../app/src/main/java/com/kooo/evcam/service/RecordingFloatingService.java) 内部切换改为 `com.kooo.evcam.internal.TOGGLE_RECORDING` 避免双触发；[MainActivity.java](../../app/src/main/java/com/kooo/evcam/MainActivity.java) 冷启动跳过默认起始页/小窗覆盖并提供薄调度入口。

## 验证与证据（2026-11-23 16:04:43 +08:00）

- **单元测试**：新增 [ExternalCommandRouterTest.java](../../app/src/test/java/com/kooo/evcam/external/ExternalCommandRouterTest.java)（10 测）；`.\tools\verify.ps1` 运行 `405` 测全过（`0` 败/错/跳，`work/verification/build-20261123-155316-666.log`），单 A 版 Debug/Release 构建通过。
- **Lint 与 APK 核验**：`:app:lintIndependentDebug --no-daemon --offline` `0` errors（`2244` warnings）；`build27` Release APK（`8,042,320` B，SHA256 `9d55f0a8cdf5195dc41b22315932de122c1aaa6af2ef384fea7590cde2b8b390`）通过 V2 签名（`a40da80a...`）、`lib/arm64-v8a/libvhal_decoder.so`（8,408 B）与 4 份默认标定资产校验；尚未实车验证。

## 最近记录

- 2026-11-23 16:04:43 +08:00 | 完成 `0bb9b04` 提交与本地 `build27` 封包（`1.6.6-test-11231552`） | 归档 `build25` 至 `outputs/archive/`，产出单 A 版 `build27` 5 份本地文件（暂未推 GitHub Pre-release） | [notes](../../outputs/EVCam-E5-build27-1.6.6-test-11231552-notes.md)
- 2026-11-23 16:01:40 +08:00 | 实现 13 个外部广播快捷入口并完成离线验证（`1.6.6-test-11231552`） | `verify.ps1` `405` 测全过、Lint `0` errors、Release APK 签名与原生/标定资产核验通过 | [STATUS.md](../STATUS.md)

## 下一步

1. 安装本地 `build27`（`EVCam-E5-A-Independent-1.6.6-test-11231552-build27-release.apk`）上银河 E5 实车联调按钮广播与批次 A+B+C；如需推送 GitHub Pre-release，待用户确认后执行。
