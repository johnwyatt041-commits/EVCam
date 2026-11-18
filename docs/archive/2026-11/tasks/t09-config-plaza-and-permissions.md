# T09 银河 E5 离线配置广场与系统权限诊断面板

- **状态**: 已完成 `build11` 离线开发、单测与双风味打包（待 E5 实车验证）
- **最后核对**: `2026-11-17 00:16:00 +08:00`
- **关联构建**: `1.6.6-test-11170004`（`versionCode = 80`，源码提交 `3c3c2ba`，Tag `v1.6.6-test-11170004-build11`）
- **基线范围**: 基于 `build10`（`13e2f59`）仅实施 [重构总方案](../EVCAM_SMARTCAM_REFACTOR_PLAN.md) T09，未修改 T10 与已验证的 T01–T08 模块。

## 实装内容

1. **12. 配置广场（完全离线、银河 E5 白名单约束）**
   - 新增 `com.kooo.evcam.configplaza` 包（`E5ConfigSection`、`E5ConfigSnapshot`、`E5ConfigSchema`、`E5ConfigApplier`、`AndroidE5ConfigTarget`、`AndroidE5ConfigBackupStore`、`ConfigPlazaRepository`）与界面 [ConfigPlazaFragment.java](..\..\..\..\app\src\main\java\com\kooo\evcam\ui\ConfigPlazaFragment.java)。
   - 严格禁止 SC 云端共享、自定义车型、旋转外设 `rotary_controls` 及飞书凭据/PAT/本地文件路径；采用手动 `JsonObject`/`JsonParser` 编解码保障 R8 安全。
   - 内置 3 套 E5 离线预设（`e5_balanced_daily.json`、`e5_low_power_smooth.json`、`e5_surround_reverse_enhanced.json`），支持多选分区预览应用、`AtomicFile` 应用前自动备份与一键回滚、SAF 导入导出与剪贴板 JSON 导入、摄像头物理 ID 冲突探测。
2. **13. 系统与权限诊断面板**
   - 新增纯 Java 评估器 [PermissionDiagnostics.java](..\..\..\..\app\src\main\java\com\kooo\evcam\PermissionDiagnostics.java)，将 10 项权限按常规运行与特殊保活分组、按核心必需/推荐保障/按需可选分级并随 API 28/30/33+ 动态判定；改用 API 29+ `unsafeCheckOpNoThrow` 消除原有 `[NewApi]` Lint 错误。
   - 重构 [PermissionSettingsFragment.java](..\..\..\..\app\src\main\java\com\kooo\evcam\PermissionSettingsFragment.java) 与 [fragment_permission_settings.xml](..\..\..\..\app\src\main\res\layout\fragment_permission_settings.xml)，提供缺失常规权限一键申请、单项直达设置、开机自启动、`dialog_first_launch_guide` 使用指南、ADB 一键授权与车机白名单备份还原。
   - 在 [SettingsCatalog.java](..\..\..\..\app\src\main\java\com\kooo\evcam\ui\SettingsCatalog.java) 与 [SettingsFragment.java](..\..\..\..\app\src\main\java\com\kooo\evcam\SettingsFragment.java) 注册 `12. 配置广场`，同步 [RecorderSettingsFragment.java](..\..\..\..\app\src\main\java\com\kooo\evcam\RecorderSettingsFragment.java#L1586-L1587) 提示文案。

## 离线验证与待验缺口

- **已实测**：`tools\verify.ps1` 离线通过 `386` 项单测（`193` x 2 风味，0 失败/0 跳过；含 `E5ConfigPlazaTest`、`PermissionDiagnosticsTest`、`SettingsCatalogAndAppearanceTest`）；`:app:lintIndependentDebug` `18 errors`（较基线 `19` 净减 1，T09 文件 0 错误）；A/B 双风味 Release APK 已通过 `apksigner` 校验且含 `libvhal_decoder.so`。
- **待实测**：E5 实车双击安装包覆盖、配置广场 SAF 导入导出/局部应用回滚、权限逐项系统设置跳转与本地 ADB 一键授权实车验收。
