# 录像整改与 A/B APK

用户 2026-11-14 要求：整改录像问题，并分别生成 EVCam 原有方法和 Smartcam 方法的两个 APK 比较。串行任务，无子代理；工作分支 `codex/recording-ab-comparison`，基线 `d802152`。

按已告知方案实现 A=三路独立编码文件、B=多路 GL 合成单编码器。可选异步问题未收到补充；没有把无回答视为审批。B 飞书四路仍独立。

已实现并验证：`AsyncMuxerWriter`（每编码器 4 MiB/64 样本预算、池化、独立线程、`.pending` 成功封装后再发布）；独立 codec drain；GL 所属线程释放；关键帧切换 muxer 不重建 codec；FPS 支持范围选择/不兼容回退；`RecordingEncoderSelector` 按尺寸/帧率验证并实际 configure，优先硬件，按 MIME 分别计算码率。

完成范围：

1. 删除旧未调用的编码选择/重启分段代码，增加真实 GL→编码→muxer 分段/EOS 测试；停止请求阻止排队恢复任务复活录制。
2. B 日常左/后/右一个文件，图库、停止/恢复、合成回放双击保护；原尺寸优先，硬件配置失败后尝试缩小，日志明确实际尺寸。未复制 Smartcam 源码/素材。
3. independent/composite 两个 flavor，桌面和设置区分 A/B；同包名同签名顺序覆盖安装共享配置。
4. 保留默认关闭的车辆状态水印、飞书、补盲和两种 HUD。低频日志区分源帧/GL/编码/写盘，未宣称实车卡顿已消失。
5. 版本 `1.6.6-test-11140403`。交付源码/SHA/验证汇总见 `docs/STATUS.md`、`outputs/EVCam-Recording-AB-notes.md`，使用与局限见 `docs/RECORDING_AB_TEST.md`。

证据：`work/verification/` 构建与每 flavor 73 项 JVM 测试；`work/recording-ab/` 实际编码文件、日志与 UI 截图。模拟器发现合成首帧可能只有一路，已修复为等待全部首帧或 1.5 秒启动宽限，用三色独立输入验证。模拟器使用低分辨率软件 H.264，不能代表 E5 硬件性能。尚待实车 CPU/PSS/帧率、长时间录制、真实多路 Camera2、飞书端到端、VHAL、补盲和 HUD。

GitHub 上次推送被自动审批拒绝；没有收到用户对本次外部目标的明确确认前，不重试推送或换工具绕过。可以先完成本地两个 APK。
