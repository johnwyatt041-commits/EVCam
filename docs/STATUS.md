# 项目进度

最后更新/核对：2026-11-14 18:43:23 +08:00（Asia/Hong_Kong）
维护：Codex / progress-documentation-rules。执行规范：[PROGRESS_RULES.md](PROGRESS_RULES.md)。

## 当前快照

| 项目 | 当前状态 |
| --- | --- |
| 项目/设备 | E:\GPT\EVCam；银河E5，2560×1600 |
| 分支 | `codex/e5-recording-startup-fix`；文档HEAD以`git log`为准 |
| 最新APK | `1.6.6-test-11141103`，A/B同包名，停止后顺序覆盖安装 |
| APK源码 | `85870d254c9e4fec75dcae60ca01744946aad535`；后续纯文档提交不改变APK |
| 下载 | [GitHub测试发布](https://github.com/johnwyatt041-commits/EVCam/releases/tag/v1.6.6-test-11141103-e5-startup-fix)；8附件SHA与本地一致、APK链接已验HTTP200 |
| 验证 | 每版78项JVM、Debug/Release/必要Lint、每版3项模拟器录像测试通过 |
| 实车 | **等待用户测试；未确认修复成功，缺少失败当次日志** |

## 当前结论

- 上版`11140958` A/B实车均等待不启动。新候选恢复E5兼容配置、修正会话失败/启动顺序、加入20秒首写超时；唯一实车触发点未确认。
- A保留三路独立文件；B默认1280×800的2×2三格合成，每格约640×400，右下黑；飞书四路仍独立。保留大侧栏、水印、补盲及两条HUD。
- 模拟器验证了单路软件Camera2→公共DCIM→首写和三路合成输入；不能替代E5多路硬件、HEVC、HUD/VHAL、飞书及长期回归。
- 关闭预览旧片24.57fps；样本场景不同，不能据此定性CPU不足。原片仅本地保留。

## 下一步

1. 用户先测A：内置存储、关预览录30秒，再开预览对照；A可录后测B。
2. 若仍失败，取当次日志和提示，按输入/GL/编码/写盘计数定位，不继续猜参数。
3. 再回归飞书四路/恢复、补盲、两条HUD、车辆信号与长时间录像。并行AI改代码前先登记并建独立工作树。

## 最近更新（最多5条）

- 2026-11-14 18:43:23 +08:00 | 规范 | 强制同步/时间戳生效；总览142→43行，历史保留，篇幅/链接检查通过 | [任务](tasks/progress-documentation-rules.md)
- 2026-11-14（时刻未记录） | 交接 | Smartcam原APK已复制进项目并验SHA，反编译摘录可本机共享 | [索引](AI_HANDOFF.md)
- 2026-11-14（时刻未记录） | 发布 | `11141103` A/B已上传并验附件，待E5实车 | [启动任务](tasks/e5-recording-startup.md)
- 2026-11-14（时刻未记录） | 反馈 | `11140958` A/B不能开始；新增关闭预览旧片 | [分析](E5_STARTUP_FIX_20261114.md)

## 按需索引

- [需求](REQUIREMENTS.md) · [协作](COLLABORATION.md) · [当前有效坑](PITFALLS.md) · [新AI资料](AI_HANDOFF.md)
- [E5启动分析](E5_STARTUP_FIX_20261114.md) · [A/B试录](RECORDING_AB_TEST.md) · [本机开发](DEVELOPMENT.md)
- 本轮证据：`work/e5-startup/`；构建：`work/verification/build-20261114-110338-526.log`；哈希：`outputs/EVCam-E5-StartupFix-manifest.json`。
- [整理前完整历史快照](archive/2026-11/STATUS_20261114_183403.md)；历史按需查阅，不默认全读。
