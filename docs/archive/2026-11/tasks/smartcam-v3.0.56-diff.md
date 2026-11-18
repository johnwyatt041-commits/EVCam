# 任务文档：smartcam-v3.0.56-diff

- 任务 ID：`smartcam-v3.0.56-diff`；执行：纯静态反编译、差分分析与基线切换（不修改 `app/`、`outputs/`、`versionName`）。
- 分支：`codex/e5-recording-startup-fix`；最后核对：`2026-11-15 10:56:00 +08:00`（Asia/Hong_Kong）。
- 状态：已完成（仅静态）；旧版 `3.0.40` APK 已移除，**后续均以 `V3.0.56` 为唯一参考 APK**，未做实车/模拟器动态运行。

## 对象与改动边界

- 当前唯一 Smartcam 参考包：`work/reference-apks/Smartcam-V3.0.56_11_14_15_39.apk`（`13,153,082` B，SHA-256 `9E70961BAE45D72310B450F262393AD1A9C847B6A23ED2E6E5F42850E435363D`，`versionCode=2`，`versionName=3.0.56`）。
- 已移除旧包：`work/reference-apks/Smartcam-V3.0.40_11_04_02_02.apk`（`12,956,470` B，SHA-256 `352ce9a0902fbe88038709b83195c4cce6a099847cea5d57d07a2a0a2fc195a5`）已按用户要求删除；历史静态文本档案保留于 `work/smartcam-analysis/3.0.40/` 供追溯。
- 整体包变化：`AndroidManifest.xml` 权限/组件、`.so`、`res/` 与 `assets/surround/xingjian7/*` 未变（仅更新 `versionName`）；DEX `+198,908` B（`6,283,524 -> 6,482,432`），类 `5601 -> 5711`，CJK 字符串 `+170/-32`，新增 15 个 Kotlin data class。

## 静态分析核心结论

1. **录像/GL/预览解绑基干保持稳定**：`Lp13;`、`Lo13;`、`Lds2;`、`Llf4;` 保留 2560×1600 画布（`Lv33.c`）、`recorder-gl -8`、6 槽 FBO、40ms/25fps 节拍（`Lv33.h`）、`live-output -4`、`"video/avc"` `colorFormat=2130708361`、`1024×132` 水印（`Lp13.f`/`Lil.a` 去掉 `uMirror.y`）。`Lmo3;` 仍保留预览解绑 `m()` 与重绑 `C(Surface)`（`"detachPreview"` 因 R8 重命名消失而非移除）；`Ldo3;` 补耗时日志，Binder case `25` 扩为 19 字段 `Lrv3;`。
2. **启动健壮性与恢复页**：`Lk13;` 新增 5 段配置签名 `G()`、外置存储 30 秒等待与录制中延迟生效，并接 `CameraRecoveryActivity`（`camera_recovery_page_enabled`）。
3. **8 项重点新增/重构能力**（详表见 [SMARTCAM_V3.0.56_DIFF.md](..\..\..\SMARTCAM_V3.0.56_DIFF.md)）：
   - 分段 MP4（`FRAGMENTED`，`androidx.media3.muxer` 2 秒片段 `2,000,000 us`）与启动 `.mp4.part` 恢复（`Ld02;` -> `Lyf3.b/g`）。
   - 设备 `usableSpace` 百分比配额（`Lk02;`/`Lfz2;`，预留 >=10% 或 10GB）并修复 `3.0.40` 删文件 `elapsedRealtime` 相减恒 0 缺陷（`Lk02.d()`）。
   - 独立悬浮预览窗（`Lqw2;` `FULLSCREEN`/`SIDEBAR`/`WINDOW`，`Low2.c` 叠加层，`SCREEN_OFF` 自动关闭）与 23 项 `Lsy0;` 外部控制指令。
   - 补盲四向独立校准（`La33;`）、`Lq01;` GLSL 修复烘焙镜像依赖、`preview_suppress_blind_spot` 与 `"转向保持"`。
   - 360 环视外部模型导入（`Lhl4;`/`Lew3;`）、`Luk4;` `prepared.bin` 引导线缓存、在线车模（`Ll10;`/`Lg10;`）与 `Ltk4;` smoothstep 地面补丁。
   - 远程 WSS 控制（`/evcam/ws` `Lun0;`：对讲、推流、历史回放 `Lax1;`/`Lhb3;`、停车记录上传、车型指纹下发）与 BLE GATT 服务端（`LK1_S_...`）。
   - 局域网分享（`LanShareService`、`Laq1;` 网卡选优、`Lgq1;` 最深 12 层扫描外置存储 `external:` ID）。
   - 动态车况/方控/远程配置（`Lyn2;` `/evcam/rotary-presets/`、`Lyb4;` `/evcam/vehicle-config/sync`、`Lqb4;` 三级里程回退与 `"awaiting_stable_fuel_baseline"`、`Lhi0;`、`ble/version.json` 与遥测接口）。

## 交付、基线切换与清理

- 差异报告：[docs/SMARTCAM_V3.0.56_DIFF.md](..\..\..\SMARTCAM_V3.0.56_DIFF.md)；基础目录：[SMARTCAM_FEATURE_CATALOG.md](../SMARTCAM_FEATURE_CATALOG.md)（已加 3.0.56 切换说明）；交接索引：[AI_HANDOFF.md](..\..\..\AI_HANDOFF.md)。
- APK 副本与清单：`work/reference-apks/Smartcam-V3.0.56_11_14_15_39.apk`、[Smartcam-V3.0.56-manifest.json](..\..\..\..\work\reference-apks\Smartcam-V3.0.56-manifest.json)、[Smartcam-manifest.json](..\..\..\..\work\reference-apks\Smartcam-manifest.json)、[reference-apks/README.md](..\..\..\..\work\reference-apks\README.md)。
- 清理与留存：已删除 `work/reference-apks/Smartcam-V3.0.40_11_04_02_02.apk` 与临时目录 `work/tmp/smartcam-v3.0.56-diff/`；`work/smartcam-analysis/` 反编译证据留存不删。

## V3.0.56 运行时服务与对 EVCam 11150014 静态开销对照

- **Smartcam V3.0.56 服务拆分与“单留 `:core`”核实**：双进程；`:core` 常驻 `SmartCamCoreService`（`l33` + `k13` + 5 协程）、`KeepAliveService`（前台 3001/3003，`camera|connectedDevice`）与 `StatusBarStateProvider`。**退界面并非平时只剩 `:core`**：`Lkh.d` 仅设 `ui_exited=1` 并调 `finishAndRemoveTask()` 清 UI 任务，不杀主进程；只要未触发 `user_exited`，主进程仍常驻或在被回收后由主进程 `BootReceiver`（含 `SCREEN_ON`/`OFF`、`WAKEUP`、`STR_RESUME`、网络/蓝牙等）及 15 分钟 `KeepAliveWorker` 拉起 `SmartcamApplication.d()`（`y93` `RootService`、`Lsc4` 24 路 VHAL、`Lun0` 15 秒 WSS、`qr3`），`MainActivity.onStop()` 也仅向 `:core` 发 `jm(4, 0)`。`BlePeripheralService`/`LK1_S_` GATT、`ParkingLocationService`+高德 `APSService`、`LanShareService`、360 环视（`Lew3`）、补盲着色（`Lq01`）、`Lax1` RTMP 与 `KeepAliveAccessibilityService` 则主要在触发或开启对应开关后才占用算力/内存。
- **静态资源模型对照（当前 EVCam 为 `1.6.6-test-11150014`）**：EVCam 单进程，R8 后 APK/DEX 为 `6.43–6.45 MB` / `3.01–3.02 MB`（Smartcam 为 `13.15 MB` / `6.48 MB`、8 个 `.so`），且 `E5GlideModule` 限额 `8+4 MiB` 并在退场/低内存释放。录像缓冲方面，Smartcam 在 `:core` 建 6 槽 `2560×1600` RGBA FBO 环（`~93.75 MiB`）、3–4 个 EGL `Lil` 线程、`Luq` `32 MiB/1024 samples` 队列与 `4 MiB` 复用池、`1024×132` 水印 `Bitmap`；EVCam A 三路独立（3 编码器/EGL 上下文）与 B 合成（1 H.264 编码器/单 `MultiCameraCompositor`）均无 6 槽 FBO 环，`AsyncMuxerWriter` 每编码器封顶 `4 MiB / 64 samples`（A 三路合计上限 `12 MiB`、B `4 MiB`），时间水印按秒 `texSubImage2D` 原地更新。
- **后台录制有效帧率偏低/分格卡顿的代码根因与优化空间（仅静态）**：[录制对比](../RECORDING_COMPARISON_20261115.md) 中 Smartcam 后台 MP4 容器约 `24.5 fps` 但分格仅 `20.5–21.5` 次实际更新/秒；对应 `V3.0.56` 代码链路可解释为：① `Lc33.h`/`Lr51`/`Lv33.h` 将五路全部封顶 `<=25fps`；② `Lwz.a` 无 `[25,25]` 时退回变帧率 `CONTROL_AE_TARGET_FPS_RANGE`，暗光降帧；③ `Lo13` 用 `this.P = now` + 毫秒 `postDelayed(40ms)` 产生相位漂移，且只要任一摄首帧就绪即对 4 格全画布合图，晚到通道复用旧 OES 纹理（`Lkw0.d[]` 内建重复计数）；④ `Lp13` 6 槽 `2560×1600` 全屏调色 + `glFlush()`（无 `EGLSync`）跨线程投递给 `Lil`（4 槽队列）与 `Lf9`（`40ms+20ms` 刚性重采样），后台拆显示端 `q81.a` 仍保留重负载合图与 `~10.9 Mbps` H.264 编码。直接基于已混淆 APK 改码耦合私有云（`yuan.clqya.com`）与 `RootServiceMain`，更建议保留干净源码（如 EVCam）仅移植其 `FRAGMENTED` MP4 + `.part` 自愈、`usableSpace` 配额、独立悬浮预览及 `uOutputMirrorHorizontal` 解耦。

## 未解决项与下一步

- 仅完成静态反编译与结构/上限对照，未在 E5 实车收集 `Smartcam V3.0.56` `RC1`/`RG1`/`RV1`/`RE1`/`LoopRecorder` 现场日志，也未采集 Smartcam 与 EVCam `11150014`（A/B）的 `dumpsys meminfo`（PSS/RSS/Graphics）与 `top` / `dumpsys cpuinfo`；后续按同路线同视角补齐动态证据。
