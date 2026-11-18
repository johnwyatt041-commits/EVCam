# 可选录像车辆状态水印

版本：`1.6.6-test-11140245`；任务分支：`codex/vehicle-status-watermark`。

## 使用与边界

设置 → 录像车辆状态水印，默认关闭。开启后在保存的 Codec 录像左下角显示车速、刹车踏板、左/右转向状态。开关可在录制过程中使用，不重建相机会话，不改变原视频编码格式、日常三路/飞书四路、HUD 或转向补盲。

未收到有效数据时显示“未知”或速度 `--`，断线清空状态；连续型的车速/踏板数据超过 3 秒未更新会过期。旧版转向枚举中的非标准值显示未知，不猜测双闪编码。显示的是最近收到的信号状态，不是具有同步精度保证的车辆事件记录；源端延迟及视频缓冲会影响实际对齐，需实车确认。

**刹车尚未在 E5 上验证**。当前尝试标准 `BRAKE_PEDAL_COMPRESSION_PERCENTAGE`（`0x11600310`），车机不提供该字段时持续显示未知；不把手刹、能量回收、ABS 或减速当成踩刹车。没有从 Smartcam APK 复制源代码、库或资源，没有接入其云服务。不要把完成离线开发描述为三项信号均已在 E5 可用。

## 关闭时的行为

- 不创建 `VehicleStatusWatermark`、Bitmap、GL 纹理或水印着色器，不调用状态水印绘制。
- 不租用车辆状态数据源，不为本功能创建 gRPC 连接、轮询或后台定时任务。
- 开启后关闭，或最后一路录像停止时，释放专用资源和订阅；补盲已有的 VHAL 连接继续由补盲管理。
- 录制入口保留开关状态和少量判断；新增 APK 代码体积也存在。因此不能承诺绝对零成本，尚无实车 CPU、PSS 或 fps 百分比数据。

## 实现定位

| 文件 | 职责 |
| --- | --- |
| `vehicle/VehicleStatusDecoder.java` | 有边界的只读 protobuf 解码、字段验证、过期和乱序处理，未知与关闭分开 |
| `vehicle/VehicleStatus.java` | 不可变显示快照，显示值不变时复用对象 |
| `vehicle/RecordingVehicleSource.java` | 多相机引用计数；优先复用现存 VHAL 观察者，没有时才按需创建一份，补盲启动后让出自有连接 |
| `VhalSignalObserver.java` | 可选原始数据回调、观察者生命周期登记、初始快照读取及异步停止；没有车辆写入调用 |
| `camera/VehicleStatusWatermark.java` | 编码线程上的小区域 GL 叠加；640×44 复用 Bitmap/Canvas/Paint/纹理，显示内容变化才重新上传 |
| `camera/EglSurfaceEncoder.java` | 提交原编码 Surface 前叠加，关闭时分离资源，水印初始化失败时继续基础录像 |
| `camera/CodecVideoRecorder.java`、`MultiCameraManager.java` | 录像开始/停止与设置切换的线程交接；避免准备录像与关闭开关竞态 |
| `AppConfig.java`、`SettingsFragment.java`、`fragment_settings.xml` | 默认关闭、偏好保存与设置入口 |

所有 Java 文件均位于 `app/src/main/java/com/kooo/evcam/` 下；布局位于 `app/src/main/res/layout/`。未向 MainActivity 新增业务逻辑，未新增运行依赖或修改原 ARM64 native 库。

## 协议依据

- 现有 `libvhal_decoder.so` 的只读检查确认外层 repeated field 1，条目内 field 1 为属性值；未改变该库。审计指令见 `work/evcam-vhal-disassembly.txt`。
- 属性值字段采用 [AOSP VehicleHalProto.proto](https://android.googlesource.com/platform/hardware/interfaces/+/cd6da073278d1326e202b2286355d49e2e6f4ca6/automotive/vehicle/proto/VehicleHalProto.proto)：prop=1、timestamp=3、area=4、sint32 values=5、float values=7、status=10；只接收全局有效值。
- [Android VehiclePropertyIds](https://developer.android.com/reference/android/car/VehiclePropertyIds) 定义车速为 m/s、踏板为 0–100 百分比。标准属性存在不代表 E5 固件实现了该属性。
- 旧 [VehicleTurnSignal](https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/android10-release/automotive/vehicle/2.0/types.hal) 定义 NONE=0、RIGHT=1、LEFT=2，和本项目现有 native 方向转换相符。

## 验证

- 64 项 JVM 测试通过：包含默认关闭无数据源/GL 调用、三路共享、关闭不停止补盲连接、自有连接让出/最后租约退出、方向不颠倒、未知、连续值过期、状态不可用、乱序、非法和截断数据。
- Debug/Release 构建与 Release 必要 Lint 通过。
- Android 11 x86 隔离模拟器运行 1 项实际 EGL/GLES 绘制测试：未知/有效状态切换、内容未变时像素稳定、水印区外画面不变、无 GL 错误；输入是合成状态，没有用模拟器伪造实车信号验证。
- 设置 UI 自动化验证默认关闭、开启保存、重启保持和再次关闭。截图及脚本在 `work/vehicle-watermark-ui/`、`work/vehicle_watermark_ui.py`。
- GPU 证据 `status-watermark-active.png` / `status-watermark-unknown.png` 为单色测试背景，不能视作实车录像。
- UI/GL 测试仅对安装到专用 x86 模拟器的副本去掉不兼容 native 库，并重新签名；正式 APK 保留 `lib/arm64-v8a/libvhal_decoder.so`。

本功能不包含上次审查建议的录像写盘解耦、编码器能力选择或源 FPS 设置调整。原卡顿问题仍需单独定位。实车应检查三路/四路交接、实际车速单位、转向方向及闪烁、踏板信号可用性、状态与画面延迟、开关前后帧率/负载。
