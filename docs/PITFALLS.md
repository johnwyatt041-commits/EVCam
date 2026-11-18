# 当前有效的坑与约束

最近更新：2026-11-19 02:27:29 +08:00。历史首次发现时刻未记录；表内时间是各次复核时间，不冒充发现时间。最多20条，失效项归档；详见 [进度规范](PROGRESS_RULES.md)。

| ID | 复核时间（+08:00） | 触发/后果 → 正确做法 | 证据 |
| --- | --- | --- | --- |
| P001 | 2026-11-14 18:34:03 | 旧README把E5标为MediaRecorder、Codec标为软件编码，容易误判。实际自动路径是Codec；硬/软取决于所选编码器，查代码与日志。 | [启动分析](archive/2026-11/E5_STARTUP_FIX_20261114.md) |
| P002 | 2026-11-14 18:34:03 | 录制会话失败后移除录像输出、仅预览成功，会掩盖无输入。保留录像/HUD/补盲输出，有限重试并明确失败；首写超时退出等待。 | [修正任务](archive/2026-11/tasks/e5-recording-startup.md) |
| P003 | 2026-11-14 18:34:03 | Synthetic GL→私有目录通过，不代表Camera2→公共DCIM→首写能启动。两条链路都测；模拟器不代表E5多路硬件。 | [验证边界](archive/2026-11/E5_STARTUP_FIX_20261114.md) |
| P004 | 2026-11-14 18:34:03 | FPS/编码实时参数、超宽尺寸未经E5实测存在兼容风险。当前E5用HAL默认FPS/保守编码；B用原生尺寸画布。唯一实车触发点仍未确认。 | [当前对照](archive/2026-11/RECORDING_AB_TEST.md) |
| P005 | 2026-11-14 18:34:03 | 关闭主预览/退后台不等于无相机需求。录像、补盲和两种HUD分别保留，勿再误删副屏或ESP32输出。 | [需求](REQUIREMENTS.md) |
| P006 | 2026-11-14 18:34:03 | `127.0.0.1:5557 emu kill`可能不杀模拟器。用`emulator-5556 emu kill`，再核对本任务PID/监听端口；不操作未知设备。 | [任务记录](archive/2026-11/tasks/e5-recording-startup.md) |
| P007 | 2026-11-14 18:34:03 | `work/outputs`不随Git或新工作树复制；Smartcam只有APK及反编译摘录，没有完整源码。按共享绝对路径读取，用户原片不上传。 | [交接索引](AI_HANDOFF.md) |
| P008 | 2026-11-14 18:34:03 | 历史改源码/发布脚本可能固定旧路径、版本和标签，重跑会覆盖源码或发错包。先审阅，不把它们当通用流水线。 | [开发规范](DEVELOPMENT.md) |
| P009 | 2026-11-14 18:34:03 | A/B同包名同签名，先停录像再顺序覆盖安装；不要两套并行抢相机。B每格缩小，比较帧率也要比较细节。 | [对照方法](archive/2026-11/RECORDING_AB_TEST.md) |
| P010 | 2026-11-14 18:34:03 | 旧片开/关预览场景不同，不能单凭fps认定CPU不足或证明改动有效。保留原片、参数和日志，同条件对比。 | [样本分析](archive/2026-11/E5_STARTUP_FIX_20261114.md) |
| P011 | 2026-11-14 18:50:48 | 旧CLAUDE/Cursor入口另存规则副本，会让不同AI读到过时配置。已有入口统一转引根AGENTS，不再私建平行规则。 | [统一规范](PROGRESS_RULES.md) |
| P012 | 2026-11-14 19:08:17 | work积累了可重建安装/签名副本及编辑中转件。结束按白名单清理，交付/原件/必要证据留用；回归源码不是垃圾。 | [清理记录](archive/2026-11/tasks/directory-hygiene.md) |
| P013 | 2026-11-15 00:13:32 | 开R8后仅构建通过不保证冷启动。当前WorkManager/Room组合会裁掉`WorkDatabase_Impl`反射无参构造器，须精确保留并测试冷启动；Debug AndroidX runner不能直接链接混淆后的Kotlin，Release用平台Instrumentation。 | [优化与失败证据](archive/2026-11/tasks/ab-test-preoptimization.md) |
| P014 | 2026-11-15 00:13:32 | “心跳推图”与无障碍保活心跳不同；HUD仍复用旧鱼眼配置键。删功能须核对实际调用，保留保活定时器及HUD访问器，不能按同名批量裁剪共享能力。 | [优化边界](archive/2026-11/tasks/ab-test-preoptimization.md) |
| P015 | 2026-11-15 02:41:26 | 用户确认本次EPERM由车机权限不足引起，已要求撤销存储改造。受限模拟器可复现，不等于必须改App文件协议；先核对目标车机授权，不能只凭EPERM判为应用缺陷。0220候选已撤回。 | [用户更正/撤回](archive/2026-11/tasks/recording-storage-eperm.md) |
| P016 | 2026-11-18 13:10:00 | 多分辨率布局（`sw600dp`/`port`）遗漏按钮或 `texture_single`、或 `GLSurfaceView` 构造期触发 `requestRender()`（`mGLThread` 为空）会在开预览切回放/`360 A` 时 NPE 闪退；`SingleCamera` 重复附着同 Surface 会引发重建。须全分辨率同步 ID、延后渲染并在 `OutputConfiguration` 判重。 | [包A任务](tasks/fix-pack-a-crashes-and-reverse-signal.md) |
| P017 | 2026-11-18 13:10:00 | E5 `libvhal_decoder.so` 与 Smartcam `libvhal_proto_parser.so` 的 `field 5` 均是 `sint32` ZigZag（不能改普通 `int32`），但 `GEAR` 不限于 `area==0` 且 `0x11400400`/`0x11400401` 不应共用状态相互覆盖；未开补盲而用 VHAL 规则/随档位视图也须持有 VHAL 租约。 | [包A任务](tasks/fix-pack-a-crashes-and-reverse-signal.md) |
| P018 | 2026-11-18 16:48:00 | `360 A` 退出时若立即在 `backgroundHandler` 清 surround Surface，此时 `TextureView.isAvailable()` 尚未完成绘制就绪会触发 `closeIfUnused()` 并在同线程 `join()` 自锁 2s；另外运行中直接 `wm size reset` 触发重启重建时 `PlaybackImageMemory.clearViews` 对已销毁 Activity 调 `Glide.with()` 会崩。前者已加锁跳过自等并延迟一帧解绑，后者切分辨率前应先停进程或另案处理。 | [包B任务](tasks/fix-pack-b-preview-and-mode-d.md) |
| P019 | 2026-11-19 01:35:22 | 在「设置 → 360 画面 → 4.1」从 `B/C` 切到 `A/D`（主预览开且未录像）时卡顿，并非 360 GL 懒加载（设置页 `surroundHostEligible=false` 尚未建 GL 上下文），而是 `PreviewModeController.setPreviewMode()` 仍立即下发可见掩码，触发 `SingleCamera.setMainPreviewEnabled(false)` → `closeIfUnused()` 在 UI 主线程同步执行 `closeCamera()`（关闭会话/设备并 `join(2000)`；2 摄模拟器 `front/back` 为空、仅映射 `left/right`，故 `B↔C` 不关相机而 `B/C→D/A` 同步关 1~2 路）。应延后后台页面相机拆建或异步化 `closeCamera()`。 | [包B任务](tasks/fix-pack-b-preview-and-mode-d.md) |
| P020 | 2026-11-19 02:27:29 | Android 11+ 未授 `MANAGE_EXTERNAL_STORAGE` 时直接 `listFiles()` 扫 `Download` 会返回空；若仅弹 `Toast` 且未授 `POST_NOTIFICATIONS`（如 WSA/Android 13 模拟器），会表现为导入按钮“点不了”。`SurroundSettingsFragment` 车模/校准导入已补 SAF `OpenDocument` 回退与失败弹窗提示；另预乘 Alpha 纹理在 GL 须用 `GL_ONE, GL_ONE_MINUS_SRC_ALPHA`。 | [包B任务](tasks/fix-pack-b-preview-and-mode-d.md) |
