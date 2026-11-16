package com.kooo.evcam.update;

import android.content.SharedPreferences;

import fi.iki.elonen.NanoHTTPD;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Call;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class GitHubUpdateTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private SharedPreferences createPrefs(Map<String, Object> store) {
        SharedPreferences prefs = mock(SharedPreferences.class);
        SharedPreferences.Editor editor = mock(SharedPreferences.Editor.class);
        when(prefs.edit()).thenReturn(editor);
        when(prefs.getString(anyString(), nullable(String.class))).thenAnswer(inv -> {
            Object v = store.get(inv.getArgument(0));
            return v instanceof String ? (String) v : inv.getArgument(1);
        });
        when(prefs.getBoolean(anyString(), anyBoolean())).thenAnswer(inv -> {
            Object v = store.get(inv.getArgument(0));
            return v instanceof Boolean ? (Boolean) v : inv.getArgument(1);
        });
        when(editor.putString(anyString(), nullable(String.class))).thenAnswer(inv -> {
            store.put(inv.getArgument(0), inv.getArgument(1));
            return editor;
        });
        when(editor.putBoolean(anyString(), anyBoolean())).thenAnswer(inv -> {
            store.put(inv.getArgument(0), inv.getArgument(1));
            return editor;
        });
        when(editor.remove(anyString())).thenAnswer(inv -> {
            store.remove(inv.getArgument(0));
            return editor;
        });
        return prefs;
    }

    @Test
    public void versionComparatorHandlesGitHubTagsWithoutFalseUpdates() {
        assertTrue(AppVersionComparator.isValidVersionFormat("v1.6.6-test-11170004-build11"));
        assertTrue(AppVersionComparator.isValidVersionFormat("1.6.6"));
        assertFalse(AppVersionComparator.isValidVersionFormat("latest"));

        // 同一版本的 build11 Tag 不应反复判定为“有更新”
        assertFalse(AppVersionComparator.isNewerVersion("v1.6.6-test-11170004-build11", "1.6.6-test-11170004"));
        // 时间戳更高的 build12 应识别为新版本
        assertTrue(AppVersionComparator.isNewerVersion("v1.6.6-test-11170125-build12", "1.6.6-test-11170004"));
        // 双方均带 buildN 时应按 buildN 递增比较
        assertTrue(AppVersionComparator.isNewerVersion("v1.6.6-test-11170004-build12", "1.6.6-test-11170004-build11"));
        // 主版本升级
        assertTrue(AppVersionComparator.isNewerVersion("v1.7.0", "1.6.6-test-11170004"));
        assertFalse(AppVersionComparator.isNewerVersion("v1.6.5", "1.6.6-test-11170004"));
    }

    @Test
    public void releaseParserSelectsExactFlavorAndFiltersDraftOrPrerelease() throws Exception {
        String json = "["
                + "{\"tag_name\":\"v1.6.6-test-11170200-draft\",\"draft\":true,\"prerelease\":true,\"assets\":[]},"
                + "{\"tag_name\":\"v1.6.6-test-11170150-build12\",\"name\":\"build12\",\"body\":\"T10 notes\",\"draft\":false,\"prerelease\":true,"
                + "\"assets\":["
                + "{\"id\":101,\"name\":\"EVCam-E5-A-Independent-1.6.6-test-11170150-build12-release.apk\",\"url\":\"https://api.github.com/repos/a/b/releases/assets/101\",\"browser_download_url\":\"https://github.com/a/b/releases/download/v/a.apk\",\"size\":6800000,\"digest\":\"sha256:0000\"},"
                + "{\"id\":102,\"name\":\"EVCam-E5-B-Composite-1.6.6-test-11170150-build12-release.apk\",\"url\":\"https://api.github.com/repos/a/b/releases/assets/102\",\"browser_download_url\":\"https://github.com/a/b/releases/download/v/b.apk\",\"size\":6900000}"
                + "]},"
                + "{\"tag_name\":\"v1.6.5\",\"name\":\"stable\",\"draft\":false,\"prerelease\":false,\"assets\":[{\"id\":90,\"name\":\"EVCam-v1.6.5-release.apk\",\"url\":\"https://api.github.com/repos/a/b/releases/assets/90\",\"size\":6000000}]}"
                + "]";

        List<GitHubReleaseParser.ReleaseInfo> withPre = GitHubReleaseParser.parseReleases(json, true);
        assertEquals(2, withPre.size());
        List<GitHubReleaseParser.ReleaseInfo> stableOnly = GitHubReleaseParser.parseReleases(json, false);
        assertEquals(1, stableOnly.size());
        assertEquals("1.6.5", stableOnly.get(0).version);

        GitHubReleaseParser.UpdateCandidate ind = GitHubReleaseParser.findLatestUpdate(withPre, "1.6.6-test-11170004", false);
        assertNotNull(ind);
        assertEquals(101L, ind.asset.id);
        assertTrue(ind.asset.name.contains("Independent"));

        GitHubReleaseParser.UpdateCandidate comp = GitHubReleaseParser.findLatestUpdate(withPre, "1.6.6-test-11170004", true);
        assertNotNull(comp);
        assertEquals(102L, comp.asset.id);
        assertTrue(comp.asset.name.contains("Composite"));

        // 缺失对应风味时不可误装相反风味包
        List<GitHubReleaseParser.ReleaseAsset> onlyA = List.of(ind.asset);
        assertNull(GitHubReleaseParser.selectAssetForFlavor(onlyA, true));
    }

    @Test
    public void patStorageEncryptsSecretsIsolatedAndRefusesPlaintextFallback() throws Exception {
        Map<String, Object> configMap = new HashMap<>();
        Map<String, Object> secretMap = new HashMap<>();

        PatCipher xorTestCipher = new PatCipher() {
            @Override
            public String encrypt(String plaintext) {
                return "enc:v1:" + Base64.getEncoder().encodeToString(plaintext.getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public String decrypt(String encodedCiphertext) throws GeneralSecurityException {
                if (!encodedCiphertext.startsWith("enc:v1:")) {
                    throw new GeneralSecurityException("bad prefix");
                }
                return new String(Base64.getDecoder().decode(encodedCiphertext.substring(7)), StandardCharsets.UTF_8);
            }
        };

        SharedPreferences configPrefs = createPrefs(configMap);
        SharedPreferences secretPrefs = createPrefs(secretMap);
        GitHubUpdateConfig updateConfig = new GitHubUpdateConfig(configPrefs, secretPrefs, xorTestCipher);

        String pat = "github_pat_11AAA_secretToken9876";
        updateConfig.savePat(pat);

        assertFalse(configMap.toString().contains(pat));
        assertFalse(secretMap.toString().contains(pat));
        assertTrue(updateConfig.hasPat());
        assertEquals(pat, updateConfig.loadPat());
        assertEquals("gith****9876", updateConfig.getMaskedPatHint());
        assertTrue(updateConfig.saveRepoSlug("https://github.com/johnwyatt041-commits/EVCam.git"));
        assertEquals("johnwyatt041-commits/EVCam", updateConfig.getRepoSlug());
        assertFalse(updateConfig.saveRepoSlug("../invalid"));

        // Keystore 损坏时保存必须直接抛出安全异常，绝不保存明文
        PatCipher failingCipher = new PatCipher() {
            @Override
            public String encrypt(String plaintext) throws GeneralSecurityException {
                throw new GeneralSecurityException("KeyStore unavailable");
            }

            @Override
            public String decrypt(String encodedCiphertext) throws GeneralSecurityException {
                throw new GeneralSecurityException("KeyStore invalidated");
            }
        };
        Map<String, Object> failSecrets = new HashMap<>();
        GitHubUpdateConfig failingConfig = new GitHubUpdateConfig(configPrefs, createPrefs(failSecrets), failingCipher);
        try {
            failingConfig.savePat("ghp_mustNotSavePlaintext");
            fail("Expected GeneralSecurityException when cipher fails");
        } catch (GeneralSecurityException expected) {
            assertTrue(failSecrets.isEmpty());
        }

        // 密文损坏时读取应返回 null 并自动清除损坏条目
        secretMap.put("pat_enc_v1", "corrupted");
        assertNull(updateConfig.loadPat());
        assertFalse(updateConfig.hasPat());
    }

    @Test
    public void privateRepoDownloadStripsAuthorizationAcrossRedirectAndVerifiesSha256() throws Exception {
        byte[] fakeApk = new byte[128];
        fakeApk[0] = 0x50; // 'P'
        fakeApk[1] = 0x4B; // 'K'
        fakeApk[2] = 0x03;
        fakeApk[3] = 0x04;
        for (int i = 4; i < fakeApk.length; i++) {
            fakeApk[i] = (byte) (i * 31);
        }
        String sha256Hex = sha256(fakeApk);

        AtomicReference<String> apiAuthSeen = new AtomicReference<>();
        AtomicReference<String> assetAcceptSeen = new AtomicReference<>();
        AtomicReference<String> cdnAuthSeen = new AtomicReference<>("UNTOUCHED");

        NanoHTTPD cdnServer = new NanoHTTPD("127.0.0.1", 0) {
            @Override
            public Response serve(IHTTPSession session) {
                cdnAuthSeen.set(session.getHeaders().get("authorization"));
                return newFixedLengthResponse(Response.Status.OK,
                        "application/vnd.android.package-archive",
                        new ByteArrayInputStream(fakeApk),
                        fakeApk.length);
            }
        };
        cdnServer.start();
        int cdnPort = cdnServer.getListeningPort();

        final AtomicReference<String> apiBaseRef = new AtomicReference<>();
        NanoHTTPD apiServer = new NanoHTTPD("127.0.0.1", 0) {
            @Override
            public Response serve(IHTTPSession session) {
                String uri = session.getUri();
                if ("/repos/myorg/priv/releases".equals(uri)) {
                    apiAuthSeen.set(session.getHeaders().get("authorization"));
                    String apiBase = apiBaseRef.get();
                    String payload = "[{\"tag_name\":\"v1.6.6-test-11170200-build12\",\"draft\":false,\"prerelease\":true,"
                            + "\"assets\":[{\"id\":77,\"name\":\"EVCam-E5-A-Independent-1.6.6-test-11170200-build12-release.apk\","
                            + "\"url\":\"" + apiBase + "/repos/myorg/priv/releases/assets/77\","
                            + "\"browser_download_url\":\"http://invalid.example.com/404\","
                            + "\"size\":" + fakeApk.length + ",\"digest\":\"sha256:" + sha256Hex + "\"}]}]";
                    return newFixedLengthResponse(Response.Status.OK, "application/json", payload);
                }
                if ("/repos/myorg/priv/releases/assets/77".equals(uri)) {
                    assetAcceptSeen.set(session.getHeaders().get("accept"));
                    apiAuthSeen.set(session.getHeaders().get("authorization"));
                    Response redirect = newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "");
                    redirect.addHeader("Location", "http://127.0.0.1:" + cdnPort + "/cdn/app.apk");
                    return redirect;
                }
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found");
            }
        };
        apiServer.start();
        int apiPort = apiServer.getListeningPort();
        String apiBase = "http://127.0.0.1:" + apiPort;
        apiBaseRef.set(apiBase);

        try {
            GitHubReleaseClient client = new GitHubReleaseClient(true);
            String pat = "ghp_privateRepoTestPat123";
            List<GitHubReleaseParser.ReleaseInfo> releases =
                    client.fetchReleases(apiBase, "myorg", "priv", pat, true);
            assertEquals(1, releases.size());

            GitHubReleaseParser.UpdateCandidate candidate =
                    GitHubReleaseParser.findLatestUpdate(releases, "1.6.6-test-11170004", false);
            assertNotNull(candidate);

            File outFile = new File(tempFolder.getRoot(), "downloaded.apk");
            Call call = client.prepareDownloadAsset(candidate.asset, apiBase, pat);
            File downloaded = client.executeAssetDownload(candidate.asset, apiBase, pat, outFile, call, null);

            assertTrue(downloaded.exists());
            assertEquals(fakeApk.length, downloaded.length());
            assertEquals("Bearer " + pat, apiAuthSeen.get());
            assertEquals("application/octet-stream", assetAcceptSeen.get());
            // 关键验证：重定向到另一域名/端口时绝不泄露 PAT Authorization 头
            assertNull(cdnAuthSeen.get());

            // 错误 SHA-256 摘要必须拒绝写入目标 APK 且清理 .part 临时文件
            GitHubReleaseParser.ReleaseAsset badDigestAsset = new GitHubReleaseParser.ReleaseAsset(
                    77L, candidate.asset.name, candidate.asset.apiUrl, candidate.asset.browserDownloadUrl,
                    fakeApk.length, "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");
            File badOut = new File(tempFolder.getRoot(), "bad_digest.apk");
            Call badCall = client.prepareDownloadAsset(badDigestAsset, apiBase, pat);
            try {
                client.executeAssetDownload(badDigestAsset, apiBase, pat, badOut, badCall, null);
                fail("Expected IOException for SHA-256 mismatch");
            } catch (java.io.IOException expected) {
                assertFalse(badOut.exists());
                assertFalse(new File(tempFolder.getRoot(), "bad_digest.apk.part").exists());
            }

            assertTrue(GitHubReleaseClient.translateHttpError(401, true).contains("401"));
            assertTrue(GitHubReleaseClient.translateHttpError(404, false).contains("PAT"));
        } finally {
            apiServer.stop();
            cdnServer.stop();
        }
    }

    private static String sha256(byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(String.format(Locale.US, "%02x", b));
        }
        return sb.toString();
    }
}
