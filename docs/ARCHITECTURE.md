# 开发定位与历史决定

所有路径相对于仓库根目录。包名为 `com.kooo.evcam`，Android 单模块 Java 应用，GPL-3.0。

## 关键路径

| 路径/类 | 职责与修改注意 |
| --- | --- |
| `app/src/main/java/com/kooo/evcam/MainActivity.java` | 生命周期、UI 与摄像头衔接；文件较大，新增业务逻辑放独立控制器 |
| `AppConfig.java`、`AppFeatures.java` | 配置与功能开关；以实际开关判定启用能力 |
| `camera/MultiCameraManager.java` | 多摄像头需求和录像切换，避免日常/远程交接竞态 |
| `camera/SingleCamera.java`、`camera/PreviewDemand.java` | 单相机生命周期、不同输出和临时拍照需求；停主预览不能一并关 HUD/录制 |
| `camera/VideoRecorder.java` | MediaRecorder 路径 |
| `camera/CodecVideoRecorder.java`、`camera/EglSurfaceEncoder.java` | GL/MediaCodec 路径，输入帧、编码输出与 EOS 收尾 |
| `camera/FrameTimestampClock.java`、`camera/MuxerTimestampClock.java` | 源帧时序、分段时间轴和回退修复 |
| `vehicle/`、`camera/VehicleStatusWatermark.java` | 默认关闭的录像状态水印；多路共用 VHAL 信号租约，按需创建小纹理，未知/过期不渲染为关闭；E5 踏板字段尚待验证 |
| `feishu/`、`remote/` | 飞书长连接、统一命令调度、媒体上传；勿将长期等待放 UI 线程 |
| `BlindSpotService.java`、`VhalSignalObserver.java` | 车身信号与转向补盲，实车依赖 |
| `playback/` | 文件列表、播放器与显示/隐藏时释放逻辑 |
| `ui/AppShellController.java` | 固定主导航、页头、二级返回和功能切换 |
| `ui/UiThemeController.java` | 主题偏好、忙时延迟应用 |
| `ui/PreviewLayoutModel.java` | 四窗口归一化几何、预设和输入校验 |
| `ui/EditablePreviewLayout.java` | 同一组 TextureView 移动/缩放、编辑绘制、坐标/层级持久化 |
| `ui/PreviewEditorController.java` | 编辑工具按需创建，保存/取消/预设控制 |
| `app/src/main/res/` | 原生布局、深浅色资源和文字；按现有资源体系扩展 |
| `app/src/test/` | JVM 回归测试；当前发布基线 49 项 |

类名在表格中未展开前缀时均位于 `app/src/main/java/com/kooo/evcam/`。定位 HUD 代码可搜索 `SECONDARY_DISPLAY`、`NETWORK_STREAMING`、`EVCC`、`ESP32`、`Presentation`，不要只看名称就裁掉整条链路。

## 资源所有权

主页面预览、日常录像、远程录像/拍照、转向补盲、HUD 是不同需求。判断摄像头是否可关必须合并所有有效需求；主 Activity 不可见只说明主预览应停，并不说明所有摄像头都可关。飞书临时前摄任务完成后释放临时需求，并恢复原日常配置。

Codec 管线不等于软件编码；MediaCodec 最终选用何种编码器必须查看运行日志/能力，不能按类名推断。帧率设置、实际源帧、编码输出、写盘时序和播放端负载都可能影响观感。

## 已确定的交互决定

- 默认预览矩形：左 `(0,0,.3,1)`，右 `(.7,0,.3,1)`，前 `(.3,0,.4,.5)`，后 `(.3,.5,.4,.5)`。
- 模型坐标为比例，最小尺寸 0.18，校验非有限数和越界；保存 16 个浮点数及四个叠放顺序整数。
- 编辑复用四个窗口，编辑条退出即移除；不以截图、第二组预览或额外摄像头实现布局编辑。
- 原生主题默认深色。录像中、停止交接中或编辑中延迟应用，空闲时恢复页面后重建；不要为换色中断录像。
- 不打包大型字体，不恢复全局强制 1.2 倍字体放大，不恢复持续录制按钮闪烁任务。

## 后续优化的证据要求

先记录车型、录制路径、编码器、分辨率/帧率/码率、摄像头数量、主预览、HUD 和存储位置，再比较 PSS/CPU/温度/帧间隔。历史单段文件可说明存在低帧率/时间戳异常，不能单独锁定处理器、U 盘或播放器。
