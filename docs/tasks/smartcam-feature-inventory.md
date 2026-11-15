# 任务：Smartcam 全功能与实现梳理（smartcam-feature-inventory）

最后更新/核对：2026-11-14 23:24:27 +08:00（Asia/Hong_Kong）
执行者：Antigravity（只读逆向分析 + 文档入库，未修改 `app/` 与 `outputs/`）

## 结论与交付

- **目标样本**：`work/reference-apks/Smartcam-V3.0.40_11_04_02_02.apk`（`com.yuan.smartcam`，`versionName="3.0.40"`，`versionCode=2`，`minSdk=28`，`targetSdk=37`，单 `classes.dex` 含 5601 个类、4 个 `.so`）。
- **主交付文档**：[SMARTCAM_FEATURE_CATALOG.md](../SMARTCAM_FEATURE_CATALOG.md)，覆盖全 15 类功能实现机制、快速索引（§16），以及 **Smartcam 与当前 EVCam A/B 的内存结构及优劣对照（§17）**：核明 Smartcam 强在双进程可仅留 `:core`、R8 单 DEX（`6.28 MB`）、共享 GL+5 点限帧、硬件探测与异常恢复；但 `oy2` 预设创建 6 张 `2560×1600` RGBA FBO（约 `93.75 MiB`）、`uq` 写盘积压达 `32 MiB`、`Lk04` 缩略图缓存 `24 MiB`。EVCam 优势在于架构精简无图床/提权依赖、`AsyncMuxerWriter` `4 MiB`/编码器严格封顶、支持三路独立清晰留存与双模 HUD；劣势在于 `isMinifyEnabled = false`（DEX `21.58 MB`）、单进程混载与 `Glide`/MJPEG 缓冲缺积极回收。
- **边界声明**：全部结论来自静态包/源码核查；**无 E5 实车或同条件 `meminfo`（PSS/RSS）实测数据**，不输出总内存高低定论或百分比。
- **交接索引更新**：[AI_HANDOFF.md](../AI_HANDOFF.md) 已补充完整功能手册与 14 份证据文件位置；[STATUS.md](../STATUS.md) 已同步本任务进度。
- **分析方法边界**：全部结论来自静态反编译（`aapt2`、DEX 提取与 `jadx` 反编译）与字符串/资源交叉验证，未在实机/车机动态运行 Smartcam。

## 保留证据与临时清理

- **保留分析证据**：`work/smartcam-analysis/3.0.40/smartcam-feature-inventory/`（14 个文件：含 `badging.txt`、`manifest_xmltree.txt`、`apk_structure_summary.json`、`aapt2_resources.txt`、`aapt2_strings_only.txt`、`dex_class_strings.json`、`cjk_strings.txt`、`urls_and_keywords.txt`、`feature_discovery_dump.txt`、`cjk_by_class.txt`、`xingjian7_calibration_head.txt`、`native_so_symbols.txt`、`core_and_unobfuscated_decompiled.txt`、`verified_details_decompiled.txt`），不纳入 Git。
- **临时文件清理**：`work/tmp/smartcam-feature-inventory/` 下 5 个分析脚本（`step1_overview.py` 至 `step5_verify_details.py`）已清除。

## 验证记录

- `python work/progress-rules/check_docs.py`：通过（`limits`、`relative_links`、`mandatory_entry_links`、`archive_content_preserved`、`application_unchanged` 均为 `true`）。
- 篇幅自检：`docs/STATUS.md` 与 `docs/tasks/smartcam-feature-inventory.md` 均满足 `≤80` 行、`≤6000` 字符及最近更新 `≤5` 条限制。
- `git diff --name-only -- app`：空（确认未改动任何应用源码或 `versionName`）。

## 最近更新（最多5条）

- 2026-11-14 22:37:02 +08:00 | 核对 | 复核 EVCam 删减耦合与 HUD 生命周期：`FeishuVideoUploadService` 仍依赖 `dingtalk.VideoThumbnailExtractor`、`VhalSignalObserver` 依赖 `io.grpc`；副屏/EVCC 按 `currentSignalCamera` 打灯创建/熄灯销毁，MJPEG 在 `linkageMode=true` 时亦仅打灯绑定（默认仅开服务端可按需保留，`Client`/`Esp32Discovery` 为纯 ESP32 推流）。
- 2026-11-14 21:43:54 +08:00 | 对比 | 补全 Smartcam 与 EVCam A/B 的静态内存结构（DEX/FBO/队列/缓存/进程）及优劣对照（[§17](../SMARTCAM_FEATURE_CATALOG.md#L254)），无实车 PSS 数据不作百分比估算。
- 2026-11-14 20:30:21 +08:00 | 完成 | 梳理 Smartcam v3.0.40 全功能与实现方法并落盘 `docs/SMARTCAM_FEATURE_CATALOG.md`，同步交接索引与任务文档，清理临时脚本。
