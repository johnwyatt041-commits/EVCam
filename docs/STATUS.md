# 项目进度

最后更新/核对：2026-11-19 16:10:00 +08:00（Asia/Hong_Kong）
维护：Antigravity（已完成 `build19` / `1.6.6-test-11191602` 源码提交 `9d2dc5c`、本地封包与 GitHub Pre-release 发布，通过 `512` 测、双风味构建/Lint 与资产 SHA256 核验，待真机/实车实测）。执行规范：[PROGRESS_RULES.md](PROGRESS_RULES.md)。

## 当前快照

| 项目 | 当前状态 |
| --- | --- |
| 分支与提交 | `codex/e5-recording-startup-fix`；源码基线 `9d2dc5c`（`1.6.6-test-11191602`，`versionCode = 86` / `build19`，基于 `3258fe0` 合流） |
| 本地交付 | `outputs/` 顶层保留 **`build18`** + **`build19`**（12 文件，`A` `7,849,908` B / `B` `7,866,292` B）；`build17` 已归档至 `outputs/archive/` |
| GitHub | `johnwyatt041-commits/EVCam` 已发布 Pre-release [`v1.6.6-test-11191602-build19`](https://github.com/johnwyatt041-commits/EVCam/releases/tag/v1.6.6-test-11191602-build19)（`6` 资产 size/SHA256 核验一致） |
| 离线与设备验证 | 当前 `1.6.6-test-11191602` 已通过 `verify.ps1` `512` 测（`0` 失败/`0` 跳过）、`assembleDebug`/`assembleRelease`、双风味 Lint（`0` errors，`2110` warnings）及签名/资产 SHA256 核验；本版尚未重跑模拟器与真机 |
| 实车状态 | 银河 E5 实车四路硬件录制、`B/C→D` 切换、包 A–E 全集、`4.3` 实时调校及星舰7默认标定+`.mesh` 实景拼接均**尚未上车实测** |

## 当前结论与确认边界

- **手机端“一直显示录像准备中”诊断与 `build18` 修复已合流**：保持 `.mp4.pending` 协议（[P015](PITFALLS.md)）前提下完成权限核心级诊断、实写探测、手动「去授权」引导、致命写入快败及 UI/远程收口。
- **包 E / 包 D / 包 C 既有基线与 `4.3` + 星舰7默认标定已合流**：在 `3258fe0` 基础上合流 `4.3` 实时预览调校与 `星舰7标定_avm_calib_res.zip` 默认标定资产/网格及车底盲区边界去拉伸修复。
- **用户规则拍板（2026-11-18 已确认）**：① **鱼眼矫正保持关闭**（`AppFeatures.FISHEYE_CORRECTION = false`）；② **ESP32 与浏览器投屏保持保留不动**（`AppFeatures.NETWORK_STREAMING = true`）。
- **`W1`（白色主题 · 精密镜头）图标已提交至 `904e500`**：已打入 `build14`–`build18`，详情见 [图标任务](tasks/app-icon-redesign.md)。

## 下一步

1. 下载安装 **`build19`**（`1.6.6-test-11191602`，已合流 `build18` 权限启动修复、星舰7默认 360 标定与 `4.3` 实时预览）在手机端与银河 E5 实车联测独立版 A / 共存版 B（先后覆盖，切勿并行）。

## 最近更新（最多5条）

- 2026-11-19 16:10:00 +08:00 | 合流并发布 build19（星舰7默认标定 + 4.3 实时预览 + build18） | 源码 `9d2dc5c` 打标 `v1.6.6-test-11191602-build19` 推送并发布 GitHub Pre-release（6 资产 SHA256 核验一致）；`build17` 归档，通过 `512` 测、`0` Lint error 与 APK 签名/资产核验（待实车实测） | [星舰7默认标定](tasks/default-xingjian7-calibration.md)
- 2026-11-19 15:15:42 +08:00 | 设置星舰7默认 360 标定包与网格支持 | 将 `星舰7标定_avm_calib_res.zip`（`calibration.json` + `bird/front_wheels/rear_wheels.mesh`）设为默认内置标定，适配多项式鱼眼模型、`Y +143cm` 居中、GPU 视图缩放、裁剪与 `.zip` 导入 | [星舰7默认标定](tasks/default-xingjian7-calibration.md)
- 2026-11-19 14:28:30 +08:00 | 手机权限诊断/快败修复与 build18 发布 GitHub | 将 `ALL_FILES` 设为 Android 11+ 核心必需权限，新增 `.mp4.pending` 实写探测、手动“去授权”弹窗、致命存储错误快败与 `RemoteCommandHandler` 收口（`06bcd22` / `v1.6.6-test-11191414-build18`），`504` 测 `0` 失败、`0` Lint error | [权限启动修复](tasks/fix-storage-permission-startup.md)
- 2026-11-19 13:12:02 +08:00 | 新增 4.3 实时标定与视野调校预览 | 在「360 画面 → 4.3」新增双栏实时调校子页（鸟瞰大图/完整模式 A、平滑拖动缓存优化与按引用精确解绑销毁） | [4.3实时调校](tasks/surround-calibration-live-preview.md)
- 2026-11-19 12:11:00 +08:00 | P018 回看销毁保护修复与 build17 发布 GitHub | 修复 `PlaybackImageMemory.clearViews()` 在 `Activity.isDestroyed()` 调 `Glide.with()` 闪退，加固全量 Glide 加载/清理与 `AppShellController.restore()` 根容器显隐恢复（`e946c50` / `build17`） | [P018修复](tasks/fix-p018-playback-glide-destroy.md) · [P018](PITFALLS.md)

## 归档与按需索引

- **归档全索引**：[2026-11 历史文档与任务全索引](archive/2026-11/INDEX.md)（`T01`–`T10`、`build01`–`build13`、早期报告均已收录）；APK 归档见 `outputs/archive/INDEX.md`
- **按需规范**：[需求](REQUIREMENTS.md) · [坑表](PITFALLS.md) · [协作](COLLABORATION.md) · [开发](DEVELOPMENT.md) · [本机资料](AI_HANDOFF.md)
