# 本机开发、验证与发布

## 路径与依赖

- 主仓库：`E:\GPT\EVCam`。
- 便携 JDK：`work/toolchain/jdk-21.0.13.0.2+1`；构建需要 JDK 17+。系统默认 Java 曾为旧版本，不可直接依赖默认 `java`。
- Android SDK：`C:\Users\luka\AppData\Local\Android\Sdk`，本次不搬 SDK。
- Gradle 用户缓存和 Git Credential Manager 仍由系统管理，本次没有复制凭据库。
- compileSdk/targetSdk 36、minSdk 28，AGP 9.0、Gradle 9.1；Java 源码兼容 11。

## 标准验证

在当前任务的仓库/工作树根目录运行：

```powershell
.\tools\verify.ps1
```

当前录像 A/B 分支默认离线运行 `:app:testIndependentDebugUnitTest :app:testCompositeDebugUnitTest :app:assembleDebug :app:assembleRelease --no-daemon`，日志写入主项目的 `work/verification/`，不会覆盖已发布的 `outputs/` APK。离线缺依赖时，网络可用再运行 `-Online`。可用 `-SharedRoot 'E:\GPT\EVCam'` 指定共享工具位置；独立工作树默认使用此位置。可用 `-AndroidSdk 'C:\Users\luka\AppData\Local\Android\Sdk'` 指定 SDK。

脚本不安装 APK、不启动模拟器、不操作设备、不发布。构建成功不等于实车功能验证。

新临时输出放 `work/tmp/<task-id>/`，任务结束按 `PROGRESS_RULES.md` 清理；必要日志/样本留任务证据目录。模拟器重签名APK/idsig不作为长期交付保存，复测先重新生成；共享JDK/依赖/AVD与增量缓存按需保留。

| 产物 | 位置 |
| --- | --- |
| A Debug/Release APK | `app/build/outputs/apk/independent/{debug,release}/app-independent-{debug,release}.apk` |
| B Debug/Release APK | `app/build/outputs/apk/composite/{debug,release}/app-composite-{debug,release}.apk` |
| JVM XML 结果 | `app/build/test-results/test{Independent,Composite}DebugUnitTest/TEST-*.xml` |
| 单元测试报告 | `app/build/reports/tests/test{Independent,Composite}DebugUnitTest/index.html` |

两版同包名同签名，按顺序覆盖安装，共享偏好；不要用不同包名同时运行两套摄像头。A 是整改后的三路独立文件，B 日常三路合成 H.264 单文件；B 的飞书四路仍分别录制。`RecordingExperiment` 和 flavor 的 `BuildConfig.SMARTCAM_COMPOSITE` 决定方法，不能只改 APK 文件名冒充不同架构。详见 `docs/tasks/recording-ab.md`。

Release 使用仓库自带的**公开 AOSP 测试签名**，别当作私人生产密钥。构建/迁移不应修改 `outputs/EVCam-E5-UI-1.6.6-test-11140102.apk`；核验时与 `docs/STATUS.md` 的固定 SHA 对比。

## 版本和 Git

- 应用代码改动后按 `AGENTS.md` 更新 `versionName`：基础 `1.6.6` + `-test-MMddHHmm`，客户端 Asia/Hong_Kong 时间。当前 versionCode 77；是否递增按后续发布需要决定，不要凭空更改兼容策略。
- 本次搬迁/文档没有改变应用代码和已发布版本。
- `origin` 是上游 `https://github.com/suyunkai/EVCam.git`。用户 fork 为 `https://github.com/johnwyatt041-commits/EVCam.git`，历史直接向该 URL 推送。先执行 `git remote -v` 核实，不要默认 `origin` 是自己的仓库。
- 现有 Git 设置 `core.autocrlf=true`，部分旧文件索引含 CRLF。避免全文件换行漂移；检查 `git diff --stat` 与 `git -c core.whitespace=cr-at-eol diff --check`。
- 不要执行 `release.bat` 当作普通验证，它包含交互发布步骤。

## 发布约定

用户此前授权测试 APK 发布到其 fork 的 Releases。发布前确定本次任务范围，使用新测试标签、准确源码提交和 prerelease；不要覆盖已有 APK 或误发布上游/master。

保留版本、源码提交/分支、APK 字节数/SHA-256、测试结果和未实测范围。核验签名、ARM64 `libvhal_decoder.so` 存在、已删除宣传资产未混回；发布后核对 GitHub 附件与本地哈希。

历史 `work/github_release.py`、`work/publish_ui.py` 使用 Git Credential Manager 获取令牌并通过 Windows curl 的 stdin 配置发送；没有把令牌存入文件。它们**固定旧版本和旧标签**，只作参考，不能直接复用为新发布。不要输出认证信息或提交令牌。

## 历史辅助资料

- `work/README.md` 标记可用证据与旧脚本限制。
- `work/analyze_video.py` 和 `work/video-analysis/` 是旧片分析；依赖在 `work/video-deps/`，不是 Android 运行依赖。
- `work/start_ui_emulator.ps1`、`work/make_ui_emulator.ps1`、`work/ui_smoke.py` 是既有 UI 测试辅助。模拟器副本移除 ARM64 库只为 x86 UI 测试，绝不能当作发布 APK。
- 既有专用模拟器使用 5556/5557，专用 adb server 5039；不要自动连接或操作其他物理设备。重新测试前检查脚本、设备列表与当前端口占用。
- `work/edit_*.py`、`wire_*.py`、`trim_features.py`、`finalize_*.py` 等是过去的一次性源码改写脚本，**不要重新运行**。维护源码应直接修改并审阅差异。

## 多工作树

共享 JDK/SDK 与缓存，分开源码、构建目录和日志。先读 `docs/COLLABORATION.md`。不要同时改同一签出目录，不要让一个对话的构建与另一个对话的源码修改交叉发生。
