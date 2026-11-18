# Smartcam V3.0.56 静态差异分析报告（对比 V3.0.40 基线）

> **按需查阅文档（非开局必读）**：仅在需要检索 `work/smartcam-analysis/3.0.56/` 混淆类名与 Smartcam V3.0.56 实现细节时按需打开；新对话开局请勿默认加载。

分析时间：2026-11-15 03:54:00 +08:00（Asia/Hong_Kong）
目标车型参照：吉利银河 E5（2560×1600）
证据边界：本文全部结论均来自原始 APK 静态结构比对、`aapt2` 与 Androguard DEX 反编译/字节码核对，**未进行实机或模拟器动态安装运行**。文中仅区分“静态实测”和“工程推断”。

---

## 一、APK 元数据与结构差异总览

| 对比维度 | V3.0.40（11-04 基线） | V3.0.56（11-14 新版） | 变化说明 |
| --- | --- | --- | --- |
| 文件名 | `Smartcam-V3.0.40_11_04_02_02.apk` | `Smartcam-V3.0.56_11_14_15_39.apk` | 已在桌面核对原包 SHA-256，未改动原件 |
| 文件大小 | `12,956,470` 字节 | `13,153,082` 字节 | `+196,612` 字节 |
| SHA-256 | `352ce9a0902fbe88038709b83195c4cce6a099847cea5d57d07a2a0a2fc195a5` | `9E70961BAE45D72310B450F262393AD1A9C847B6A23ED2E6E5F42850E435363D` | 副本存放在 `work/reference-apks/` |
| 包名 / 版本 | `com.yuan.smartcam` / `3.0.40` | `com.yuan.smartcam` / `versionCode=2`, `versionName=3.0.56` | 无清单权限/组件增减 |
| Manifest / 资源 / 原生库 | 基线 | 与基线一致 | `res/`、`.so`、`assets/surround/xingjian7/*` 均未改 |
| DEX 体积与类数 | `6,283,524` 字节 / `5,601` 类 | `6,482,432` 字节 / `5,711` 类 | 纯代码增量 `+198,908` 字节、`+110` 类；`+170`/`-32` 中文字符串、`+15` Kotlin data classes |

**核心定位**：`3.0.56` 是一次聚焦**录像断电容错、剩余空间配额、独立悬浮预览、补盲畸变/镜像独立校正、360 环视性能缓存与在线车模、远程 WS/PTT/回放推流及云端动态车信同步**的纯 DEX 大版本迭代；底层 Camera2 取流、GL 2560×1600 合图、硬编探测及水印排版主干保持稳定。

---

## 二、核心录像、GL 合成、预览与水印通路核对

| 子系统 | V3.0.40 类 | V3.0.56 等价类 | 核对结论与具体变化 |
| --- | --- | --- | --- |
| GL 合成主环 | `oy2` | `Lp13;` / `Lo13;` | **主干不变**：画布默认 `2560×1600`（`Lv33.c(quality, 2560, 1600)`，质量 `50..100` 偶数对齐），渲染线程 `recorder-gl`（优先级 `-8`），6 槽 FBO 环缓冲，目标帧间隔 `40ms`（`Lv33.h` 限 1..25fps），`live-output`（`-4`）；传入 `r51(25,25,25,25,25)`。<br>**变化**：`Lp13.f` 移除垂直镜像 `uMirror.y`（固定 `0`）。 |
| Surface 分发输出 | `il` | `Lil;` / `Lj13;` | **主干不变**：GL 启动（`Lj13;`）固定创建 3 路 `il` 输出：`recorder-display`（`q81.a`，`-4`）、`recorder-overlay`（`q81.b`，`-8`）与 `atlas-encoder`（`q81.c`，`-8`）。<br>**变化**：`Lil.a` 当 360 环视状态 `H.a` 为真时直接对主预览 `q81.a` 调用 `ew3.b(...)`；`q81.b` 走 `Lq01.c(...)` 补盲着色；`q81.c` 不再判断 `.e.b` 垂直镜像。 |
| 主预览解绑机制 | `Lzd1;` | `Lmo3;` / `Lwf1;` / `Ldo3;` | **澄清**：`3.0.40` 字符串 `"detachPreview"` 消失系 **R8 混淆与方法重构**，并非删除解绑逻辑。`ISmartCamCore$Stub`（`Lmo3;`）仍保留 `C(Surface)`（挂载 `k13.v` + `p13.a(..., q81.a, ...)`）和 `m()`（置空 `k13.v` + `p13.e(q81.a, 0)`）；`Ldo3;` 新增 `"视频接入核心 队列等待="` 与 `"视频接入完成 耗时="` 诊断日志，且支持 Binder `transact(25)` 下发 360/补盲状态 `Bundle`。 |
| 录像协调与恢复 | `jy2` | `Lk13;` / `CameraRecoveryActivity` | `Lk13.G()` 对设置生成 5 段 `\|` 签名；未检测到外部存储时进入 `"未检测到外部存储，等待 30 秒"` 等待；录制中修改配置延后生效（`"录制中延后应用摄像头/画质配置，目标画质=..."`）；异常断流可触发 `CameraRecoveryActivity`（倒计时 `1..30` 秒，截止 `60` 秒）并在后台关闭时回调 `k13.a.E(sessionId)`。 |
| Camera2 会话 | — | `Luz;` / `Lwz;` | 在 `onConfigured` 记录 `"<id>: 取流已启动 elapsedMs=... size=... fpsRange=..."`。 |
| 硬编探测器 | `lp2` | `Lds2;` | **基本未变**：固定 `"video/avc"`、`colorFormat=2130708361`、`i-frame-interval=1`；API >= 29 要求 `isHardwareAccelerated() && !isSoftwareOnly()`（低版本按名称屏蔽 `"google"`、`".sw."`、`"c2.android."`、`"omx.google."`）；沿用 `(priority=0 + operating-rate=fps) ∈ {true,false} × profile=1 ∈ {false,true}`（及可选 `bitrate-mode=1`）回退矩阵。 |
| 水印叠加层 | `nb4` | `Llf4;` | **基本未变**：`1024×132` `ARGB_8888` Bitmap Canvas、`sans-serif-medium` / `sans-serif`、`yyyy-MM-dd  HH:mm:ss` 格式，按 1 秒时间戳与 `hf4`/`if4` 字段内容缓存复用纹理。 |

---

## 三、重点新增与重构功能拆解

### 1. 分段 MP4（Fragmented MP4）与异常断电录像恢复（`Lw33;` / `Lp51;` / `Lo51;` / `Lyf3;`）

1. **双封装模式切换**：`Lc33.q()` 新增设置项 `recording_mp4_format`（枚举 `Lw33;`：`STANDARD` 默认 / `FRAGMENTED`，由设置回调 `Let3;` case 0 保存并触发 `lo3.a.d()`）。
2. **`media3.muxer` 接入**：`Lk02;`（对应旧 `ey1`）取消直接持有 `android.media.MediaMuxer`，改通过 `zf3` 接口隔离：
   - `STANDARD` -> `Lyq3;`（封装标准 `MediaMuxer`，`Luq;` 写入队列上限 `<=16MB` / `<=512` 样本）。
   - `FRAGMENTED` -> `Lp51;` -> `Lk51;` / `Lo51;`（基于 `androidx.media3.muxer`，字符串标记 `"media3.muxer"`，片元周期 `2,000,000 us` = `2` 秒；`Lp51.b(MediaFormat)` 将视频/HDR/Dolby Vision/SAR/`csd-*`/`track-id` 转为 `d51` 格式）。
3. **启动空文件清理与 `.part` 断电恢复闭环**：
   - 启动阶段 `Ld02;` case 0 调用 `yf3.a.b(...)`（日志 tag `"SegmentIndex"`）。
   - 对长度为 `0` 的 `.mp4` / `.part` / 孤儿 `.lock` / `.marks` 执行清理，日志输出 `"已清理空录像文件 count=..."`。
   - 对长度 `> 0` 的 `.mp4.part` 文件，调用 `yf3.g()` 用 `MediaExtractor` 检测是否存在有效样本（`getSampleSize() > 0`）的 `video/` 轨，通过后直接重命名为 `.mp4`（`"已恢复异常退出录像 <name>"`）并重建 SQLite `segments` 索引（**工程推断**：这与 `FRAGMENTED` 2 秒持续写片元的机制配套，保证突发断电后 `.part` 仍自带元数据可被 `MediaExtractor` 恢复）。

### 2. 存储配额改为磁盘可用空间并修复 `3.0.40` 静态 Bug（`Lk02;` / `Lfz2;` / `Lv33;`）

1. **动态余量配额（`QuotaFreeSpace` `Lfz2;`）**：`Lk02;` 不再统计文件夹累计占用估算清理，而是直接在 `j()` 读取 `File.getUsableSpace()`，构造包含 `minimumBytes` / `triggerBytes` / `targetBytes` 的 `QuotaFreeSpace`。
2. **余量保护公式（`Lv33.e/f/i`）**：按存储总容量计算可用上限，**系统至少保留 `10%` 或 `10 GB`**（取小余量即 `v33.e(totalGb)`：`total*10/100 <= 10 ? total*90/100 : total-10`），下限 `min(10, max)`，未配置时默认目标 `80%`。
3. **帧级上限拦截**：`Lk02.o()` 写样本前校验 `(y + size) <= z`，超限立即置 `c0 = 1` 触发切段/保护。
4. **Bug 修复与侧车清理**：修复了 `3.0.40` `ey1` 第 473 行 `SystemClock.elapsedRealtime() - elapsedRealtime()` 恒为 0 的删除耗时统计错误（`Lk02.d()` 现为 `elapsedRealtime - startMs`）；写段失败或零字节 `.part` 在 `f()` 自动删除残余 `.lock`/`.marks`。

### 3. 独立悬浮预览窗口与外部命令扩展（`Lqw2;` / `Low2;` / `Lnw2;` / `Liw2;` / `Lsy0;`）

1. **三种预览展示形态（`Lqw2;`）**：设置键 `preview_presentation` 定义 `FULLSCREEN("全屏")`、`SIDEBAR("保留侧边栏")`、`WINDOW("独立悬浮窗")`；旧布尔键 `preview_fullscreen` 仅作缺省回退。
2. **不拉起 `MainActivity` 的悬浮预览（`Low2;` / `ExternalCommandReceiver`）**：
   - 当配置为 `WINDOW` 时，触发 `OPEN_PREVIEW`（含倒车触发 `reverse_preview`）不再跳转 `MainActivity`，而是直接调用 `Low2.c(context, reversePreview)` 添加全屏 `WindowManager` 视图（日志 tag `"PreviewOverlay"`）。
   - 窗口类型支持无障碍保活服务 `KeepAliveAccessibilityService` 提供的 `TYPE_ACCESSIBILITY_OVERLAY (2032)` 或标准悬浮窗 `TYPE_APPLICATION_OVERLAY (2038)`；API >= 30 配合 `nv2.u`/`nv2.t` 处理系统栏适配（`Lnw2.onSizeChanged` 记录 `"实际预览区域=...，系统适配栏位"`），并监听 `ACTION_SCREEN_OFF` 息屏自动关闭。
   - 内部挂载全屏 Compose UI（`r90`）与底层 `SurfaceView`（`ic2` + `Liw2;` 异步上送 Surface 给核心服务），退出时清理视图、注销广播并重置 `rv3` 状态。
3. **外部广播动作（`Lsy0;`）**：`ExternalCommandReceiver` 共支持 `23` 项 `com.yuan.smartcam.action.EXTERNAL_COMMAND`，覆盖录制/停止、摄像头连接/释放、手动标记、四向/关闭补盲、雨夜开关、预览开启/关闭、后台服务/拉起主界面及车外喊话/外放音频 1–5。

### 4. 补盲独立校正与 GLSL 镜像解耦修复（`La33;` / `Lq01;` / `Lcl4;`）

1. **分方向独立校正**：`La33;`（`"补盲分方向画面校正"`）明确说明*各方向边缘裁切、水平镜像与鱼眼校正仅用于补盲，不影响预览、录像、停车拍照与直播*；并清理 `recorder_settings` 中遗留的 `lens_<DIRECTION>` 旧键，新增 `preview_suppress_blind_spot`（倒车等场景抑制补盲）与 `"转向保持"`。
2. **着色器核心修复（`Lq01;`）**：
   - 同时保留预生成 UV LUT FBO 路径（按 `RG32F` -> `RG16_UNORM` -> `RGBA8_PACKED16` -> `RG16F` 降级尝试）和直接像素回退路径（`q01.m`）。
   - **关键点**：引入独立 uniform `uOutputMirrorHorizontal` 与图集物理镜像 `uMirrorHorizontal` 分离（GLSL 原文注释：*`Output mirroring is independent of the physical camera mapping baked into the atlas.`*），当 `uMirrorHorizontal != uOutputMirrorHorizontal` 时先对输出 UV 做水平翻转，再经 `uRayBasis` 与鱼眼畸变多项式投影回相机图集并应用物理 `uMirrorHorizontal` 翻转。避免旧实现下镜像破坏鱼眼主点/非对称矫正的问题。

### 5. 360° 环视扩展：引导线缓存、在线车模库与地面参数（`Lhl4;` / `Lew3;` / `Luk4;` / `Ltk4;` / `Lrv3;` / `Ll10;`）

1. **底层网表与导入校验**：继续内置 `assets/surround/xingjian7/*`（E02 参数未变），新增 `files/surround-imports/<64位小写hex>/` 导入方案支持（强校验 `calibration.param`、`bird-offset.txt`、`bird.mesh`、`front_wheels.mesh`、`rear_wheels.mesh`）。
2. **引导线二进制缓存（`Luk4;`）**：首次将 `.db` 倒车/前视引导线预解析写入 `files/surround-guidance/prepared.bin`（绑定 `sourceDir` 大小与修改时间），后续直接反序列化复用，日志输出 `"引导线复用 elapsedMs=..."`。
3. **在线车模库（`Ll10;` / `Lg10;` / `Lrh4;`）**：从 `https://yuan.clqya.com:5000/hwn9mFEubWgL94xj` 获取车模列表与原始 PNG（校验 `bytes` 上限、`sha256`、`width×height` 与锚点 `anchor {x,y}`、`appearance="original"`），缓存在 `files/car-models/`。
4. **状态同步与画面细节（`Lrv3;` / `Ltk4;`）**：通过 `ISmartCamCore` `transact(25)` 同步 19 个字段状态，包括默认地宽/地长（`groundWidthCm=206`, `groundLengthCm=530`, `groundFrontExtensionCm=0`）、`stretchRoadViews`、滚轮视图折叠 `wheelsCollapsed`；`Ltk4;` 使用 `uGroundColor` + `smoothstep(0.0, 0.045, ...)` 羽化绘制车底地面贴片。

### 6. 远程互联 WSS、远程车外喊话 PTT、RTMP 回放推流与 BLE（`Lun0;` / `Lax1;` / `Lhb3;` / `SmartcamApplication`）

1. **远程设备 WebSocket（`Lun0;`）**：连接 `wss://yuan.clqya.com:5000/hwn9mFEubWgL94xj/evcam/ws`（携带 `deviceId`、`ts`、`sign` 签名），统一处理：
   - `start_talk` / `stop_talk` -> 启动/停止 `"OutsideVoiceRemote"` 实时车外喊话（受 `outside_voice` SP `remote_enabled` 开关及扬声器占线保护限制）。
   - `start_push` / `stop_push` -> 实时画面推流。
   - `request_recordings` / `request_parking_upload` -> 远程检索录像列表与请求停车照片上传。
   - `hello_ack` / `welcome` / `bind_status*` -> 同步车型识别与配置组至 `vehicle_identity` SP（`build_model`, `label`, `in_library`, `config_group`）。
2. **本地录像 RTMP 点播推流（`Lax1;` / `Lhb3;`）**：新增 `"recording-stream"` 线程使用 `MediaExtractor` 读取选中录像音视频轨道，通过 `Lhb3;` 推流至 `rtmp://`/`rtmps://`/`rtmpt://` 地址实现远程回看。
3. **BLE 外设服务（`SmartcamApplication`）**：当非受限模式且 `ble_peripherals` SP 开启 `incoming_connections_enabled` 时，在具备蓝牙连接/广播权限后启动名为 `LK1_S_<deviceId末尾3位>` 的 `EmpGattServer`，并搭配 `https://smartcam.clqya.com/firmware/ble/version.json` 固件检查。

### 7. 局域网分享与外部哨兵存储浏览器（`LanShareService` / `Laq1;` / `Lgq1;`）

1. **多网卡 IP 选择（`LanShareService` / `Laq1;`）**：支持 `lan_share_ip` Intent 指定网卡 IP；`Laq1;` 枚举所有 `isUp && !isLoopback` 网卡中的 RFC1918 私有 IPv4（`10.*` / `192.168.*` / `172.16-31.*`），并按 `en0` > `wlan/wifi` > `eth/en*` > `ap/softap/p2p` 优先级排序。
2. **内置 Web 回看扩展（`Lgq1;`）**：页面（`"灵视 录像回看"`）支持全部 / 今天 / 有标记 / 已锁定筛选与缩略图异步加载，且支持递归扫描**外部哨兵存储目录**（深度上限 12 层、跳过符号链接，生成 `external:` Base64Url ID）提供 `/api/files`、`/api/status`、`/info/`、`/t/`、`/p/`、`/d/` 服务。

### 8. 车机动态信号配置、里程/油电基线与方控预设（`Lyn2;` / `Lyb4;` / `Lqb4;` / `Lhi0;` / 网络接口）

1. **方控/旋钮动作绑定（`Lyn2;`）**：内置 7 项 App 预设（`smartcam_rain_night`、`smartcam_lock_video`、`smartcam_toggle_recording`、`smartcam_media_volume`、`smartcam_notification_volume`、`smartcam_outside_voice`、`smartcam_open_menu`），并通过 `/evcam/rotary-presets/` 拉取远程 `VHAL` / `APVP` 自定义绑定配置。
2. **云端车信运行时同步（`Lyb4;`）**：通过 `/evcam/vehicle-config/sync` 更新 `vehicle_runtime_config` SP，支持动态 `bindings`、`property_catalog:{apvp,aidl,capi}`、`source_policy` 及信号转换公式 `formula:...`。
3. **里程与补能追踪鲁棒性（`Lqb4;` / `Lhi0;`）**：
   - 单程距离按优先级三层回退：`day_odometer` -> `total_odometer` -> `vehicle_speed_estimate`（`speedDistanceKm` 车速积分估算仅在无 `rejectedDistanceSamples`、无观测缺口且未标记不完整时启用）。
   - 油耗/加油窗口引入 `awaiting_stable_fuel_baseline`、`*BaselineUncertain` 与 `baselineInvalidFields` 防跳变。
   - `DataPreviewCard`（`Lhi0;`）直观渲染挡位（D/R/N/P）、转向灯、以及车内雷达占位（`"有人"`/`"无人"`）。
4. **其他后端运维/诊断接口**：`urls_diff` 显示新增 `/evcam/debug-log`、`/evcam/telemetry`、`/evcam/camera-profile/submissions` 与 `https://gitee.com/api/v5/repos/Cyuan_cai/yuan-cam/releases`。

---

## 四、对 EVCam（银河 E5）开发的借鉴与风险提示

1. **最值得优先借鉴的工程实践**：
   - **Fragmented MP4 + 启动 `.part` Extractor 自愈**：在 E5 车机突发断电/异常杀进程场景下，定期写片元的分段 MP4 与启动校验重命名比传统最后写 `moov` 更能保住关键尾部录像（当前 EVCam 可作为下一阶段 `.pending` 恢复参考）。
   - **`usableSpace` 配额与保护余量**：循环覆盖判定直接基于卷可用空间并强制留足至少 10% / 10GB 系统余量，能避开因外部目录扫描误差或其它 App 占满存储导致的录停问题；同时必须规避 `3.0.40` 曾出现的时间差归零笔误。
   - **独立 `WindowManager` 悬浮预览 + `uOutputMirrorHorizontal` 解耦**：需要不打断车机当前桌面/导航的瞬时倒车或盲区弹窗时，`WindowManager` 独立悬浮预览比拉起 `Activity` 响应更干净；若日后引入补盲鱼眼校正，也必须区分物理镜像与输出镜像避免映射错位。
2. **仅作参考、不盲从的差异边界**：
   - Smartcam 许多新增能力高度依赖作者私有服务端（`yuan.clqya.com:5000`、`smartcam.clqya.com`）与 E02 模型资产/私有协议，EVCam 的核心差异化仍是本地可控、独立飞书/多平台远程控制与 E5 实车高可靠性，不应引入私有云强绑定。
   - 任何上述机制在 E5 车机的实际 CPU/GPU 内存占用、25fps 帧稳态与无障碍覆盖兼容性，均**必须以实车实测为准**，静态反编译本身不构成运行通过依据。
