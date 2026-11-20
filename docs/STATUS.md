# 项目进度

最后更新/核对：2026-11-21 02:52:45 +08:00（Asia/Hong_Kong）
维护：Antigravity（已完成方案 B 修复与原厂 `avm_calib_res` 导入并随本提交入库 `codex/e5-recording-startup-fix`，`1.6.6-test-11210240` 通过离线 `verify.ps1` 534 测与双风味 Lint `0` errors，暂未封包 `build21`/未推送；对外最新已发布包仍为 `build20` [`v1.6.6-test-11192353-build20`](https://github.com/johnwyatt041-commits/EVCam/releases/tag/v1.6.6-test-11192353-build20)）。执行规范：[PROGRESS_RULES.md](PROGRESS_RULES.md)。

## 当前快照

| 项目 | 当前状态 |
| --- | --- |
| 分支与提交 | `codex/e5-recording-startup-fix`；含随本次提交入库的方案 B + 原厂导入改动（`1.6.6-test-11210240`，`versionCode = 86`；上个基线 `4836da2`） |
| 本地交付 | `outputs/` 顶层保留 **`build19`** + **`build20`**（各 6 文件，共 12 文件；`build18` 已归档至 `outputs/archive/`；`1.6.6-test-11210240` 暂未封包） |
| GitHub | `johnwyatt041-commits/EVCam` 最新 Pre-release：[`v1.6.6-test-11192353-build20`](https://github.com/johnwyatt041-commits/EVCam/releases/tag/v1.6.6-test-11192353-build20)（6 资产 SHA256 一致；新改动未推送/未发布） |
| 离线与设备验证 | `1.6.6-test-11210240` 已通过 `verify.ps1` 双风味 `534` 测（`0` 失败/`0` 跳过）、`assembleDebug`/`assembleRelease` 与双风味 Lint `0` errors（`2180` warnings）；**尚未封包、未上实车测试** |
| 实车状态 | `build20` 实车反馈 360 车头/车尾与两侧对不上、比例怪异、缝隙明显；已完成车框/零跳变/焦距缩放+辅助开关与原厂 `avm_calib_res` 导入并入库，待装车实测 |

## 当前结论与确认边界

- **方案 B 修复与原厂格式直导已在 `codex/e5-recording-startup-fix` 落地并通过离线验证**：详见 [实车修复与 4.3 外参微调](tasks/fix-e5-realcar-360-guidance-mode-d-ui.md)（E5 实车车框 `190×462` / PNG `226×464`、静态 `bird.mesh` 零跳变原位重投影、`4.3` 新增 `Scale` 与 `Δcx/Δcy/Δk1` + `250 cm` 参考网格/隐藏车模/单路独显、以及 `Table.db` + 704B `.param` 原厂目录扫描/`.zip`/SAF 文件夹直导）。
- **手机端与 `build19`/`build20` 既有基线保持完整**：APVP `40007`、双层纯两侧引导线、模式 D 转向灯联动、四分栏统一透明度、`.mp4.pending` 权限启动修复与星舰7默认标定均保持。
- **用户规则拍板（2026-11-18 已确认）**：① **鱼眼矫正保持关闭**（`AppFeatures.FISHEYE_CORRECTION = false`）；② **ESP32 与浏览器投屏保持保留不动**（`AppFeatures.NETWORK_STREAMING = true`）。

## 下一步

1. 待用户确认是否封包 `build21` 并推送 GitHub Pre-release；随后上银河 E5 实车验证 `4.3` 焦距缩放/参考网格调校效果，或导入提取到的 E5 原厂 `avm_calib_res` 标定文件夹。

## 最近更新（最多5条）

- 2026-11-21 02:52:45 +08:00 | 提交方案 B 修复与原厂 `avm_calib_res` 直导（`1.6.6-test-11210240`，全量离线验证通过，暂未封包发布） | 修正 `190×462` 车框与 `bird.mesh` 原位零跳变重投影，新增 `Scale`/`Δcx/Δcy/Δk1` 与参考网格/隐藏车模/单路独显及 `Table.db`+`.param` 目录/`.zip`/SAF 导入；`verify.ps1` `534` 测、`assembleDebug`/`assembleRelease` 与 Lint `0` errors 通过，待实车测试 | [实车修复与微调](tasks/fix-e5-realcar-360-guidance-mode-d-ui.md)
- 2026-11-21 01:49:06 +08:00 | 记录 `build20` 实车 360 错位现象并核对原厂标定与车模影响（纯文档，未改源码） | 用户确认车头/车尾与两侧对不上、比例怪、缝隙明显（星舰7车模）；确认 PNG 不参与投影但源码车框 `213×534 cm` 偏大拉歪比例与接缝，获取 E5 原厂 `avm_calib_res` 可治本，当前仅支持 JSON/ZIP 导入 | [实车修复与微调](tasks/fix-e5-realcar-360-guidance-mode-d-ui.md)
- 2026-11-20 00:43:30 +08:00 | 封包发布 build20 并推送 GitHub Pre-release（`1.6.6-test-11192353`） | 源码 `4836da2` 打标 `v1.6.6-test-11192353-build20` 推送，A/B APK 与 `mappings.zip` 等 6 资产远端 SHA256 核验一致、`build18` 归档，待实车复测 | [实车修复与微调](tasks/fix-e5-realcar-360-guidance-mode-d-ui.md)
- 2026-11-20 00:01:30 +08:00 | 新增 4.3 四路镜头外参微调、按方案分槽独立保存与 JSON 导出 | 针对 E5 与星舰7前后摄俯仰/高度差，支持 `ΔPitch/Yaw/Roll/X/Y/Z` 实时调校与 `(44, 68)` 重算、内置/本地槽互不覆盖及 `calibration.json` 导出；`530` 测与构建/Lint 全过（`0` errors） | [实车修复与微调](tasks/fix-e5-realcar-360-guidance-mode-d-ui.md)
- 2026-11-19 23:04:30 +08:00 | 新增 360「编辑布局」引导线宽度调整与四分栏统一按键底色透明度 | 顶栏在 `360 A` 开放「编辑布局」，新增右上前/后视引导线宽度（`50%–150%`）与 A/B/C/D 统一按键底色透明度（`0%–100%`，默认 `80%`）滑条及关按键黑框消隙；`524` 测与双风味构建/Lint 全过 | [实车修复与调校](tasks/fix-e5-realcar-360-guidance-mode-d-ui.md)

## 归档与按需索引

- **归档全索引**：[2026-11 历史文档与任务全索引](archive/2026-11/INDEX.md)（`T01`–`T10`、`build01`–`build13`、早期报告均已收录）；APK 归档见 `outputs/archive/INDEX.md`
- **按需规范**：[需求](REQUIREMENTS.md) · [坑表](PITFALLS.md) · [协作](COLLABORATION.md) · [开发](DEVELOPMENT.md) · [本机资料](AI_HANDOFF.md)
