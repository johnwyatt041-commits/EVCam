# 设置页「6. 广播中心」快捷复制入口

最后更新：2026-11-28 02:07:30 +08:00
最后核对：2026-11-28 02:07:30 +08:00
任务/执行者：add-broadcast-center-ui / Antigravity
分支/工作树：codex/e5-recording-startup-fix / E:\GPT\EVCam
状态：等待实测（已完成离线代码实现、单元测试与 Lint 校验，待打包发布与实车联调）
当前结论：已在设置页新增「6. 广播中心」，集中收纳全部 14 项外部可调用内置广播并支持分类筛选、关键词检索、一键复制（包名/Action/完整配置/ADB 命令/全部清单）与本机广播测试，单 A 版离线 `410` 测与 Lint `0` errors 通过。

## 实现要点

- **纯 Java 广播清单与格式化**：新增 [ExternalBroadcastCatalog.java](../../app/src/main/java/com/kooo/evcam/external/ExternalBroadcastCatalog.java)，汇总 [ExternalCommandContract.java](../../app/src/main/java/com/kooo/evcam/external/ExternalCommandContract.java) 全部 13 个控制广播与 [KeepAliveReceiver.java](../../app/src/main/java/com/kooo/evcam/KeepAliveReceiver.java) 的 `ACTION_KEEP_ALIVE`（共 14 项），按「前后台 / 录像与抓拍 / 主视图切换 / 保活唤醒」分类，提供过滤检索、ADB 命令、完整配置片段及全量清单格式化。
- **设置项与零重建主题 UI**：在 [SettingsCatalog.java](../../app/src/main/java/com/kooo/evcam/ui/SettingsCatalog.java) 注册 `ID_BROADCAST_CENTER`（「6. 广播中心」），在 [SettingsFragment.java](../../app/src/main/java/com/kooo/evcam/SettingsFragment.java) 路由至 [BroadcastCenterFragment.java](../../app/src/main/java/com/kooo/evcam/ui/BroadcastCenterFragment.java)，配套 [fragment_broadcast_center.xml](../../app/src/main/res/layout/fragment_broadcast_center.xml) 与 [ic_nav_broadcast.xml](../../app/src/main/res/drawable/ic_nav_broadcast.xml)；实现 `UiThemeController.ThemeAware` 与 `UiScaleController.ScaleAware`，无常驻后台开销。
- **版本号更新**：[build.gradle.kts](../../app/build.gradle.kts) 中 `versionCode = 89`、`versionName` 更新为 `1.6.6-test-11280205`。

## 验证与证据（2026-11-28 02:07:30 +08:00）

- **单元测试与构建**：新增 [ExternalBroadcastCatalogTest.java](../../app/src/test/java/com/kooo/evcam/external/ExternalBroadcastCatalogTest.java)（3 测）并更新 [SettingsCatalogAndAppearanceTest.java](../../app/src/test/java/com/kooo/evcam/ui/SettingsCatalogAndAppearanceTest.java)；`.\tools\verify.ps1` 运行 `410` 测全过（`0` 败/错/跳，日志 `work/verification/build-20261128-020451-529.log`），单 A 版 Debug 与 Release 构建通过。
- **Lint 与签名**：`:app:lintIndependentDebug --no-daemon --offline` `0` errors（`2263` warnings）；Release APK V2 签名（`a40da80a...`）、`libvhal_decoder.so` 与 4 份默认标定资产校验通过；尚未实车联调。

## 最近记录

- 2026-11-28 02:07:30 +08:00 | 完成「6. 广播中心」源码定版与全量单测/Lint 校验（`1.6.6-test-11280205` / `versionCode = 89`） | 新增目录清单、UI 与 3 项单测；`verify.ps1` `410` 测全过、Debug/Release 构建、Lint `0` errors 与 APK 签名资产校验通过 | [STATUS.md](../STATUS.md)

## 下一步

1. 在真机/车机设置页「6. 广播中心」验证深浅色/车机大号缩放、剪贴板复制及第三方按键软件绑定广播调用。
