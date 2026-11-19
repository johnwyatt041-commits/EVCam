package com.kooo.evcam.surround;

import com.kooo.evcam.surround.SurroundCalibrationManager.ImportResult;
import com.kooo.evcam.surround.SurroundCalibrationProfile.CameraCalibration;
import com.kooo.evcam.surround.SurroundExtrinsicAdjustment.CameraOffset;
import com.kooo.evcam.surround.SurroundGuidanceCalculator.LineStripBatch;
import com.kooo.evcam.surround.SurroundMeshBuilder.GridMeshData;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

/**
 * 验证 E5 标定正逆投影、本地 calibration.json 校验导入（无领克08）、内存拼接网格、阿克曼动态引导线与离线 PNG 车模检验。
 */
public class SurroundCalibrationAndGuidanceTest {

    @Test
    public void builtInE5ProfileProjectsGroundPointsIntoValidUvAndBuildsStitchMesh() {
        SurroundCalibrationProfile profile = SurroundCalibrationManager.createBuiltInE5Profile(206, 530, 20);
        assertEquals("银河 E5 内置校准", profile.profileName);
        assertFalse(profile.isCustomCalibration);
        assertEquals(206, profile.groundWidthCm);
        assertEquals(530, profile.groundLengthCm);
        assertEquals(20, profile.groundFrontExtensionCm);

        SurroundCalibrationProfile.CameraCalibration front = profile.getCamera("front");
        assertNotNull(front);
        double[] frontGround = front.projectWorldToUv(0.0, 340.0, 0.0);
        assertTrue(frontGround[0] > 0.0 && frontGround[0] < 1.0);
        assertTrue(frontGround[1] > 0.0 && frontGround[1] < 1.0);
        assertEquals(1.0, frontGround[2], 1e-6);

        GridMeshData birdMesh = SurroundMeshBuilder.buildBirdGroundMesh(profile, 16, 24);
        assertEquals(17 * 25 * SurroundMeshBuilder.BIRD_FLOATS_PER_VERTEX, birdMesh.vertices.length);
        assertEquals(16 * 24 * 6, birdMesh.indices.length);

        GridMeshData roadMesh = SurroundMeshBuilder.buildRoadPatchMesh(true, 1.8f, true, 12, 10);
        assertEquals(13 * 11 * SurroundMeshBuilder.PATCH_FLOATS_PER_VERTEX, roadMesh.vertices.length);
        assertEquals(12 * 10 * 6, roadMesh.indices.length);

        GridMeshData wheelMesh = SurroundMeshBuilder.buildWheelPatchMesh(0, 10, 10);
        assertEquals(11 * 11 * SurroundMeshBuilder.PATCH_FLOATS_PER_VERTEX, wheelMesh.vertices.length);
    }

    @Test
    public void customCalibrationJsonParsesAllFourCamerasAndRejectsBrokenPayload() throws Exception {
        String validJson = "{\n"
                + "  \"profile_name\": \"E5 定制 1.3\",\n"
                + "  \"cameras\": {\n"
                + "    \"front\": {\"fx\": 318.0, \"fy\": 318.0, \"cx\": 640.0, \"cy\": 400.0, \"distortion\": [0.13, -0.04, 0.01, -0.002], \"position\": [0.0, 0.8, 0.65], \"world_to_camera_rotation\": [[1,0,0],[0,-0.4,-0.9],[0,0.9,-0.4]]},\n"
                + "    \"rear\": {\"fx\": 318.0, \"fy\": 318.0, \"cx\": 640.0, \"cy\": 400.0, \"distortion\": [0.13, -0.04, 0.01, -0.002], \"position\": [0.0, -3.65, 0.99], \"world_to_camera_rotation\": [[-1,0,0],[0,0.46,-0.88],[0,-0.88,-0.46]]},\n"
                + "    \"left\": {\"fx\": 318.0, \"fy\": 318.0, \"cx\": 640.0, \"cy\": 400.0, \"distortion\": [0.13, -0.04, 0.01, -0.002], \"position\": [-1.03, -0.92, 1.08], \"world_to_camera_rotation\": [[0,1,0],[0.74,0,-0.66],[-0.66,0,-0.74]]},\n"
                + "    \"right\": {\"fx\": 318.0, \"fy\": 318.0, \"cx\": 640.0, \"cy\": 400.0, \"distortion\": [0.13, -0.04, 0.01, -0.002], \"position\": [1.03, -0.92, 1.08], \"world_to_camera_rotation\": [[0,-1,0],[-0.74,0,-0.66],[0.66,0,-0.74]]}\n"
                + "  }\n"
                + "}";

        File tmpDir = Files.createTempDirectory("evcam_calib_test").toFile();
        File src = new File(tmpDir, "calibration.json");
        try (FileOutputStream fos = new FileOutputStream(src)) {
            fos.write(validJson.getBytes(StandardCharsets.UTF_8));
        }

        File destDir = new File(tmpDir, "internal");
        ImportResult ok = SurroundCalibrationManager.importCalibrationFile(src, destDir);
        assertTrue(ok.success);
        assertNotNull(ok.importedFilePath);
        assertTrue(new File(ok.importedFilePath).isFile());

        SurroundCalibrationProfile custom = SurroundCalibrationManager.parseCalibrationJson(validJson, 210, 540, 10);
        assertTrue(custom.isCustomCalibration);
        assertEquals(210, custom.groundWidthCm);
        assertNotNull(custom.getCamera("back"));
        double[] uv = custom.getCamera("back").projectWorldToUv(0.0, -420.0, 0.0);
        assertTrue(uv[0] >= 0.001 && uv[0] <= 0.999);
        assertTrue(uv[1] >= 0.001 && uv[1] <= 0.999);

        File invalid = new File(tmpDir, "bad_calibration.json");
        try (FileOutputStream fos = new FileOutputStream(invalid)) {
            fos.write("{\"cameras\":{}}".getBytes(StandardCharsets.UTF_8));
        }
        ImportResult fail = SurroundCalibrationManager.importCalibrationFile(invalid, destDir);
        assertFalse(fail.success);
    }

    @Test
    public void guidanceStripsCurveWithSteeringAngleAndPngSignatureCheckWorksOffline() {
        SurroundCalibrationProfile profile = SurroundCalibrationManager.createBuiltInE5Profile(206, 530, 0);

        int roadSteps = 10;
        LineStripBatch straight = SurroundGuidanceCalculator.buildRoadGuidanceStrips(0f, true, roadSteps);
        LineStripBatch rightTurn = SurroundGuidanceCalculator.buildRoadGuidanceStrips(25f, true, roadSteps);
        LineStripBatch leftTurn = SurroundGuidanceCalculator.buildRoadGuidanceStrips(-25f, false, roadSteps);
        // 图 2 风格：4 条纯两侧双层引导带（左内粗/左外细/右内粗/右外细），中间无横线
        assertEquals(4, straight.stripVertexCounts.length);
        assertEquals(straight.triangleStripVertices.length, rightTurn.triangleStripVertices.length);
        assertEquals(straight.triangleStripVertices.length, leftTurn.triangleStripVertices.length);

        // 验证近端左右完全分居两侧、中间彻底留空
        int floatsPerStep = 2 * SurroundGuidanceCalculator.FLOATS_PER_COLOR_VERTEX;
        int strip0Floats = straight.stripVertexCounts[0] * SurroundGuidanceCalculator.FLOATS_PER_COLOR_VERTEX;
        float straightLeftNearX = centerXAtStep(straight, 0, 0);
        float straightLeftOuterNearX = centerXAtStep(straight, strip0Floats, 0);
        float straightRightNearX = centerXAtStep(straight, strip0Floats * 2, 0);
        float straightRightOuterNearX = centerXAtStep(straight, strip0Floats * 3, 0);
        assertTrue(straightLeftOuterNearX < straightLeftNearX && straightLeftNearX < -0.4f);
        assertTrue(straightRightOuterNearX > straightRightNearX && straightRightNearX > 0.4f);

        // 右转远端相对直行向右侧(+X)偏移；左转远端向左侧(-X)偏移
        float straightFarLeftX = centerXAtStep(straight, 0, roadSteps);
        float rightFarLeftX = centerXAtStep(rightTurn, 0, roadSteps);
        float leftFarLeftX = centerXAtStep(leftTurn, 0, roadSteps);
        assertTrue(rightFarLeftX > straightFarLeftX);
        assertTrue(leftFarLeftX < straightFarLeftX);

        // 验证前/后视引导线宽度调节及上下界钳位（50% ~ 150%）
        LineStripBatch narrowRoad = SurroundGuidanceCalculator.buildRoadGuidanceStrips(0f, false, 0.60f, roadSteps);
        LineStripBatch wideRoad = SurroundGuidanceCalculator.buildRoadGuidanceStrips(0f, false, 1.40f, roadSteps);
        LineStripBatch clampedLow = SurroundGuidanceCalculator.buildRoadGuidanceStrips(0f, false, 0.10f, roadSteps);
        LineStripBatch exactLow = SurroundGuidanceCalculator.buildRoadGuidanceStrips(0f, false, 0.50f, roadSteps);
        LineStripBatch clampedHigh = SurroundGuidanceCalculator.buildRoadGuidanceStrips(0f, false, 3.00f, roadSteps);
        LineStripBatch exactHigh = SurroundGuidanceCalculator.buildRoadGuidanceStrips(0f, false, 1.50f, roadSteps);
        assertEquals(4, narrowRoad.stripVertexCounts.length);
        assertEquals(4, wideRoad.stripVertexCounts.length);
        float narrowLeftX = centerXAtStep(narrowRoad, 0, 0);
        float normalLeftX = centerXAtStep(straight, 0, 0);
        float wideLeftX = centerXAtStep(wideRoad, 0, 0);
        float narrowRightX = centerXAtStep(narrowRoad, strip0Floats * 2, 0);
        float normalRightX = centerXAtStep(straight, strip0Floats * 2, 0);
        float wideRightX = centerXAtStep(wideRoad, strip0Floats * 2, 0);
        assertTrue(wideLeftX < normalLeftX && normalLeftX < narrowLeftX && narrowLeftX < 0f);
        assertTrue(wideRightX > normalRightX && normalRightX > narrowRightX && narrowRightX > 0f);
        assertEquals(centerXAtStep(exactLow, 0, 0), centerXAtStep(clampedLow, 0, 0), 1e-4f);
        assertEquals(centerXAtStep(exactHigh, 0, 0), centerXAtStep(clampedHigh, 0, 0), 1e-4f);

        // 鸟瞰 4 象限阿克曼方向验证（含修复前进右转反折 Bug）及倒车外摆弧线
        int birdSteps = 12;
        LineStripBatch birdFwdStraight = SurroundGuidanceCalculator.buildBirdGuidanceStrips(0f, false, profile, birdSteps);
        LineStripBatch birdFwdRight = SurroundGuidanceCalculator.buildBirdGuidanceStrips(25f, false, profile, birdSteps);
        LineStripBatch birdFwdLeft = SurroundGuidanceCalculator.buildBirdGuidanceStrips(-25f, false, profile, birdSteps);
        assertEquals(2, birdFwdStraight.stripVertexCounts.length);
        assertEquals(2, birdFwdRight.stripVertexCounts.length);
        assertEquals(2, birdFwdLeft.stripVertexCounts.length);

        assertTrue(centerYAtStep(birdFwdRight, 0, birdSteps) > centerYAtStep(birdFwdRight, 0, 0));
        assertTrue(centerXAtStep(birdFwdRight, 0, birdSteps) > centerXAtStep(birdFwdStraight, 0, birdSteps));
        assertTrue(centerYAtStep(birdFwdLeft, 0, birdSteps) > centerYAtStep(birdFwdLeft, 0, 0));
        assertTrue(centerXAtStep(birdFwdLeft, 0, birdSteps) < centerXAtStep(birdFwdStraight, 0, birdSteps));

        LineStripBatch birdRevSmall = SurroundGuidanceCalculator.buildBirdGuidanceStrips(-3f, true, profile, birdSteps);
        assertEquals(2, birdRevSmall.stripVertexCounts.length);

        LineStripBatch birdRevRight = SurroundGuidanceCalculator.buildBirdGuidanceStrips(25f, true, profile, birdSteps);
        LineStripBatch birdRevLeft = SurroundGuidanceCalculator.buildBirdGuidanceStrips(-25f, true, profile, birdSteps);
        assertEquals(3, birdRevRight.stripVertexCounts.length); // 含前左外摆弧
        assertEquals(3, birdRevLeft.stripVertexCounts.length);  // 含前右外摆弧

        int birdStrip0Floats = birdRevRight.stripVertexCounts[0] * SurroundGuidanceCalculator.FLOATS_PER_COLOR_VERTEX;
        assertTrue(centerYAtStep(birdRevRight, 0, birdSteps) < centerYAtStep(birdRevRight, 0, 0));
        assertTrue(centerXAtStep(birdRevRight, 0, birdSteps) > centerXAtStep(birdRevRight, 0, 0));
        assertTrue(centerXAtStep(birdRevRight, birdStrip0Floats * 2, 0) < 0f); // 倒车右转前外摆弧在左侧(-X)
        assertTrue(centerYAtStep(birdRevLeft, 0, birdSteps) < centerYAtStep(birdRevLeft, 0, 0));
        assertTrue(centerXAtStep(birdRevLeft, 0, birdSteps) < centerXAtStep(birdRevLeft, 0, 0));
        assertTrue(centerXAtStep(birdRevLeft, birdStrip0Floats * 2, 0) > 0f);  // 倒车左转前外摆弧在右侧(+X)
        assertEquals(floatsPerStep > 0, birdRevRight.triangleStripVertices.length > 0);

        byte[] validPngHeader = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        byte[] fakeJpgHeader = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0};
        assertTrue(SurroundCarModelRepository.isValidPngSignatureBytes(validPngHeader));
        assertFalse(SurroundCarModelRepository.isValidPngSignatureBytes(fakeJpgHeader));
    }

    private static float centerXAtStep(LineStripBatch batch, int stripFloatOffset, int step) {
        int base = stripFloatOffset + step * 2 * SurroundGuidanceCalculator.FLOATS_PER_COLOR_VERTEX;
        return (batch.triangleStripVertices[base]
                + batch.triangleStripVertices[base + SurroundGuidanceCalculator.FLOATS_PER_COLOR_VERTEX]) * 0.5f;
    }

    private static float centerYAtStep(LineStripBatch batch, int stripFloatOffset, int step) {
        int base = stripFloatOffset + step * 2 * SurroundGuidanceCalculator.FLOATS_PER_COLOR_VERTEX;
        return (batch.triangleStripVertices[base + 1]
                + batch.triangleStripVertices[base + SurroundGuidanceCalculator.FLOATS_PER_COLOR_VERTEX + 1]) * 0.5f;
    }

    @Test
    public void defaultCarModelResourceExistsAndImportStreamValidatesPngDimensions() throws Exception {
        File defaultPng = new File("src/main/res/drawable-nodpi/e5_car_model_default.png");
        if (!defaultPng.isFile()) {
            defaultPng = new File("app/src/main/res/drawable-nodpi/e5_car_model_default.png");
        }
        assertTrue("Built-in default car model PNG resource must exist", defaultPng.isFile());
        byte[] defaultBytes = Files.readAllBytes(defaultPng.toPath());
        assertTrue(defaultBytes.length > 0 && defaultBytes.length <= SurroundCarModelRepository.MAX_CAR_MODEL_PNG_BYTES);
        assertTrue(SurroundCarModelRepository.isValidPngSignatureBytes(defaultBytes));
        int[] dims = SurroundCarModelRepository.parsePngIhdrDimensions(defaultBytes);
        assertNotNull(dims);
        assertEquals(504, dims[0]);
        assertEquals(1024, dims[1]);

        File tmpDir = Files.createTempDirectory("evcam_car_model_test").toFile();
        File destDir = new File(tmpDir, "surround");
        SurroundCarModelRepository.ImportResult ok = SurroundCarModelRepository.importCustomCarModelBytes(
                defaultBytes, "my_e5.png", destDir);
        assertTrue(ok.success);
        assertNotNull(ok.importedFilePath);
        assertTrue(new File(ok.importedFilePath).isFile());

        SurroundCarModelRepository.ImportResult rejected = SurroundCarModelRepository.importCustomCarModelBytes(
                new byte[]{0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09}, "bad.png", destDir);
        assertFalse(rejected.success);
    }

    @Test
    public void builtInDefaultProfileMatchesBundledCalibrationJsonAndMeshes() throws Exception {
        File assetDir = resolveAssetDir("src/main/assets/surround/default", "app/src/main/assets/surround/default");
        assertTrue("Default surround asset dir must exist", assetDir.isDirectory());

        File jsonFile = new File(assetDir, "calibration.json");
        File birdFile = new File(assetDir, "bird.mesh");
        File frontWheelsFile = new File(assetDir, "front_wheels.mesh");
        File rearWheelsFile = new File(assetDir, "rear_wheels.mesh");
        assertTrue(jsonFile.isFile());
        assertTrue(birdFile.isFile());
        assertTrue(frontWheelsFile.isFile());
        assertTrue(rearWheelsFile.isFile());

        String jsonRaw = new String(Files.readAllBytes(jsonFile.toPath()), StandardCharsets.UTF_8);
        SurroundCalibrationProfile parsedJson = SurroundCalibrationManager.parseCalibrationJson(jsonRaw, 206, 530, 0);
        SurroundCalibrationProfile builtIn = SurroundCalibrationManager.createBuiltInE5Profile(206, 530, 0);
        assertTrue(builtIn.useBuiltInMeshes);

        for (String key : new String[]{"front", "back", "left", "right"}) {
            SurroundCalibrationProfile.CameraCalibration bCam = builtIn.getCamera(key);
            SurroundCalibrationProfile.CameraCalibration jCam = parsedJson.getCamera(key);
            assertNotNull(bCam);
            assertNotNull(jCam);
            assertTrue(bCam.usePolynomialDistortion);
            assertEquals(jCam.fx, bCam.fx, 1e-6);
            assertEquals(jCam.fy, bCam.fy, 1e-6);
            assertEquals(jCam.cx, bCam.cx, 1e-6);
            assertEquals(jCam.cy, bCam.cy, 1e-6);
            assertEquals(jCam.posXCm, bCam.posXCm, 1e-4);
            assertEquals(jCam.posZCm, bCam.posZCm, 1e-4);
            assertEquals(jCam.posYCm, bCam.posYCm, 1.0);
            assertArrayEquals(jCam.r0, bCam.r0, 1e-6);
            assertArrayEquals(jCam.r1, bCam.r1, 1e-6);
            assertArrayEquals(jCam.r2, bCam.r2, 1e-6);
            assertArrayEquals(jCam.distortion, bCam.distortion, 1e-6);
        }

        byte[] birdBytes = Files.readAllBytes(birdFile.toPath());
        GridMeshData birdMesh = SurroundMeshBuilder.parseBirdMeshBinary(
                birdBytes, SurroundCalibrationProfile.DEFAULT_BIRD_CENTER_OFFSET_Y_CM);
        assertNotNull(birdMesh);
        assertEquals(82872, birdMesh.indices.length);
        assertEquals(14732 * SurroundMeshBuilder.BIRD_FLOATS_PER_VERTEX, birdMesh.vertices.length);
        for (short idx : birdMesh.indices) {
            assertTrue(idx >= 0 && idx < 14732);
        }

        // Verify a single-camera front ground vertex in bird.mesh matches CameraCalibration.projectWorldToUv
        boolean checkedFrontMatch = false;
        for (int i = 0; i < 14732; i++) {
            int base = i * SurroundMeshBuilder.BIRD_FLOATS_PER_VERTEX;
            float wx = birdMesh.vertices[base];
            float wy = birdMesh.vertices[base + 1];
            int pIdx = Math.round(birdMesh.vertices[base + 6]);
            int sIdx = Math.round(birdMesh.vertices[base + 7]);
            float under = birdMesh.vertices[base + 9];
            if (pIdx == SurroundMeshBuilder.CAM_FRONT && sIdx == SurroundMeshBuilder.CAM_FRONT
                    && under < 0.1f && wy > 230.0f && wy < 450.0f && Math.abs(wx) < 80.0f) {
                double[] uv = builtIn.getCamera("front").projectWorldToUv(wx, wy, 0.0);
                assertEquals(uv[0], birdMesh.vertices[base + 2], 2e-4);
                assertEquals(uv[1], birdMesh.vertices[base + 3], 2e-4);
                checkedFrontMatch = true;
                break;
            }
        }
        assertTrue("Should verify front camera UV consistency with bird.mesh", checkedFrontMatch);

        // Verify under-car blind-zone vertices reach full dark weight (1.4f) and boundary triangles propagate neighbor UV
        boolean checkedUnderCarHole = false;
        boolean checkedBoundaryNoCornerLeak = false;
        for (int t = 0; t + 2 < birdMesh.indices.length; t += 3) {
            int b0 = (birdMesh.indices[t] & 0xFFFF) * SurroundMeshBuilder.BIRD_FLOATS_PER_VERTEX;
            int b1 = (birdMesh.indices[t + 1] & 0xFFFF) * SurroundMeshBuilder.BIRD_FLOATS_PER_VERTEX;
            int b2 = (birdMesh.indices[t + 2] & 0xFFFF) * SurroundMeshBuilder.BIRD_FLOATS_PER_VERTEX;
            float u0 = birdMesh.vertices[b0 + 9];
            float u1 = birdMesh.vertices[b1 + 9];
            float u2 = birdMesh.vertices[b2 + 9];
            if (u0 >= 1.39f && u1 >= 1.39f && u2 >= 1.39f) {
                checkedUnderCarHole = true;
            }
            if ((u0 < 0.5f || u1 < 0.5f || u2 < 0.5f) && (u0 >= 1.39f || u1 >= 1.39f || u2 >= 1.39f)) {
                int holeBase = u0 >= 1.39f ? b0 : (u1 >= 1.39f ? b1 : b2);
                float holeU = birdMesh.vertices[holeBase + 2];
                float holeV = birdMesh.vertices[holeBase + 3];
                assertFalse("Boundary hole vertex should inherit neighbor UV instead of corner (0.001, 0.999)",
                        Math.abs(holeU - 0.001f) < 1e-4f && Math.abs(holeV - 0.999f) < 1e-4f);
                checkedBoundaryNoCornerLeak = true;
            }
        }
        assertTrue("Should detect full-dark under-car blind-zone triangles", checkedUnderCarHole);
        assertTrue("Should propagate valid UV on under-car boundary triangles", checkedBoundaryNoCornerLeak);

        GridMeshData[] frontPair = SurroundMeshBuilder.parseWheelMeshPair(Files.readAllBytes(frontWheelsFile.toPath()));
        GridMeshData[] rearPair = SurroundMeshBuilder.parseWheelMeshPair(Files.readAllBytes(rearWheelsFile.toPath()));
        assertNotNull(frontPair);
        assertNotNull(rearPair);
        assertEquals(11880, frontPair[0].indices.length);
        assertEquals(11880, frontPair[1].indices.length);
        assertEquals(11880, rearPair[0].indices.length);
        assertEquals(11880, rearPair[1].indices.length);
        for (int i = 0; i < rearPair[0].vertices.length; i += SurroundMeshBuilder.PATCH_FLOATS_PER_VERTEX) {
            assertEquals(1.0f, rearPair[0].vertices[i + 4], 1e-5f);
        }
        for (int i = 0; i < rearPair[1].vertices.length; i += SurroundMeshBuilder.PATCH_FLOATS_PER_VERTEX) {
            assertEquals(1.0f, rearPair[1].vertices[i + 4], 1e-5f);
        }
        assertWheelSpanFullNdc(frontPair[0]);
        assertWheelSpanFullNdc(frontPair[1]);
    }

    @Test
    public void calibrationZipBundleImportsJsonAndMeshesAndSubsequentJsonClearsStaleMeshes() throws Exception {
        File assetDir = resolveAssetDir("src/main/assets/surround/default", "app/src/main/assets/surround/default");
        File tmpDir = Files.createTempDirectory("evcam_zip_calib_test").toFile();
        File zipFile = new File(tmpDir, "xingjian7_avm_calib_res.zip");

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (String name : new String[]{"calibration.json", "bird.mesh", "front_wheels.mesh", "rear_wheels.mesh"}) {
                zos.putNextEntry(new ZipEntry("avm_calib_res/" + name));
                zos.write(Files.readAllBytes(new File(assetDir, name).toPath()));
                zos.closeEntry();
            }
        }
        try (FileOutputStream fos = new FileOutputStream(zipFile)) {
            fos.write(bos.toByteArray());
        }

        File destDir = new File(tmpDir, "surround");
        ImportResult zipRes = SurroundCalibrationManager.importCalibrationFile(zipFile, destDir);
        assertTrue(zipRes.success);
        assertTrue(new File(destDir, SurroundCalibrationManager.CUSTOM_CALIBRATION_FILE_NAME).isFile());
        assertTrue(new File(destDir, SurroundCalibrationManager.CUSTOM_BIRD_MESH_FILE_NAME).isFile());
        assertTrue(new File(destDir, SurroundCalibrationManager.CUSTOM_FRONT_WHEELS_MESH_FILE_NAME).isFile());
        assertTrue(new File(destDir, SurroundCalibrationManager.CUSTOM_REAR_WHEELS_MESH_FILE_NAME).isFile());

        // Subsequent standalone JSON import should clean up old zip meshes so they are never mixed
        ImportResult jsonOnlyRes = SurroundCalibrationManager.importCalibrationFile(
                new File(assetDir, "calibration.json"), destDir);
        assertTrue(jsonOnlyRes.success);
        assertTrue(new File(destDir, SurroundCalibrationManager.CUSTOM_CALIBRATION_FILE_NAME).isFile());
        assertFalse(new File(destDir, SurroundCalibrationManager.CUSTOM_BIRD_MESH_FILE_NAME).exists());
        assertFalse(new File(destDir, SurroundCalibrationManager.CUSTOM_FRONT_WHEELS_MESH_FILE_NAME).exists());
        assertFalse(new File(destDir, SurroundCalibrationManager.CUSTOM_REAR_WHEELS_MESH_FILE_NAME).exists());
    }

    private static File resolveAssetDir(String moduleRelative, String rootRelative) {
        File f = new File(moduleRelative);
        return f.isDirectory() ? f : new File(rootRelative);
    }

    private static void assertWheelSpanFullNdc(GridMeshData mesh) {
        float minX = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        for (int i = 0; i < mesh.vertices.length; i += SurroundMeshBuilder.PATCH_FLOATS_PER_VERTEX) {
            minX = Math.min(minX, mesh.vertices[i]);
            maxX = Math.max(maxX, mesh.vertices[i]);
        }
        assertEquals(-1.0f, minX, 0.01f);
        assertEquals(1.0f, maxX, 0.01f);
    }

    @Test
    public void extrinsicAdjustmentRotatesCameraAccuratelyAndPreservesOrthogonality() {
        SurroundCalibrationProfile base = SurroundCalibrationManager.createBuiltInE5Profile(206, 530, 0);
        assertSame(base, SurroundExtrinsicAdjustment.IDENTITY.applyToProfile(base));
        assertFalse(base.hasExtrinsicAdjustments);

        // 验证 0.5° 步进舍入、越界钳位与 Z 最小安全高度
        CameraOffset rounded = new CameraOffset(3.26f, -3.24f, 99.0f, -100, 200, -900);
        assertEquals(3.5f, rounded.pitchDeg, 1e-4f);
        assertEquals(-3.0f, rounded.yawDeg, 1e-4f);
        assertEquals(SurroundExtrinsicAdjustment.MAX_ROLL_DEG, rounded.rollDeg, 1e-4f);
        assertEquals(SurroundExtrinsicAdjustment.MIN_POS_X_CM, rounded.posXCm);
        assertEquals(SurroundExtrinsicAdjustment.MAX_POS_Y_CM, rounded.posYCm);
        assertEquals(SurroundExtrinsicAdjustment.MIN_POS_Z_CM, rounded.posZCm);

        SurroundExtrinsicAdjustment adj = SurroundExtrinsicAdjustment.IDENTITY
                .withCamera("front", new CameraOffset(7.0f, 5.0f, -3.0f, 0, 0, 0))
                .withCamera("back", new CameraOffset(-8.0f, 0.0f, 2.0f, 0, 0, -24));
        SurroundCalibrationProfile tuned = adj.applyToProfile(base);
        assertTrue(tuned.hasExtrinsicAdjustments);

        for (String key : new String[]{"front", "back", "left", "right"}) {
            CameraCalibration c = tuned.getCamera(key);
            assertNotNull(c);
            double det = c.r0[0] * (c.r1[1] * c.r2[2] - c.r1[2] * c.r2[1])
                    - c.r0[1] * (c.r1[0] * c.r2[2] - c.r1[2] * c.r2[0])
                    + c.r0[2] * (c.r1[0] * c.r2[1] - c.r1[1] * c.r2[0]);
            assertEquals(1.0, det, 1e-6);
        }

        // 前摄 +7°（更平/减少向下俯角）：前方 2m 地面点在原图像素纵向更靠下（v = 1 - py/H 减小）
        CameraCalibration baseFront = base.getCamera("front");
        CameraCalibration tunedFront = tuned.getCamera("front");
        assertNotNull(baseFront);
        assertNotNull(tunedFront);
        double[] baseFrontUv = baseFront.projectWorldToUv(0.0, baseFront.posYCm + 200.0, 0.0);
        double[] tunedFrontUv = tunedFront.projectWorldToUv(0.0, baseFront.posYCm + 200.0, 0.0);
        assertTrue(tunedFrontUv[1] < baseFrontUv[1]);

        // 后摄 -8° + 高度 -24cm（更低更陡）：后方 2m 地面点在原图像素纵向更靠上（v = 1 - py/H 增大）
        CameraCalibration baseBack = base.getCamera("back");
        CameraCalibration tunedBack = tuned.getCamera("back");
        assertNotNull(baseBack);
        assertNotNull(tunedBack);
        double[] baseBackUv = baseBack.projectWorldToUv(0.0, baseBack.posYCm - 200.0, 0.0);
        double[] tunedBackUv = tunedBack.projectWorldToUv(0.0, baseBack.posYCm - 200.0, 0.0);
        assertTrue(tunedBackUv[1] > baseBackUv[1]);
    }

    @Test
    public void extrinsicAdjustmentJsonSerializationAndExportRoundTrip() {
        assertEquals(SurroundExtrinsicAdjustment.IDENTITY, SurroundExtrinsicAdjustment.fromJson(null));
        assertEquals(SurroundExtrinsicAdjustment.IDENTITY, SurroundExtrinsicAdjustment.fromJson(""));
        assertEquals(SurroundExtrinsicAdjustment.IDENTITY, SurroundExtrinsicAdjustment.fromJson("{broken"));

        SurroundExtrinsicAdjustment adj = SurroundExtrinsicAdjustment.IDENTITY
                .withCamera("front", new CameraOffset(6.5f, -1.5f, 1.0f, 4, 25, -8))
                .withCamera("back", new CameraOffset(-7.0f, 2.0f, -1.0f, -6, -35, -20));
        assertEquals(2, adj.countNonZeroCameras());
        String serialized = adj.toJson();
        assertEquals(adj, SurroundExtrinsicAdjustment.fromJson(serialized));

        SurroundCalibrationProfile base = SurroundCalibrationManager.createBuiltInE5Profile(206, 530, 0);
        SurroundCalibrationProfile tuned = adj.applyToProfile(base);
        String exportedJson = SurroundCalibrationManager.exportCalibrationJson(tuned);
        SurroundCalibrationProfile reparsed =
                SurroundCalibrationManager.parseCalibrationJson(exportedJson, 206, 530, 0);

        for (String key : new String[]{"front", "back", "left", "right"}) {
            CameraCalibration expected = tuned.getCamera(key);
            CameraCalibration actual = reparsed.getCamera(key);
            assertNotNull(expected);
            assertNotNull(actual);
            assertEquals(expected.posXCm, actual.posXCm, 1e-3);
            assertEquals(expected.posYCm, actual.posYCm, 1e-3);
            assertEquals(expected.posZCm, actual.posZCm, 1e-3);
            for (int i = 0; i < 3; i++) {
                assertEquals(expected.r0[i], actual.r0[i], 1e-5);
                assertEquals(expected.r1[i], actual.r1[i], 1e-5);
                assertEquals(expected.r2[i], actual.r2[i], 1e-5);
            }
        }
    }

    @Test
    public void extrinsicAdjustmentProceduralMeshGeneratesValidBirdGeometry() {
        SurroundCalibrationProfile base = SurroundCalibrationManager.createBuiltInE5Profile(206, 530, 0);
        SurroundExtrinsicAdjustment adj = SurroundExtrinsicAdjustment.IDENTITY
                .withCamera("front", new CameraOffset(5.0f, 0f, 0f, 0, 0, 0));
        SurroundCalibrationProfile tuned = adj.applyToProfile(base);
        assertTrue(tuned.hasExtrinsicAdjustments);
        assertTrue(tuned.useBuiltInMeshes);

        GridMeshData bird = SurroundMeshBuilder.buildBirdGroundMesh(tuned, 44, 68);
        assertEquals(45 * 69 * SurroundMeshBuilder.BIRD_FLOATS_PER_VERTEX, bird.vertices.length);
        assertEquals(44 * 68 * 6, bird.indices.length);
        for (float v : bird.vertices) {
            assertTrue(Float.isFinite(v));
        }
    }
}
