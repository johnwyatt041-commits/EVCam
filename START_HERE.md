# EVCam E5 项目入口

本项目已迁往 **`E:\GPT\EVCam`**。这是 Git 仓库根目录，可直接作为后续本地开发对话的项目目录，不需要进入另一个同名子目录。

## 新对话先读

1. `AGENTS.md`：仓库规则及本项目限制。
2. `docs/REQUIREMENTS.md`：用户已经确认的要求，避免重复询问或恢复已删除功能。
3. `docs/STATUS.md`：当前代码、发布版本、验证结果与待办。
4. 修改前读 `docs/COLLABORATION.md`；构建时读 `docs/DEVELOPMENT.md`。

按任务参考 `docs/ARCHITECTURE.md`。本地搬迁的校验结果见 `docs/MIGRATION.md`。每次完成工作都更新进度；口头聊天记录不是唯一交接依据。

## 当前可下载版本

**E5 用户反馈 `11140958` A/B 均停在等待录制。当前分支 `codex/e5-recording-startup-fix`，已上传修正候选 `1.6.6-test-11141103`。请先读 [E5 启动修正](docs/E5_STARTUP_FIX_20261114.md) 和 `docs/STATUS.md`；没有失败现场日志，仍待 E5 实车确认。**

- 最新测试版：`1.6.6-test-11141103`，E5 启动兼容修正，保留大屏侧栏与 A/B 对照。
- [GitHub 下载页](https://github.com/johnwyatt041-commits/EVCam/releases/tag/v1.6.6-test-11141103-e5-startup-fix)：A 为三路独立录像，B 为 E5 原生画布 2×2 三格合成；飞书四路仍独立，同包名顺序覆盖安装。
- APK 源码提交 `85870d254c9e4fec75dcae60ca01744946aad535`。8 个附件 SHA-256 与本地一致，两个公开 APK 下载链接均 HTTP 200。原视频没有上传。
- 用户本轮明确要求上传至 GitHub，已解除此前待上传状态；实车性能和触控手感仍待确认。

## 此前公开 UI 版本（历史）

- 版本：`1.6.6-test-11140102`，E5 性能精简、HUD 保留、统一深浅色 UI 和可编辑四路预览布局。
- 应用源码提交：`c9c493ef2eac5d0956677c3b3835f0a67cb31ccb`。
- 分支：`e5-ui-layout-test-11140102`。迁移文档可能在此提交之后，使用 `git log -5 --oneline` 看最新状态。
- [GitHub 测试版发布页](https://github.com/johnwyatt041-commits/EVCam/releases/tag/v1.6.6-test-11140102-e5-ui)
- 本地 APK：`outputs/EVCam-E5-UI-1.6.6-test-11140102.apk`。
- SHA-256：`e6fcd0204b28ec5a7d2c1f76516c86627670bee971803cef89a7bacbb6fd54c2`。

49 项 JVM 单元测试、Debug/Release 构建、必要 Lint 和模拟器 UI 检查已通过；**未完成 E5 摄像头/HUD 实车回归及 CPU、内存、帧率对照测试**。用户只做过短时间试录，感觉更流畅，尚未确认。

## 后续测试版本与本地文件

**最新：大屏侧栏优化 `1.6.6-test-11140958`**，分支 `codex/large-screen-navigation`。按用户 2560×1600 屏幕放大侧栏按钮/图标/文字，适配不同 DPI；A/B 两版同步。交付说明 `outputs/EVCam-LargeNav-notes.md`，完整状态见 `docs/STATUS.md`。

- A：`outputs/EVCam-A-Independent-LargeUI-1.6.6-test-11140958.apk`；B：`outputs/EVCam-B-Composite-LargeUI-1.6.6-test-11140958.apk`。
- 源码 `4268f03821bceb82c7489680831e435b48e3b504`。两版构建/签名/源码 revision 校验通过；大屏三种密度、六项边缘触控、深浅色和大字体已验证。原录像对照包仍保留。

当前交付 **录像整改 A/B `1.6.6-test-11140403`**，分支 `codex/recording-ab-comparison`。A 保留日常三路独立文件，B 参考 Smartcam 思路合成单个 H.264 视频；两版同包名顺序覆盖安装，飞书四路都仍独立。详见 [对照试录说明](docs/RECORDING_AB_TEST.md) 和 `docs/STATUS.md`；旧 GitHub 发布尚未更新。

- A：`outputs/EVCam-A-Independent-1.6.6-test-11140403.apk`；B：`outputs/EVCam-B-Composite-1.6.6-test-11140403.apk`。
- 应用源码 `f8a8e6ee60279925f575931da67497c737ce6a79`；每版 73 项 JVM、实际编码分段/GPU 与合成回放检查通过，签名及内嵌源码 revision 已核验。实车性能仍需对照测试。

- `1.6.6-test-11140245`：默认关闭的录像车辆状态水印，显示车速、刹车踏板、左右转向；未收到信号显示未知，E5 刹车字段可用性尚待实车确认。
- 源码 `03a261e04474e2bce3eda834e87665e6d6da6ddc`，分支 `codex/vehicle-status-watermark`。
- 本地 APK：`outputs/EVCam-E5-Status-1.6.6-test-11140245.apk`；64 项 JVM 测试、Debug/Release/必要 Lint、1 项模拟器 GPU 测试与设置开关验证通过。
- GitHub 推送被自动审批拒绝，等待用户对 `johnwyatt041-commits/EVCam` 的本次上传作明确确认。上述“当前可下载版本”仍是旧 GitHub 发布。详情见 `docs/STATUS.md`、`docs/VEHICLE_STATUS_WATERMARK.md`。

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
