# 任务：实车录像写入 EPERM（recording-storage-eperm）

最后更新：2026-11-15 02:30:34 +08:00
最后核对：2026-11-15 02:30:34 +08:00
任务/执行者：recording-storage-eperm / Codex
分支/工作树：`codex/e5-recording-startup-fix` / `E:\GPT\EVCam`；串行，无并行写入。
状态：修正候选`1.6.6-test-11150220`离线验证与本地交付完成，等待E5复测；源码提交见Git与StorageFix manifest。

## 用户反馈与直接证据

- 用户确认`11150014` A/B均准备后超时停止；两版预览均卡顿、目测A略好，但无录制视频，不能定性性能。另称同车原版EVCam可录、Smartcam不可录，原版具体版本/配置未核对，Smartcam原因未定位。
- B原日志只读保留：`.codex-attachments/827e18a2-9d50-473a-9a19-6f043e1dfc8e/4c021530-878e-464c-a418-e6783439ead0/evcam_log_20261115_015720.txt`。SHA-256为`b2cda13a09f2817c8c4f2150455726b8ccbe413d2222ceaa9b2fe857fca1bed5`，未上传/修改。
- 日志明确：`/storage/emulated/11/DCIM/EVCam_Video/*_combined.mp4.pending`在MediaMuxer创建时`EPERM`；已有`OMX.hantro.video.encoder.avc`硬件H.264输出，`written=0`，重建后仍失败，再由首写看门狗停止。不能把该次失败写成没有相机/编码数据或CPU不足。
- Android 11撤销所有文件访问后已复现旧扩展名同类EPERM，隐藏且保留`.mp4`后缀可创建、完成后可同目录重命名。原车授权状态未由日志证明；A共用写入器但暂无A日志，不断言A/Smartcam必为同因。

## 已实现

1. `RecordingFiles`统一暂存命名为`.<final-name>.pending.mp4`：保留媒体扩展名，隐藏未完成文件，Muxer成功结束后同目录重命名为原正式名；不额外复制视频。已有旧暂存文件不迁移/删除。
2. 回放、飞书查找与自动清理排除新旧暂存协议；缓存过期清理亦保护未完成录像，标准扫码浏览本来就排除隐藏文件。保留有界异步写入、首写确认及正确完成时机。
3. 写入错误使用独立`onOutputError`，管理器立即停止本次任务并提示检查存储；请求ID隔离迟到的启动/首写/失败回调，避免停止后旧任务再启动。相机/预览、HUD、合成双GL不改。
4. 版本`1.6.6-test-11150220`，同包名/同AOSP签名/versionCode77；原ARM64 VHAL库一致，Release DEX已核对包含新暂存协议与写入失败路径。

## 验证与证据

证据目录：`work/recording-storage-eperm/`。用户日志摘要/行号/哈希：`device-log-summary.json`；旧命名拒绝证据：`initial-scoped-probe{,-logcat}.txt`。

| 检查 | 结果 |
| --- | --- |
| JVM、Debug、Release R8 | A/B各84项JVM，失败/跳过0；构建通过，见`validation.log` |
| 受限Android 11 | 显式`MANAGE_EXTERNAL_STORAGE=deny`，每版6项通过：Camera2→公共DCIM首写、独立/合成分段/MP4解码及发布、车辆水印、时间水印重建、扫码HTTP、旧后缀拒绝/新隐藏MP4创建及重命名 |
| Release | 每版1项平台冒烟通过：冷启动、六页、Glide、JNI名称、共用开始/停止命令；不是E5四路飞书实测 |
| 暂存隔离/写失败 | JVM覆盖正式可见、两种暂存不可清/不可回放、拒绝写入只报错一次且不发布；Android验证真实隐藏属性与MP4完成后可解码 |
| Lint | 精确既有基线下新增错误0；原19项错误、每版1674项警告仍保留，见`lint-summary.json`/`lint-final.txt` |
| 检查过程限制 | 先前模拟器放行所有文件访问漏测受限DCIM。本轮首次JVM隐藏属性断言受Windows语义影响，改验证前导点，Android仍验`isHidden`；初次日志在`initial-unit-test-platform-assertion.log`。B Release安装曾ADB短暂断连，重连后同包通过，失败日志保留 |

## 本地交付

- A：`outputs/EVCam-E5-A-Independent-1.6.6-test-11150220-release.apk`，6,433,351字节；日常三路独立。
- B：`outputs/EVCam-E5-B-Composite-1.6.6-test-11150220-release.apk`，6,449,735字节；1280×800三格合成、单格约640×400、右下黑；飞书四路仍独立。
- 同目录`EVCam-E5-StorageFix-1.6.6-test-11150220-{manifest.json,SHA256.txt,mappings.zip,notes.md}`；未上传GitHub。先停录像后顺序覆盖，建议先A验证计时和可播放文件，再B同条件。

## 最近记录（最多5条）

- 2026-11-15 02:30:34 +08:00 | 交付/清理 | 两版84+6+1、R8与必要Lint通过；签名/原库/DEX核验通过。清26个本任务临时文件，6份交付哈希与用户原件不变；任务模拟器/5039/5556/5557已关闭，共享Toolbox ADB未操作。
- 2026-11-15 02:24:58 +08:00 | 验证 | 两版无所有文件访问下均完成公共DCIM首写/分段/有效MP4与合成回归；原版可录、Smartcam不可录的反馈已记录，不混同原因。
- 2026-11-15 02:18:35 +08:00 | 改动 | 修暂存命名、消费者隔离及文件错误及时传播，添加请求隔离；正在构建。原单位测试平台断言失配已纠正，非应用失败。
- 2026-11-15 02:13:34 +08:00 | 复现 | 无所有文件访问的Android 11重现`.mp4.pending`被EPERM拒绝，隐藏MP4后缀创建通过。
- 2026-11-15 02:08:30 +08:00 | 用户日志 | B写入失败而硬编已有输出；A/B预览卡顿仅目测。优先修公共写入，不据此判CPU或合成架构优劣。

## 未解决与下一步

E5实车仍需确认新包能否开始计时并保存可播放视频；A未有独立日志、Smartcam未定位，预览卡顿与实际录像性能不作结论。HEVC、双HUD/VHAL、飞书联网四路及长期可靠性未实车验证。原失败暂存恢复留后续；保留最小视频、复测/打包脚本与失败日志供复现，共享JDK/AVD/Gradle缓存保留；清理明细见`cleanup-manifest.json`。
