# `docs/archive/2026-11/` 历史文档与任务归档索引

更新：2026-11-19 11:26:00 +08:00（Asia/Hong_Kong）。本目录存放已完成或被后续基线取代的历史报告、蓝图、旧快照与任务记录，**不作新对话开局必读**；当前状态请看 [STATUS.md](../../STATUS.md)，当前活跃/待实测任务在 `docs/tasks/`。

## 1. 阶段报告、蓝图与历史快照（11 项）

| 文档 | 内容摘要 | 适用边界 |
| --- | --- | --- |
| [EVCAM_SMARTCAM_REFACTOR_PLAN.md](EVCAM_SMARTCAM_REFACTOR_PLAN.md) | EVCam 融合 Smartcam V3.0.56 `T01`–`T10` 分步重构蓝图与接力规范 | 已在 `build01`–`build13` 收官；最新规范见 `AGENTS.md` |
| [SMARTCAM_FEATURE_CATALOG.md](SMARTCAM_FEATURE_CATALOG.md) | Smartcam `V3.0.40` 全功能与静态机制目录 | 阅读时需对照 [SMARTCAM_V3.0.56_DIFF.md](../../SMARTCAM_V3.0.56_DIFF.md) |
| [STATUS_20261114_183403.md](STATUS_20261114_183403.md) | 2026-11-14 项目进度历史快照 | 仅供追溯，不代表当前状态 |
| [MIGRATION.md](MIGRATION.md) | 2026-11-14 独立项目搬迁至 `E:\GPT\EVCam\` 记录 | 已完成，仅留档 |
| [E5_STARTUP_FIX_20261114.md](E5_STARTUP_FIX_20261114.md) | E5 等待录制不启动的早期分析与兼容修正（`11141103`） | 历史阶段分析 |
| [RECORDING_AB_TEST.md](RECORDING_AB_TEST.md) | 录像架构对照实验 A（三路独立）vs B（合成单文件）方法说明 | 参考对比口径 |
| [RECORDING_REVIEW_20261114.md](RECORDING_REVIEW_20261114.md) | 2026-11-14 实车录像样本与 Smartcam 早期对比 | 仅对应当时样本条件 |
| [RECORDING_COMPARISON_20261115.md](RECORDING_COMPARISON_20261115.md) | 2026-11-15 `rec.zip` 四段录像样本帧率与时间戳分析 | 仅对应当时样本条件 |
| [recording-storage-eperm-before-rollback-20261115.md](recording-storage-eperm-before-rollback-20261115.md) | `11150220` 存储权限 EPERM 候选修复回退前记录 | 已按用户要求撤回（见 `P015`） |
| [VEHICLE_STATUS_WATERMARK.md](VEHICLE_STATUS_WATERMARK.md) | 早期录像车辆状态水印设计（`11140245`） | 刹车字段已在包 D 删除，现行看 [包 D](../../tasks/fix-pack-d-recorder-settings-and-watermark.md) |
| [legacy-claude-settings.local.json](legacy-claude-settings.local.json) | 迁移归档的旧 `.claude/settings.local.json` | 历史留存，非现行配置 |

## 2. 融合重构 `T01`–`T10` 与 `build13` 任务记录（`tasks/`，11 项）

| 任务记录 | 关联构建 / 提交 | 范围摘要 |
| --- | --- | --- |
| [build12-e5-acceptance-and-lint-cleanup.md](tasks/build12-e5-acceptance-and-lint-cleanup.md) | `build13` / `8ef5b36` | `build12` 实车验收总清单与 18 项历史 Lint 清零 |
| [t01-lifecycle-and-signal-foundation.md](tasks/t01-lifecycle-and-signal-foundation.md) | `build01` | 精简架构底座、退出机制与补盲遗留清理 |
| [t02-shell-navigation-and-theme.md](tasks/t02-shell-navigation-and-theme.md) | `build02` / `build05` | 全局外壳 UI、SC 风格左栏与深浅色主题 |
| [t03-multi-mode-preview-and-dynamic-stream.md](tasks/t03-multi-mode-preview-and-dynamic-stream.md) | `build03` | 多模式主预览框架与动态关流（模式 B+C） |
| [t04-floating-architecture-and-blindspot-fusion.md](tasks/t04-floating-architecture-and-blindspot-fusion.md) | `build04` | 悬浮小窗架构与补盲择优融合 |
| [t05-video-pipeline-and-recorder-32-34.md](tasks/t05-video-pipeline-and-recorder-32-34.md) | `build06` | 视频管线优化、调色/水印/提示音 |
| [t06-vehicle-rules-and-signal-presets.md](tasks/t06-vehicle-rules-and-signal-presets.md) | `build07` | 车辆状态规则引擎与信号预设匹配 |
| [t07-surround-360-and-mode-a.md](tasks/t07-surround-360-and-mode-a.md) | `build08` / `build09` | 360 全景 + 分视角模式 A 与辅助设置 |
| [t08-playback-and-lan-share.md](tasks/t08-playback-and-lan-share.md) | `build10` | 回看重构与局域网浏览器分享 |
| [t09-config-plaza-and-permissions.md](tasks/t09-config-plaza-and-permissions.md) | `build11` | 银河 E5 离线配置广场与系统权限诊断面板 |
| [t10-feishu-pgear-github-update-and-log-coherence.md](tasks/t10-feishu-pgear-github-update-and-log-coherence.md) | `build12` / `29fe92b` | 飞书 P 档门控、私有 GitHub 更新与日志连贯性 |

## 3. 早期专题与整理任务记录（`tasks/`，12 项）

| 任务记录 | 范围摘要 |
| --- | --- |
| [workspace-cleanup-and-doc-slimming.md](tasks/workspace-cleanup-and-doc-slimming.md) | `2026-11-18` 工作路径整理、文档瘦身与历史产物归档（`7d03610`） |
| [directory-hygiene.md](tasks/directory-hygiene.md) | `2026-11-14` 目录索引与任务收尾清理规范落地 |
| [progress-documentation-rules.md](tasks/progress-documentation-rules.md) | 统一 `AGENTS.md` 与进度规范落地 |
| [e5-recording-startup.md](tasks/e5-recording-startup.md) | E5 A/B 等待录制不启动修正（`11141103`） |
| [recording-ab.md](tasks/recording-ab.md) | 录像整改与 A/B 双风味实现记录 |
| [large-screen-navigation.md](tasks/large-screen-navigation.md) | 早期 `2560×1600` 大屏侧栏触控优化 |
| [ab-test-preoptimization.md](tasks/ab-test-preoptimization.md) | 实车前代码裁剪、R8 与时间水印优化（`11150014`） |
| [recording-storage-eperm.md](tasks/recording-storage-eperm.md) | 存储修正撤回记录（确认车机权限原因） |
| [recording-comparison-20261115.md](tasks/recording-comparison-20261115.md) | 2026-11-15 EVCam A 与 Smartcam 实车样本对比 |
| [smartcam-feature-inventory.md](tasks/smartcam-feature-inventory.md) | Smartcam `3.0.40` 全功能与静态梳理任务 |
| [smartcam-v3.0.56-diff.md](tasks/smartcam-v3.0.56-diff.md) | Smartcam `V3.0.56` 静态差分与基线切换任务 |
| [evcam-smartcam-refactor-plan.md](tasks/evcam-smartcam-refactor-plan.md) | 融合重构蓝图与接力规则落盘任务 |

## 4. 关联归档索引

- **历史 APK 构建归档**：`outputs/archive/INDEX.md`（Git 忽略，本地查阅：`build14` 及更早全量构建产物）
- **本机资料与验证日志归档**：`work/archive/INDEX.md`（Git 忽略，本地查阅；总览见 [AI_HANDOFF.md](../../AI_HANDOFF.md)）
