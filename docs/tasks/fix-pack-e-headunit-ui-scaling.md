# 包 E：车机端菜单、顶栏与设置控件尺寸适配

最后更新：2026-11-18 23:29:00 +08:00
最后核对：2026-11-18 23:29:00 +08:00
任务/执行者：fix-pack-e-headunit-ui-scaling / Antigravity
分支/工作树：codex/e5-recording-startup-fix / E:\GPT\EVCam
状态：模拟器与离线全验证通过（`1.6.6-test-11182319`，待银河 E5 实车验证）
当前结论：已重构 5 档单调 `w`/`h` 响应式维度（替换旧 `sw600dp*`）、新增“菜单与按钮缩放”原地切换（`标准自适应` vs `车机大号 1.16f`），并完成全屏顶栏/左栏/设置/回看短按钮不溢出改造；`468` 测 `0` 失败、双风味 `0` Lint error，且在 `emulator-5556` 上通过 `1600×900`（`240`/`160dpi`）与 `2560×1600`（`240`/`160dpi`）全巡检与零崩溃核查。

## 已完成改动摘要

1. **5 档单调 `w`/`h` 资源限定符与统一样式基准**：
   - 删除旧 `values-sw600dp` / `values-sw600dp-h900dp` / `values-sw600dp-h1200dp` `ui_dimens.xml`，改为 `values`（`<960dp`）→ `values-w960dp`（`1600×900@240dpi`）→ `values-w1200dp`（`1600×900@160dpi` / `2560×1600@320dpi`）→ `values-w1200dp-h900dp`（`2560×1600@240dpi`）→ `values-w1200dp-h1200dp`（`2560×1600@160dpi`），保持在 `1600×900` 与 `2560×1600` 不同 DPI 组合下均可随视口尺寸显著放大触控目标。
   - 统一 `ui_styles.xml`（`Button`、`TopBarButton`、`OptionPill`、`OptionPill.Compact`、`RecorderTab`、`EditorButton`、`CompactFilterButton`）及各设置/回看/编辑器布局引用 `@dimen/ui_*`，消除小尺寸硬编码。
2. **原地菜单与按钮缩放切换（不依赖系统字体、不重启 Activity、不断录）**：
   - 在 `AppConfig`、`UiScaleHelper`、`UiScaleController`、`AppShellController`、`SettingsFragment`、`AppearanceSettingsFragment`、`ConfigPlazaFragment`、`PreviewEditorController` 与 `MainActivity` 接入 `ui_scale_mode`（`standard` = `1.0f` / `large` = `1.16f`）与 E5 ConfigPlaza 架构同步；按钮缓存首帧像素基准并跳过 `CompoundButton`、`ui_preview_layout`、`ui_mode_a_surround_container` 与补盲可视化画布，保障 `360 A` 与 `16:10` 比例不变、反复切换不叠加放大。
3. **小屏/大号防截断专项打磨**：
   - `appearance` 默认主预览 4 项改为 2×2 等宽网格、开门退出 5 档改为等权铺满；补盲触发策略 5 项与配置广场顶部 5 动作拆为双行分组；全部 8 份 `*playback_new.xml` 筛选短按钮接入 `CompactFilterButton`（`2dp` 水平内边距 + 单行防折行）。

## 验证结果与实车边界

- **离线验证**：`.\tools\verify.ps1` 通过（468 测 `0` 失败、`assembleDebug`/`assembleRelease` 成功，日志 `work/verification/build-20261118-232447-851.log`）；`:app:lintIndependentDebug` 与 `:app:lintCompositeDebug` `0 errors`。
- **`emulator-5556` 验证（`1600×900` & `2560×1600`）**：实测 `1600×900@240dpi`（标准左栏 `144px`/顶栏 `126px`；大号左栏 `167px`/顶栏 `140px`）、`1600×900@160dpi`、`2560×1600@240dpi`（标准左栏 `204px`/顶栏 `150px`；大号左栏 `237px`/顶栏 `168px`）与 `2560×1600@160dpi`（标准左栏 `204px`；大号左栏 `237px`）全视图 `OVERFLOW (non-scroll): NONE`，顶栏含 `MODE_D` 单行无换行、设置与回看筛选按钮无裁剪、`AndroidRuntime:E` 无崩溃。
- **未覆盖边界**：真实银河 E5 车机实屏 `wm size`/`wm density` 与实车触控体感尚未上车实测。

## 最近记录

- 2026-11-18 23:29:00 +08:00 | 完成包 E 全栈适配与全分辨率双端复检 | 落盘 5 档 `w`/`h` `ui_dimens.xml`、`UiScaleController`/`UiScaleHelper` 原地缩放切换与 4 项小屏防折行修复；`468` 测 `0` 失败、双风味 `0` Lint error，`emulator-5556` `1600×900` 与 `2560×1600`（`240`/`160dpi` + 深浅主题 + 标准/大号）全部无截断零崩溃 | [fix-pack-e-headunit-ui-scaling.md](fix-pack-e-headunit-ui-scaling.md)
- 2026-11-18 15:40:00 +08:00 | 建立包 E 规格 | 完成包 E 问题定位与依赖梳理，排在包 B/C 布局定型后收口执行 | [fix-pack-e-headunit-ui-scaling.md](fix-pack-e-headunit-ui-scaling.md)

## 下一步

1. 在银河 E5 实车核对 `wm size` / `wm density` 与「标准自适应 / 车机大号」两档下的实体点击舒适度，同步并入包 A–E 实车整机联测。
