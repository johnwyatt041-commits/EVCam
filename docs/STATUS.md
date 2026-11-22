# 项目进度

最后更新/核对：2026-11-22 12:47:10 +08:00（Asia/Hong_Kong）
维护：Antigravity（已提交源码 **`9f90c63`** 并本地封包 **`build24`** `v1.6.6-test-11221230-build24`，`1.6.6-test-11221230`，双风味 `650` 测、`0` Lint error 与 `apksigner` v2 校验全过；`build22` 已归档至 `outputs/archive/`，待银河 E5 实车验证）。执行规范：[PROGRESS_RULES.md](PROGRESS_RULES.md)。

## 当前快照

| 项目 | 当前状态 |
| --- | --- |
| 分支与提交 | `codex/e5-recording-startup-fix`；源码基线 **`9f90c63`**（tag `v1.6.6-test-11221230-build24`，`versionCode = 86`） |
| 本地交付 | `outputs/` 顶层保留 **`build23`** + **`build24`**（各 6 文件，共 12 文件；`build22` 已归档至 `outputs/archive/`） |
| GitHub | `johnwyatt041-commits/EVCam` 最新已发布 Pre-release：[`v1.6.6-test-11220818-build23`](https://github.com/johnwyatt041-commits/EVCam/releases/tag/v1.6.6-test-11220818-build23)（`build24` 已在本地完成封包打标） |
| 离线与设备验证 | `build24`（`9f90c63`）已通过 `verify.ps1` 双风味 `650` 测（`0` 失败/`0` 跳过）、`assembleDebug`/`assembleRelease`、双风味 Lint `0` errors（`2227` warnings）与 `apksigner` v2 校验；**尚未上实车测试** |
| 实车状态 | 待安装 `build24` 上银河 E5 实车验证 10 项性能优化、U 盘测速、`build22` 6 项可靠性修复及网卡/视图E/补盲/飞书/`build21` 360 |

## 当前结论与确认边界

- **10 项录像与预览性能优化已离线完成**：详见 [录像与预览性能优化任务](tasks/optimize-recording-preview-perf.md)（录像侧：去逐帧 `glGetError()` 与冗余 `eglMakeCurrent()`、分段异步收尾、自适应退避防 10ms 空轮询、修复 HEVC 忽略用户码率、水印零分配与中转降优；预览侧：360 模式 A 网格迁 VBO/IBO、4 路 OES 33ms 合并限频、Shader 跳过非接缝双采样与缓存 Location/Mirror、隐藏计时器停刷 UI、普通多窗预览默认去圆角裁剪与防冗余 Outline/overdraw）。
- **U 盘手动写入速度检测已离线完成**：详见 [U 盘速度检测任务](tasks/add-usb-speed-test.md)（在「记录仪 → 存储位置」下方新增「U盘写入速度检测」卡片，通过 `FileChannel.force(true)` 真实落盘测算均速与最低分块速度，结合当前路数/分辨率/帧率/码率等级与 A/B 合成模式评估是否满足直写或中转写入要求）。

- **多路录像启动、U 盘中转/遗留 `.pending`/动态清理、模式 A 断联与压制规则忙等 6 项问题已离线修复**：详见 [多路录制与系统可靠性修复](tasks/fix-recording-storage-modea-rule-reliability.md)（全 6 项均在源码核实存在：`RecordingStartupTracker` 全路启动跟踪、`FileTransferManager` 落盘核验后删+`.part`原子拷贝、`ActiveRecordingRegistry`+`PendingFileRecoveryManager` 区分活跃与遗留 `.pending`、`StorageSpaceGuard` 分段 O(1) 门控与写入速率动态预留、`SingleCamera`/`SurroundGlRenderer`/`SurroundCameraWatchdog` 修复模式 A 会话死锁与断联自愈、`VehicleRuleEngine` 消除被压制延迟规则 20ms 忙等）。
- **回放局域网分享“未检测到网卡”已离线修复**：详见 [回放分享网卡修复任务](tasks/fix-lan-share-nic-detection.md)（对照 SmartCam V3.0.56 `Laq1`/`Lvp1`/`Lxn0`，改为 `contains` 匹配与逐接口容错，增加 `ConnectivityManager`/`WifiManager` 兜底、通配符绑定、异步扫描、「热点设置」与诊断摘要）。
- **主预览视图「左右 E」已离线完成**：详见 [新增视图 E 任务](tasks/add-preview-mode-e-sides.md)（左摄居左、右摄居右默认 50%/50% 并排，隐藏释放前/后主预览流，全链路接入顶栏、布局编辑、外观/360 设置与配置广场）。
- **补盲三模式输出、副屏坐标校正、单卡统一调校与飞书测试连接已离线完成**：详见 [补盲与飞书测试连接任务](tasks/fix-blindspot-screens-and-feishu-test.md)（`仅主屏/仅副屏/主副屏同时`、`0/90/180/270°` 视觉与逻辑坐标正反映射、单卡「先选屏幕再选摄像头+四路同步」、去除重复「分方向画面矫正」卡片，及飞书两步连接诊断）。
- **`build21` 已合并发布方案 B 修复与原厂 `avm_calib_res` 格式直导**：详见 [实车修复与 4.3 外参微调](tasks/fix-e5-realcar-360-guidance-mode-d-ui.md)（`190×462` 车框、零跳变重投影、`Scale`+辅助开关及 `Table.db`+`.param` 原厂直导）。
- **用户规则与历史基线保持**：① **鱼眼矫正保持关闭**（`AppFeatures.FISHEYE_CORRECTION = false`）；② **ESP32 与浏览器投屏保持保留**（`AppFeatures.NETWORK_STREAMING = true`）；手机端 APVP `40007`、双层引导线、模式 D/E、四分栏透明度及 `.mp4.pending` 修复均保持。

## 下一步

1. 视用户确认决定是否提交推送并封包测试第一批性能优化，在银河 E5 实车验证流畅度、U 盘测速与既有可靠性修复，再结合实车日志决定第二批待修复项优先级。

## 最近更新（最多5条）

- 2026-11-22 12:47:10 +08:00 | 提交源码 `9f90c63` 并本地封包 build24（`1.6.6-test-11221230`） | 第一批 9 项 + 普通多窗预览默认去圆角裁剪与防冗余合成共 10 项优化全部完成；打标 `v1.6.6-test-11221230-build24`，A/B APK 与 `mappings.zip` 等 6 资产落盘、`build22` 归档，双风味 `650` 测与 Lint `0` errors 全过 | [性能优化清单](tasks/optimize-recording-preview-perf.md)
- 2026-11-22 09:00:57 +08:00 | 封包发布 build23 并推送 GitHub Pre-release（`1.6.6-test-11220818`） | 源码 `bb34e66` 打标 `v1.6.6-test-11220818-build23` 推送，A/B APK 与 `mappings.zip` 等 6 资产远端 SHA256 一致、`build21` 归档，`638` 测与 Lint `0` errors，待实车测试 | [U盘速度检测](tasks/add-usb-speed-test.md)
- 2026-11-22 08:55:30 +08:00 | 提交并推送 U 盘手动写入速度检测至 GitHub fork（`1.6.6-test-11220818`） | 在记录仪存储位置新增「U盘写入速度检测」卡片，基于 `FileChannel.force(true)` 真实落盘测速并评估直写/中转达标状态，提交推送至 `johnwyatt041-commits/EVCam` `codex/e5-recording-startup-fix`；`638` 测与 Lint `0` errors 全过 | [U盘速度检测](tasks/add-usb-speed-test.md)
- 2026-11-22 07:52:49 +08:00 | 封包发布 build22 并推送 GitHub Pre-release（`1.6.6-test-11212325`） | 源码 `215753f` 打标 `v1.6.6-test-11212325-build22` 推送，A/B APK 与 `mappings.zip` 等 6 资产远端 SHA256 一致、`build20` 归档，`624` 测与 Lint `0` errors，待实车测试 | [可靠性修复任务](tasks/fix-recording-storage-modea-rule-reliability.md)
- 2026-11-22 07:34:13 +08:00 | 合流提交 4 轮累积改动并推送 GitHub（`215753f` / `1.6.6-test-11212325`） | 将补盲多屏/倒置坐标/飞书测试、`MODE_E`、回放分享网卡与 6 项录制/存储/模式 A/规则修复一并提交推送至 `johnwyatt041-commits/EVCam` `codex/e5-recording-startup-fix`；`624` 测与 Lint `0` errors | [可靠性修复任务](tasks/fix-recording-storage-modea-rule-reliability.md)

## 归档与按需索引

- **归档全索引**：[2026-11 历史文档与任务全索引](archive/2026-11/INDEX.md)（`T01`–`T10`、`build01`–`build13`、早期报告均已收录）；APK 归档见 `outputs/archive/INDEX.md`
- **按需规范**：[需求](REQUIREMENTS.md) · [坑表](PITFALLS.md) · [协作](COLLABORATION.md) · [开发](DEVELOPMENT.md) · [本机资料](AI_HANDOFF.md)
