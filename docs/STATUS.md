# 项目进度

最后更新/核对：2026-11-21 02:59:13 +08:00（Asia/Hong_Kong）
维护：Antigravity（已提交 `3869f7f`、封包 `build21` 并发布至 fork Pre-release [`v1.6.6-test-11210240-build21`](https://github.com/johnwyatt041-commits/EVCam/releases/tag/v1.6.6-test-11210240-build21)，`534` 测、`0` Lint error、6 资产远端 SHA256 核验一致；待银河 E5 实车测试）。执行规范：[PROGRESS_RULES.md](PROGRESS_RULES.md)。

## 当前快照

| 项目 | 当前状态 |
| --- | --- |
| 分支与提交 | `codex/e5-recording-startup-fix`；源码提交 **`3869f7f`**（含方案 B 修复与原厂 `avm_calib_res` 导入，`1.6.6-test-11210240`，`versionCode = 86`） |
| 本地交付 | `outputs/` 顶层保留 **`build20`** + **`build21`**（各 6 文件，共 12 文件；`build19` 已归档至 `outputs/archive/`） |
| GitHub | `johnwyatt041-commits/EVCam` 最新 Pre-release：[`v1.6.6-test-11210240-build21`](https://github.com/johnwyatt041-commits/EVCam/releases/tag/v1.6.6-test-11210240-build21)（6 资产 SHA256 一致） |
| 离线与设备验证 | `build21` 已通过 `verify.ps1` 双风味 `534` 测（`0` 失败/`0` 跳过）、`assembleDebug`/`assembleRelease`、`0` Lint error（`2180` warnings）与签名/资产核验；**尚未上实车测试** |
| 实车状态 | 针对 `build20` 360 比例/错位与接缝反馈已完成 `190×462` 车框、零跳变重投影、`Scale` 缩放+辅助工具与原厂 `avm_calib_res` 直导并发布 `build21`，待装车实测 |

## 当前结论与确认边界

- **`build21` 已合并发布方案 B 修复与原厂 `avm_calib_res` 格式直导**：详见 [实车修复与 4.3 外参微调](tasks/fix-e5-realcar-360-guidance-mode-d-ui.md)（E5 实车车框 `190×462` / PNG `226×464`、静态 `bird.mesh` 零跳变原位重投影、`4.3` 新增 `Scale` 与 `Δcx/Δcy/Δk1` + `250 cm` 参考网格/隐藏车模/单路独显、以及 `Table.db` + 704B `.param` 原厂目录扫描/`.zip`/SAF 文件夹直导）。
- **手机端与 `build19`/`build20` 既有基线保持完整**：APVP `40007`、双层纯两侧引导线、模式 D 转向灯联动、四分栏统一透明度、`.mp4.pending` 权限启动修复与星舰7默认标定均保持。
- **用户规则拍板（2026-11-18 已确认）**：① **鱼眼矫正保持关闭**（`AppFeatures.FISHEYE_CORRECTION = false`）；② **ESP32 与浏览器投屏保持保留不动**（`AppFeatures.NETWORK_STREAMING = true`）。

## 下一步

1. 下载安装 `build21`（`1.6.6-test-11210240`）上银河 E5 实车使用 4.3「参考网格/隐藏车模/单路独显/焦距缩放 Scale」调校测试，或导入提取到的 E5 原厂 `avm_calib_res` 标定文件夹。

## 最近更新（最多5条）

- 2026-11-21 02:59:13 +08:00 | 封包发布 build21 并推送 GitHub Pre-release（`1.6.6-test-11210240`） | 源码 `3869f7f` 打标 `v1.6.6-test-11210240-build21` 推送，A/B APK 与 `mappings.zip` 等 6 资产远端 SHA256 一致、`build19` 归档，`534` 测与 Lint `0` errors，待实车测试 | [实车修复与微调](tasks/fix-e5-realcar-360-guidance-mode-d-ui.md)
- 2026-11-21 01:49:06 +08:00 | 记录 `build20` 实车 360 错位现象并核对原厂标定与车模影响（纯文档，未改源码） | 用户确认车头/车尾与两侧对不上、比例怪、缝隙明显（星舰7车模）；确认 PNG 不参与投影但源码车框 `213×534 cm` 偏大拉歪比例与接缝，获取 E5 原厂 `avm_calib_res` 可治本，当前仅支持 JSON/ZIP 导入 | [实车修复与微调](tasks/fix-e5-realcar-360-guidance-mode-d-ui.md)
- 2026-11-20 00:43:30 +08:00 | 封包发布 build20 并推送 GitHub Pre-release（`1.6.6-test-11192353`） | 源码 `4836da2` 打标 `v1.6.6-test-11192353-build20` 推送，A/B APK 与 `mappings.zip` 等 6 资产远端 SHA256 核验一致、`build18` 归档，待实车复测 | [实车修复与微调](tasks/fix-e5-realcar-360-guidance-mode-d-ui.md)
- 2026-11-20 00:01:30 +08:00 | 新增 4.3 四路镜头外参微调、按方案分槽独立保存与 JSON 导出 | 针对 E5 与星舰7前后摄俯仰/高度差，支持 `ΔPitch/Yaw/Roll/X/Y/Z` 实时调校与 `(44, 68)` 重算、内置/本地槽互不覆盖及 `calibration.json` 导出；`530` 测与构建/Lint 全过（`0` errors） | [实车修复与微调](tasks/fix-e5-realcar-360-guidance-mode-d-ui.md)
- 2026-11-19 23:04:30 +08:00 | 新增 360「编辑布局」引导线宽度调整与四分栏统一按键底色透明度 | 顶栏在 `360 A` 开放「编辑布局」，新增右上前/后视引导线宽度（`50%–150%`）与 A/B/C/D 统一按键底色透明度（`0%–100%`，默认 `80%`）滑条及关按键黑框消隙；`524` 测与双风味构建/Lint 全过 | [实车修复与调校](tasks/fix-e5-realcar-360-guidance-mode-d-ui.md)

## 归档与按需索引

- **归档全索引**：[2026-11 历史文档与任务全索引](archive/2026-11/INDEX.md)（`T01`–`T10`、`build01`–`build13`、早期报告均已收录）；APK 归档见 `outputs/archive/INDEX.md`
- **按需规范**：[需求](REQUIREMENTS.md) · [坑表](PITFALLS.md) · [协作](COLLABORATION.md) · [开发](DEVELOPMENT.md) · [本机资料](AI_HANDOFF.md)
