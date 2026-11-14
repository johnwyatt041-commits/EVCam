# EVCam E5 项目入口

更新：2026-11-14 19:08:17 +08:00。项目根目录：`E:\GPT\EVCam`。

## 每次任务必读

1. [AGENTS.md](AGENTS.md)：项目强制规则。
2. [进度同步规范](docs/PROGRESS_RULES.md)：有效改动/重要发现及时记录，每次结束必须核对更新。
3. [当前进度](docs/STATUS.md)：唯一当前状态入口，版本/源码/验证/待办以此为准。
4. [需求](docs/REQUIREMENTS.md)与[协作规范](docs/COLLABORATION.md)：保留能力、分工和独立工作树。

同时检查 `git status --short`、`git branch --show-current`、`git log -5 --oneline` 及 `work/ACTIVE_TASKS.md`。不把其他对话口头结论当成已同步。

## 目录索引

| 路径 | 用途 |
| --- | --- |
| `app/src/main/` | 应用源码与资源 |
| `app/src/test/`、`app/src/androidTest/` | 保留的回归测试源码 |
| `docs/`、`docs/tasks/`、`docs/archive/` | 当前文档、任务进度、按需历史 |
| `tools/` | 可复用构建/验证流程 |
| `assets/`、`gradle/`、`keystore/` | 工程依赖资源、构建包装器和既有签名配置 |
| `outputs/` | 正式/历史交付APK、校验与说明，保留 |
| `work/` | 本机工具/参考/必要证据，分类见`work/README.md` |
| `work/tmp/<task-id>/` | 新任务可清理临时文件；结束清理 |
| `.codex-attachments/` | 用户原附件，保留且不提交 |
| `.gradle/`、`app/build/`、`build/` | 可重建的构建缓存/产物；按需回收 |

## 按需读取

- [新AI交接与Smartcam本机资料](docs/AI_HANDOFF.md)
- [当前有效坑](docs/PITFALLS.md)
- [开发/构建](docs/DEVELOPMENT.md) · [架构](docs/ARCHITECTURE.md)
- 历史、测试日志、长分析只通过进度中的链接按需读取，不默认加载整套归档。

`docs/`纳入Git；`work/`、`outputs/`是本机参考/证据/交付，不随新工作树或GitHub克隆复制。用户附件不混入提交。

## 新AI开场模板

```text
项目：E:\GPT\EVCam，银河E5，屏幕2560×1600。
先读 AGENTS.md、START_HERE.md、docs/PROGRESS_RULES.md、docs/STATUS.md。
本任务范围：【填写】。并行修改先登记，建立独立工作树和codex/分支。
有效改动/重要发现立即更新任务文档；每次结束核对时间戳、证据、待办。
总览只留当前状态与最近5条，历史归档。未同步文档不得宣告完成。
```
