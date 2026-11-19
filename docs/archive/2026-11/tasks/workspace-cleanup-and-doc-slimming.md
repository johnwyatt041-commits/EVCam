# 工作路径整理、文档瘦身与历史产物归档

- 最后更新：2026-11-18 09:19:00 +08:00（Asia/Hong_Kong）
- 最后核对：2026-11-18 09:19:00 +08:00（Asia/Hong_Kong）
- 任务/执行者：`workspace-cleanup-and-doc-slimming` / Antigravity
- 分支/工作树：`codex/e5-recording-startup-fix` / `E:\GPT\EVCam`
- 状态：已完成整理、校验与分拆提交（`W1` 源码已提交 `904e500`，本整理同步提交；未改 `app/` 版本）
- 当前结论：完成 `docs/`（17→9）、`docs/tasks/`（23→2）、`work/` 与 `outputs/`（147→12 顶层）整理归档，删除 `229` 个临时测试 `.mp4`（释放 `11.23 MB`）、`8` 个空/死进程临时文件及根 `logcat_debug.txt`；保留全部图片、反编译档案及工具链；确认 **关闭鱼眼矫正** 与 **保留 ESP32/浏览器投屏**。

## 整理清单摘要（未破坏 `app/`、`W1` 已先行提交为 `904e500`）

- **规则与根目录**：`git rm logcat_debug.txt`（694 B）；`git mv .claude/settings.local.json docs/archive/2026-11/legacy-claude-settings.local.json`；核实 `CLAUDE.md` 与 `.cursor/rules/*.mdc` 仅转引 `AGENTS.md`。
- **文档归档与开局精简**：`git mv` 22 份旧任务至 [docs/archive/2026-11/tasks/](.)、8 份阶段报告与旧蓝图/3.0.40 目录至 [docs/archive/2026-11/](../)；`START_HERE.md` 与 [STATUS.md](../../../STATUS.md) 收窄开局必读为 `AGENTS.md` → `START_HERE.md` → `docs/STATUS.md` → `docs/tasks/<task-id>.md`，[SMARTCAM_V3.0.56_DIFF.md](../../../SMARTCAM_V3.0.56_DIFF.md) 标为按需查阅；在 [tools/check_docs.py](../../../../tools/check_docs.py) 沉淀通用文档与死链校验（全仓 0 死链）。
- **`work/` 整理**：保留 `analyze_video.py`、`start_ui_emulator.ps1`、`evcam-vhal-disassembly.txt`、`avd/`、`toolchain/`、`video-deps/`、`signal-deps/`、`reference-apks/`、`smartcam-analysis/3.0.56/`、`app-icon-redesign/`、`tmp/`；删除 `229` 个测试 `.mp4`（`11,774,436` B）与 `8` 个 0 字节/死 PID/空 XML（`287` B），**全部测试图片与截图均保留**；51 个失效根脚本/日志/截图移至 `work/archive/legacy-scripts-and-logs/`；V3.0.40 3.0.40 目录与 55 个散落文件收拢至 `work/archive/smartcam-3.0.40/`；12 个专题目录与 49 条早期构建日志移至 `work/archive/2026-11-runs/`（`work/verification/` 保留 `11-17` 最新 10 条日志）。
- **`outputs/` 整理**：顶层仅留 `build12` + `build13`（12 文件 / `50.28 MB`），其余 135 个历史构建（`586.58 MB`）移入 `outputs/archive/` 并建 `INDEX.md`。
- **规则冲突确认**：按用户拍板，[REQUIREMENTS.md](../../../REQUIREMENTS.md) 明确 `FISHEYE_CORRECTION = false`（不开启鱼眼矫正）且 `NETWORK_STREAMING = true`（ESP32/浏览器投屏保持保留）。

## 最近记录（最多 5 条）

- 2026-11-19 11:26:00 +08:00 | 归档迁入 | 本任务已于 `7d03610` 完成后归档至 `docs/archive/2026-11/tasks/`，后续 `build14`、`11-17`/`11-18` 构建日志与多目录索引同步见 [归档总索引](../INDEX.md) | [STATUS](../../../STATUS.md)
- 2026-11-18 09:19:00 +08:00 | 同步提交 | 先行独立提交 `W1` 图标代码（`904e500`），随后跑通 `tools/check_docs.py` 并提交本轮工作区整理与文档瘦身改动（纯本地提交，未推送） | [STATUS](../../../STATUS.md)
- 2026-11-18 08:53:00 +08:00 | 执行与验证 | 完成文档归档、开局链精简、`work/` 与 `outputs/` 整理及全量相对链接修复；`python tools/check_docs.py` 全仓 0 死链通过，未触碰 `app/` 未提交 `W1` 改动 | [STATUS](../../../STATUS.md)
- 2026-11-18 08:49:24 +08:00 | 边界拍板 | 用户确认 7 项清理边界：可用保留/不可用归档、删测试视频留图片、留反编译删 `logcat_debug.txt`、挂 13 build 链接、归档 `.claude`、不要鱼眼矫正、保留 ESP32/浏览器投屏 | [REQUIREMENTS](../../../REQUIREMENTS.md)
- 2026-11-18 03:01:46 +08:00 | 盘点报告 | 完成全仓只读盘点、体积统计、232 条待修链接扫描与两条规则冲突核对 | —

## 下一步

1. 决定是否推送分支并封包发布 `W1` 新版测试 APK，随后在银河 E5 实车验收 `build13`/`W1` 并推进后续整改任务包。
