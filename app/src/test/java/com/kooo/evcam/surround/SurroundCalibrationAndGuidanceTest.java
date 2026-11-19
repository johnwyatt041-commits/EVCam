package com.kooo.evcam.surround;

import com.kooo.evcam.surround.SurroundCalibrationManager.ImportResult;
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
}
