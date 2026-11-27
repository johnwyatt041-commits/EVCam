# 修复回放局域网分享“未检测到网卡”

最后更新：2026-11-26 20:22:00 +08:00
最后核对：2026-11-26 20:22:00 +08:00
任务/执行者：fix-lan-share-nic-detection / Antigravity
分支/工作树：codex/e5-recording-startup-fix / E:\GPT\EVCam
状态：等待信息（已确认 `wlan0` 需手动开热点才出现，待用户选定后续方案及确认 SC 表现）
当前结论：用户补充实车确认为「每次手动打开车机热点才显示 `wlan0 · Wi-Fi (192.168.14.146)`」（符合 Android 11+ SoftAP 随机私有 IPv4 机制，且静态核对 SmartCam V3.0.56 同样不会自动开系统热点、仅提示连 Wi-Fi/开热点并跳转设置）；待用户确认不开热点时 SmartCam 是否也无网卡（若有料需抓 `LAN NIC scan`），并决定是否增加网络状态自动复扫/UI 优化或实验性 `LocalOnlyHotspot`。

## 根因对照与已落地改动

1. **SmartCam V3.0.56 对照结论（静态 DEX 实测）**：
   - SmartCam `Laq1.b()` 采用 `contains("wlan"/"wifi"/"eth"/"ap"/"softap"/"p2p")` + `"en0"`/`"en[0-9]+"` 且无 `veth`/`tap` 前置屏蔽；EVCam 原 `LanNetworkSelector` 使用 `startsWith`、拉黑 `veth`/`tap` 并丢弃 `PRIORITY_UNKNOWN`，导致 Android 11+/车机 `swlan0`、`sap0`、`uap0`、`ap_br_wlan*`、`br_ap0`、`tether0` 等网卡被过滤；且单层 `try/catch` 使任一受限接口异常中断全量扫描。
2. **网卡枚举与兜底增强**：
   - [LanNetworkSelector.java](../../app/src/main/java/com/kooo/evcam/transfer/LanNetworkSelector.java)：对齐 `en0 > wlan/wifi/wlp > eth/en* > ap/softap/p2p > usb/rndis/ncm > bridge/veth/tap/vlan > fallback(5)` 匹配，严格排除 `lo` 与蜂窝/VPN 隧道（含 `seth_lte`/`wwan` 等）；`NetworkInterface` 改为逐接口容错并补入 `ConnectivityManager`（`LinkProperties`）与 `WifiManager` 私有 IPv4 兜底及 `ScanResult` 诊断摘要；`NetworkEndpoint.toString()` 展示真实网卡名。
3. **服务绑定与分享弹窗交互修复**：
   - [FileTransferServer.java](../../app/src/main/java/com/kooo/evcam/transfer/FileTransferServer.java)、[LanShareService.java](../../app/src/main/java/com/kooo/evcam/transfer/LanShareService.java)：传入 `Context` 枚举，首选 IP 失效时自动回退可用列表，首选严格绑定失败后补充 `18765..18788` 通配符端口绑定并输出日志。
   - [dialog_qr_transfer_v2.xml](../../app/src/main/res/layout/dialog_qr_transfer_v2.xml)、[QrTransferDialog.java](../../app/src/main/java/com/kooo/evcam/transfer/QrTransferDialog.java)：改为后台线程异步扫描、修复 `onStart()`/`onStop()` 监听生命周期及未运行状态覆盖错误提示的竞态、新增「热点设置」直达按钮（对齐 `Lxn0.a` 跳转序列）与未检出已见网卡诊断展示。
4. **验证结果（2026-11-21 ~ 2026-11-26）**：
   - **已通过（离线）**：扩充 [LanPathGuardAndNetworkTest.java](../../app/src/test/java/com/kooo/evcam/transfer/LanPathGuardAndNetworkTest.java)；`verify.ps1` 双风味 `564` 测 `0` 失败/`0` 跳过（`build-20261121-152746-161.log`），Debug/Release APK 编译通过，双风味 Lint `0` errors（`2218` warnings）。
   - **已复核（实车反馈与静态核对，2026-11-26）**：实车照片确认检出 `wlan0 · Wi-Fi (192.168.14.146)` 并起服 `18765` 端口，且用户补充该网卡**必须手动打开车机热点才显示**（吻合 Android 11+ SoftAP 随机网段；未开热点且未连 Wi-Fi 时无可用局域网 IPv4）。静态核对 SmartCam V3.0.56 清单与 DEX，确认其同样无静默开启系统共享热点能力，仅跳转热点设置并提示用户连 Wi-Fi 或开热点；另确认 `ip_spinner` 纯色背景遮挡下拉箭头。
   - **未验证**：不开热点时 E5 是否存在被过滤网卡（待抓 `LAN NIC scan` 或对比 SC）；`LocalOnlyHotspot` 在 E5 Flyme Auto 上的系统兼容性与手机访问 `192.168.14.146:18765` 实测。

## 最近记录

- 2026-11-26 20:22:00 +08:00 | 记录用户补充并完成 SmartCam 热点能力核对 | 确认 `wlan0 (192.168.14.146)` 为手动开启车机热点后才出现的 SoftAP 网卡；静态核对 SmartCam V3.0.56 同样不自动开系统热点、仅提示与跳转设置，待用户确认不开热点时 SC 表现及后续方案 | [STATUS.md](../STATUS.md)
- 2026-11-21 15:33:53 +08:00 | 修复回放局域网分享“未检测到网卡”并完成离线验证（`1.6.6-test-11211526`） | 对照 SmartCam V3.0.56 修复网卡匹配与逐接口容错，增加 `ConnectivityManager`/`WifiManager` 兜底、通配符回退、异步扫描、「热点设置」与诊断提示；`564` 测与双风味构建/Lint `0` errors 全过，待实车测试 | [LanNetworkSelector.java](../../app/src/main/java/com/kooo/evcam/transfer/LanNetworkSelector.java)

## 下一步

1. 等待用户确认：不开车机热点时 SmartCam 是否也无可用网卡（若 SC 仍能看到网卡，导出一份不开热点下的 EVCam 日志 `LAN NIC scan` 对齐）。
2. 按用户选择推进实现：① 在 `QrTransferDialog` 增加网络/热点状态监听自动复扫，并补齐 `ip_spinner` 下拉箭头与诊断提示；② 如需要，实验性接入 `WifiManager.startLocalOnlyHotspot()` 一键临时局域网热点并上车实测兼容性。
