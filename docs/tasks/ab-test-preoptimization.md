# 任务：A/B 测试版实车前优化（ab-test-preoptimization）

最后更新：2026-11-15 00:26:46 +08:00（Asia/Hong_Kong）
最后核对：2026-11-15 00:26:46 +08:00
任务/执行者：ab-test-preoptimization / Codex
分支/工作树：`codex/e5-recording-startup-fix` / `E:\GPT\EVCam`（串行执行，无并行工作树）
状态：已实现、离线验证及本地交付完成；应用源码提交`5f5ce4626a8f7060cc96cc3758150c497eca6257`；等待 E5 实车测试。

## 当前结论

用户 2026-11-14 23:23 确认的 1–5 项已实施，版本 `1.6.6-test-11150014`。A/B 各79项JVM、Debug/Release R8构建、各5项模拟器录像/扫码回归、各1项R8冷启动冒烟通过。精确历史基线Lint无新增错误；完整扫描仍有19项既有错误，不称为全量Lint零问题。

## 已确认范围与实施结果

1. **物理移除停用功能、开启R8**：移除钉钉/Telegram/心跳推图及SDK、录像/预览矫正、图像调节、超视/长视、车门联动、自定义键、模拟信号与死菜单。`VideoThumbnailExtractor`迁至`feishu/`并验证真实MP4封面提取。
   - 保留扫码传文件、ZXing/NanoHTTPD、飞书、`io.grpc`/VHAL、`BlindSpotCorrection`、Glide、WorkManager及原无障碍保活心跳。HUD复用的旧鱼眼偏好访问器保留，不恢复录像矫正器。
   - Release开启R8；保留JNI、Glide生成入口、持久化Worker类及Room反射构造器。保留飞书后台持续开始/停止共用Intent。
2. **界面图片退场释放**：Glide内存缓存8MiB、BitmapPool 4MiB；回收列表项/退出回放清请求和图片，AppShell退场、应用退后台、`onTrimMemory`/低内存清缓存。模拟器录制中触发图片内存释放后仍完成分段。
3. **方案3-稳妥**：`EglSurfaceEncoder`复用Canvas/Paint，首次分配纹理后用`texSubImage2D`更新，多路共享秒级时间缓存并复用Date；跨秒、时钟回退/时区、释放重建已测。
4. **HUD原样保留**：stream包、MJPEG设置、`VhalNative`、`KeepAliveAccessibilityService`与HEAD一致；`SECONDARY_DISPLAY`/`NETWORK_STREAMING`能力及默认关闭偏好不变。`MultiCameraCompositor`与`CodecVideoRecorder`双GL管道无改动。
5. **版本/验证**：两版同包名、同AOSP测试签名、versionCode 77；ARM64 VHAL库逐字节一致。版本、源码、SHA与映射见交付manifest；应用提交以Git及manifest为准。

## 验证与证据

| 检查 | 结果/证据 |
| --- | --- |
| JVM、Debug、Release R8 | 每版79项，失败/跳过0；`work/ab-test-preoptimization/validation-r8-fixed.log` |
| 模拟器Debug | 每版5项：Camera2→公共目录首写、分段/合成及飞书封面、车辆水印、时间水印纹理重建、二维码解码/HTTP原字节下载；同目录`*-debug-instrumentation.txt` |
| 模拟器Release | 每版1项平台Instrumentation：冷启动、六页导航、Glide发现、JNI名称与共用开始/停止Intent；`*-release-instrumentation.txt`。只证明命令被接收，不证明飞书联网四路实车执行 |
| Lint | 首轮22项错误代码均存在原HEAD；删除3页后19项。临时逐位置基线，两版新增错误0、各1675项既有警告；`lint-baseline-source-verification.json`、`lint-existing-errors.xml` |
| R8失败与修复 | 初版冷启动因Room反射无参构造器被裁失败，补单构造器规则后A/B冷启动通过；失败日志`r8-room-constructor-failure-logcat.txt`。AndroidX runner与混淆Kotlin冲突，改平台runner；见P013 |
| 保留边界 | 保活心跳曾被同名清理误删，审查后还原且未交付；见P014。扫码与飞书封面已实测；HUD/VHAL/联网飞书四路、HEVC、帧率/CPU/PSS/长期可靠性仍待实车 |

## 交付与安装

- `outputs/EVCam-E5-A-Independent-1.6.6-test-11150014-release.apk`：6,433,347字节，日常左/右/后三路独立文件。
- `outputs/EVCam-E5-B-Composite-1.6.6-test-11150014-release.apk`：6,449,731字节，日常1280×800三格合成、每格约640×400、右下黑；飞书四路继续独立。
- 同目录 `EVCam-E5-Preoptimization-1.6.6-test-11150014-{manifest.json,SHA256.txt,mappings.zip,notes.md}`；最新APK尚未上传GitHub，旧11141103发布保留。
- 停止录像后顺序覆盖安装，不同时运行两套；先A内置存储关/开预览，再B同条件采日志/PSS。不能据APK体积下降推断运行内存或帧率收益。

## 最近记录（最多5条）

- 2026-11-15 00:26:46 +08:00 | 交付核验/清理 | 源码`5f5ce46`已入manifest，APK哈希不变；两版79+5+1通过，清46个临时文件，任务模拟器及5039/5556/5557端口已关闭。
- 2026-11-15 00:19:49 +08:00 | 修复验证 | Room精确构造器规则后两版R8启动/六页/共用命令通过；失败证据保留，见P013。
- 2026-11-15 00:05:50 +08:00 | 回退 | 误删的无障碍保活心跳已恢复HEAD，HUD共享访问器及双GL均保留；重新构建/回归，见P014。
- 2026-11-14 23:58:16 +08:00 | 发现 | 首轮完整Lint22项错误均核对为HEAD原有；删3张停用页面后19项，使用精确临时基线检查新增。
- 2026-11-14 23:24:27 +08:00 | 确认 | 用户确认保留扫码、方案3-稳妥、双路HUD暂不动；本轮按1–5项实施。

## 下一步

E5实测A/B同场景帧率/清晰度、PSS与日志；确认HUD实际接法后再决定后续裁剪。选B后才深合双GL；`.pending`恢复和状态机收敛留后续。本轮不把离线通过当成实车修复成功。保留构建日志、失败证据、临时Lint基线、复测脚本及最小录像样本在`work/ab-test-preoptimization/`；清理明细见`cleanup-manifest.json`。共享JDK/AVD/Gradle缓存及历史交付保留。
