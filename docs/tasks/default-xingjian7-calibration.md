# default-xingjian7-calibration

最后核对：2026-11-19 16:10:00 +08:00（Asia/Hong_Kong）
负责人：Antigravity | 分支：`codex/e5-recording-startup-fix`（`E:\GPT\EVCam`） | 状态：已提交 `9d2dc5c` 并发布 GitHub Pre-release `v1.6.6-test-11191602-build19`（`versionCode = 86`，通过 `512` 测、双风味构建/Lint 与 APK/GitHub 资产 SHA256 核验，待真机/实车实测）

## 目标与范围

- 将 `C:\Users\luka\WorkBuddy\2026-11-19-01-48-05\星舰7标定\星舰7标定_avm_calib_res.zip`（`avm_calib_res/`）设为 App 内置默认 360 标定方案，适配 `opencv_fisheye_theta_polynomial` 模型与 `.mesh` 投影资产。
- 同步支持导入 `.zip` 标定包（`calibration.json` + 可选 `.mesh`），并保持与 `4.3` 实时标定预览滑块（`SurroundCalibrationLivePreview`）、E5 透明车模及动态引导线（`SurroundGuidanceCalculator`，保留 275cm 轴距 / 162cm 轮距）坐标对齐。

## 改动与验证

- **默认标定资产入库**（`app/src/main/assets/surround/default/`，含恢复原始 LF 字节后的 `calibration.json`）：
  - `bird.mesh`（2,651,904 B）、`calibration.json`（7,256 B，`sha256:987040df...`）、`front_wheels.mesh`（760,320 B）、`rear_wheels.mesh`（760,320 B）。
- **核心代码修改**（`versionName = 1.6.6-test-11191602` / `versionCode = 86`）：
  - [SurroundCalibrationProfile.java](../../app/src/main/java/com/kooo/evcam/surround/SurroundCalibrationProfile.java)：新增 `DEFAULT_BIRD_CENTER_OFFSET_Y_CM = -143.0` 与 `useBuiltInMeshes`。
  - [SurroundCalibrationManager.java](../../app/src/main/java/com/kooo/evcam/surround/SurroundCalibrationManager.java)：内置默认替换为星舰7相机参数（`fx=fy=318.0, cx=640.0, cy=400.0`、`usePolynomialDistortion=true`、`Y +143.0 cm` 居中），`parseCalibrationJson` 补自动 Y 居中，新增 `.zip` 导入与单 JSON 导入清理过期 `.mesh`。
  - [SurroundAssetImportHelper.java](../../app/src/main/java/com/kooo/evcam/ui/SurroundAssetImportHelper.java)：`CALIBRATION_MIME_TYPES` 增加 `application/zip` 与 `application/x-zip-compressed`。
  - [SurroundMeshBuilder.java](../../app/src/main/java/com/kooo/evcam/surround/SurroundMeshBuilder.java)：新增 `parseBirdMeshBinary`（去重 `14,732` 顶点，车底盲区 `underCarWeight = 1.4f` 并向边界三角形盲区顶点传播有效邻点 UV）、`parseWheelMeshPair`（后轮边缘放宽至 `[-0.02, 1.02]`）及多项式/球面模型补丁网格构建。
  - [SurroundGlRenderer.java](../../app/src/main/java/com/kooo/evcam/surround/SurroundGlRenderer.java)：`BIRD_VERTEX_SHADER` 增加 `uViewTransform` 实时响应地宽/地长滑块，`BIRD_FRAGMENT_SHADER` 车底混色上限放开至 `1.0` 覆盖盲区（保持过程化路径上限 `0.72`），`drawBirdViewport` 启用 `GL_SCISSOR_TEST` 并接入内置/自定义 `.mesh`。
  - [SurroundCalibrationAndGuidanceTest.java](../../app/src/test/java/com/kooo/evcam/surround/SurroundCalibrationAndGuidanceTest.java)：新增默认星舰7参数/网格、盲区边界 UV 传播、后轮边缘有效区与 `.zip` 导入测试。
- **验证证据（`1.6.6-test-11191602` / `versionCode = 86`）**：
  - 离线单测（`verify.ps1`）：`512` 测（A/B 各 `256` 测），`0` 失败 / `0` 错误 / `0` 跳过（日志 `work/verification/build-20261119-160143-321.log`）。
  - 构建、Lint 与包校验：`assembleDebug`/`assembleRelease` 通过，双风味 `lint` 均为 **`0` errors**（`2110` warnings）；两版 Release APK 均通过 `apksigner verify`（v2 证书 `a40da8...1bf5dc`）、`libvhal_decoder.so`（`8,408` B）与 4 份标定资产 SHA-256 精确核验。
  - 注：此前 `1.6.6-test-11191506` 在 `emulator-5556` 做过 GL 冒烟；合流 `build18` 及车底优化后的 `1.6.6-test-11191602` 尚未重跑模拟器与实车四路实测。

## 未解决项与下一步

- 银河 E5 实车验证四路实景在星舰7默认标定 + `.mesh` 下的拼接接缝、车模比例、前/后轮视角及 `4.3` 滑块实时缩放，并同步验证手机/车机 `build18` 权限启动逻辑。

## 最近记录

- 2026-11-19 16:10:00 +08:00 | 封包 `build19` 并发布 GitHub Pre-release | 提交源码 `9d2dc5c` 并打附注标签 `v1.6.6-test-11191602-build19` 推送至 `johnwyatt041-commits/EVCam`，完成 `outputs/` 封包（`A` `7,849,908` B、`B` `7,866,292` B、`mappings.zip` `13,065,514` B）与 `build17` 归档，GitHub 6 份资产上传后大小与 SHA256 摘要回读一致（待真机/实车实测）。
- 2026-11-19 16:06:00 +08:00 | 合流 `build18`、修复盲区边界 UV 与 `calibration.json` LF 字节并通过 `512` 测/Lint/验包 | 车底盲区混色达 `1.0` 且边界邻点不再跨图拉伸，`1.6.6-test-11191602`（`versionCode = 86`）通过 `verify.ps1` `512` 测、`0` Lint error 及双风味 Release APK 签名/资产核验，准备打包 `build19` 推送 GitHub。
- 2026-11-19 15:15:42 +08:00 | 设置星舰7默认 360 标定包与网格支持 | 完成初始星舰7默认标定资产入库、多项式投影、`.mesh` 加载与 `1.6.6-test-11191506` 离线 `496` 测及模拟器 GL 冒烟（待实车验证）。
