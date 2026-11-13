# EVCam E5 项目入口

本项目已迁往 **`E:\GPT\EVCam`**。这是 Git 仓库根目录，可直接作为后续本地开发对话的项目目录，不需要进入另一个同名子目录。

## 新对话先读

1. `AGENTS.md`：仓库规则及本项目限制。
2. `docs/REQUIREMENTS.md`：用户已经确认的要求，避免重复询问或恢复已删除功能。
3. `docs/STATUS.md`：当前代码、发布版本、验证结果与待办。
4. 修改前读 `docs/COLLABORATION.md`；构建时读 `docs/DEVELOPMENT.md`。

按任务参考 `docs/ARCHITECTURE.md`。本地搬迁的校验结果见 `docs/MIGRATION.md`。每次完成工作都更新进度；口头聊天记录不是唯一交接依据。

## 当前可下载版本

- 版本：`1.6.6-test-11140102`，E5 性能精简、HUD 保留、统一深浅色 UI 和可编辑四路预览布局。
- 应用源码提交：`c9c493ef2eac5d0956677c3b3835f0a67cb31ccb`。
- 分支：`e5-ui-layout-test-11140102`。迁移文档可能在此提交之后，使用 `git log -5 --oneline` 看最新状态。
- [GitHub 测试版发布页](https://github.com/johnwyatt041-commits/EVCam/releases/tag/v1.6.6-test-11140102-e5-ui)
- 本地 APK：`outputs/EVCam-E5-UI-1.6.6-test-11140102.apk`。
- SHA-256：`e6fcd0204b28ec5a7d2c1f76516c86627670bee971803cef89a7bacbb6fd54c2`。

49 项 JVM 单元测试、Debug/Release 构建、必要 Lint 和模拟器 UI 检查已通过；**未完成 E5 摄像头/HUD 实车回归及 CPU、内存、帧率对照测试**。用户只做过短时间试录，感觉更流畅，尚未确认。

## 目录

| 路径 | 内容 |
| --- | --- |
| `app/`、`assets/`、`gradle/` | Android 源码与构建配置 |
| `.git/` | 完整本地 Git 历史和分支 |
| `docs/` | 需求、进度、架构、开发和协作交接文档，纳入 Git |
| `tools/verify.ps1` | 从当前工作树运行构建与 JVM 测试 |
| `outputs/` | 已发布/历史 APK、补丁、校验文件和 UI 截图，本地保留 |
| `work/` | JDK、视频分析证据、模拟器数据、日志与历史辅助脚本，本地保留 |

## 给新对话的开场模板

```text
这个对话负责 EVCam 项目的【任务名称】。
项目主目录是 E:\GPT\EVCam。先阅读 AGENTS.md、START_HERE.md、
docs/REQUIREMENTS.md、docs/STATUS.md 和 docs/COLLABORATION.md，
再检查 Git 当前状态。保留既定的录制、飞书与 HUD 能力。
如果其他对话正在改代码，先建立独立工作树并登记负责范围，避免修改同一文件。
完成后记录改动、提交、验证结果和未验证事项，更新项目进度。
本次具体目标：【填写】。
```

把项目目录绑定到具有本地文件访问能力的开发对话后，要求每个新对话主动读这些文件；不要假定聊天标题或同属一个项目就会自动同步未写入文件的决定。
