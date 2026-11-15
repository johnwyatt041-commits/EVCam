# 项目进度

最后更新/核对：2026-11-15 22:50:24 +08:00（Asia/Hong_Kong）
维护：Codex / Antigravity（`T02` 已完成编码与离线验证 `72dc456`，待实车；公开封存点仍为 `build01`）。执行规范：[PROGRESS_RULES.md](PROGRESS_RULES.md)。

## 当前快照

| 项目 | 当前状态 |
| --- | --- |
| 项目/设备 | E:\GPT\EVCam；银河E5，2560×1600 |
| 分支 | `codex/e5-recording-startup-fix`；本地最新源码 `72dc456`（`T02`）；最近公开 Tag `v1.6.6-test-11152059-build01`（`ea395a0`） |
| 最新编译版本 | `1.6.6-test-11152245`（`T02` 离线已编 Debug/Release APK）；`outputs/` 最近封存交付仍为 **`build01`：`1.6.6-test-11152059`** |
| APK源码 | `T02` 本地源码 `72dc456`；`build01` 关联 `T01` `ea395a0` 与 `outputs/EVCam-E5-build01-1.6.6-test-11152059-manifest.json` |
| 本地交付 | `outputs/EVCam-E5-{A-Independent,B-Composite}-1.6.6-test-11152059-build01-release.apk` 及 SHA/mappings/notes |
| GitHub | 已推送分支截至 `build01` [`v1.6.6-test-11152059-build01`](https://github.com/johnwyatt041-commits/EVCam/releases/tag/v1.6.6-test-11152059-build01)；`T02`（`72dc456`）暂仅本地提交 |
| 验证 | `T02`：`tools/verify.ps1` 188 项(每风味 94 项)JVM、Debug/Release R8 通过；`:app:lintIndependentDebug` 保持 19 项既有错误（0 新增） |
| 实车 | `T01`（`build01`）与 `T02` 均**尚未上车实测**；A/Smartcam 四段短片仅静态/离线分析 |

## 当前结论

- **`T02` 编码与离线验证完成（待实车）**：完成三态不重建主题（全新默认跟随系统+旧 `light_theme` 迁移）、SC 4+2 左栏与回看视频/照片合并入口、7 项精简设置目录与 `1.外观与交互`/独立子页拆分；离线 188 项单测与 Release R8/0 新增 Lint 通过，见 [T02任务](tasks/t02-shell-navigation-and-theme.md)。
- **`build01` 记录点已封存（尚未实车）**：将 `T01` 成果打包为 A/B 两版 Release APK 校验签名与原库一致后同步推送 GitHub；其余详情见 [T01/build01任务](tasks/t01-lifecycle-and-signal-foundation.md)。
- 四段录像中A后台行驶约24.95帧、开预览19.76帧；Smartcam文件约24.5帧但分格近重复，估计20.5–22.6次更新/秒。仅代表各自配置：A三路/Smartcam四路负载不等，同负载优劣与画质未定，B未测。Smartcam有Surface销毁解绑主预览机制，后台瓶颈未锁定。[报告](RECORDING_COMPARISON_20261115.md)
- 已完成 Smartcam V3.0.56 深度静态剖析：`Lkh.d` 退界面仅清 UI，主进程仍可由 `BootReceiver`/15m `KeepAliveWorker` 唤醒；重头在 6 槽 FBO（`~93.75 MiB`）与 `~10.9 Mbps` 编码、及 `Lr51`/`Lwz.a`/`Lo13`/`Lf9` 节流，建议保留 EVCam 并逐步按 `T03`–`T10` 优化。[3.0.56差异](SMARTCAM_V3.0.56_DIFF.md)
- 用户已确认前次失败为车机权限不足；撤销`2082be7`保留1–5项已确认优化（精简SDK+R8、Glide限额、纹理时间戳复用、保留扫码/飞书/补盲/双HUD）；R8 Room 保留与无障碍保活见 [P013/P014](PITFALLS.md)。
- A为三路独立文件；B为1280×800三格合成（每格约640×400、右下黑）；飞书四路独立；模拟器仅覆盖基础采集/水印/HTTP 扫码，不能替代 E5 实车硬件、VHAL、双 HUD 与长期稳定性测试。

## 下一步

1. 等用户从 [蓝图](EVCAM_SMARTCAM_REFACTOR_PLAN.md) 指定下一授权任务包（建议优先 **`T03`** 多模式主预览框架与动态关流，或 **`T04`** 悬浮小窗与补盲镜像解耦+双 HUD）。
2. 待实车确认：`T01` 退出防复活与 4 类信号、`T02` E5 实机三态主题无重建/录像中切主题、左栏 4+2/合并回看/设置分类触控，以及 A/B 同场景帧率与 PSS 对照。
3. 确认原车 HUD 真实路径与后续在 `T04` 落地双镜像解耦；19 项既有 Lint 错误仍待独立整改。

## 最近更新（最多5条）

- 2026-11-15 22:50:24 +08:00 | T02完成(离线) | 实施 `T02` 三态不重建主题、SC 4+2 左栏/回看合并、7 项精简设置目录与子页拆分（`72dc456` / `1.6.6-test-11152245`）；`tools/verify.ps1`（188 测）+ Release R8 + 0 新增 Lint 通过，待实车 | [T02任务](tasks/t02-shell-navigation-and-theme.md)
- 2026-11-15 22:06:00 +08:00 | T02定稿 | 完成 SC V3.0.56 与 EC 外壳/主题/设置核查，用户拍板「默认跟随系统 / SC 式回看合并入口 / 仅列已实装设置分类」，待新会话实施编码 | [T02任务](tasks/t02-shell-navigation-and-theme.md)
- 2026-11-15 21:36:00 +08:00 | build01存档 | 基于 `T01`（`1.6.6-test-11152059`）打包 `build01` 双风味 Release APK、校验 SHA256/R8 映射并推送 GitHub 标签与 Pre-release（尚未实车测试），转入 `T02` 源码核查 | [T01任务](tasks/t01-lifecycle-and-signal-foundation.md)
- 2026-11-15 21:08:30 +08:00 | T01完成(离线) | 收敛单进程双退出状态与 Option A 重启拦截、清理补盲弹窗/动效、落盘 4 类车辆信号 Hub；174 项单测、Debug/Release R8 与 0 新增 Lint 通过，待实车 | [T01任务](tasks/t01-lifecycle-and-signal-foundation.md)
- 2026-11-15 20:10:42 +08:00 | 规划定稿 | 用户定稿「帧率方案 A」及「飞书若启用仅在 P 档加载、关闭/其他档位彻底断连」规则；更新 `T01`–`T10` 蓝图与任务文档，未改 `app/`/`outputs/` | [蓝图](EVCAM_SMARTCAM_REFACTOR_PLAN.md) · [任务](tasks/evcam-smartcam-refactor-plan.md)

## 按需索引

- [融合重构蓝图](EVCAM_SMARTCAM_REFACTOR_PLAN.md) · [需求](REQUIREMENTS.md) · [协作](COLLABORATION.md) · [坑](PITFALLS.md) · [新AI资料](AI_HANDOFF.md) · [Smartcam全功能](SMARTCAM_FEATURE_CATALOG.md) · [3.0.56差异](SMARTCAM_V3.0.56_DIFF.md)
- [E5启动分析](E5_STARTUP_FIX_20261114.md) · [A/B试录](RECORDING_AB_TEST.md) · [开发](DEVELOPMENT.md)
- 本轮证据：`work/ab-test-preoptimization/`；历史启动证据仍在`work/e5-startup/`，不默认全读。
- [整理前历史快照](archive/2026-11/STATUS_20261114_183403.md)；历史按需读取。
