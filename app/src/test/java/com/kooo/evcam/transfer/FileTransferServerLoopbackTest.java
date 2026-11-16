package com.kooo.evcam.transfer;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class FileTransferServerLoopbackTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private Context context;
    private FileTransferServer server;

    @Before
    public void setUp() {
        context = mock(Context.class);
        when(context.getApplicationContext()).thenReturn(context);
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    @Test
    public void selectedModeSupportsTokenPrefixRangeStreamingAndHead() throws Exception {
        File clip = temp.newFile("20261116_210000_combined.mp4");
        byte[] payload = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ".getBytes(StandardCharsets.UTF_8);
        Files.write(clip.toPath(), payload);

        AtomicLong fakeClock = new AtomicLong(10_000L);
        LanIdleWatchdog watchdog = new LanIdleWatchdog(fakeClock::get, 60_000L);
        server = new FileTransferServer(context, "127.0.0.1", 0,
                FileTransferServer.ShareMode.SELECTED_FILES, watchdog);
        server.setTokenRequired(true);
        server.addFile("clip_0", clip);
        server.start();

        String baseUrl = server.getShareUrl();
        assertNotNull(baseUrl);
        assertTrue(baseUrl.startsWith("http://127.0.0.1:"));

        // Unauthenticated path or legacy /api/browse must fail with 404
        assertEquals(404, requestStatus("http://127.0.0.1:" + server.getPort() + "/api/files", "GET", null));
        assertEquals(404, requestStatus(baseUrl + "api/browse?path=/etc", "GET", null));

        // /api/files returns registered entry
        String filesJson = readText(baseUrl + "api/files");
        assertTrue(filesJson.contains("20261116_210000_combined.mp4"));
        assertTrue(filesJson.contains("/m/clip_0"));

        // HEAD advertises Accept-Ranges and total Content-Length
        HttpURLConnection head = open(baseUrl + "m/clip_0", "HEAD", null);
        assertEquals(200, head.getResponseCode());
        assertEquals("bytes", head.getHeaderField("Accept-Ranges"));
        assertEquals(String.valueOf(payload.length), head.getHeaderField("Content-Length"));
        head.disconnect();

        // Single Range GET returns 206 Partial Content with exact slice
        HttpURLConnection partial = open(baseUrl + "m/clip_0", "GET", "bytes=10-15");
        assertEquals(206, partial.getResponseCode());
        assertEquals("bytes 10-15/" + payload.length, partial.getHeaderField("Content-Range"));
        assertEquals("6", partial.getHeaderField("Content-Length"));
        byte[] slice = readBytes(partial);
        assertArrayEquals("ABCDEF".getBytes(StandardCharsets.UTF_8), slice);
        partial.disconnect();

        // Out-of-range GET returns 416 Range Not Satisfiable
        HttpURLConnection invalidRange = open(baseUrl + "m/clip_0", "GET", "bytes=9999-");
        assertEquals(416, invalidRange.getResponseCode());
        assertEquals("bytes */" + payload.length, invalidRange.getHeaderField("Content-Range"));
        invalidRange.disconnect();

        // Download endpoint adds RFC 5987 Content-Disposition
        HttpURLConnection dl = open(baseUrl + "d/clip_0", "GET", null);
        assertEquals(200, dl.getResponseCode());
        String cd = dl.getHeaderField("Content-Disposition");
        assertNotNull(cd);
        assertTrue(cd.contains("attachment;"));
        assertArrayEquals(payload, readBytes(dl));
        dl.disconnect();

        assertEquals(0, watchdog.getActiveStreamCount());
    }

    @Test
    public void libraryModeExposesLockedTagsAndBlocksTraversal() throws Exception {
        File libRoot = temp.newFolder("EVCam_Video");
        File outside = temp.newFolder("Outside");
        File unlocked = new File(libRoot, "20261116_120000_front.mp4");
        File locked = new File(libRoot, "20261116_120500_front.mp4");
        File lockSidecar = new File(libRoot, "20261116_120500_front.mp4.lock");
        File pending = new File(libRoot, "20261116_121000_front.mp4.pending");
        File secret = new File(outside, "secret.mp4");

        Files.write(unlocked.toPath(), "videoA".getBytes(StandardCharsets.UTF_8));
        Files.write(locked.toPath(), "videoB".getBytes(StandardCharsets.UTF_8));
        Files.write(lockSidecar.toPath(), new byte[0]);
        Files.write(pending.toPath(), "writing".getBytes(StandardCharsets.UTF_8));
        Files.write(secret.toPath(), "secret".getBytes(StandardCharsets.UTF_8));

        server = new FileTransferServer(context, "127.0.0.1", 0, FileTransferServer.ShareMode.LIBRARY);
        server.setAuthorizedLibraryRoots(Collections.singletonList(libRoot));
        server.start();

        String baseUrl = server.getShareUrl();
        String lockedList = readText(baseUrl + "api/files?filter=locked");
        assertTrue(lockedList.contains("20261116_120500_front.mp4"));
        assertFalse(lockedList.contains("20261116_120000_front.mp4"));
        assertFalse(lockedList.contains(".pending"));

        // Crafted Base64Url path traversal ID targeting sibling directory must return 404
        String evilB64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("../Outside/secret.mp4".getBytes(StandardCharsets.UTF_8));
        assertEquals(404, requestStatus(baseUrl + "d/lib_0_" + evilB64, "GET", null));
    }

    private static HttpURLConnection open(String url, String method, String rangeHeader) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(3000);
        if (rangeHeader != null) {
            conn.setRequestProperty("Range", rangeHeader);
        }
        return conn;
    }

    private static int requestStatus(String url, String method, String rangeHeader) throws Exception {
        HttpURLConnection conn = open(url, method, rangeHeader);
        int code = conn.getResponseCode();
        conn.disconnect();
        return code;
    }

    private static String readText(String url) throws Exception {
        HttpURLConnection conn = open(url, "GET", null);
        assertEquals(200, conn.getResponseCode());
        byte[] bytes = readBytes(conn);
        conn.disconnect();
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(HttpURLConnection conn) throws Exception {
        try (InputStream is = conn.getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[1024];
            int n;
            while ((n = is.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }
}
