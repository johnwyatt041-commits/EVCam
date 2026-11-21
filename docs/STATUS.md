# 项目进度

最后更新/核对：2026-11-21 23:47:07 +08:00（Asia/Hong_Kong）
维护：Antigravity（当前工作区在既有补盲/飞书/视图「左右 E」/回放分享网卡修复未提交改动上，完成多路录像启动、U盘中转、`.pending` 恢复、动态分段清理、模式 A 断联及规则忙等 6 项可靠性修复，`1.6.6-test-11212325`，`624` 测、`0` Lint error；待提交与实车测试；最新已发布测试包仍为 [`v1.6.6-test-11210240-build21`](https://github.com/johnwyatt041-commits/EVCam/releases/tag/v1.6.6-test-11210240-build21)）。执行规范：[PROGRESS_RULES.md](PROGRESS_RULES.md)。

## 当前快照

| 项目 | 当前状态 |
| --- | --- |
| 分支与提交 | `codex/e5-recording-startup-fix`；已提交基线 **`80fdcc8` / `3869f7f`**，当前工作区含补盲、飞书、视图「左右 E」、回放分享及本次 6 项可靠性修复未提交改动（`1.6.6-test-11212325`，`versionCode = 86`） |
| 本地交付 | `outputs/` 顶层保留 **`build20`** + **`build21`**（各 6 文件，共 12 文件；`build19` 已归档至 `outputs/archive/`） |
| GitHub | `johnwyatt041-commits/EVCam` 最新 Pre-release：[`v1.6.6-test-11210240-build21`](https://github.com/johnwyatt041-commits/EVCam/releases/tag/v1.6.6-test-11210240-build21)（6 资产 SHA256 一致；未包含本次新改） |
| 离线与设备验证 | 当前改动已通过 `verify.ps1` 双风味 `624` 测（`0` 失败/`0` 跳过）、`assembleDebug`/`assembleRelease` 及双风味 Lint `0` errors（`2220` warnings）；**尚未上实车测试** |
| 实车状态 | 待安装验证本次 6 项可靠性修复（多路部分失败/U盘中转断联恢复/`.pending` 恢复/动态清理/模式A看门狗/规则调度）及既有网卡、视图E、补盲、飞书、`build21` 360 |

## 当前结论与确认边界

- **多路录像启动、U 盘中转/遗留 `.pending`/动态清理、模式 A 断联与压制规则忙等 6 项问题已离线修复**：详见 [多路录制与系统可靠性修复](tasks/fix-recording-storage-modea-rule-reliability.md)（全 6 项均在源码核实存在：`RecordingStartupTracker` 全路启动跟踪、`FileTransferManager` 落盘核验后删+`.part`原子拷贝、`ActiveRecordingRegistry`+`PendingFileRecoveryManager` 区分活跃与遗留 `.pending`、`StorageSpaceGuard` 分段 O(1) 门控与写入速率动态预留、`SingleCamera`/`SurroundGlRenderer`/`SurroundCameraWatchdog` 修复模式 A 会话死锁与断联自愈、`VehicleRuleEngine` 消除被压制延迟规则 20ms 忙等）。
- **回放局域网分享“未检测到网卡”已离线修复**：详见 [回放分享网卡修复任务](tasks/fix-lan-share-nic-detection.md)（对照 SmartCam V3.0.56 `Laq1`/`Lvp1`/`Lxn0`，改为 `contains` 匹配与逐接口容错，增加 `ConnectivityManager`/`WifiManager` 兜底、通配符绑定、异步扫描、「热点设置」与诊断摘要）。
- **主预览视图「左右 E」已离线完成**：详见 [新增视图 E 任务](tasks/add-preview-mode-e-sides.md)（左摄居左、右摄居右默认 50%/50% 并排，隐藏释放前/后主预览流，全链路接入顶栏、布局编辑、外观/360 设置与配置广场）。
- **补盲三模式输出、副屏坐标校正、单卡统一调校与飞书测试连接已离线完成**：详见 [补盲与飞书测试连接任务](tasks/fix-blindspot-screens-and-feishu-test.md)（`仅主屏/仅副屏/主副屏同时`、`0/90/180/270°` 视觉与逻辑坐标正反映射、单卡「先选屏幕再选摄像头+四路同步」、去除重复「分方向画面矫正」卡片，及飞书两步连接诊断）。
- **`build21` 已合并发布方案 B 修复与原厂 `avm_calib_res` 格式直导**：详见 [实车修复与 4.3 外参微调](tasks/fix-e5-realcar-360-guidance-mode-d-ui.md)（`190×462` 车框、零跳变重投影、`Scale`+辅助开关及 `Table.db`+`.param` 原厂直导）。
- **用户规则与历史基线保持**：① **鱼眼矫正保持关闭**（`AppFeatures.FISHEYE_CORRECTION = false`）；② **ESP32 与浏览器投屏保持保留**（`AppFeatures.NETWORK_STREAMING = true`）；手机端 APVP `40007`、双层引导线、模式 D/E、四分栏透明度及 `.mp4.pending` 修复均保持。

## 下一步

1. 在银河 E5 实车验证多路异常启录、U 盘中转恢复、`.pending` 启动扫描、动态分段清理、模式 A 长时防断联、规则调度，以及既有回放网卡/视图「左右 E」/补盲/飞书/`build21` 360 调校（或按需重打包测试 APK）。

## 最近更新（最多5条）

- 2026-11-21 23:41:10 +08:00 | 完成多路录制启动、U盘中转与清理、模式A断联及压制规则忙等 6 项修复（`1.6.6-test-11212325`，未提交/未封包） | 6 项问题全核实修复，涵盖 `RecordingStartupTracker`、落盘核验+`.part` 拷贝、`ActiveRecordingRegistry`+启动 `.pending` 恢复、`StorageSpaceGuard` O(1) 动态清理、模式 A 状态机+OES 线程+看门狗及 `RuleRuntime.suppressed`；`624` 测与双风味构建/Lint `0` errors 全过 | [可靠性修复任务](tasks/fix-recording-storage-modea-rule-reliability.md)
- 2026-11-21 15:33:53 +08:00 | 修复回放局域网分享“未检测到网卡”（`1.6.6-test-11211526`，未提交/未封包） | 对照 SmartCam V3.0.56 修复网卡匹配与逐接口容错，增加系统网络服务兜底、通配符端口回退、异步扫描、「热点设置」及状态竞态修复；`564` 测与双风味构建/Lint `0` errors 全过 | [回放分享网卡修复](tasks/fix-lan-share-nic-detection.md)
- 2026-11-21 13:07:21 +08:00 | 新增主预览视图「左右 E」（`1.6.6-test-11211258`，未提交/未封包） | 左摄在左+右摄在右默认等分并排、隐藏释放前/后摄主预览，接入顶栏/编辑器/外观与 360 设置/配置广场；`556` 测与双风味构建/Lint `0` errors 全过 | [新增视图 E](tasks/add-preview-mode-e-sides.md)
- 2026-11-21 03:45:31 +08:00 | 完成补盲主副屏模式/倒置坐标/统一调校重构与飞书测试连接（`1.6.6-test-11210334`，未提交/未封包） | 补盲支持三模式输出、消除重复矫正卡、按「屏幕→摄像头」独立调整并支持四路同步、修复 `180°` 副屏拖动反向，飞书新增两步在线测试连接；`550` 测与双风味构建/Lint `0` errors 全过 | [补盲与飞书测试连接](tasks/fix-blindspot-screens-and-feishu-test.md)
- 2026-11-21 02:59:13 +08:00 | 封包发布 build21 并推送 GitHub Pre-release（`1.6.6-test-11210240`） | 源码 `3869f7f` 打标 `v1.6.6-test-11210240-build21` 推送，A/B APK 与 `mappings.zip` 等 6 资产远端 SHA256 一致、`build19` 归档，`534` 测与 Lint `0` errors，待实车测试 | [实车修复与微调](tasks/fix-e5-realcar-360-guidance-mode-d-ui.md)

## 归档与按需索引

- **归档全索引**：[2026-11 历史文档与任务全索引](archive/2026-11/INDEX.md)（`T01`–`T10`、`build01`–`build13`、早期报告均已收录）；APK 归档见 `outputs/archive/INDEX.md`
- **按需规范**：[需求](REQUIREMENTS.md) · [坑表](PITFALLS.md) · [协作](COLLABORATION.md) · [开发](DEVELOPMENT.md) · [本机资料](AI_HANDOFF.md)
