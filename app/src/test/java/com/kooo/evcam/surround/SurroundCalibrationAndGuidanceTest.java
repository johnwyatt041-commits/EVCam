package com.kooo.evcam.surround;

import com.kooo.evcam.surround.SurroundCalibrationManager.ImportResult;
import com.kooo.evcam.surround.SurroundGuidanceCalculator.LineStripBatch;
import com.kooo.evcam.surround.SurroundMeshBuilder.GridMeshData;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

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

        LineStripBatch straight = SurroundGuidanceCalculator.buildRoadGuidanceStrips(0f, true, 10);
        LineStripBatch rightTurn = SurroundGuidanceCalculator.buildRoadGuidanceStrips(25f, true, 10);
        assertEquals(5, straight.stripVertexCounts.length); // 2 rails + 3 distance bars
        assertEquals(straight.triangleStripVertices.length, rightTurn.triangleStripVertices.length);

        // 右转远端中点相对直行向右偏移
        int lastLeftIdx = 10 * 2 * SurroundGuidanceCalculator.FLOATS_PER_COLOR_VERTEX;
        assertTrue(rightTurn.triangleStripVertices[lastLeftIdx] > straight.triangleStripVertices[lastLeftIdx]);

        LineStripBatch bird = SurroundGuidanceCalculator.buildBirdGuidanceStrips(-20f, true, profile, 12);
        assertEquals(2, bird.stripVertexCounts.length);
        assertTrue(bird.triangleStripVertices.length > 0);

        byte[] validPngHeader = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        byte[] fakeJpgHeader = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0};
        assertTrue(SurroundCarModelRepository.isValidPngSignatureBytes(validPngHeader));
        assertFalse(SurroundCarModelRepository.isValidPngSignatureBytes(fakeJpgHeader));
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
}
