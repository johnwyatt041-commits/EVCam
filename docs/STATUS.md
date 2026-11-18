# 项目进度

最后更新/核对：2026-11-18 09:19:00 +08:00（Asia/Hong_Kong）
维护：Antigravity（已完成 `W1` 图标代码提交 `904e500` 与工作区/文档整理归档；已发布交付基线仍为 **`build13`**）。执行规范：[PROGRESS_RULES.md](PROGRESS_RULES.md)。

## 当前快照

| 项目 | 当前状态 |
| --- | --- |
| 分支与提交 | `codex/e5-recording-startup-fix`；最新代码 `904e500`（`W1` 图标 `1.6.6-test-11171732` / `versionCode = 82`）+ 本次整理提交（未推送 GitHub） |
| 已发布 APK | **`build13`：`1.6.6-test-11171608`**（A `6,791,918` / B `6,808,302` B）；`W1` 现编 `1.6.6-test-11171732` 暂未封包入 `outputs/` |
| 本地交付 | `outputs/` 顶层仅留 `build12` + `build13`（12 文件 / 50.28 MB）；早期 135 项已归档至 `outputs/archive/` |
| GitHub | `johnwyatt041-commits/EVCam` 远端最新仍为 `build13` Pre-release（`8ef5b36`）；本地 `W1` 与本次整理尚未推送 |
| 离线验证 | `904e500`（`W1`）离线通过：A/B `432` 测（0 失败/跳过）、双风味 Lint **`0 errors`**、Release R8/`apksigner`/`aapt2`；文档校验通过 `tools/check_docs.py` |
| 实车状态 | `build01`–`build13` 与 `W1` 均**尚未上车实测**；实车待检项汇总于 [build13 与 T01–T10 验收清单](archive/2026-11/tasks/build12-e5-acceptance-and-lint-cleanup.md) |

## 当前结论与确认边界

- **工作区整理完成**：`docs/`（17→9）与 `docs/tasks/`（23→2）已精简归档；`work/` 清除 `229` 个临时测试 `.mp4`（释放 `11.23 MB`）、保留全部截图/缩略图与 `3.0.56`/`3.0.40` 反编译档案；删除 `logcat_debug.txt`，归档 `.claude/settings.local.json`。
- **用户规则拍板（2026-11-18 已确认）**：① **鱼眼矫正保持关闭**（`AppFeatures.FISHEYE_CORRECTION = false`）；② **ESP32 与浏览器投屏保持保留不动**（`AppFeatures.NETWORK_STREAMING = true`）。
- **`W1`（白色主题 · 精密镜头）图标已提交至 `904e500`（暂未打包发布）**：详情见 [图标任务](tasks/app-icon-redesign.md)。

## 下一步

1. 决定是否推送分支、封装 `W1`（`build14`）发布至 GitHub，随后按 [build13 验收清单](archive/2026-11/tasks/build12-e5-acceptance-and-lint-cleanup.md) 在银河 E5 上车实测并开启后续整改包。

## 最近更新（最多5条）

- 2026-11-18 09:19:00 +08:00 | 目录与文档整理 | 归档 22 份旧任务与 8 份阶段报告至 `docs/archive/2026-11/`，精简 `START_HERE.md`/`STATUS.md`/`work/`/`outputs/`，删除 229 个测试 `.mp4` 与 `logcat_debug.txt`，保留图片与反编译资料并确认不启用鱼眼、保留 ESP32 | [整理任务](tasks/workspace-cleanup-and-doc-slimming.md)
- 2026-11-18 09:18:00 +08:00 | 图标提交（`904e500`） | 提交用户选定 **`W1`（白色主题·精密镜头）** 全套图标与单色通知图标（`1.6.6-test-11171732`，暂未封包推送）；`432` 测、双风味 `0` Lint error、R8/`apksigner`/`aapt2` 通过（未实车） | [图标任务](tasks/app-icon-redesign.md)
- 2026-11-17 16:16:18 +08:00 | build13存档 | 清零全部 18 项 Lint + 加固接收器并补 9 项单测，`versionCode = 82`（`8ef5b36` / `1.6.6-test-11171608`）发布 `build13`（未实车） | [验收与Lint任务](archive/2026-11/tasks/build12-e5-acceptance-and-lint-cleanup.md)
- 2026-11-17 01:49:00 +08:00 | build12存档 | 完成 **`T10`**（`29fe92b` / `1.6.6-test-11170138`）并发布 `build12`（飞书 P 档门控、私有 GitHub 更新与日志连贯性，未实车） | [T10任务](archive/2026-11/tasks/t10-feishu-pgear-github-update-and-log-coherence.md)
- 2026-11-17 00:16:00 +08:00 | build11存档 | 完成 **`T09`**（`3c3c2ba` / `1.6.6-test-11170004`）并发布 `build11`（E5 离线配置广场与权限诊断，未实车） | [T09任务](archive/2026-11/tasks/t09-config-plaza-and-permissions.md)

## 13 个 Build 与归档按需索引

- **Build 全集任务记录（`build01`–`build13`）**：[build13 零 Lint 与实车清单](archive/2026-11/tasks/build12-e5-acceptance-and-lint-cleanup.md) · [T01](archive/2026-11/tasks/t01-lifecycle-and-signal-foundation.md) · [T02](archive/2026-11/tasks/t02-shell-navigation-and-theme.md) · [T03](archive/2026-11/tasks/t03-multi-mode-preview-and-dynamic-stream.md) · [T04](archive/2026-11/tasks/t04-floating-architecture-and-blindspot-fusion.md) · [T05](archive/2026-11/tasks/t05-video-pipeline-and-recorder-32-34.md) · [T06](archive/2026-11/tasks/t06-vehicle-rules-and-signal-presets.md) · [T07](archive/2026-11/tasks/t07-surround-360-and-mode-a.md) · [T08](archive/2026-11/tasks/t08-playback-and-lan-share.md) · [T09](archive/2026-11/tasks/t09-config-plaza-and-permissions.md) · [T10](archive/2026-11/tasks/t10-feishu-pgear-github-update-and-log-coherence.md)
- **阶段蓝图与专题归档**：[重构蓝图](archive/2026-11/EVCAM_SMARTCAM_REFACTOR_PLAN.md) · [Smartcam 3.0.40 全功能](archive/2026-11/SMARTCAM_FEATURE_CATALOG.md) · [3.0.56 差异（按需）](SMARTCAM_V3.0.56_DIFF.md) · [历史快照（2026-11-14）](archive/2026-11/STATUS_20261114_183403.md)
- **按需规范**：[需求](REQUIREMENTS.md) · [坑表](PITFALLS.md) · [协作](COLLABORATION.md) · [开发](DEVELOPMENT.md) · [本机资料](AI_HANDOFF.md)
