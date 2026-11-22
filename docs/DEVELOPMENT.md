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

当前项目自 2026-11-22 起仅维护 A 版（`independent`），默认离线运行 `:app:testIndependentDebugUnitTest :app:assembleDebug :app:assembleRelease --no-daemon`，日志写入主项目的 `work/verification/`，不会覆盖已发布的 `outputs/` APK。离线缺依赖时，网络可用再运行 `-Online`。可用 `-SharedRoot 'E:\GPT\EVCam'` 指定共享工具位置；独立工作树默认使用此位置。可用 `-AndroidSdk 'C:\Users\luka\AppData\Local\Android\Sdk'` 指定 SDK。

脚本不安装 APK、不启动模拟器、不操作设备、不发布。构建成功不等于实车功能验证。

新临时输出放 `work/tmp/<task-id>/`，任务结束按 `PROGRESS_RULES.md` 清理；必要日志/样本留任务证据目录。模拟器重签名APK/idsig不作为长期交付保存，复测先重新生成；共享JDK/依赖/AVD与增量缓存按需保留。

| 产物 | 位置 |
| --- | --- |
| A Debug/Release APK | `app/build/outputs/apk/independent/{debug,release}/app-independent-{debug,release}.apk` |
| JVM XML 结果 | `app/build/test-results/testIndependentDebugUnitTest/TEST-*.xml` |
| 单元测试报告 | `app/build/reports/tests/testIndependentDebugUnitTest/index.html` |

当前仅打包和交付 **A 版（`independent`）** 三路独立文件；B 版（`composite`）已于 2026-11-22 停更下线，不再恢复或发布。历史对比方法与回看兼容背景见 [recording-ab.md](archive/2026-11/tasks/recording-ab.md)。

Release 使用仓库自带的**公开 AOSP 测试签名**，别当作私人生产密钥。`outputs/` 顶层仅保留最新基线（`build19`/`build20`），历史包与说明均归档在 `outputs/archive/`（见 `outputs/archive/INDEX.md`）。构建不应修改历史已发布归档产物。

文档限长与相对链接校验可通过 `python tools/check_docs.py` 执行。

## 版本和 Git

- 应用代码改动后按 `AGENTS.md` 更新 `versionName`：基础 `1.6.6` + `-test-MMddHHmm`，客户端 Asia/Hong_Kong 时间。当前 versionCode 77；是否递增按后续发布需要决定，不要凭空更改兼容策略。
- 本次搬迁/文档没有改变应用代码和已发布版本。
- `origin` 是上游 `https://github.com/suyunkai/EVCam.git`。用户 fork 为 `https://github.com/johnwyatt041-commits/EVCam.git`，历史直接向该 URL 推送。先执行 `git remote -v` 核实，不要默认 `origin` 是自己的仓库。
- 现有 Git 设置 `core.autocrlf=true`，部分旧文件索引含 CRLF。避免全文件换行漂移；检查 `git diff --stat` 与 `git -c core.whitespace=cr-at-eol diff --check`。
- 不要执行 `release.bat` 当作普通验证，它包含交互发布步骤。

## 发布约定

用户此前授权测试 APK 发布到其 fork 的 Releases。发布前确定本次任务范围，使用新测试标签、准确源码提交和 prerelease；不要覆盖已有 APK 或误发布上游/master。

保留版本、源码提交/分支、APK 字节数/SHA-256、测试结果和未实测范围。核验签名、ARM64 `libvhal_decoder.so` 存在、已删除宣传资产未混回；发布后核对 GitHub 附件与本地哈希。

历史打包/发布脚本（如 `github_release.py`、`publish_ui.py`）已移至 `work/archive/legacy-scripts-and-logs/`，**固定旧版本和旧标签**，只作参考，不得直接复用为新发布。不要输出认证信息或提交令牌。

## 历史辅助资料

- `work/README.md` 标记可用工具、3.0.56 反编译目录与归档分类。
- `work/analyze_video.py` 保留在 `work/` 根目录，依赖 `work/video-deps/`，可直接用于分析新实车 MP4 帧率与时间戳。
- `work/start_ui_emulator.ps1` 保留在 `work/` 根目录，用于启动 `work/avd/EVCamUI.avd`（端口 5556）；旧版单风味 `make_ui_emulator.ps1` 与旧版 `ui_smoke.py` 已归档至 `work/archive/legacy-scripts-and-logs/`。
- 既有专用模拟器使用 5556/5557，专用 adb server 5039；不要自动连接或操作其他物理设备。重新测试前检查脚本、设备列表与当前端口占用。
- `work/edit_*.py`、`wire_*.py`、`trim_features.py`、`finalize_*.py` 等一次性源码改写脚本已统一归档在 `work/archive/legacy-scripts-and-logs/`，**不要重新运行**。维护源码应直接修改并审阅差异。

## 多工作树

共享 JDK/SDK 与缓存，分开源码、构建目录和日志。先读 `docs/COLLABORATION.md`。不要同时改同一签出目录，不要让一个对话的构建与另一个对话的源码修改交叉发生。
