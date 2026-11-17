# build12 E5 实车验收盘点与 18 项历史 Lint 清零

- 最后更新：2026-11-17 16:13:38 +08:00（Asia/Hong_Kong）
- 最后核对：2026-11-17 16:13:38 +08:00（Asia/Hong_Kong）
- 任务/执行者：`build12-e5-acceptance-and-lint-cleanup` / Antigravity
- 分支/工作树：`codex/e5-recording-startup-fix` / `E:\GPT\EVCam`（`1.6.6-test-11171608` / `versionCode = 82`）
- 状态：已完成离线改码与 Lint/单测/R8 验证，正在封装 `build13`（**尚未上车实测**）
- 当前结论：已按定稿方案清零全部 18 项历史 Lint 错误（7 类 ID）并顺带加固 `MainActivity` 3 处 `ContextCompat.registerReceiver`；双风味 `lint` 均 `0 errors`、离线单测 `432/432` 通过、A/B Release R8 与签名/`libvhal_decoder.so` 复核通过（未上车实测）。

## 18 项历史 Lint 错误与加固整改落地

| Lint ID | 数量 | 改动文件 | 落地结果 |
| --- | --- | --- | --- |
| `MissingPermission` | 2 | [MainFloatingWindowView.java](../../app/src/main/java/com/kooo/evcam/MainFloatingWindowView.java) | 移除 `longPressRunnable` 内 `Vibrator` 震动代码与未使用 `Build` 导入，保留 `isDragEnabled = true`，不加 `VIBRATE` |
| `MissingSuperCall` + `GestureBackNavigation` | 1 + 3 | [MainActivity.java](../../app/src/main/java/com/kooo/evcam/MainActivity.java)、[MainBackNavigationRouter.java](../../app/src/main/java/com/kooo/evcam/ui/MainBackNavigationRouter.java)、[QrTransferDialog.java](../../app/src/main/java/com/kooo/evcam/transfer/QrTransferDialog.java)、[TransparentBootActivity.java](../../app/src/main/java/com/kooo/evcam/TransparentBootActivity.java) | 三处均移除 `onBackPressed()` 重写并改用 `getOnBackPressedDispatcher().addCallback`；`MainActivity` 抽共享 `handleBackNavigation()` 与 `MainBackNavigationRouter` 并更新 L285/L895；`QrTransferDialog` 改继承 `ComponentDialog`；`TransparentBootActivity` 改继承 `ComponentActivity`（保留 `Theme.Cam.Transparent`） |
| `NewApi` | 4 | [values/themes.xml](../../app/src/main/res/values/themes.xml)、[values-night/themes.xml](../../app/src/main/res/values-night/themes.xml)、[values-v31/themes.xml](../../app/src/main/res/values-v31/themes.xml)、[values-night-v31/themes.xml](../../app/src/main/res/values-night-v31/themes.xml) | 拆出 `Base.V28.Theme.Cam`，仅将 `windowSplashScreenBackground/AnimatedIcon` 迁至 `values-v31` 与 `values-night-v31` |
| `UnspecifiedRegisterReceiverFlag` + `UnsafeImplicitIntentLaunch` | 5 + 2（+3） | [FloatingWindowService.java](../../app/src/main/java/com/kooo/evcam/FloatingWindowService.java)、[RecordingFloatingService.java](../../app/src/main/java/com/kooo/evcam/service/RecordingFloatingService.java)、[MainActivity.java](../../app/src/main/java/com/kooo/evcam/MainActivity.java)、[RecorderSettingsFragment.java](../../app/src/main/java/com/kooo/evcam/RecorderSettingsFragment.java) | 8 处动态接收器统一改 `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)`；`ACTION_UPDATE_SIZE` 抽取 `sendRecordingFloatingSizeBroadcast` 并补 `setPackage(context.getPackageName())` |
| `UseAppTint` | 1 | [fragment_feishu.xml](../../app/src/main/res/layout/fragment_feishu.xml) | 补 `xmlns:app` 并将 `android:tint` 改为 `app:tint="@color/text_primary"` |

## 离线验证记录（未上车实测）

- **版本号**：[build.gradle.kts](../../app/build.gradle.kts) 递进为 `versionCode = 82`、`versionName = "1.6.6-test-11171608"`（严禁且未使用任何 `@SuppressLint` / `tools:ignore`）。
- **新增单测**：[MainBackNavigationRouterTest.java](../../app/src/test/java/com/kooo/evcam/ui/MainBackNavigationRouterTest.java)（7 项：6 级返回路由优先级 + `OnBackPressedDispatcher` 分发）与 [RecorderSettingsBroadcastTest.java](../../app/src/test/java/com/kooo/evcam/RecorderSettingsBroadcastTest.java)（2 项：`ACTION_UPDATE_SIZE` 包名限定与 Extras）。
- **构建与 Lint**：`.\tools\verify.ps1` 全量离线单测 `432` 项通过（`independent`/`composite` 各 `57` 套 `216` 测，0 失败/跳过）；`:app:lintIndependentDebug` 与 `:app:lintCompositeDebug` 均为 **`0 errors`**（`2013` warnings，0 基线过滤）。
- **APK 核验**：`app-independent-release.apk`（`6,791,918`B，SHA-256 `f8f1ea3d5f40541d43b94ad6bcc650a61d167aa12a3acbeaf3b75d463647ff13`）与 `app-composite-release.apk`（`6,808,302`B，SHA-256 `036ba43bcba65ecda0d11ed5e46d24421bac6f341a89c23af4faa75d5cff8a09`）均通过 `apksigner` 校验且含 `lib/arm64-v8a/libvhal_decoder.so`（`8,408`B）。
- **实车待检（`T01`–`T10` + Lint 行为）**：P0 三路 HEVC vs 2×2 H.264、四类 VHAL、补盲 `2032/2038` 与双 HUD；P1 退出防复活、R/D 弹出、P 档 2s/非 P 0.5s 飞书断连；P2 返回手势、`QrTransferDialog` 返回确认弹窗及悬浮窗拖动（**均尚未上车实测**）。

## 最近记录（最多 5 条）

- 2026-11-17 16:13:38 +08:00 | 递进版本与验证 | `versionCode = 82` / `1.6.6-test-11171608` 完成 `432` 测、双风味 Lint `0 errors`、R8 与签名/`so` 核验，准备提交并封装 `build13`（未实车） | [STATUS](../STATUS.md)
- 2026-11-17 14:16:58 +08:00 | Lint清零与验证 | 落地 18 项 Lint 清零 + `MainActivity` 3 处 `ContextCompat.registerReceiver` 并补 9 项单测；双风味 Lint `0 errors`，离线 `432` 测与 A/B Release R8 通过（未实车） | [STATUS](../STATUS.md)
- 2026-11-17 13:15:15 +08:00 | 方案定稿 | 盘点 `build12`（`e6271a2`/`29fe92b`）E5 实车待检清单并定稿 18 项 Lint（7 类 ID）清零方案：不要震动 + 顺改 `MainActivity` 3 处接收器 | [STATUS](../STATUS.md)

## 下一步

封装 `build13` 资产至 `outputs/`、推送代码/标签并发布 GitHub Pre-release，同步归档四件套文档后运行 `python work/progress-rules/check_docs.py`。
