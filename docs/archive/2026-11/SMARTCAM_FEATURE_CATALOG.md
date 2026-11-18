# Smartcam v3.0.40 全功能与实现机制参考目录

> 整理时间：2026-11-14（Asia/Hong_Kong）
> **版本声明（2026-11-15 更新）**：旧版 `Smartcam-V3.0.40_11_04_02_02.apk` 已按用户要求从 `work/reference-apks/` 移除；**后续对 Smartcam 的参考与对照统一以新版 `V3.0.56`（`work/reference-apks/Smartcam-V3.0.56_11_14_15_39.apk`）为准**。本文档正文保留作为基础架构/功能底座，阅读时请先结合 [SMARTCAM_V3.0.56_DIFF.md](..\..\SMARTCAM_V3.0.56_DIFF.md) 查看 `3.0.56` 等价混淆类映射及新增/重构机制（Fragmented MP4、`.part` 自愈、`usableSpace` 配额、悬浮预览、补盲独立校正、WSS/对讲/回放等）。
> 历史分析对象：原 `work/reference-apks/Smartcam-V3.0.40_11_04_02_02.apk`（`com.yuan.smartcam` / “灵视 ✨”，`versionName="3.0.40"`，`versionCode=2`，`minSdk=28`，`targetSdk=37`，`compileSdk=37`，单 `classes.dex` 共 5601 类，`arm64-v8a` + `armeabi-v7a`；APK 原包已移除，静态文本档案留存于 `work/smartcam-analysis/3.0.40/`）。
> **证据边界**：以下均为对 R8 混淆 APK、Manifest、资源与 4 个 Native `.so` 的静态逆向实测结论（无原作者工程源码；未在本机实车动态运行该 APK）。反编译类/控制流存在混淆重命名（文中标注混淆名与未混淆类名），**严禁直接复制 Smartcam 代码、资源或 APK 进 `app/`**，仅作 EVCam 架构设计对照参考。

---

## 1. 总体进程、组件与持久化拓扑

### 1.1 双进程与组件拓扑

Smartcam 将 UI 与后台持续能力拆分为两进程（`manifest_xmltree.txt`）：

| 进程 | 组件 | 职责 |
|---|---|---|
| 主进程 (`com.yuan.smartcam`) | `MainActivity` | Jetpack Compose + Material3 主界面；`onStart`/`onStop` 向 `:core` 报告前后台可见性；支持 `VIEW`、桌面 Shortcut 与可选应用开启即录 |
| 主进程 | `AmapLocationSetupActivity` | 高德定位 SDK 5 步自助 Key 配置向导（含 `WebView`/权限/测试） |
| 主进程 | `SmartcamStatusBarPlugin` / `SmartcamStatusBarPluginV2` | Flyme Auto SystemUI 插件实现（`com.flyme.auto.plugin.systemui.StatusBarPlugin`，`getID()=133`） |
| `:core` 进程 | `SmartCamCoreService` | 相机采集、GL 管线、录像、盲区、360 环视与远程拍照/推流的常驻核心服务 |
| `:core` 进程 | `KeepAliveService` | 后台保活前台服务，兼发 Flyme 状态栏图标扩展通知；监听 `SCREEN_ON/OFF`、`USER_PRESENT`、`STATUS_DENSITY_CHANGED` |
| `:core` 进程 | `StatusBarActionReceiver` / `StatusBarStateProvider` | 接收状态栏快捷动作广播；向 `com.android.systemui`（校验 callingUid/包名）提供状态快照 |
| `:core` 进程 | `CameraRecoveryActivity` | 全屏透明/恢复 Activity：后台重试打开相机失败后拉起短时前台恢复相机 |
| `:core` / 主进程 | `KeepAliveAccessibilityService` / `KeepAliveWorker` / `BootReceiver` | 无障碍保活、WorkManager 定期拉活、开机及车机唤醒/ACC/蓝牙状态等广播拉起 |
| 独立声明 Service | `LanShareService` | Wi-Fi 局域网录像浏览/下载 HTTP 服务（前景通知 ID `7101`） |
| 独立声明 Service | `BlePeripheralService` | “灵枢”外设 BLE 保活前台服务（通知 ID `3002`） |
| 独立声明 Service | `ParkingLocationService` | 停车拍照开启期间持有前台定位类型（ID `1042`，Android 14+ `location` 类型） |
| 外部触发 Receiver | `ExternalCommandReceiver` | 接收 `com.yuan.smartcam.action.EXTERNAL_COMMAND`（ADB/车机自动化调用 23 种指令） |

### 1.2 跨进程 AIDL 协议

- `ISmartCamCore`（`Lkl3;` Binder Stub / `Lzd1;` Proxy，25 个 `onTransact` 方法）：UI 进程控制 `:core` 启停录制、切预览 `Surface`、查询/订阅状态、推流配置、盲区/360 开关、拍照、调试锚点与水印刷新。
- `ICoreStateObserver`（`Lhl3;`/`Ljl3;`/`Lod1;`）：向 UI 推送 `enabled/requested/phase/session/start_reason/elapsed/wait/detail/has_failed_cameras` 状态 Bundle 及盲区显示状态。
- `ILivePushCallback`（`Lrd1;`/`Lz23;`）：远程推流回调。
- `IParkingPhotoCallback`（`Ldl3;`/`Lwd1;`）：P 挡停车抓拍回调（由 `ti2.b` `AtomicBoolean` 互斥防并发拍照）。

### 1.3 Native 库（`.so`）与持久化文件

- **Native `.so`**：
  - `libble_crypto.so`：`BleNativeCrypto.nGetPsk`，内嵌 `evcam_verify_apk_caller`（通过 `SigningInfo.getApkContentsSigners` + SHA-256 校验调用方签名），保护 BLE 外设鉴权 PSK。
  - `libws_link_crypto.so`：`WsNativeCrypto.nSignWsHandshake` / `nVerifyCmdSign`，同样带 `evcam_verify_apk_caller` 校验，用于微信小程序远控 WS 握手与命令验签。
  - `libvhal_proto_parser.so`：`VhalProtoNative`（`nParseChunk`、`nSetInterestFilter`、`nEncodeSetProperty`），C++ 快速流式解析/过滤 VHAL gRPC protobuf 字节流并编码写属性请求。
  - `libandroidx.graphics.path.so`：AndroidX Compose 矢量路径辅助库。
- **SQLite 数据库**：
  - `recorder_segments.db`（WAL 开启，`Lwc3;` + `Lyc3;`）：`segments(path PRIMARY KEY, name, directory, storage_kind, storage_uuid, started_at, duration_ms, first_pts_us, last_pts_us, width, height, frames, bytes, locked, complete, session_id)`。
  - `androidx.work.workdb`：WorkManager 任务表。
- **SharedPreferences 文件全表**（静态字节码实测）：`appearance`、`ui_density`、`ui_flags`、`app_keep_alive`、`camera_anchor_debug`、`recorder_feature`、`recorder_settings`、`recording_overlay_settings`、`recording_overlay_position`、`blind_spot`、`ble_peripherals`、`ble_overlay_settings`、`ble_peripheral_presets`、`rotary_menu_position`、`outside_voice`、`vehicle_signal_cache`、`vehicle_runtime_config`、`vehicle_identity`、`automations`、`journey_settings`、`charge_energy_estimate`、`data_preview`、`car_pet_overlay`、`parking_photo`、`parking_location`、`amap_location_config`、`status_bar_options`、`root_connection`、`app_crash_capture`、`app_log`。

---

## 2. 主界面、密度适配与车机兼容性

| 功能 | 入口/触发 | 核心实现方法 |
|---|---|---|
| 6 大主导航页 | Compose 主壳（`Lai;`/`Lti;`/`Lc82;`） | `主页`、`记录仪`、`回看`、`外设`、`自动化`、`设置`；支持底部/侧边适配、首次使用声明与推荐摄像头配置自动应用 |
| 主题切换 | 设置页 / `MainActivity` 启动加载 `appearance.theme_mode` | 支持 `跟随系统` / `深色` / `浅色`（Compose Material3 `ColorScheme`） |
| 车机屏幕尺寸 DPI 自适配 | `ui_density.diagonal_inches`（`Lu44;`） | 内置 `10.25 / 11 / 12.3 / 12.8 / 13.2 / 14.6 / 15.4 / 17 英寸` 预设及自定义 PPI/density；变更时发广播 `com.yuan.smartcam.action.STATUS_DENSITY_CHANGED`，联动状态栏插件与悬浮窗按比例重绘 |
| AndroidManifest 隐私与权限 | 按需动态申请 + Manifest 声明 | 支持悬浮窗、相机、录音、精粗定位、蓝牙扫描/连接/广播、通知、安装 APK、外部存储管理（`MANAGE_EXTERNAL_STORAGE`）；含高德定位合规声明与 Android 16 兼容处理 |

---

## 3. 相机采集、共享 GL 管线与 5 点独立限帧

1. **三类摄像头拓扑归一化（`CameraNormalizationProfile` / `Lf00;`）**：
   - `四路独立`：独立打开最多 4 路 Camera2 设备并映射前/后/左/右，校验无重复且至少一路有效。
   - `单路水平`：单路超宽横向拼接源按区域拆分为 4 向视口。
   - `单路四宫格`：单路 2×2 合图源按象限切分并支持镜像/旋转变换（`CameraImageTransform` / `Lqz;`）。
2. **共享 GL 管线与 5 点独立限帧（`La41;`、`Lz31;`、`FrameRateLimits` / `Ly31;`、`Loy2;`）**：
   - Camera2 采集原始帧（保持硬件原生帧率） → **限帧点 1（取流后·共享处理前）** → 进入共享 OpenGL ES 处理（拼接、裁剪、`GlImageAdjustments` 亮度/对比度/饱和度调整、鱼眼畸变校正） → 分流为 4 条独立消费支路，互不阻塞：
     - **限帧点 2**：录像编码前（进入合成图集 FBO 与硬编 `MediaCodec`）
     - **限帧点 3**：应用内预览显示前
     - **限帧点 4**：盲区悬浮窗校正/绘制前
     - **限帧点 5**：远程推流编码前
3. **OpenGL ES 3.0 鱼眼畸变校正（`FisheyeCalibration` / `Lyy0;`、`Lgz0;`）**：
   - 使用 OpenCV 鱼眼模型（`uRayBasis`、`uIntrinsics`、`uDistortion` k1..k4 偶次多项式、`uValidCircle`、`uStrength`），支持直接实时着色器采样（`gz0.m`）或按分辨率缓存 LUT 查找表烘焙着色器（`gz0.l`，优先探测 `RG32F` → `RG16_UNORM` → `RGBA8_PACKED16` 16bit 打包 → `RG16F`）。

---

## 4. 后台保活、防回收与相机恢复机制

- **底层组件**：`:core` 内 `KeepAliveService` 启动前景通知（ID `3001`，`smartcam_background_connection`，Android 14+ 按状态组合 `connectedDevice(16) | camera(64)` 前台类型） + `KeepAliveWorker`（WorkManager 周期兜底） + `KeepAliveAccessibilityService`（无障碍服务被系统绑定时拉起 `:core`） + `BootReceiver`（监听 `BOOT_COMPLETED`、`ACTION_BLUETOOTH_ADAPTER.STATE_CHANGED` 与车机/系统恢复广播）。
- **后台开相机 1×1 透明悬浮锚点（`CameraAnchorConfig` / `Loz;`、`Llh;`）**：
  - 需 `SYSTEM_ALERT_WINDOW` 权限；默认在屏幕左上角创建 `1×1 px`、`alpha=0.01f` 的系统悬浮窗占位，使 Android 认为进程具备可见窗口从而放行后台打开摄像头；提供 `camera_anchor_debug`（可调宽高/透明度/纯白实心调试）排查车机策略限制。
- **相机占用/异常恢复状态机（`CameraRecoveryActivity`、`Ljy2;`）**：
  - 打开或采集异常时先在后台按 **5 秒间隔最多重试 3 次**；仍失败则通过通知或短时拉起全屏透明/提示页 `CameraRecoveryActivity`（默认 3s，范围 1–30s，含“立即返回”按钮），在前台上下文重试 3 次恢复相机连接。

---

## 5. 行车记录仪录制、编码封装与存储治理

1. **硬件编码器探测与帧队列抗压（`jy2` / `ey1` / `lp2` / `uq`）**：
   - 线程分层：`recorder-gl`（GL 合成与纹理图集 FBO `oy2`） → `codec-drain`（异步抽取 `MediaCodec` H.264 编码输出） → `recorder-writer`（有界队列消费与 `MediaMuxer` 写盘）。
   - 编码器探测优先匹配硬件编码能力（`MediaCodecInfo`、`areSizeAndRateSupported`、`PerformancePoint`、`isBitrateModeSupported`），支持 H.264 清晰度档位与码率配置。
   - **有界异步缓冲队列（`uq`）**：上限 **32 MiB（33,554,432 B）或 1024 个 sample**；超限后暂停入队并在降至 **16 MiB（16,777,216 B）且 512 个 sample** 才恢复，从下一个 **关键帧（IDR）** 续接并显式记录丢帧/缺口日志，杜绝内存 OOM 飙升；写入卡死超 **60s** 自动停录并走恢复流程。
2. **分段文件与 `segments` 数据库（`Lwc3;` / `Lyc3;`）**：
   - 写入阶段使用 `.part` 临时后缀，完成封装后再重命名为最终 `.mp4` 并更新 `segments` 表（含 `locked/complete/bytes/duration_ms/frames`）。
   - 应用崩溃重启后由 `Lyc3;` 扫描数据库与文件系统残留 `.part` 文件（避开刚修改 < 60s 的活跃文件），按首尾 PTS 恢复可播放段落或清理坏块；加锁文件通过 `.lock` 标记，循环配额清理（总配额/单目录空间上限）自动跳过已加锁段落。
   - **存储等待与慢写检测（`Lb9;` / `Liy2;` / `Ldy2;`）**：U 盘挂载较慢时最长等待 **30s**（通知栏显示“等待存储设备”）；连续慢写弹出通知 `7310`（可设置静音）提示更换高速 U 盘或降档。
3. **动态画面水印（`o03` + `nb4`）**：
   - 与合成 GL 同线程叠加 **1024×132** 透明背景 Bitmap 纹理，按秒（`currentTimeMillis / 1000`）+ 状态变化节流重绘：显示日期时间、自定义文字及可选车况字段（车速/挡位/电耗/SOC/外温/胎压等，通过 `WatermarkConfig` 按模块勾选）。
4. **启停提示音与悬浮回看快捷球（`Lh13;` / `Lb13;`）**：
   - 支持系统提示音或从系统 `Download` 目录导入自定义音频文件（限制 **≤ 20 MB**）；独立可选悬浮控制球记录位置于 `recording_overlay_position`。

---

## 6. 360 环视拼接、星舰 7/领克 08 标定与倒车自动预览

1. **GL 多边形/网格投影拼接（`Lch4;`）**：
   - 内置 `assets/surround/xingjian7/`：`bird.mesh`（2.65 MB）、`front_wheels.mesh`、`rear_wheels.mesh`（各 760 KB）、`calibration.json`（OpenCV 鱼眼模型 `1280×800`, `fx=fy=318.0`, `distortion` 4 项及各向外参 `rotation_vector`/`position`）以及 4 个动态辅助线库 `assist_line_*.db`。
   - Vertex/Fragment Shader 接收 4 路纹理裁剪框 `uFrontCrop/uBackCrop/uLeftCrop/uRightCrop`、4 个 3×3 单应/映射矩阵 `uFrontMap/uBackMap/uLeftMap/uRightMap` 以及权重混合通道 `vExtraUv`、`vBlend`、`vTexId`，完成地面融合与 Gamma `pow(..., 0.9)` 校正。
   - 另支持领克 08 内置方案及从外部导入标准 1.3 版标定文件（704 字节 `.param` + `Table.db` + `table_cam_two_{front,rear}.db` + `assist_line_*.db`）。
2. **多视图切换与泊车辅助叠加**：
   - `Lis3;` 支持 `随档位`、`前视`、`后视`、`前轮`、`后轮`、`左右侧视` 与上下分屏布局。
   - **雷达条（`RadarBarGeometry` / `Lmw2;`、`Lwg4;`）**：读取车机 8+ 区 cm 级雷达距障信号，按车体坐标绘制弧形/分区彩条告警。
   - **动态轨迹线（`GuidanceStrip` / `Lh81;`、`Lji2;`）**：根据方向盘转角查表/几何投影生成随动引导轨迹带。
3. **倒车自动全屏预览与自动退出（`ReversePreviewConfig` / `PreviewSpeedCloseConfig` / `ParkPreviewCloseConfig`）**：
   - 挂入 `R` 挡延迟 **0–5000 ms**（默认 **200 ms**）自动拉起预览画面；切离 `R` 挡后，可配车速达到 **15–50 km/h** 自动关闭，或挂入 `P` 挡延迟 **1–60 s**（默认 **3 s**）自动退回后台。

---

## 7. 盲区监测悬浮窗（`Lst;` / `Lcu;` / `Lcv;` / `Lku;` / `Ltt;`）

- **多源触发与抑制策略**：
  - 触发源：左右转向灯、双闪（前/后视图）、副驾及后排车门开启（排除主驾门防频繁误弹）、方向盘转角超阈值（支持左右独立标定与中心偏离/大于/小于比较符）、外部命令广播、自定义“雨夜模式”（同时显示双侧画面）。
  - **R 挡抑制（`suppressTurnsInReverse`）**：倒车时优先让位给 360/倒车影像，抑制转向盲区侧窗。
- **窗口与渲染特性**：
  - 窗口位姿（`BlindSpotPlacement`）使用屏幕归一化比例，可直接在悬浮窗拖拽移动与缩放，支持圆角开关（`cornerRadius`）与方向盘切换丝滑缩放过渡。
  - 每个方位（前/后/左/右）独立配置源裁剪框（`Lku;`），并可选**低速/特殊工况回退原始未翻转未旋转单路画面**。

---

## 8. 车宠与数据卡片桌面悬浮层

| 模块 | 实现类与存储 | 功能机制 |
|---|---|---|
| **车宠悬浮窗** | `La82;`（自定义 View）、`Lj10;`、`Ls72;`；配置存 `car_pet_overlay` | 内置 3 个角色（`球球`、`云宝`、`亮亮`），原生 `Canvas` + 矢量/眼环点阵渲染多组表情（编号 `00`–`21`、`30`–`41` 等），随车速/转向/刹车/加速/倒车/待机实时变换，支持点击与桌面拖拽 |
| **悬浮数据卡片** | `Lkg3;`、`Lde;`；配置存 `data_preview` | 可拖拽浮窗，提供标准与紧凑（`compact`）两种外观、RGB+透明度自定义、字体字号、支持拖拽重排显示指标（含车速/功率/SOC/胎压/续航以及蓝牙空气传感器温湿度/CO₂/甲醛） |

---

## 9. 本地回看与 Wi-Fi 局域网分享（`LanShareService` / `Leo1;` / `Lms;`）

- **本地回看**：按日期/小时分组，标红/徽标高亮加锁（`.lock`）及事件打点（`.marks`）录像，支持多角度同步切换、倍速播放、一键加锁/删除。
- **Wi-Fi 局域网分享**：
  - 启动 `LanShareService` 获取当前录像根目录，并在 `18765..18788` 依次尝试绑定内网 HTTP 端口，挂载随机 `UUID` 前缀防猜解（`http://<LAN_IP>:<port>/<uuid>/`），界面生成二维码供手机扫码访问，默认 **60 秒无客户端请求自动停服**（带原子时间戳心跳延期）。
  - 继承轻量 HTTP 框架（`Ln72;`，类 NanoHTTPD）：
    - `/`：返回内嵌响应式 HTML5 单页应用（暗色/亮色适配、文件日期筛选、离线状态指示、视频播放器）。
    - `/api/files`：分页检索视频列表（含文件名、相对路径 Base64 ID、尺寸、`locked`/`.marks` 状态与缩略图 URL）。
    - `/api/status`：实时返回存储盘总容量/可用空间 JSON（`Cache-Control: no-store`）。
    - `/info/<id>`：用 `MediaMetadataRetriever` 实时提取时长与分辨率。
    - `/t/<id>`：抽取 `1s` 处关键帧压缩为 JPEG（q=75，`max-age=60`）。
    - `/p/<id>` 与 `/d/<id>`：解析 HTTP `Range: bytes=...` 请求头，支持 **206 Partial Content** 拖拽播放与 `416 Range Not Satisfiable`；`/d/` 附带 `Content-Disposition: attachment` 触发下载。

---

## 10. 手机云互联、远程直播/回放与停车拍照定位

- **云端后端与签名**：基址 `https://yuan.clqya.com:5000/hwn9mFEubWgL94xj`；状态绑定 `/evcam/bind-status`；长连接 WebSocket `/evcam/ws`（`Lmm0;`）使用 `libws_link_crypto.so` 对握手包与远程下发指令做 APK 签名绑定与 HMAC/摘要校验。
- **微信小程序远程看车与远程回放（`Lf83;` / `Lmv1;` / `Lwu1;` / `Lxu1;`）**：
  - **实时看车**：自定义纯 Java/Kotlin **RTMP/RTMPS/RTMPT** 客户端（`f83` 正则解析 `rtmpt?s?://`，支持 chunk size、bandwidth ack、`FlvPacket`），从共享 GL pipe 的**限帧点 5（远程输出前）**编码 H.264 视频推流；同时支持“远程看车时暂停本地录像”以避让编码器/算力竞争。
  - **远程回放**：`Lwu1;` 通过 `MediaExtractor` 按选定日期时间切片读取本地 `.mp4` 裸流封装 FLV/RTMP 推回云端供小程序拖拽点播。
- **P 挡停车自动拍照（`ParkingLocationService` + `Lih1;` + `Lxi2;`）**：
  1. 检测进入 `P` 挡稳定后向 `:core` `ISmartCamCore.j(IParkingPhotoCallback)` 请求抓拍单帧并编码为 **WebP（`parking.webp`）**；若当前无网则先缓存在本地（保留最新张）。
  2. **两段式上传（实测链路）**：先请求自有后端 `/evcam/sign` 获取临时签名 `{id, ts, sign}` → 将 `parking.webp` 直接 `multipart/form-data` POST 至第三方图床 `https://api.superbed.cn/upload` 拿回 `imageUrl` → 再 POST `{imageUrl, latitude, longitude, capturedAt}` 至 `/evcam/report`。
- **双源定位与坐标系归一化**：
  - 优先调用高德定位 SDK（支持 `AmapLocationSetupActivity` 展示当前包名与 **APK SHA1**，引导用户在高德控制台 5 步自助申请 Key 填入，单次超时 **20 s**，已是 `GCJ02` 坐标）。
  - 若高德不可用或超时，自动降级至 Android 系统定位（`LocationManager`，保留 **5 分钟**内最近位置缓存），上传 `/evcam/report` 前由 `qi0.d0(lat, lon)` 自动从 `WGS84` 纠偏为 `GCJ02`；同时开启 `ParkingLocationService`（`foregroundServiceType="location"`）保障熄屏/后台合法获取位置。

---

## 11. 多源车辆信号抽象、公式引擎与云同步

Smartcam 将不同车机平台信号统一抽象为 `StandardVehicleState` / `StandardSignalCandidate`，共支持 **4 类输入源**（`vehicle_runtime_config`）：

| 信号源 | 核心类 | 机制与特征 |
|---|---|---|
| **VHAL gRPC** | `Laa4;` + `VhalProtoNative`（`libvhal_proto_parser.so`） | 直连或经本机提权端口请求 `vhal_proto.VehicleServer/{GetAllPropertyConfig, StartPropertyValuesStream, SendAllPropertyValuesToStream, SetProperty}`；JNI 侧使用 `nSetInterestFilter` 白名单过滤仅关心的 propId 并解码 chunk，极大降低 GC 压力 |
| **APVP** | `Lrj;` / `Lqj;` | 扫描 APVP 属性目录（`property_catalog:apvp`），按信号名建立流式订阅与回写 |
| **ECarX CarAPI / AIDL** | `Le10;` / `Lt5;` | 通过 `IECarXCar$Stub` / `IECarXCarProperty$Stub` / `com.ecarx.xui.adaptapi.car.sensor.CarSensor` 注册回调；不支持订阅的属性退化为 `CarApi(pollIntervalMillis=500)`（内部高频支持 50ms/500ms）轮询 |
| **BLE 广播外设** | `Lds;` / `Lm0;` | 被动扫描蓝牙空气监测仪/温湿度/CO₂/甲醛广播包（`BleBroadcastSignal`）；带智能省电窗口（亮屏或非 P 挡持续 **10 s** 启动扫描，熄屏且驻车 **10 s** 自动停扫；数据过期阈值 `staleAfterMillis=15000`） |

- **表达式与公式引擎（`Le70;` / `Lxi3;`）**：支持自定义信号通过公式实时派生（如功率=电压×电流/1000、左右转向合并等），支持四则运算、比较运算符、逻辑与/或/非及最大 **32 层嵌套** `if(cond, t, f)` 条件表达式（仅限制文本类型不可公式化）。
- **车型配置同步与属性诊断导出**：通过 `/evcam/vehicle-config/sync` 按车机 `buildModel` 和本地各模块版本号拉取增量 `property_catalog`、`bindings`、`signal_mapping`、`source_policy`；调试界面可将扫描到的全量属性导出为 `.csv` 并上传 `/evcam/property-csv` 协助适配。

---

## 12. 自动化规则引擎与开门自动退出

- **规则编排（`Lo24;` / `AutomationRule` / `AutomationCondition` / `AutomationAction`）**：
  - 支持多条件 `AND / OR` 组合与 6 类比较运算符，内置 **0–3600 秒** 稳定防抖延时；支持规则持久化及启停日志 `AutomationLog`。
  - 触发动作支持两类：**写回车辆信号属性**（受 `WriteFeedback` 检查保护，可配防重复/忽略条件）或触发应用内置录制/拍照/盲区/外放语音等动作。
- **开门自动退出 UI（`Lk03;`）**：
  - 监听主驾/指定车门触发且避开应用启动初期保护期；若 UI 已在后台则直接执行“停录→放开相机”；若 UI 在前台则弹出**倒计时确认框（支持立即退出）**，倒计时结束后停录并关闭界面同时**保留 `KeepAliveService` 与车辆信号后台运行**。

---

## 13. “灵枢”BLE 外设、快捷菜单与 BLE DFU OTA

- **双向蓝牙架构（`BlePeripheralService` + `EmpGattServer` `Lju0;` + `Llt;` GATT Client + `BleNativeCrypto`）**：
  - **GATT Server 模式**：按开关以设备名 `LK1_S_<3字符标识>` 广播标准蓝牙 Base UUID 服务 `FFF0`（特征 `FFF1`、`FFF2`、`2902` CCCD），等待旋钮/按键设备主动反连。
  - **GATT Client 模式**：主动连接自定义 UUID 服务 `11110001/11110002/11110003-1111-1111-1111-111111111111` 的外设，连接后发随机挑战并调用 `libble_crypto.so` `nGetPsk` 完成 PSK 双向握手鉴权；断线支持延迟重连（蓝牙开启后默认延迟 `adapter_ready_delay_seconds=3` s）。
- **7 种手势与旋钮悬浮菜单（`Li73;` / `Lcw2;` / `QuickBinding`）**：
  - `QuickGesture`（`Lcw2;`）识别 **7 种手势**：`SHORT`（按一下）、`DOUBLE`（按两下）、`TRIPLE`（按三下）、`QUAD_PLUS`（连按四下及以上）、`LONG`（长按）、`ROTATE_CW`（右转一格）、`ROTATE_CCW`（左转一格），可配多击间隔 `multi_click_`；支持 `1–16` 个自定义动作槽位、可拖拽且可配常驻打开（`rotary_stays_open`）的旋钮悬浮菜单，外加 **2–10 秒（每档 0.5s）或常驻** 的 `QuickActionHudContent` 快捷动作反馈 HUD。
  - 个人按键预设可经 `/evcam/rotary-presets/` 云端同步备份。
- **BLE DFU OTA（`Lns;` / `Lvd;`）**：
  - 从 `https://smartcam.clqya.com/firmware/ble/version.json` 检查在线新版本或从本地选 `.zip` 固件包（校验大小 **≤ 64 MB**，解压解析 `dfu_setting.dat`、`config.txt`、`APP1`/`APP2` 分区），按 IUS GATT 分包协议与超时重试（含 120s 命令等待与旧版兼容回退）写入固件。

---

## 14. 车外喊话、音频外放与 ECarX HIDL 功放控制

- **语音喊话链路（`Lzf2;` / `Lbv2;`）**：
  - 支持枚举系统默认/车载/USB/蓝牙/有线多种输入麦克风；按 **16 kHz / 16-bit PCM Mono** 创建低延迟 `AudioRecord` 与 `AudioTrack`。
  - 系统级 `AcousticEchoCanceler` + 噪声抑制叠加软件级 `ProtectionResult(echoSuppressed, overloadLimited)` 啸叫/回声能量门限与削波限幅保护。
- **车外扬声器通道控制（HIDL + 媒体流降级）**：
  - 优先反射/原生尝试枚举并调用 4 种 ECarX 音频 HIDL HAL 接口：`vendor.ecarx.{,xma.}audiocontrol@{1.0,2.0}::IEcarxAudioControl`（实例 `default` 或 `audiocontrol`），内置交互式向导扫描功放切换码 `hidl_code`（1–9999，默认 56）并将生效接口持久化到 `outside_voice`。
  - 无 HIDL 权限或非兼容车机自动切换至标准 Android `AudioAttributes` 音频流输出。
- **5 路快捷外放音频槽位**：支持导入最多 **5 组**自定义音频文件（MP3/WAV/OGG/M4A/AAC/FLAC/AMR），支持播放中防并发抢占保护。

---

## 15. 行程/充电统计、胎压预警、Flyme 状态栏与提权运维

| 模块 | 实现方法 | 关键参数与证据 |
|---|---|---|
| **行程/充电/加油统计** | `Lt84;` / `Lbw;` / `Lab1;` | 读取信号预设中档位/车速/SOC/交直流功率/续航/油量：`P` 挡无车速且里程 **> 100 m** 自动结算一次行程；自动检测充电并按 **90 天窗口自学习电池容量**；`powerRule` 强制取 `max(nonnegative DC, AC), never sum`；停车油量稳步上涨识别加油；算出实际续航达成率；离开车辆弹窗“停车回顾”；离线加密隔离队列同步 `/evcam/telemetry` |
| **胎压失压预警** | `Ln0;` / `ToneGenerator` / 弹窗通知 | 持续监控四轮胎压与掉压斜率，异常时触发提示音 + 常驻告警 |
| **Flyme 状态栏插件** | `KeepAliveService` + `SmartcamStatusBarPlugin{,V2}` + `StatusBarStateProvider` | **双保险方案**：1）`KeepAliveService` 发带 Flyme 私有扩展字段的静默通知（`flag_status_icon_notification=1`, `id=136`, `rank=136`）常驻顶栏；2）SystemUI 加载插件 Service（ID `133`）后，通过 ContentProvider `content://com.yuan.smartcam.statusbar.state` 校验 callingUid 为本应用或 `com.android.systemui` 获取实时录制通知状态，弹出包含“开始/停止录制、锁定片段、打开应用、退出界面”4 个磁贴的快捷面板（自动按通知内 `smartcam.status.density` 缩放） |
| **Root 提权代理** | `Lqw0;` / `Ly4;` / `Lpa;` / `Lgt;` / `RootServiceMain` | 当连接车机本地 VHAL/隐藏服务需高权限时，应用内置三种提权通道：**本地 ADB TCP（默认 5555）**、**Android 11+ ADB TLS（含 6 位无线调试配对）** 或 **本地 Telnet + `su`**；提权后拉起常驻进程 `app_process /system/bin com.yuan.smartcam.root.RootServiceMain`，监听 abstract socket `smartcam.root.<uid>`，校验对端 UID 与 **32 字节随机密钥**，再以 UID 0 同步执行 100ms–10s 超时限长的 JSON Shell 请求（输出截断保护） |
| **外部广播控制** | `ExternalCommandReceiver` + `Lix0;` | 监听 `com.yuan.smartcam.action.EXTERNAL_COMMAND` 的 extra `command`，支持 **23 种命令**：`start_recording`、`stop_recording`、`connect_cameras`、`release_cameras`、`mark_current_video`、`show_{front,rear,left,right}_blind_spot`、`hide_blind_spot`、`enable/disable_rain_night`、`open/close_preview`（支持 `reverse_preview`）、`start_background_service`、`open_app`、`start/stop_outside_voice`、`play_outside_audio_1..5` |
| **硬件性能评估** | `Lfk2;` (`PerfReport`) / `Lyu;` / `Ljv1;` | 按分辨率阶梯实测单核/多核 CPU、纯 OpenGL 多通道纹理后处理帧率，以及完整 **OpenGL → 硬件编码**链路稳定能力，输出 `recommendations` 推荐配置档位报告 |
| **配置广场与车型/共创闭环** | `Lqa0;`/`Lea0;`、`/evcam/config-square/`、`/evcam/camera-profile{,/submissions}`、`/evcam/feedback` | 支持打包导出/一键应用他人完整或局部配置（导入前自动原子备份支持回滚）；社区车型摄像头方案提报审核；功能反馈 14 天周期投票；版本更新直连 Gitee Releases (`Cyuan_cai/yuan-cam`) |
| **诊断与分块日志** | `Lyh;` / `/evcam/debug-log` | 记录应用运行日志（循环限额 ~1 MB）+ 信号/行程日志（~16 MB），支持**分片切块上传**诊断服务器；未捕获异常写入 `app_crash_capture` 并在下次重启自动上传 |

---

## 16. EVCam 后续参考快速索引

| EVCam 后续改造场景 | 最值得参考的 Smartcam 实现 | 对应本地分析文件（均在本机 gitignored `work/`） |
|---|---|---|
| 录像高占用 / 丢帧 / 卡死重启修复 | `jy2`+`ey1`+`oy2`（6 槽图集 FBO、`uq` 32M/16M 关键帧水位控流、60s 写卡死保护与 `Lyc3;` `.part` 恢复） | `work/smartcam-analysis/jy2.java`、`ey1.java`、`oy2.java`、`uq.java`、`lp2.java` |
| 水印高频刷新降功耗 & 多车况展示 | `nb4`（1024×132 单次秒级+变更重绘离屏 Bitmap 纹理） | `work/smartcam-analysis/nb4.java` |
| 后台长期保活 & 相机防杀/恢复 | `1×1/0.01 alpha` 悬浮窗锚点（`Loz;`/`Llh;`） + 后台 3 次/5s 与 `CameraRecoveryActivity` 3 次恢复 | `work/smartcam-analysis/3.0.40/smartcam-feature-inventory/core_and_unobfuscated_decompiled.txt`、`verified_details_decompiled.txt`（搜索 `Llh;`、`CameraRecoveryActivity`） |
| 转向/开门盲区与 R 挡抑制交互 | `Lst;`/`Lcu;`/`Lku;`（R 挡互斥、每方向独立裁剪、拖拽比例窗口与低速原图回退） | `work/smartcam-analysis/3.0.40/smartcam-feature-inventory/verified_details_decompiled.txt`（搜索 `Lst;`、`Lcu;`、`Lku;`） |
| E5 / 吉利 Flyme 状态栏快捷控制 | `KeepAliveService` Flyme 通知图标字段（ID 136） + `SmartcamStatusBarPlugin`（ID 133） + `StatusBarStateProvider` | `work/smartcam-analysis/3.0.40/smartcam-feature-inventory/core_and_unobfuscated_decompiled.txt` |
| 多源车辆信号（VHAL/APVP/ECarX/BLE）与公式 | `VhalProtoNative` JNI 过滤 + `ECarX AIDL` 轮询退化 + `Le70;` 表达式计算器 | `work/smartcam-analysis/vehicle-api-index.txt`、`aa4.java`、`e70.java`、`z74.java` |
| OpenGL 鱼眼畸变去畸变 / 360 拼接 | `Lgz0;` 鱼眼 LUT 烘焙 shader + `Lch4;` `bird.mesh`/Homography 融合 shader | `work/smartcam-analysis/3.0.40/smartcam-feature-inventory/verified_details_decompiled.txt`、`xingjian7_calibration_head.txt` |
| 局域网 Wi-Fi 手机看回放（含 206 Range） | `LanShareService` + `Leo1;`（18765..18788 端口 + 随机 UUID + Range 下载/播控 + `MediaMetadataRetriever` 缩略图） | `work/smartcam-analysis/3.0.40/smartcam-feature-inventory/verified_details_decompiled.txt`（搜索 `Leo1;`） |
| 停车拍照上传与高德/系统定位纠偏 | `ParkingLocationService` + `/evcam/sign` → 图床 → WGS→GCJ02 → `/evcam/report` | `work/smartcam-analysis/3.0.40/smartcam-feature-inventory/verified_details_decompiled.txt`（搜索 `Lih1;`、`AmapLocationSetupActivity`） |
| 原始全量提取清单（字符串/类/Manifest/SQL/.so） | 本次全盘静态测绘全集（14 个产物文件） | `work/smartcam-analysis/3.0.40/smartcam-feature-inventory/`（先读 `feature_discovery_dump.txt`、`urls_and_keywords.txt`、`badging.txt`） |

---

## 17. 与当前 EVCam 的内存结构及优劣对照（静态实测）

> **边界声明**：本节所有数值与行为对比均来自双方安装包/源码的静态核查（Smartcam `v3.0.40` DEX/资源 + EVCam 当前 `codex/e5-recording-startup-fix` 源码与构建产物 `EVCam-A/B-1.6.6-test-11141103`）；**当前尚无 E5 实车或同条件 `adb shell dumpsys meminfo`（PSS/RSS/Graphics）对照实测数据**，不能下结论“谁的总 PSS 必然更低”，更不给出百分比。

### 17.1 内存结构关键差异

| 维度 | Smartcam v3.0.40（静态实测） | 当前 EVCam A / B（源码实测） |
|---|---|---|
| **包体积与 DEX 映射** | APK `12.96 MB`，单 `classes.dex` **`6.28 MB`**（开启 R8 混淆裁剪 + `baseline.prof`），含 4 个 `.so` | APK `25.04 MB`，DEX 总计 **`21.58 MB`**（`isMinifyEnabled = false` 未裁剪），仍打包 DingTalk Stream SDK/Glide/NanoHTTPD/gRPC 等（`AppFeatures` 仅关运行时入口） |
| **进程模型与 UI/后台隔离** | **双进程**：主进程（Compose UI + 插件）与 `:core`（`SmartCamCoreService` 等）；按 `Lk03;` 开门退出 UI 后可仅留 `:core` 在后台，但双进程同在时需承担两份 ART/Framework 基础开销与 AIDL 传输 | **单进程**（`com.kooo.evcam`）：MainActivity、Fragments、Glide、Feishu/Heartbeat/BlindSpotService 与 MultiCameraManager 共用同一堆内存与 ART 进程 |
| **共享合成 GL / FBO 缓冲** | `oy2.c()` 创建 **6 个** `GL_RGBA` 纹理 + FBO 图集环形槽；尺寸由 `t03.c(scale, 2560, 1600)` 决定，默认 `scale=100` 为 **`2560×1600`**（约 `15.625 MiB/张`，6 张合计 **`93.75 MiB`** 显存/GraphicBuffer；最小 `scale=50` 即 `1280×800` 仍约 `23.44 MiB`） | **A（独立）**：3 路各有 1 个 `EglSurfaceEncoder` EGL 上下文直推 `MediaCodec.createInputSurface()`，**无**中间 RGBA FBO 图集；**B（合成）**：`MultiCameraCompositor` 单 EGL 上下文将 3 路 `SurfaceTexture` 直接合成绘制到 `MediaCodec` 输入 `Surface`，**无** 6 槽 FBO 环 |
| **写盘积压队列与复用池** | 1 路编码器；`uq` 允许在途写盘积压峰值 **`32 MiB / 1024 samples`**（16 MiB/512 回落恢复并等待 IDR 丢帧），`e70` 空闲复用池上限 **`4 MiB / 64 buffers`** | `AsyncMuxerWriter` 对已分配 + 队列总额严格封顶 **`4 MiB / 64 samples / 每编码器`**（A 三路日常上限合计 **`12 MiB`**、飞书四路 `16 MiB`；B 一路 **`4 MiB`**），停止即 `pool.clear()` 释放 |
| **MediaCodec 与相机流实例** | 共享 GL 分流：正常仅 **1 个录像编码器**（推流时再开第 2 个）；Camera2 每路主取流进共享 `oy2` | **A**：日常 **3 个独立编码器**（HEVC/H.264，远程四路可到 4 个）；**B**：日常 **1 个 H.264 编码器**；Camera2 每路拆为“共享预览输出组（主预览+悬浮窗+副屏+MJPEG StreamGlEncoder）”+“独立 `recordSurface`” |
| **水印 Bitmap / 纹理** | 输出管线单份 `nb4` 离屏 `Bitmap`（最大 `1024×132 ARGB_8888 ≈ 528 KiB`）+ 1 个 GL 纹理 | **时间水印**：每个 `EglSurfaceEncoder` 各持 1 份 `400×44`（`~68.75 KiB`）且每秒调 `texImage2D`；**车况水印（默认关）**：开启时每编码器 1 份 `640×44`（`~110 KiB`，`VehicleStatusWatermark` 用 `texSubImage2D`），关闭时不分配 |
| **缩略图与网络/流缓存** | `Lk04;` 使用 **`24 MiB`** `android.util.LruCache` 缓存 `320×200` 视频帧；回放 `recording-stream` 另分配 `8 MiB` DirectBuffer | 视频/图片列表使用 **Glide** 默认内存缓存与 BitmapPool（未自定义极小上限，且 `MainActivity`/`PlaybackFragmentNew` 未调 `onTrimMemory`/`clearMemory`）；Wi-Fi/ESP32 开启 `StreamGlEncoder` 含双 PBO readback + `MjpegStreamManager` 每帧分配 `int[]`/`byte[]`（无客户端时暂停） |
| **可选重资产与附加服务** | 默认 APK 带星舰 7 3D `mesh`/辅助线 `.db`（仅开启 360 环视时加载）、高德 SDK 31 类；另含局域网 HTTP、WS 远控、BLE 外设/DFU、喊话音频流、行程/自动化常驻逻辑 | 无高德定位/3D 环视网格/BLE DFU/云 RTMP 推流/车外喊话；精简后主要常驻仅为 3 路录像、飞书 WebSocket、转向补盲与按需 HUD |

### 17.2 各自优劣总结

- **Smartcam 的优势**：
  1. **后台可只留 `:core` 且包体积精简**：R8 裁剪使 DEX 仅 `6.28 MB`；UI 退出后 Compose 界面不长期占用 `:core` 堆，后台常驻边界清晰。
  2. **多消费者共享一套相机+GL 处理与 5 点独立限帧**：取流、调色/鱼眼去畸变只做一次，再分别按不同帧率喂给录像/预览/盲区/推流，不会因为多路 consumer 各建重复 GL 变换。
  3. **硬件编码探测与异常自愈完整**：`lp2` 对尺寸/帧率/PerformancePoint 做前置探测与降级；`CameraRecoveryActivity`、`segments` `.part` 数据库修复与写卡死 `60s` 自愈闭环成熟；功能生态完整（360 环视、云端/小程序实时看车、局域网 Range 播放、VHAL/APVP/ECarX/BLE 多源+公式引擎、Flyme 状态栏插件、旋钮外设）。
- **Smartcam 的劣势 / 不宜盲搬之处**：
  1. **图集 FBO 环形显存开销重**：`oy2` 默认直接创建 6 张最高 `2560×1600` RGBA8888 纹理/FBO（`~93.75 MiB`），在内存与带宽吃紧的车机上未必比多路直推省内存，且把三路画面压进单图集也会牺牲单摄独立高分辨率留存。
  2. **功能高度耦合云服务与复杂提权**：强绑定自有后端与超级图床、高德自助 Key 向导、ADB/Telnet UID 0 提权代理，安全性/隐私合规与维护面远重于本地化定位；`uq` 积压时静默丢帧等关键帧（虽有日志）对取证完整性存在取舍。
- **EVCam 的优势**：
  1. **职责纯粹、低外部依赖与严格有界内存**：日常三路独立保留原始清晰度（A）或轻量无中间 FBO 的直接 2×2 合成（B）；`AsyncMuxerWriter` 每编码器 `4 MiB / 64 samples` 封顶比 Smartcam `32 MiB` 更保守可控；飞书直连交互、EVCC 副屏与 ESP32/Wi-Fi MJPEG HUD 贴合车机实车极客需求；开源 GPL-3.0 代码可控、无隐式第三方图床与提权依赖。
- **EVCam 的劣势 / 可重点借鉴 Smartcam 的改进点**：
  1. **单进程未裁剪且缺少全局低内存回收**：`isMinifyEnabled = false` 导致 `21.58 MB` DEX 连带未启用 SDK 一起映射；单进程内 Glide/Playback/Fragment 与底层录像共享内存且无 `onTrimMemory` 主动清理；`MjpegStreamManager` 每帧逐帧 `new int[]`/`byte[]` 易造成频繁 GC 抖动。
  2. **多消费者独立硬件输出流与逐编码器重复水印**：3 路独立时需维护 3 个编码器与 EGL 上下文（每路各自画时间/车况水印，且时间水印每秒 `texImage2D` 全量重建而非 `texSubImage2D`）。
  3. **待实车补齐的工程化细节**：`RecordingFpsPolicy` / `RecordingEncoderSelector` 在 E5 的兼容表现仍待实车验证；缺少类似 Smartcam `recorder_segments.db` 的异常 `.pending` 事后扫描恢复、以及全局统一的录制/恢复状态机收敛（当前 `MainActivity`/`BlindSpotService`/`MultiCameraManager` 状态仍偏分散）。
