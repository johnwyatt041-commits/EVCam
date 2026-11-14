# 项目进度

更新：2026-11-14，Asia/Hong_Kong。后续每个任务结束时更新；读取时同时执行 `git status --short`、`git log -5 --oneline`，避免文档落后于代码。

新 AI 协作交接见 [AI_HANDOFF.md](AI_HANDOFF.md)。当前等待用户实车反馈，没有新的实车成功结论。Smartcam 原 APK 已复制到本机 `work/reference-apks/` 并核对哈希；26个Java反编译摘录/29个指令或索引文件在 `work/smartcam-analysis/`，不是完整原作者源码，不纳入 Git 或云端自动同步。

## E5 等待录制修正（2026-11-14）

- 用户实车反馈 `11140958` A/B 均停在等待，没有额外提示。**这两个包不能视为实车可用基线**。当前分支 `codex/e5-recording-startup-fix`，候选修正版 `1.6.6-test-11141103`。
- 原作者当前 master 与原始 `0876b97` 相同；实际自动模式为 Codec，不能根据历史 README 误认 E5 当前用 MediaRecorder。重新检查 Smartcam 初始化；没有复制代码。
- 用户新片关闭预览：HEVC 1280×800，1477 帧/60.1195秒=24.5677fps，p95间隔40.0111ms，3次>100ms，无PTS回退。原片/截图仅本地保存，不随源码或 Release 上传。
- E5 回到 HAL 默认 FPS、旧版编码 profile 优先，不加新 operating-rate/priority/VBR/max-B 参数；修正会话失败删除录像/HUD/补盲输出而继续预览的假成功、启动回调登记顺序，以及没有终止等待的启动错误反馈。
- 20 秒首次写入看门狗，超时停止并提示保存日志，包含输入/GL提交/编码/写入计数；不会用补帧或“请求成功”假定录制成功。
- B 默认原生画布 1280×800 的 2×2 三格，每格约640×400，右下留黑；不再首先尝试超宽3840×800，清晰度代价明确说明，飞书四路不变。大屏侧栏保留。
- 新增模拟器实际 Camera2→管理器→公共 DCIM→首次写入/停止完成回归，补足此前只测试合成输入和私有目录的缺口；B 三色合成/多次分段/PTS/尾部/GPU 水印通过。没有现场失败日志，**不能确认唯一实车根因，也不能宣称已实车修复**。
- 详细证据、限制与试录步骤见 [E5 启动修正](E5_STARTUP_FIX_20261114.md)，任务 `docs/tasks/e5-recording-startup.md`。
- 最终 `11141103` 每版 78 项 JVM 测试（总计 156）及 Debug/Release/必要 Lint 通过，日志 `work/verification/build-20261114-110338-526.log`。A/B 实际 GL 分段、Camera2 公共目录首写和水印 GPU 测试各 3 项通过，证据归档 `work/e5-startup/`。模拟器的软件 H.264/单路 Camera2 不代表 E5 多路硬件验证。
- 应用源码 `85870d254c9e4fec75dcae60ca01744946aad535`，提交后重建 Release，两 APK 内嵌 revision 一致、v2签名及原证书通过、原ARM64库不变；DEX含新增看门狗。日志 `work/verification/e5-startup-source-release.log`。
- A：`outputs/EVCam-A-E5-StartupFix-1.6.6-test-11141103.apk`，SHA-256 `d16bc4d06a7b4c696f5f577d4c2033eefc16cf3e3b3855405f6b46b121e55677`。
- B：`outputs/EVCam-B-E5-StartupFix-1.6.6-test-11141103.apk`，SHA-256 `e0d4015032332d7cb7741f8d0036c4fb5e515026714f93cdcacb57df38fd5fc1`。两包均25,042,814字节，说明/清单/校验/源码补丁同目录保留。
- 已推送任务分支并新建 [v1.6.6-test-11141103-e5-startup-fix](https://github.com/johnwyatt041-commits/EVCam/releases/tag/v1.6.6-test-11141103-e5-startup-fix) prerelease，标签指向上述 APK 源码提交，8个附件SHA与本地逐一一致，两个公开APK链接均HTTP200。核验 `work/e5-startup/github-release-verified.json`。没有上传用户原ZIP/视频/抽帧。
- 本次使用 `emulator-5556 emu kill` 真正停止专用模拟器并关闭5039 adb服务，已核对无任务模拟器进程/监听端口；未操作物理设备。后续文档提交不改变APK。

## 上一轮 GitHub 交付（历史）

- 用户明确要求“传到 github 不然我下载不了”，已授权将当前两个大屏 UI APK 和对应源码上传至此前确认的用户 fork `johnwyatt041-commits/EVCam`。此前自动审批拒绝属于历史状态，本次上传成功。
- 已推送 `codex/large-screen-navigation`；新建独立 prerelease [v1.6.6-test-11140958-e5-recording-ab-large-ui](https://github.com/johnwyatt041-commits/EVCam/releases/tag/v1.6.6-test-11140958-e5-recording-ab-large-ui)，未覆盖旧版本或上游仓库。
- 发布标签与 APK 源码提交均为 `4268f03821bceb82c7489680831e435b48e3b504`。两个 APK、SHA、清单、说明、UI 补丁、深浅色预览及录像对照说明共 9 个附件均为 uploaded，GitHub 返回的 SHA-256 与本地逐一一致。
- A/B 两个公开下载链接经无认证 HEAD 跟随跳转检查均为 HTTP 200。核验结果 `work/large-navigation/github-release-verified.json`。APK 无修改、无需重建，文件哈希仍为下节记录。

## 大屏侧栏优化（2026-11-14）

- 用户屏幕 2560×1600，反馈侧边选项太小。分支 `codex/large-screen-navigation`，基于上述 A/B 交付后提交 `dba5808`，版本 `1.6.6-test-11140958`。
- 同步放大 A/B 的侧栏、整块按钮、文字、图标与间距；按屏幕最小宽度/可用高度使用资源分档，大屏文字 22–30sp、图标 36–52dp、按钮最小高 88–144dp。高度可随字体增长，小屏保留滚动。录像、HUD 与补盲实现未改。
- 146 项现有 JVM 测试（每版 73）及两版 Debug/Release/必要 Lint 通过，日志 `work/verification/build-20261114-095604-453.log`。
- 2560×1600 模拟器检查 160/240/320dpi，六项完整可见；240dpi 六项按钮边缘点击切换正确。深浅色与 1.3 倍系统字体通过，紧凑屏可滚动到设置，截图已人工查看。实际按钮分别 176×144、240×170、272×187px。证据 `work/large-navigation/`，任务说明 `docs/tasks/large-screen-navigation.md`。
- 仍需用户在物理 E5 上确认触控手感；屏幕分辨率不等于逻辑 dp，未读取车机真实 DPI。交付 APK/源码/哈希见 `outputs/EVCam-LargeNav-notes.md`。起初本地交付，随后按用户明确要求完成上述 GitHub 发布。
- 源码 `4268f03821bceb82c7489680831e435b48e3b504`，提交后重建 Release，两 APK 内嵌 revision 一致，签名与此前相同、原 ARM64 库保持不变。重建日志 `work/verification/large-nav-source-commit-release.log`。
- A：`outputs/EVCam-A-Independent-LargeUI-1.6.6-test-11140958.apk`，SHA-256 `3d53d97bfda6ff7ed773e54de8aa4e27d4a4d14f872c08c92e07f8f022ed23b2`。
- B：`outputs/EVCam-B-Composite-LargeUI-1.6.6-test-11140958.apk`，SHA-256 `30786952c78c3570f88eed6fe73cb1408da44024c392b3fd7113aaceb6b9dee6`。两包均 25,042,814 字节。
- 哈希/清单/本次 UI 源码补丁及深浅色预览已导出；专用模拟器和 adb 服务已停止。

## 本地录像整改 A/B（2026-11-14）

- 分支 `codex/recording-ab-comparison`，版本 `1.6.6-test-11140403`。A 保留 GL/MediaCodec 多路独立文件；B 日常三路横排合成、硬件 H.264 单文件，飞书四路仍独立。两版同包名同签名，顺序覆盖安装，桌面名称区分。
- 共用整改：独立 drain/有界异步 writer、pending 成功封装后发布、关键帧仅切 muxer、停止尾部/EOS 和恢复竞态、FPS 支持范围选择与 HAL 回退、按实际尺寸/FPS configure 和 MIME 码率、低频源帧/GL/编码/写盘统计。
- B 全尺寸优先，硬件无法配置时尝试 0.75/0.5 尺寸；增加合成 GL 和中间缓冲，不能承诺更省内存或更快。已接入合成图库/回放及双击保护。未复制 Smartcam 源码或素材。
- 每 flavor **73 项 JVM 测试通过**，合计 146，零失败/跳过；两版 Debug/Release 与必要 Lint 通过，日志 `work/verification/build-20261114-040318-731.log`。
- Android 11 x86 模拟器实际三色 Surface→GL→codec→MP4，覆盖 A 三编码器与 B 合成单编码器、多次关键帧分段、首帧关键帧、PTS 从零严格递增、停止完成无 pending；每版另做车辆状态水印实际 GPU 绘制。UI 检查 A/B 设置说明、状态水印默认关闭及 B 合成文件回放/双击保持单视频。证据 `work/recording-ab/`。
- Release 保留原 ARM64 库。模拟器仅使用去掉不兼容库的 Debug 副本和低分辨率软件 H.264；**没有实车帧率/CPU/PSS/温度数据，不能称卡顿已经消失**。真实 Camera2 多路、长时间存储、飞书端到端、补盲/HUD、VHAL 踏板字段仍需实车验证。
- 测试步骤与架构限制见 [A/B 试录说明](RECORDING_AB_TEST.md)，交付清单与哈希见 `outputs/EVCam-Recording-AB-notes.md`。旧 GitHub 发布不变；上次外部推送自动审批拒绝未获新授权，本次仅交付本地 APK。
- 应用源码提交 `f8a8e6ee60279925f575931da67497c737ce6a79`，提交后重建日志 `work/verification/recording-ab-source-commit-release.log`，两 APK 内嵌 revision 与此一致。后续交付文档提交不改变 APK 代码。
- A：`outputs/EVCam-A-Independent-1.6.6-test-11140403.apk`，25,032,954 字节，SHA-256 `9914ae269b957cf9659b72f9033f417c6cedee3dfb27f982e607623c7d25550e`。
- B：`outputs/EVCam-B-Composite-1.6.6-test-11140403.apk`，25,032,954 字节，SHA-256 `cecf479f981564e3bc69dc08b9fd99ef8aa919a18d9633a174157fbda1a69a21`。
- 两 APK v2 签名通过，原 ARM64 库字节一致，无已删除宣传资产；哈希、清单和从 UI 基线起的源码补丁已导出。专用模拟器及 5039 adb 服务已停止，没有操作物理设备。

## 历史 UI 基线

| 项目 | 状态 |
| --- | --- |
| 主目录 | `E:\GPT\EVCam` |
| 历史 UI 分支 | `e5-ui-layout-test-11140102` |
| 已发布应用提交 | `c9c493ef2eac5d0956677c3b3835f0a67cb31ccb` |
| 性能/HUD 前一基线 | `273c411f2eba37bd2ec06b21b7b677d0e8b58baf` |
| 上游基线 | `0876b976bff554ef6bad2c65513cf0149f451d81` |
| 应用版本 | `1.6.6-test-11140102`，versionCode 77 |
| 测试发布 | [v1.6.6-test-11140102-e5-ui](https://github.com/johnwyatt041-commits/EVCam/releases/tag/v1.6.6-test-11140102-e5-ui) |
| APK | `outputs/EVCam-E5-UI-1.6.6-test-11140102.apk`，24,983,482 字节 |
| SHA-256 | `e6fcd0204b28ec5a7d2c1f76516c86627670bee971803cef89a7bacbb6fd54c2` |

发布提交之后的搬迁/文档提交不是新的 APK 功能版本。完整当前 HEAD 以 Git 为准。

## 已完成

### 录像与资源

- 按需求打开摄像头；日常三路、飞书四路的打开/停止/恢复交接使用完成回调，减少固定延时造成的竞态。
- 主预览默认关闭、后台停止主预览；摄像头的录像、HUD、补盲和临时拍照需求分别保留，闲置资源才释放。
- 时间戳按源帧时间处理，分段保留真实间隔并修复重复/回退；API 允许时关闭 B 帧。
- Codec 管线改进非阻塞 drain、尾帧/EOS 收尾、线程优先级和必要统计，避免无任务时持续轮询。
- 飞书拍照临时需求、上传/文件任务按需启动、缓存上限、回放隐藏播放器释放等优化。
- 无关功能运行时精简；恢复并保留副屏/EVCC 和 Wi-Fi/ESP32 两条 HUD 路径。

### UI

- 录制/视频/照片/补盲/飞书/设置固定导航；统一页头、二级返回、跨功能返回栈与退出行为。
- 深色/浅色原生主题，系统字体，统一控件；录像/交接/编辑期间延后界面重建。
- 移除个人二维码、联系方式、打赏资源与相关加载。
- 四路预览默认“两侧左右、中间上下前后”；轻量编辑、预设、边界/最小尺寸限制、保存/取消/恢复。

### 交付

- 源码分支与提交已推送用户 fork；测试版已发布，7 个交付附件的 GitHub 上传结果已验证。
- `outputs/` 保留历次 APK、补丁、说明、SHA 与 UI 截图。当前测试版是 11140102；旧 11130151 曾误关 HUD 路径，已被后续版本替代，不应回退到它。
- 已迁到 `E:\GPT\EVCam`，逐文件校验 5,175 个文件；搬迁后再次通过 49 项 JVM 测试、Debug/Release 与必要 Lint。交接文档见 `docs/MIGRATION.md`，迁移不改变上述发布 APK，未推送新发布。

## 验证证据及局限

- 49 项 JVM 单元测试通过，无失败/跳过；Debug、Release 构建与发布必要 Lint 通过。
- Release APK v2 签名校验通过；使用仓库公开 AOSP 测试证书。
- Release 保留 `lib/arm64-v8a/libvhal_decoder.so`，没有已移除的 douyin 宣传图片。
- Android 11 x86 模拟器验证：主导航、二级返回、清理旧返回栈、主题切换和页面恢复、布局移动/缩放限制、取消、预设持久化和编辑导航保护。
- 模拟器仅使用去掉不兼容 ARM64 库的 Debug UI 副本；发布 APK 没删车机库。不能据此确认摄像头、VHAL、HUD 或编码性能。
- 专用 UI 模拟器和专用 adb 服务测试后已停止；没有操作当时发现的其他物理设备。
- 先前短试“似乎更流畅”未获确认；2026-11-14 用户补充反馈上一版仍卡顿，并提供右摄原片，离线分析确认低且不均匀的帧更新。没有同条件实车内存/CPU/帧率前后数据。

## 历史视频分析

- 原文件：`C:\Users\luka\Downloads\20261113-010651.mp4`，用户通过飞书下载的旧版本录像，设置为“标准”；本次不移动该下载文件。
- 1280×800，HEVC Main，359 帧/30.341 秒，平均约 11.832 fps，约 0.63 Mbps。
- 正向帧间隔中位约 82.18 ms，p95 约 179.49 ms，最大约 224.82 ms；115 个间隔超过 100 ms。
- 最后 PTS 从约 30.340955 秒回退至 14.283511 秒，MP4 样本表有对应异常。
- 证据在 `work/video-analysis/`，分析脚本 `work/analyze_video.py`。不能仅凭这段旧文件确定 CPU 不足、历史录制管线或飞书是否字节原样上传。

## 2026-11-14 新样本与 Smartcam 审查

- 用户确认条件：最新 UI 版的上一个版本（未给精确版本号）、日常三路、主预览开启、内置存储。
- `20261114_010647_right.mp4`：1280×800 HEVC，569 帧/37.320 秒，平均 15.2465 fps；85 次相邻帧间隔超过 100 ms，最长 280 ms；无时间戳回退。后段帧率较低，不能单独归因为过热或 CPU 不足。
- `273c411..c9c493e` 没有修改逐帧处理、编码或时间戳逻辑；新 UI 版不能视为新的录像卡顿修复。实际自动录制默认使用 Codec，旧“E5 默认 MediaRecorder”说明已过时。
- Smartcam 3.0.40 APK 的主路径采用多路 GL 合成后单编码器 H.264 录像，和本项目三路独立文件不能等负载比较；可借鉴独立 drain/writer、有界队列、源 FPS 配置、编码能力检查和耗时统计。
- 本次只分析与补文档，未修改应用或发布 APK。完整结论、文件哈希、证据路径和后续建议见 [录像审查](RECORDING_REVIEW_20261114.md)。
- 后续追问的状态水印已补查：Smartcam 复用小 Bitmap/GL 纹理，跨秒或状态变化才更新内容，在原编码提交前叠加；有信号解析、纹理更新与逐帧绘制成本，尚无 E5 开关对照数据，不能保证零影响。

## 下一步优先级

1. 用户方便时实车检查：日常三路录制、飞书四路与结束恢复、前摄按需打开、主预览后台释放、转向补盲和真实 HUD。
2. 确认 HUD 实际连接方式，再决定是否能进一步缩减另一条路径；确认前不能删除。
3. 固定条件对比内存/PSS、CPU、温度、录制帧时间和原始 MP4；内置存储/U 盘、主预览开关、前后台分别记录，避免把不同条件混比。
4. 在同一车辆配置检查新 UI 的字号/触控尺寸、主题与录制/编辑冲突、预览比例和实际摄像头方向。
5. 根据证据决定下一轮瓶颈优化；如删依赖或大范围重构，先验证不影响飞书、保活和 HUD。

用户补充样本已确认录像仍存在帧间隔不均；具体瓶颈尚待输入/GL/编码/写盘统计与同条件对照，不能把“尚未实测”记为已完成。

## 可选车辆状态水印任务

- 新增任务分支 `codex/vehicle-status-watermark`，本地测试版 `1.6.6-test-11140245`，versionCode 77。
- 已接入默认关闭的刹车踏板/车速/左右转向状态水印。开启时复用小区域 GL 纹理，多路共享信号源；关闭/停止录像释放专用资源，不停止补盲拥有的连接。
- 64 项 JVM 测试、Debug/Release、Release 必要 Lint 通过；隔离 Android 11 x86 模拟器通过 1 项实际 GPU 绘制测试及设置默认关闭/保存/重启/关闭检查。
- Release APK v2 签名通过，原 ARM64 native 库保持字节一致。输出 `outputs/EVCam-E5-Status-1.6.6-test-11140245.apk`，25,000,114 字节，SHA-256 `fa8e6249b777653c95e878f3d3b7fd1f87ad33a4f5b2539727e23cfb8508d0b0`。已在提交后重建，APK 内嵌 revision 与源码 `03a261e04474e2bce3eda834e87665e6d6da6ddc` 一致。
- 刹车仅尝试标准踏板字段，在 E5 是否有数据未实测；缺失、无效或断线显示未知。未测实车三路/四路/HUD、同步延迟和开关前后 CPU/PSS/fps。没有宣称修复旧卡顿。
- 架构、协议依据、验证与使用说明见 [车辆状态水印](VEHICLE_STATUS_WATERMARK.md)，交付说明在 `outputs/EVCam-E5-Status-notes.md`。
- **本地完成，尚未上传或发布**。自动审批拒绝向用户 fork `johnwyatt041-commits/EVCam` 推送分支，理由是本次缺少对该外部目标的明确授权；没有换工具绕过。若用户明确确认该目标，再推送此分支并发布新的 prerelease，不覆盖旧发布。
