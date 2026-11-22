# EVCam E5 项目入口

更新：2026-11-22 19:18:00 +08:00。项目根目录：`E:\GPT\EVCam`（银河 E5，`2560×1600`，**仅维护与发布 A 版 `independent`，B 版 `composite` 已正式下线**）。

## 开局必读（精简 4 步）

1. [AGENTS.md](AGENTS.md)：项目强制规则与编码红线。
2. [START_HERE.md](START_HERE.md)：本入口与目录定位。
3. [当前进度](docs/STATUS.md)：唯一当前状态、未提交改动、待办与历史归档链接。
4. 本任务文档 `docs/tasks/<task-id>.md`（新任务按规范创建；并行时核对 `git status --short`、`git branch --show-current`、`git log -5 --oneline` 与 [work/ACTIVE_TASKS.md](work/ACTIVE_TASKS.md)）。

## 目录索引

| 路径 | 用途 |
| --- | --- |
| `app/src/main/`、`app/src/test/` | 应用源码、资源与回归测试 |
| `docs/`、`docs/tasks/` | 当前规范、活跃任务；历史在 `docs/archive/`（按需） |
| `tools/` | `verify.ps1` 构建校验与 `check_docs.py` 文档检查 |
| `assets/`、`gradle/`、`keystore/` | 工程依赖资源、Gradle 包装器与签名配置 |
| `outputs/`、`outputs/archive/` | 顶层仅留最新基线（`build18`/`build19`）；早期包在 `archive/`（索引见 `outputs/archive/INDEX.md`） |
| `work/`、`work/archive/` | 本机工具/参考/3.0.56 反编译；历史脚本与轮次在 `archive/`（本机索引 `work/archive/INDEX.md`） |
| `work/tmp/<task-id>/` | 新任务可清理临时文件；收尾必须清理 |
| `.codex-attachments/` | 用户原始附件，保留且不提交 |

## 按需查阅（禁止作开局必读）

- 规范与约束：[进度规范](docs/PROGRESS_RULES.md) · [需求边界](docs/REQUIREMENTS.md) · [协作规范](docs/COLLABORATION.md) · [有效坑表](docs/PITFALLS.md)
- 开发与参考：[开发/构建](docs/DEVELOPMENT.md) · [架构定位](docs/ARCHITECTURE.md) · [本机资料与 Smartcam](docs/AI_HANDOFF.md) · [3.0.56 类映射](docs/SMARTCAM_V3.0.56_DIFF.md)
- 历史归档索引：历史报告、旧 `T01`–`T10` 与已收官清理任务均已归档至 [docs/archive/2026-11/INDEX.md](docs/archive/2026-11/INDEX.md)，仅按需查阅。

## 新 AI 开场模板

```text
项目：E:\GPT\EVCam，银河 E5，2560×1600。
开局只读：AGENTS.md → START_HERE.md → docs/STATUS.md → 本任务 docs/tasks/<task-id>.md。
其余 docs/*（REQUIREMENTS/PITFALLS/DEVELOPMENT/SMARTCAM_V3.0.56_DIFF 等）按模块需要查阅。
有效改动/重要发现及时同步任务文档与总览；结束运行 python tools/check_docs.py。
```
