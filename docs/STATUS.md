# 项目进度

最后更新/核对：2026-11-20 00:43:30 +08:00（Asia/Hong_Kong）
维护：Antigravity（已提交 `4836da2`、封包 `build20` 并发布至 fork Pre-release [`v1.6.6-test-11192353-build20`](https://github.com/johnwyatt041-commits/EVCam/releases/tag/v1.6.6-test-11192353-build20)，`530` 测、`0` Lint error、6 资产远端 SHA256 核验一致；待银河 E5 实车复测）。执行规范：[PROGRESS_RULES.md](PROGRESS_RULES.md)。

## 当前快照

| 项目 | 当前状态 |
| --- | --- |
| 分支与提交 | `codex/e5-recording-startup-fix`；源码提交 **`4836da2`**（含全量 E5 修复、统一调校与 4.3 外参微调，`1.6.6-test-11192353`，`versionCode = 86`） |
| 本地交付 | `outputs/` 顶层保留 **`build19`** + **`build20`**（各 6 文件，共 12 文件；`build18` 已归档至 `outputs/archive/`） |
| GitHub | `johnwyatt041-commits/EVCam` 最新 Pre-release：[`v1.6.6-test-11192353-build20`](https://github.com/johnwyatt041-commits/EVCam/releases/tag/v1.6.6-test-11192353-build20)（6 资产 SHA256 一致） |
| 离线与设备验证 | `build20` 已通过 `verify.ps1` 双风味 `530` 测（`0` 失败/`0` 跳过）、`assembleDebug`/`assembleRelease`、`0` Lint error（`2162` warnings）与签名/资产核验；**尚未上实车复测** |
| 实车状态 | 针对 `build19` 实车 4 项反馈及星舰7前/后摄俯仰高度差已完成修复并发布 `build20`，待装机实测 |

## 当前结论与确认边界

- **`build20` 已合并发布全套实车修复与 4.3 外参微调能力**：详见 [实车修复与 4.3 外参微调](tasks/fix-e5-realcar-360-guidance-mode-d-ui.md)（APVP `40007`、双层纯两侧引导线、规则开关、模式 D 联动、四分栏统一宽度/透明度、`4.3` 四路外参微调分槽独立保存与 JSON 导出）。
- **手机端与 `build19` 既有基线保持完整**：`.mp4.pending` 权限启动修复、星舰7默认标定、4.3 实时预览调校与 `W1` 图标均已合流。
- **用户规则拍板（2026-11-18 已确认）**：① **鱼眼矫正保持关闭**（`AppFeatures.FISHEYE_CORRECTION = false`）；② **ESP32 与浏览器投屏保持保留不动**（`AppFeatures.NETWORK_STREAMING = true`）。

## 下一步

1. 安装 `build20`（`1.6.6-test-11192353`）上银河 E5 实车联测：APVP 转向曲线、按车速规则开关引导线、模式 D 转向灯/手动切换、360 宽度/底色透明度与 4.3 四路外参车位实调。

## 最近更新（最多5条）

- 2026-11-20 00:43:30 +08:00 | 封包发布 build20 并推送 GitHub Pre-release（`1.6.6-test-11192353`） | 源码 `4836da2` 打标 `v1.6.6-test-11192353-build20` 推送，A/B APK 与 `mappings.zip` 等 6 资产远端 SHA256 核验一致、`build18` 归档，待实车复测 | [实车修复与微调](tasks/fix-e5-realcar-360-guidance-mode-d-ui.md)
- 2026-11-20 00:01:30 +08:00 | 新增 4.3 四路镜头外参微调、按方案分槽独立保存与 JSON 导出 | 针对 E5 与星舰7前后摄俯仰/高度差，支持 `ΔPitch/Yaw/Roll/X/Y/Z` 实时调校与 `(44, 68)` 重算、内置/本地槽互不覆盖及 `calibration.json` 导出；`530` 测与构建/Lint 全过（`0` errors） | [实车修复与微调](tasks/fix-e5-realcar-360-guidance-mode-d-ui.md)
- 2026-11-19 23:04:30 +08:00 | 新增 360「编辑布局」引导线宽度调整与四分栏统一按键底色透明度 | 顶栏在 `360 A` 开放「编辑布局」，新增右上前/后视引导线宽度（`50%–150%`）与 A/B/C/D 统一按键底色透明度（`0%–100%`，默认 `80%`）滑条及关按键黑框消隙；`524` 测与双风味构建/Lint 全过 | [实车修复与调校](tasks/fix-e5-realcar-360-guidance-mode-d-ui.md)
- 2026-11-19 20:47:30 +08:00 | 修复 E5 实车 360 引导线、规则开关、模式 D 左右视/转向灯与轮位按钮样式 | 修复 APVP 40007/嵌套协议/ID-only/`readSignal`/Qf 静默超时及阿克曼符号，重绘图 2 纯两侧金黄引导线，新增设置开关与规则动作、模式 D 左右切换及转向灯联动；`520` 测通过 | [实车4项修复](tasks/fix-e5-realcar-360-guidance-mode-d-ui.md)
- 2026-11-19 16:10:00 +08:00 | 合流并发布 build19（星舰7默认标定 + 4.3 实时预览 + build18） | 源码 `9d2dc5c` 打标 `v1.6.6-test-11191602-build19` 推送并发布 GitHub Pre-release（6 资产 SHA256 核验一致）；`build17` 归档，`512` 测、`0` Lint error | [星舰7默认标定](tasks/default-xingjian7-calibration.md)

## 归档与按需索引

- **归档全索引**：[2026-11 历史文档与任务全索引](archive/2026-11/INDEX.md)（`T01`–`T10`、`build01`–`build13`、早期报告均已收录）；APK 归档见 `outputs/archive/INDEX.md`
- **按需规范**：[需求](REQUIREMENTS.md) · [坑表](PITFALLS.md) · [协作](COLLABORATION.md) · [开发](DEVELOPMENT.md) · [本机资料](AI_HANDOFF.md)
