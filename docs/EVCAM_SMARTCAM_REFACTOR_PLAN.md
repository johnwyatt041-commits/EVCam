# EVCam 融合 Smartcam V3.0.56 重构蓝图与多轮接力总规

- 创建/更新时间：`2026-11-16 02:21:03 +08:00`（Asia/Hong_Kong）
- 代码基线：`codex/e5-recording-startup-fix` / `1.6.6-test-11160215`（`build04` 已打包并推送 GitHub，包含 `T01`–`T04` `a6f4105`，**未实车测试**；下一会话准备启动 `T05` 或 `T06`）
- 对标分析对象：`Smartcam-V3.0.56_11_14_15_39.apk`（全功能清单见 [SMARTCAM_FEATURE_CATALOG.md](SMARTCAM_FEATURE_CATALOG.md)，3.0.56 差异见 [SMARTCAM_V3.0.56_DIFF.md](SMARTCAM_V3.0.56_DIFF.md)）
- 目标硬件：吉利银河 E5（`2560×1600`，LongYing-1 SoC）；不再引入通用“屏幕尺寸设置”。

---

## 一、核心原则：先探底层、再优化、再开发

1. **禁止照搬表面交互或“双进程/FBO/硬网格”短板**：Smartcam（下称 **SC**）功能全但存在后台与渲染负担；EVCam（下称 **EC**）必须在探明 SC 底层实现及优化点后，**重写/融合为轻量化原生实现**。
2. **明确不学与移除清单（防范围膨胀）**：
   - 不学 SC：`主页 / 投票`、`外设`、`屏幕尺寸设置`、`车外喊话`、`行程与充电`、`数据卡片`、`手机互联`（原位替换为 EC 飞书远程）、`Gitee 自动更新`、`领克 08` 及非 E5 校准/配置广场预设、SC 云端服务器接口（`smartcam.clqya.com` / `yuan.clqya.com:5000`）。
   - 移除 EC 遗留项：**1) 补盲警告弹窗**（`BlindSpotDisclaimerDialogFragment`、`AppConfig.KEY_BLIND_SPOT_DISCLAIMER_ACCEPTED`、`BlindSpotSettingsFragment.maybeShowDisclaimerDialog()` 及其弹窗布局/音频资源）；**2) 补盲动效**（`AppConfig.KEY_FLOATING_WINDOW_ANIMATION_ENABLED`、`BlindSpotSettingsFragment.floatingWindowAnimationSwitch`、`BlindSpotFloatingWindowView` 内 `ValueAnimator windowAnimator` / `playShowAnimation()`）。

---

## 二、两个前置关键问题解答（供拍板）

### 问题 1（`3.1 录制`）：SC「帧率与丢帧位置」是什么、有无必要、是否增加占用？

- **SC 底层实测机制**（见 `work/smartcam-analysis/3.0.56/smartcam-v3.0.56-diff/decompiled_core_recording.txt` `Ls51` / `Lr51` / `Lc33.h()` / `Lp13` / `Lf9.a`）：
  1. SC 用 `Ls51` 将帧率节流点拆成 5 个位置：
     - `SOURCE`（`"取流后 · 共享处理前"`，对应 `fps_source`，默认 `25`，夹在 `1..25`）
     - `RECORDING`（`"录像编码前"`，对应 `"recording_fps"`）
     - `PREVIEW`（`"预览显示前"`，对应 `"fps_preview"`）
     - `OVERLAY`（`"补盲处理前"`，对应 `"fps_overlay"`）
     - `LIVE`（`"远程输出前"`，对应 `"fps_live"`）
  2. 在 `Lp13` 管线中：**进入 `glBindFramebuffer` 之前先执行 `SOURCE` 跳帧**（若降低 `fps_source` 且未命中步进直接 `break`，后续预览/录像/补盲/远程全部同步降帧）；通过 `SOURCE` 后，再分别用计数器独立跳过 `PREVIEW`（`t`）、`OVERLAY`（`u`）和 `LIVE`（`w`）的分发。
  3. **已核实细节**：主分发路径中录像消费端 `v11_3.v.h(...)` 接收了全部通过 `SOURCE` 的帧，实际录像时间轴节流由消费侧 `Lf9.a` 的 `v0_38.K + M*L + 20000000ns`（20ms 节拍网格）完成。
- **是否会提高占用？**
  - **不会提高占用，本质是降载开关**：命中丢帧即跳过绘制/分发，CPU/GPU 负载低于或等于全帧率运行。
  - **但存在“治标不治本”问题**：若某路预览已隐藏、补盲未开启或远程未开启，**最省资源的做法是彻底关闭消费端乃至关闭闲置 Camera2 会话**，而不是继续取流后再在软件层计数丢弃；同时 SC 的 `Lf9` 20ms 网格会破坏相机真实时间戳平滑度，导致分格帧率抖动。
- **用户拍板结论（2026-11-15 20:10:42 +08:00 已定稿）：采用「方案 A」**：
  - **已选定方案 A（精简直觉版）**：不搬 SC 五位点复杂菜单；底层通过“未显示即停绘 / 未用即停流”零开销省电，录制侧在 `RecordingFpsPolicy` 上只保留“录像目标帧率（15/25/30fps 等）+ 原生时间轴”以及必要时的“预览限帧档位”，彻底抛弃 `Lf9` 20ms 网格。（不选方案 B/C）

### 问题 2（架构 1 & 3 + `11. 手机互联位置`）：飞书远程平时占用资源吗？算云端长连接吗？

- **是否属于云端长连接？属于。**
  - 已核实 `FeishuBotManager` 实现：使用 `OkHttp` 建立直连飞书云端的 **WebSocket 长连接**（`readTimeout=0`、`connectTimeout=30s`），按自定义 `Pbbp2Frame` 协议收发指令，通过主 `Handler` 每 **`120000ms`（2 分钟）**发一次 `PING` 保活心跳，断线后自动重连（间隔 `5000ms`，最多 5 次）。
- **平时会占用多少资源？当前触发时机过宽：**
  - **当前现状**：在 `MainActivity`（只要 `feishuConfig.isConfigured() && feishuConfig.isAutoStart()`）及 `CameraForegroundService.onCreate()`（`appConfig.isAutoStartOnBoot()`）中会启动；同时 `MainActivity` 无条件调用 `KeepAliveManager.startKeepAliveWork(this)` 挂起后台保活任务。
  - **资源特征（区分静态事实与待测项）**：未触发拍照/录像指令时，它**不持续抓拍相机或跑视频编解码**，开销主要是 WebSocket 常驻内存、每 2 分钟网络/CPU 唤醒一次发 `PING`、以及随之带活进程/WorkManager 保活链（对 CPU/PSS/长待机唤醒的具体数值影响**尚缺实车前后实测对比**）。
- **用户拍板与定稿策略（2026-11-15 20:10:42 +08:00 已定稿：开关懒加载 + 仅 P 档运行）**：
  1. 增加明确的“飞书远程启用开关”与按需懒加载：未开启开关时不初始化 `RemoteServiceManager` / `FeishuBotManager`，纯后台本地录像时按单精简前台服务运行，不绑死远程模块。
  2. **新增核心门控规则：若启用飞书远程，仅在处于 P 档时加载并维持连接；关闭开关或切入其他档位（如 R/D/N 及信号未确认为 P 档时，具体去抖与冷启动容错在 `T10` 实读状态机时核定）立即彻底断连、停止 `PING` 心跳并清理自动重连定时器，避免行车期间任何长连接与定时唤醒开销。**

---

## 三、EC vs SC 关键架构优化决策（避坑清单）

1. **进程与后台管家重构（解决 SC 双进程反复互拉与重型 UI 常驻）**：
   - 将车辆信号监听收敛为轻量独立状态机，仅监听 **档位、车速、四车门、转向灯**（替代 SC `Ler3` 中的 48 个全量信号监听）。
   - 无预览、无弹窗时，不依赖广播/闹钟反复唤醒带完整 UI 的主进程；退出按钮与开门退出仅关闭 UI 与无关组件（参考 SC `ui_exited` / `Ll33` / `Lkh` 状态思想），保留单一精简 `CameraForegroundService` 负责必须的后台录像与四类收窄信号触发（如 R/D 换挡自动拉起）。
2. **GL 渲染与编码直通（解决 SC 6 槽 2560×1600 FBO + 20ms 网格）**：
   - 画面调色/镜像/裁剪提供**独立总开关**。关闭调色/畸变时，**绕开 SC 式多层大缓存 FBO 与全屏着色器**，在同一 GPU/EGL 上下文里把相机 OES 纹理直绘编码器 `InputSurface`（基于 EC `CodecVideoRecorder` + `EglSurfaceEncoder`）。
   - **废弃 `Lf9` 20ms 网格**：坚持使用相机硬件/采集真实时间戳推动编码；多格合成采用短暂齐帧等待或各路独立录制，绝不用旧帧填充凑数。
   - **相机取流按需关停**：普通多视角中隐藏某路、或预览全关且该路未开启录像时，必须彻底销毁输出 Target 并关闭该路 `SingleCamera` 会话。
3. **悬浮小窗与镜像解耦（解决抢焦点与左右颠倒错位）**：
   - 倒车/换挡/补盲悬浮预览窗使用不抢占导航任务栈的 WindowManager 参数，保证车机原车导航不丢前台焦点。
   - 补盲保留 EC `BlindSpotCorrection` 与双 HUD 链路（`EVCC` 虚拟副屏 + `ESP32`），但借鉴 SC `Lq01` 将着色器/纹理的 **`uMirrorHorizontal`（物理摄像头镜像）与 `uOutputMirrorHorizontal`（显示输出镜像）彻底解耦**，确保左右画面、边框、动态线完全对齐。
4. **360 全景与离线预设校验（解决外部依赖与版权/合规风险）**：
   - SC APK 内置的 `assets/surround/xingjian7/`（`bird.mesh`、`calibration.json`、`assist_line_*.db`、`*_wheels.mesh`）仅作为内部实现结构参考；正式集成入 EC 时需在 `T07` 确认是复用/适配自有 E5 标定还是编写原生 E5 网格与标定读取器，彻底移除 `领克08`、`smartcam.clqya.com` 和 `yuan.clqya.com:5000`。

---

## 四、分步任务包清单（`T01`–`T10`）

> 每次由您选定 1 个任务包授权启动；标注的优先级与难度供分步决策参考。

| 编号 | 任务包名称 | 覆盖需求范围 | SC 参照与底层优化点 | 涉及 EC 模块/文件（主要） | 难度 / 复杂度 | 建议优先级 | 前置依赖 | 状态 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| **T01** | **精简架构底座、退出机制与补盲遗留清理** | 全局退出与状态、移除补盲警告/动效、信号面收窄规范化、后台保活/懒加载接口预留 | SC `Ll33`/`Lkh` 退出态思想（去双进程）、`Ler3` 裁剪为 4 类信号、移除 EC 遗留干扰弹窗与动画 | `lifecycle/*`, `vehicle/*`, `FloatingWindowDisplayHelper`, `MainActivity`, `CameraForegroundService`, `BootReceiver`, `KeepAlive*`, `AppConfig`, `BlindSpot*`, `MainFloatingWindowView`, `RemoteServiceManager` | **中**（牵涉生命周期与全局状态收敛） | **P0**（首推起步） | 无 | `[~] build01 已存档 / 待实车`（`ea395a0`，Tag `v1.6.6-test-11152059-build01`，174 项 JVM+Debug/Release R8+0 新增 Lint；见 [t01任务](tasks/t01-lifecycle-and-signal-foundation.md)） |
| **T02** | **全局外壳 UI、SC 风格左栏与深浅色主题** | 学习 SC 菜单/UI、`设置`/`退出`放左下角、去主页/投票、默认进记录仪、跟随系统主题、`1.外观与交互`（深浅色/默认页/开门退出）、去`屏幕尺寸`、移除`4.外设`/`7.`/`8.`/`9.`入口 | SC `appearance`/`theme_mode`/`default_page`/`confirm_door_exit`，沿用 EC 原生资源避免双份页面和录制中重建 `Activity` | `UiThemeController`, `UiThemeRepainter`, `AppShellController`, `SettingsCatalog`, `AppearanceSettingsFragment`, `PlaybackHubFragment`, `SettingsFragment`, `Recorder/Update/DebugLogSettingsFragment`, `ui_app_shell.xml`, `MainActivity` | **中**（布局与多分类导航重构） | **P0**（次推） | 建议在 T01 后 | `[~] SC UI 已对齐(未封装新包) / 待实车`（基线 `build02` `72dc456`；在 `build04` 后补充 `9f443b6` SC 深浅色板、窄左栏、双栏 Master-Detail 设置与双列外观页，248 项 JVM+Debug/Release R8+0 新增 Lint；见 [t02任务](tasks/t02-shell-navigation-and-theme.md)） |
| **T03** | **多模式主预览框架与动态关流（模式 B+C）** | **模式 B**（3 列：首列前/后上下排+2/3 列左右长条，可调位置/比例，按路显隐）、**模式 C**（三路大屏：D 前/R 后 + 右侧左右 2 列，统一圆角分割）、**隐藏/未用彻底停绘停流**、左上角“返回/设置”及视图切换快捷键显隐开关 | EC `EditablePreviewLayout`/`CustomLayoutManager` 升级 + 真·停绘停流（修复 SC 后台空取流），统一圆角分割样式 | `EditablePreviewLayout`, `PreviewLayoutModel`, `PreviewEditorController`, `PreviewModeController`, `AppShellController`, `MultiCameraManager`, `SingleCamera`, `MainActivity` | **中高**（涉及动态切换 Surface 与安全开关 Camera2 会话） | **P1** | T01（部分依赖 T02 容器） | `[~] build03 已存档 / 待实车`（`4706b6f`，Tag `v1.6.6-test-11160002-build03`，214 项 JVM+Debug/Release R8+0 新增 Lint；见 [t03任务](tasks/t03-multi-mode-preview-and-dynamic-stream.md)） |
| **T04** | **悬浮小窗架构与补盲择优融合（含 HUD）** | `4.2` 预览三布局（全屏/保留侧边栏/独立悬浮窗）、`5.`/`6.` EC+SC 补盲择优、保留 HUD（`EVCC`+`ESP32`）、物理/输出镜像解耦、悬浮窗不抢导航栈、显示360暂停补盲接口 | SC `Lqw2` 三模式、`Lq01` 双镜像解耦 + EC 更强的 `BlindSpotCorrection` 与双 HUD，确保 WindowManager Flag 不抢栈 | `PreviewPresentation`, `OverlayWindowParamsFactory`, `StandalonePreviewWindow`, `MirrorTarget`, `CameraMirrorPolicy`, `BlindSpotCoordinator`, `BlindSpotSuppressionController`, `BlindSpotService`, `BlindSpotCorrection*`, `StreamGlEncoder` | **中高**（GL 双镜像与多 Window/HUD 状态一致） | **P1** | T01 | `[~] build04 已存档 / 待实车`（`a6f4105`，Tag `v1.6.6-test-11160215-build04`，246 项 JVM+Debug/Release R8+0 新增 Lint；见 [t04任务](tasks/t04-floating-architecture-and-blindspot-fusion.md)） |
| **T05** | **视频管线优化、调色/水印/提示音（`3.2`–`3.4`）** | `3.2 画面`（画质/亮度/对比度/饱和度/镜像/裁剪 + 总开关直通旁路 6-FBO 与 `Lf9`）、**已定方案 A**（录像目标帧率 + 可选预览限帧）、`3.3 显示`（浮窗+4模块水印/位置/卡片样式）、`3.4 提示音`（开始/结束自定义音效）、EC 4 路独立录制开关（不干扰预览） | 彻底绕开 SC 6 槽 FBO 与 `Lf9` 20ms 网格；改用 `EglSurfaceEncoder` 同上下文按需着色与相机真实时间戳；修复 `Lif4`/`Lfo2` | `CodecVideoRecorder`, `EglSurfaceEncoder`, `VideoRecorder`, `MultiCameraManager`, `RecordingFpsPolicy`, 录制设置子页, `AppConfig` | **高**（OpenGL/MediaCodec 管线与多路时间戳核心） | **P1** | T01 | `[ ] 待启动` |
| **T06** | **车辆状态规则引擎与信号预设匹配（`3.1`+`10.`）** | `3.1` 保摄像头前台恢复、分段时长、**已定方案 A** 帧率政策、4 条件规则引擎（档位/车速/四门/转向灯 + SC 默认 2 条 + **新增 R/D 换挡自动弹出记录仪并在 X 秒后退后台**）、向 `T10` 导出标准档位订阅状态、`10. 信号预设与匹配` | 参考 SC `Lo33`/`Lgk1.k()`/`Lu70`/`Lm33` 架构重建精简本地规则引擎；避免无意义的全局主进程长驻监听 | 新车辆规则引擎模块, `RecordingVehicleSource`, `VhalSignalObserver`, `CarSignalManagerObserver`, `CameraForegroundService`, `MainActivity` | **中高**（状态去抖、前后台切换与 R/D 超时退回逻辑） | **P1** | T01, T04 | `[ ] 待启动` |
| **T07** | **360 全景 + 分视角模式与辅助设置（模式 A+`4.1`–`4.3`）** | **模式 A**（完全对应 SC 附图：左 360、右上前/后/随档位+动态转向引导线、右下左轮/轮位切换/右轮）、去掉环境与前雷达提示字、右上角“前视/后视/随档位”等按键显隐开关、R 档延时/车速返桌/P 档返桌、E5 校准（无领克08）、离线 E5 车模、上下方视角（`Ljw3`） | 参考 SC `Ljw3`/`Lrg1` 布局与网格概念；**只在模式 A 激活时创建 360 GL 上下文**；移除全部外部云端依赖与非 E5 预设 | 新 `surround360/` GL 与渲染模块, `E5` 本地标定/车模资源, 主预览容器, `MainActivity`, 360 设置页 | **高**（全项目复杂度最高：3D 网格/拼接/标定/车模/动态线） | **P2**（工作量大，建议拆出独立里程碑） | T02, T03, T04 | `[ ] 待启动` |
| **T08** | **回看重构与局域网浏览器分享（主界面 `3.`）** | 完全学习 SC 回看 UI 交互（缩略图/四路同步或分视角回看体验）+ SC 局域网分享播放与下载（HTTP Range/多端浏览/自动超时关闭/网卡切换） | 参考 SC `Lgq1`/`LanShareService`；复用并升级 EC `PlaybackFragmentNew` / `E5GlideModule` / `FileTransferServer` | `PlaybackFragmentNew`, `E5GlideModule`, `FileTransferServer`, `playback/` 及 `transfer/` 包 | **中**（主要是列表交互、Seek/Range 传输与生命周期管理） | **P2** | T02 | `[ ] 待启动` |
| **T09** | **银河 E5 离线配置广场与系统权限面板（`12.`+`13.`）** | `12. 配置广场`（仅保留银河 E5 配置，离线化 JSON 导入导出与一键应用，剔除服务端依赖）、`13. 系统与权限`（权限集中诊断与跳转） | 对照 SC `Lgb0`/`Lrh4` 字段白名单做本地配置包规范化导入导出，增加校验防错配 | 新配置快照导出/应用器、E5 内置配置资产、权限检查面板（替换原设置零散项） | **低–中**（结构清晰、与底层相对解耦） | **P2** | T01, T02 | `[ ] 待启动` |
| **T10** | **飞书 P 档门控懒加载与私有 GitHub 手动更新（`11.`+`14.`）** | 飞书占 `11. 手机互联` 位置 + 总开关/懒加载 + **若启用仅在 P 档加载，关闭或切入其他档位彻底断连（停心跳与重连定时器）**；`14. 软件更新`（手动点击检测 **私有 GitHub 仓库** Release，支持填写并持久化 PAT/密钥，不自动后台轮询）；确认 `15. Debug 日志` 连贯性 | 保留 EC `FeishuBotManager` 四路拍照/录像能力但改造成“总开关 + P 档门控”启停；升级 EC `VersionUpdateManager` 接入 `Authorization` 请求私有 GitHub API 与加密/持久配置 | `FeishuConfig`, `FeishuBotManager`, `FeishuFragment`, `RemoteServiceManager`, `KeepAliveManager`, 档位状态订阅, `VersionUpdateManager`, 设置页 `11.`/`14.`/`15.` | **中**（增加 P 档状态订阅、防抖与断连清理定时器逻辑） | **P3**（收尾可控） | T02, T01/T06 档位信号 | `[ ] 待启动` |

---

## 五、多 AI / 跨对话 严格接力与防越权规则

为确保您在更换 AI 模型或新开对话时能无缝接力，所有参与本重构计划的 AI **必须无条件遵守以下 6 条纪律**：

1. **点菜授权制（严禁私自扩改）**：每次对话只执行 **用户明确点名授权的 1 个任务包**（例如用户说“做 T01”或“接着做 T03”）。不得擅自顺手重构未授权模块或合并多个无关任务包。
2. **先核源码再设计（先证后写）**：开始任一任务包前，**必须先读取涉及的 EC 现行 Java/XML 文件与 SC V3.0.56 对应反编译类**（参考 [SMARTCAM_V3.0.56_DIFF.md](SMARTCAM_V3.0.56_DIFF.md) 及 `work/smartcam-analysis/3.0.56/`），写出具体的“SC 缺陷 + 本包优化点”后，再改业务代码。
3. **禁止破坏当前可用主链路与规则红线**：
   - 遵循根目录 `AGENTS.md`：不在 `MainActivity` 堆业务逻辑；Shell 命令参数**纯英文**；保留 HUD（`EVCC` 副屏 + `Wi-Fi/ESP32`）和飞书基础代码目录；每次修改代码必须更新 `app/build.gradle.kts` 的 `versionName`（`1.6.6-test-MMddHHmm`）。
   - 离线通过（`tools/verify.ps1` / `./gradlew test` / `assembleDebug`）不冒充 E5 实车验证；未在实车测过帧率/内存/VHAL 的项必须标明 **`待实车确认`**。
4. **单包最小闭环验收**：每个任务包完成编码后，必须运行单元测试 + 编译 + Lint 检查（注明既有 19 项基线 Lint 不因禁用掩盖），并将未解耦完的边界记入本蓝图对应状态或 `PITFALLS.md`。
5. **四件套同步 + 递增记录点发布（收尾必做）**：每次完成或挂起任意任务包，必须同步更新：
   - 本蓝图 [EVCAM_SMARTCAM_REFACTOR_PLAN.md](EVCAM_SMARTCAM_REFACTOR_PLAN.md) 的对应任务状态（`[x]` / `[~]` + commit / Tag / 结果）
   - 专属任务文档 `docs/tasks/<task-id>.md`（<=80 行、<=6000 字符）
   - 总览 [STATUS.md](STATUS.md)（<=80 行、<=6000 字符、最近更新<=5 条）
   - 共享登记 `work/ACTIVE_TASKS.md`
   并运行 `python work/progress-rules/check_docs.py` 校验通过，否则禁止宣称完成。任务包离线验证通过后，按固定交付节奏**新增递增 `buildXX` 记录点、打包 A/B 双风味 Release APK（含 `mappings`/`manifest`/`SHA256`/`notes`）并打 annotated tag 推送 GitHub Pre-release**。
6. **接力交接尾段格式**：每次向用户交付当前任务包结果时，末尾必须列出：
   - 已变更状态摘要 + 验证范围（明确区分离线验证 vs 待实车项）
   - **接下来的推荐选项（1–2 个，附难度与建议原因）**，以及**可直接用于新会话启动下一任务包的完整提示词**。
