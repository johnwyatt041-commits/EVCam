# 项目进度

最后更新/核对：2026-11-15 02:43:21 +08:00（Asia/Hong_Kong）
维护：Codex / recording-storage-eperm（按用户确认撤回）。执行规范：[PROGRESS_RULES.md](PROGRESS_RULES.md)。

## 当前快照

| 项目 | 当前状态 |
| --- | --- |
| 项目/设备 | E:\GPT\EVCam；银河E5，2560×1600 |
| 分支 | `codex/e5-recording-startup-fix`；文档HEAD以`git log`为准 |
| 最新APK | `1.6.6-test-11150014`，A/B同包名同签名，停录像后顺序覆盖安装 |
| APK源码 | `5f5ce4626a8f7060cc96cc3758150c497eca6257`；manifest已关联，后续文档提交不改变APK |
| 本地交付 | `outputs/EVCam-E5-{A-Independent,B-Composite}-1.6.6-test-11150014-release.apk`；SHA、R8映射与说明同目录 |
| GitHub | 新优化包尚未上传；[旧11141103测试发布](https://github.com/johnwyatt041-commits/EVCam/releases/tag/v1.6.6-test-11141103-e5-startup-fix)保留 |
| 验证 | 每版79项JVM、Debug/Release R8、5项模拟器录像/扫码、1项R8启动冒烟通过；精确历史基线Lint无新增错误，完整扫描仍有19项既有错误 |
| 实车 | **用户确认此前等待/超时因车机权限不足，不是App问题；已按要求撤销存储修正。性能与长期实测仍待确认** |

## 当前结论

- 用户已确认本次失败为车机权限不足；仅撤销`2082be7`存储改造，恢复11150014及原已确认优化。0220候选保留但标撤回，不作为当前交付；[撤回记录](tasks/recording-storage-eperm.md)。
- 已实施确认的1–5项优化：物理删停用能力/SDK+R8，Glide限额及退场释放，方案3-稳妥时间水印复用/纹理原地更新；保留扫码、飞书、补盲及双HUD。
- R8首轮冷启动暴露Room反射构造器被裁，已精确保留并通过A/B冷启动。保留无障碍保活心跳及HUD共享旧配置键；见[P013/P014](PITFALLS.md)。
- A为日常三路独立文件；B仍1280×800三格合成，每格约640×400、右下黑；飞书四路仍独立。合成双GL本轮无改动。
- 模拟器验证Camera2首写、分段/合成、两种水印、扫码HTTP及飞书封面；录制中清图片缓存不阻断分段。不能替代E5硬件、HEVC、HUD/VHAL、联网飞书及长期测试。

## 下一步

1. 使用11150014并核对车机实际授权后测试A/B；不再推进已撤回的0220存储候选。流畅度/清晰度/PSS需同条件录像对照；[原优化任务](tasks/ab-test-preoptimization.md)。
2. 确认原车HUD真实路径与长期选型；选B再深合双GL，后续补`.pending`恢复与状态机收敛。
3. 19项既有Lint错误仍待独立整改；本轮未通过全局禁用检查掩盖新增问题。

## 最近更新（最多5条）

- 2026-11-15 02:41:26 +08:00 | 撤回 | 用户确认车机权限不足、不是App问题；仅撤销2082be7，app恢复a2f6962/11150014，前轮优化保留，0220标撤回 | [任务](tasks/recording-storage-eperm.md)
- 2026-11-15 00:25:19 +08:00 | 优化交付 | 新A/B `11150014`通过每版79+5+1、R8与必要Lint；签名/原库/SHA已核验，清46个临时文件并关闭任务模拟器，实车待测 | [任务](tasks/ab-test-preoptimization.md)
- 2026-11-14 23:24:27 +08:00 | 确认 | 保留扫码、选方案3-稳妥、HUD暂不动，确认范围已落盘 | [任务](tasks/ab-test-preoptimization.md)
- 2026-11-14 21:43:54 +08:00 | 参考 | Smartcam/EVCam A/B静态内存结构对照，无实车PSS数据 | [任务](tasks/smartcam-feature-inventory.md)
- 2026-11-14 19:17:43 +08:00 | 目录 | 清27个临时文件、117个保留文件SHA不变，明确参考与实现落点 | [任务](tasks/directory-hygiene.md)

## 按需索引

- [需求](REQUIREMENTS.md) · [协作](COLLABORATION.md) · [坑](PITFALLS.md) · [新AI资料](AI_HANDOFF.md) · [Smartcam全功能](SMARTCAM_FEATURE_CATALOG.md)
- [E5启动分析](E5_STARTUP_FIX_20261114.md) · [A/B试录](RECORDING_AB_TEST.md) · [开发](DEVELOPMENT.md)
- 本轮证据：`work/ab-test-preoptimization/`；历史启动证据仍在`work/e5-startup/`，不默认全读。
- [整理前历史快照](archive/2026-11/STATUS_20261114_183403.md)；历史按需读取。
