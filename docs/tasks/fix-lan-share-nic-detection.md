# 修复回放局域网分享“未检测到网卡”

最后更新：2026-11-21 15:33:53 +08:00
最后核对：2026-11-22 07:34:13 +08:00
任务/执行者：fix-lan-share-nic-detection / Antigravity
分支/工作树：codex/e5-recording-startup-fix / E:\GPT\EVCam
状态：已提交推送源码（随 `215753f` 推送 `johnwyatt041-commits/EVCam`，尚未单独封包与实车测试）
当前结论：已完成对照 SmartCam V3.0.56（`Laq1`/`LanShareService`/`Lvp1`/`Lxn0`）的网卡枚举、服务绑定与分享弹窗修复（`1.6.6-test-11211526`），离线单测与双风味构建/Lint 通过，待银河 E5 实车热点/Wi-Fi 环境验证。

## 根因对照与已落地改动

1. **SmartCam V3.0.56 对照结论（静态 DEX 实测）**：
   - SmartCam `Laq1.b()` 采用 `contains("wlan"/"wifi"/"eth"/"ap"/"softap"/"p2p")` + `"en0"`/`"en[0-9]+"` 且无 `veth`/`tap` 前置屏蔽；EVCam 原 `LanNetworkSelector` 使用 `startsWith`、拉黑 `veth`/`tap` 并丢弃 `PRIORITY_UNKNOWN`，导致 Android 11+/车机 `swlan0`、`sap0`、`uap0`、`ap_br_wlan*`、`br_ap0`、`tether0` 等网卡被过滤；且单层 `try/catch` 使任一受限接口异常中断全量扫描。
2. **网卡枚举与兜底增强**：
   - [LanNetworkSelector.java](../../app/src/main/java/com/kooo/evcam/transfer/LanNetworkSelector.java)：对齐 `en0 > wlan/wifi/wlp > eth/en* > ap/softap/p2p > usb/rndis/ncm > bridge/veth/tap/vlan > fallback(5)` 匹配，严格排除 `lo` 与蜂窝/VPN 隧道（含 `seth_lte`/`wwan` 等）；`NetworkInterface` 改为逐接口容错并补入 `ConnectivityManager`（`LinkProperties`）与 `WifiManager` 私有 IPv4 兜底及 `ScanResult` 诊断摘要；`NetworkEndpoint.toString()` 展示真实网卡名。
3. **服务绑定与分享弹窗交互修复**：
   - [FileTransferServer.java](../../app/src/main/java/com/kooo/evcam/transfer/FileTransferServer.java)、[LanShareService.java](../../app/src/main/java/com/kooo/evcam/transfer/LanShareService.java)：传入 `Context` 枚举，首选 IP 失效时自动回退可用列表，首选严格绑定失败后补充 `18765..18788` 通配符端口绑定并输出日志。
   - [dialog_qr_transfer_v2.xml](../../app/src/main/res/layout/dialog_qr_transfer_v2.xml)、[QrTransferDialog.java](../../app/src/main/java/com/kooo/evcam/transfer/QrTransferDialog.java)：改为后台线程异步扫描、修复 `onStart()`/`onStop()` 监听生命周期及未运行状态覆盖错误提示的竞态、新增「热点设置」直达按钮（对齐 `Lxn0.a` 跳转序列）与未检出已见网卡诊断展示。
4. **验证结果（2026-11-21）**：
   - **已通过（离线）**：扩充 [LanPathGuardAndNetworkTest.java](../../app/src/test/java/com/kooo/evcam/transfer/LanPathGuardAndNetworkTest.java)；`verify.ps1` 双风味 `564` 测 `0` 失败/`0` 跳过（`build-20261121-152746-161.log`），Debug/Release APK 编译通过，双风味 Lint `0` errors（`2218` warnings）。
   - **未执行**：尚未在银河 E5 实车验证开启热点/连接 Wi-Fi 后的实际网卡识别、绑定与手机扫码回看下载。

## 最近记录

- 2026-11-21 15:33:53 +08:00 | 修复回放局域网分享“未检测到网卡”并完成离线验证（`1.6.6-test-11211526`） | 对照 SmartCam V3.0.56 修复网卡匹配与逐接口容错，增加 `ConnectivityManager`/`WifiManager` 兜底、通配符回退、异步扫描、「热点设置」与诊断提示；`564` 测与双风味构建/Lint `0` errors 全过，待实车测试 | [LanNetworkSelector.java](../../app/src/main/java/com/kooo/evcam/transfer/LanNetworkSelector.java)

## 下一步

1. 在银河 E5 实车验证回放页“Wi-Fi 分享”与选中文件“扫码互传”在车机热点/Wi-Fi 下的网卡识别、扫码访问、HTTP Range 拖动播放及「热点设置」跳转。
