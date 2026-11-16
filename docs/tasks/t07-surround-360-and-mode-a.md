# 任务记录：T07 360 全景 + 分视角模式 A 与辅助设置（build08）

- 最后更新：2026-11-16 16:17:00 +08:00（Asia/Hong_Kong）
- 最后核对：2026-11-16 16:17:00 +08:00（Asia/Hong_Kong）
- 任务/执行者：`t07-surround-360-and-mode-a` / Antigravity
- 分支/工作树：`codex/e5-recording-startup-fix` / `E:\GPT\EVCam`（源码 `b9aeda1`，Tag `v1.6.6-test-11161613-build08`）
- 状态：已完成代码、单元测试、`build08` 双风味 Release(R8) 打包（离线通过，**未上车实测**）

## 实施摘要（仅限 T07，未改 T08–T10）

1. **按需创建/销毁的 360 GL 上下文与 Surface 共享**：
   - 新增 [SurroundModeAView.java](../../app/src/main/java/com/kooo/evcam/surround/SurroundModeAView.java) 与 [SurroundGlRenderer.java](../../app/src/main/java/com/kooo/evcam/surround/SurroundGlRenderer.java)（GLES20 4 路 OES、`GL_TEXTURE_2D` 车模、Ackermann 动态引导线），仅在模式 A 且前台录制页开启主预览时创建 `GLSurfaceView`，切离模式 A/Tab/后台/关预览立即释放。
   - 在 [SingleCamera.java](../../app/src/main/java/com/kooo/evcam/camera/SingleCamera.java) 与 [MultiCameraManager.java](../../app/src/main/java/com/kooo/evcam/camera/MultiCameraManager.java) 扩展 `surroundPreviewSurface`（`OutputConfiguration` surface sharing + `stopRepeatingNow()` 安全增删）。
   - 复用 [CameraMirrorPolicy.java](../../app/src/main/java/com/kooo/evcam/blindspot/CameraMirrorPolicy.java) 的物理镜像 `physicalH`/`physicalV`，并在 [PreviewModeController.java](../../app/src/main/java/com/kooo/evcam/ui/PreviewModeController.java) 中于 `suppress_on_360` 启用时同步 `BlindSpotService.setInternal360Active`。
2. **`4.1` 分视角模式 A 与右上角胶囊切换（严格去冗余项）**：
   - [SurroundViewMode.java](../../app/src/main/java/com/kooo/evcam/surround/SurroundViewMode.java) 与 [SurroundLayoutCalculator.java](../../app/src/main/java/com/kooo/evcam/surround/SurroundLayoutCalculator.java) 实现左 360 + 右上前/后/随档位 + 右下轮位/折叠 + 拉伸开关与右上角按键显隐开关（`surround_overlay_buttons_visible`）。
   - 已彻底排除通用屏幕尺寸、外设、环境提示字、前雷达提示字、领克 08 与任何云端地址。
3. **`4.3` 银河 E5 内置校准、Ackermann 引导线与离线车模**：
   - [SurroundCalibrationManager.java](../../app/src/main/java/com/kooo/evcam/surround/SurroundCalibrationManager.java) 使用纯 Java Gson 解析本地 `Download` `calibration.json` 与内置 E5 光学标定；[SurroundMeshBuilder.java](../../app/src/main/java/com/kooo/evcam/surround/SurroundMeshBuilder.java) 内存生成投影网格（零 `.mesh` / `glReadPixels`）。
   - [SurroundGuidanceCalculator.java](../../app/src/main/java/com/kooo/evcam/surround/SurroundGuidanceCalculator.java) 与 [SurroundCarModelRepository.java](../../app/src/main/java/com/kooo/evcam/surround/SurroundCarModelRepository.java) 提供动态引导线、离线 E5 俯视矢量车模及本地 PNG（`<=4MB`）导入。
4. **`4.2` R 档延时拉起/车速返桌/P 档返桌与 `4. 360 画面` 设置页**：
   - 新增 [ReversePreviewAutoCloseCoordinator.java](../../app/src/main/java/com/kooo/evcam/vehicle/ReversePreviewAutoCloseCoordinator.java)、[SurroundSettingsFragment.java](../../app/src/main/java/com/kooo/evcam/ui/SurroundSettingsFragment.java) 与 [fragment_surround_settings.xml](../../app/src/main/res/layout/fragment_surround_settings.xml)，并在 [SettingsCatalog.java](../../app/src/main/java/com/kooo/evcam/ui/SettingsCatalog.java) 注册 `4. 360 画面`。

## 离线验证与 `build08` 交付

- **单元测试（`b9aeda1`）**：`324` 项 JVM 单测（A `162` + B `162`，`0` 失败/错误/跳过），含 `SurroundViewModeTest`、`SurroundLayoutCalculatorTest`、`SurroundCalibrationAndGuidanceTest`、`ReversePreviewAutoCloseCoordinatorTest` 及更新后的 `PreviewModeControllerTest`、`SettingsCatalogAndAppearanceTest`、`SingleCameraResourceTest`。
- **编译、Lint 与签名**：A/B Debug + Release(R8) 通过；`:app:lintIndependentDebug --offline` 保持 `19` baseline 错误（`0` 新增、`1941` warnings）；`apksigner verify` 通过（Cert `a40da8...1bf5dc`），均含 `arm64-v8a/libvhal_decoder.so`（`8408`B，`243a92bc...`）。
- **`build08` 交付物**：
  - `EVCam-E5-A-Independent-1.6.6-test-11161613-build08-release.apk`（`6,627,215`B，SHA256 `2e543314...757a84f`）
  - `EVCam-E5-B-Composite-1.6.6-test-11161613-build08-release.apk`（`6,627,215`B，SHA256 `180d1c6e...79c924e`）
  - `outputs/`: [manifest.json](../../outputs/EVCam-E5-build08-1.6.6-test-11161613-manifest.json)、[notes.md](../../outputs/EVCam-E5-build08-1.6.6-test-11161613-notes.md)、[SHA256.txt](../../outputs/EVCam-E5-build08-1.6.6-test-11161613-SHA256.txt)、`mappings.zip`（`4,879,243`B）。

## 实车待验项（未上车实测）

- E5 实车四路全景拼接贴合度、`OutputConfiguration` 共享表面在录制+HUD 并发下的稳定性、模式 A <-> B <-> C 切换与 `GLSurfaceView` 销毁延迟。
- 实车物理镜像对齐、`4.2` R 档延时/车速返桌/P 档延时返桌联动、`suppress_on_360` 补盲抑制及本地 `Download` 标定/PNG 车模导入。

## 下一步

1. 基于 `build08` 启动 **`T08`**（回看重构与局域网浏览器分享）或实车安装 `build08` 联合验收 `T01`–`T07`。
