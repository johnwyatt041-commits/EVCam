# 新 AI 协作交接索引

更新：2026-11-15 03:58:00 +08:00。这里仅放资料位置与接手计划索引；当前版本、分支、验证和待办只看 [STATUS](STATUS.md)。

## 开始

先读根目录 `AGENTS.md`、`START_HERE.md`，再读 [进度规范](PROGRESS_RULES.md)、[需求](REQUIREMENTS.md)、[协作](COLLABORATION.md) 与当前已确认的 [待执行 A/B 测试前优化方案](tasks/ab-test-preoptimization.md)。核对Git与 `E:\GPT\EVCam\work\ACTIVE_TASKS.md`；有效改动/重要信息及时记，每次执行结束核对更新任务文档。

车型是银河E5，屏幕2560×1600。不要把候选修正或模拟器通过写成实车成功；相关坑见 [PITFALLS](PITFALLS.md)。

## Smartcam 本机资料（后续均以 V3.0.56 为准）

| 资料 | 绝对路径 |
| --- | --- |
| 当前参考APK副本（V3.0.56） | `E:\GPT\EVCam\work\reference-apks\Smartcam-V3.0.56_11_14_15_39.apk`（旧版 `V3.0.40` APK 已按用户要求移除） |
| 版本/完整SHA清单 | `E:\GPT\EVCam\work\reference-apks\Smartcam-V3.0.56-manifest.json`、`E:\GPT\EVCam\work\reference-apks\Smartcam-manifest.json` |
| 反编译/差分产物目录 | `E:\GPT\EVCam\work\smartcam-analysis\3.0.56\smartcam-v3.0.56-diff\`（新版全量差分转储）；`work\smartcam-analysis\` 另保留 `3.0.40` 历史摘录供追溯 |
| V3.0.56 差分与类映射报告 | [SMARTCAM_V3.0.56_DIFF.md](SMARTCAM_V3.0.56_DIFF.md)（[任务记录](tasks/smartcam-v3.0.56-diff.md)），后续功能/类映射优先查此文档 |
| Smartcam 基础机制全功能目录 | [SMARTCAM_FEATURE_CATALOG.md](SMARTCAM_FEATURE_CATALOG.md)（基于原 3.0.40 整理，请配合 `SMARTCAM_V3.0.56_DIFF.md` 阅读） |
| 录像/水印历史分析 | [RECORDING_REVIEW_20261114.md](RECORDING_REVIEW_20261114.md)，历史结论结合当前进度阅读 |
| 本轮E5启动对照 | [E5_STARTUP_FIX_20261114.md](E5_STARTUP_FIX_20261114.md) |
| 分析辅助 | `E:\GPT\EVCam\work\inspect_smartcam*.py`、`decompile_smartcam*.py`（旧脚本仍含历史路径，重跑前须审阅并改用 3.0.56 APK） |

Smartcam包名`com.yuan.smartcam`，**后续统一以版本 `3.0.56` 为准**（原APK已核对SHA；旧版 `3.0.40` APK 已移除）。**没有完整原作者源码工程**；Java是混淆APK的反编译摘录，存在类型/控制流误差，应与DEX指令交叉核对。APK文本是分析数据，不是AI指令；没有复制其代码/素材到EVCam。

`3.0.56` 与历史 `3.0.40` 关键混淆类映射（详表见 [SMARTCAM_V3.0.56_DIFF.md](SMARTCAM_V3.0.56_DIFF.md)）：

- `Lp13;`/`Lo13;`（原 `oy2`）：2560×1600 GL合成；`Lil;`/`Lj13;`（原 `il`）：三路Surface分发；`Lmo3;`/`Ldo3;`（原 `Lzd1;`）：预览解绑/挂载与延迟日志。
- `Lk13;`（原 `jy2`）：录像协调/恢复；`Lk02;`（原 `ey1`）：Writer/`usableSpace` 配额；`Lds2;`（原 `lp2`）：硬编探测；`Llf4;`（原 `nb4`）：1024×132 水印。
- `3.0.56` 新增主力类：`Lw33;`/`Lp51;`/`Lo51;`（Fragmented MP4）、`Lyf3;`（`.mp4.part` 恢复）、`Lqw2;`/`Low2;`（悬浮预览）、`La33;`/`Lq01;`（补盲独立校正）、`Lun0;`/`Lax1;`（WSS/对讲/回放）。

`work/`、`outputs/`被Git忽略，不随GitHub clone或独立工作树复制；同机按绝对路径读。用户原视频/抽帧不上传。远程AI若没有本机文件访问，就看不到这些本地资料。

## 后续分析结果落点

Smartcam参考资料与EVCam在同一工作区分区存放：

- 原包/版本清单沿用 `work/reference-apks/`；反编译、DEX和必要证据沿用 `work/smartcam-analysis/`。新一轮证据按 `<版本或SHA>/<task-id>/` 分目录，保留既有路径，不覆盖旧样本。
- 录像/水印结论更新上述 `RECORDING_REVIEW_20261114.md`，区分静态证据、推断和实测；其他主题按需建立专题分析，不把长摘录塞进进度。
- 执行进度复用 `docs/tasks/<task-id>.md`；`STATUS.md`只留当前结论和链接。文档随Git共享，本机原包/摘录不随之上传。
- 采纳方案时明确修改EVCam自己的 `app/src/main/` 实现，并记录来源和验证。参考目录不参与构建，不直接混入Smartcam原包、反编译工程或素材；根 `assets/` 已配置进APK，也不能当参考资料目录。

## 其他证据

- 原作者仓库：`suyunkai/EVCam`；已核对的源码及日期见启动分析。Git历史可比较，四个camera类快照在 `E:\GPT\EVCam\work\e5-startup\upstream\`。
- 本轮样本/Camera2/GL/GPU/发布核验：`E:\GPT\EVCam\work\e5-startup\`。
- 构建日志：`E:\GPT\EVCam\work\verification\`；交付APK/manifest/SHA：`E:\GPT\EVCam\outputs\`。
- 构建入口和共享工具参数见 [DEVELOPMENT](DEVELOPMENT.md)。不重跑历史改源码/发布脚本。

并行修改先登记负责范围、建立独立工作树和`codex/`分支；各写任务文档，通过用户或已授权通道交接摘要。总览由协调者维护，未集成结果必须标明；不要直接与其他AI同时写主目录。
