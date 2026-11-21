package com.kooo.evcam.transfer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.StorageHelper;
import com.kooo.evcam.playback.VideoGroup;
import com.kooo.evcam.playback.VideoLockStore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import fi.iki.elonen.NanoHTTPD;

/**
 * Local LAN HTTP server for browser-based playback and download.
 * Provides session token prefixing, canonical path containment, RFC 9110 single-range streaming
 * (HTTP 206 / 416), bounded worker threads, throttled thumbnail/metadata extraction, and
 * idle stream watchdog tracking.
 */
public class FileTransferServer extends NanoHTTPD {
    private static final String TAG = "FileTransferServer";
    private static final int MAX_THUMB_EDGE_PX = 320;
    private static final int THUMB_JPEG_QUALITY = 75;
    private static final int LIBRARY_SCAN_MAX_DEPTH = 4;
    private static final Pattern SAFE_KEY_PATTERN = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern LIB_ID_PATTERN = Pattern.compile("^lib_(\\d+)_([A-Za-z0-9_-]+)$");
    private static final Pattern TIMESTAMP_PREFIX_PATTERN = Pattern.compile("^(\\d{8}_\\d{6})");

    public enum ShareMode {
        SELECTED_FILES,
        LIBRARY
    }

    public interface TransferCallback {
        void onServerStarted(String ipAddress, int port, String qrUrl);
        void onServerStopped();
        void onFileRequested(String fileName);
        void onFileTransferred(String fileName, boolean success);
        void onError(String error);
    }

    private final Context context;
    private final ShareMode shareMode;
    private final LanIdleWatchdog watchdog;
    private final BoundedAsyncRunner asyncRunner;
    private final Semaphore mediaProbeSemaphore = new Semaphore(2);
    private final Map<String, File> pendingFiles = new ConcurrentHashMap<>();
    private final List<String> pendingOrder = Collections.synchronizedList(new ArrayList<>());

    @Nullable
    private volatile List<File> customLibraryRoots;
    @Nullable
    private volatile TransferCallback callback;
    private volatile int currentPort;
    @Nullable
    private volatile String currentIp;
    private volatile String sessionToken;
    private volatile boolean tokenRequired = true;
    private volatile String activeRootStorageHint = "就绪";

    public FileTransferServer(Context context) {
        this(context, null, 0, ShareMode.SELECTED_FILES, new LanIdleWatchdog());
    }

    public FileTransferServer(Context context,
                              @Nullable String bindHostname,
                              int port,
                              ShareMode shareMode) {
        this(context, bindHostname, port, shareMode, new LanIdleWatchdog());
    }

    public FileTransferServer(Context context,
                              @Nullable String bindHostname,
                              int port,
                              ShareMode shareMode,
                              LanIdleWatchdog watchdog) {
        super(bindHostname, port);
        this.context = context.getApplicationContext();
        this.shareMode = shareMode != null ? shareMode : ShareMode.SELECTED_FILES;
        this.watchdog = watchdog != null ? watchdog : new LanIdleWatchdog();
        this.currentIp = (bindHostname != null && !bindHostname.isEmpty() && !"0.0.0.0".equals(bindHostname))
                ? bindHostname : null;
        this.sessionToken = generateRandomToken();
        this.asyncRunner = new BoundedAsyncRunner();
        setAsyncRunner(this.asyncRunner);
    }

    public void setCallback(@Nullable TransferCallback callback) {
        this.callback = callback;
    }

    public void setSpecifiedIpAddress(@Nullable String ipAddress) {
        if (ipAddress != null && !ipAddress.trim().isEmpty()) {
            this.currentIp = ipAddress.trim();
        }
    }

    public void setSessionToken(@Nullable String token) {
        if (token != null && SAFE_KEY_PATTERN.matcher(token).matches()) {
            this.sessionToken = token;
        }
    }

    public void setTokenRequired(boolean tokenRequired) {
        this.tokenRequired = tokenRequired;
    }

    public void setAuthorizedLibraryRoots(@Nullable List<File> roots) {
        if (roots == null) {
            this.customLibraryRoots = null;
        } else {
            this.customLibraryRoots = Collections.unmodifiableList(new ArrayList<>(roots));
        }
    }

    public LanIdleWatchdog getWatchdog() {
        return watchdog;
    }

    public ShareMode getShareMode() {
        return shareMode;
    }

    public String getSessionToken() {
        return sessionToken;
    }

    public String getShareBasePath() {
        return tokenRequired ? "/" + sessionToken : "";
    }

    @Nullable
    public String getShareUrl() {
        if (currentIp == null || currentIp.isEmpty() || currentPort <= 0) {
            return null;
        }
        String prefix = tokenRequired ? "/" + sessionToken + "/" : "/";
        return "http://" + currentIp + ":" + currentPort + prefix;
    }

    @Override
    public void start() throws IOException {
        super.start(SOCKET_READ_TIMEOUT, false);
        watchdog.reset();
        currentPort = getListeningPort();
        if (currentIp == null || currentIp.isEmpty()) {
            List<LanNetworkSelector.NetworkEndpoint> endpoints = LanNetworkSelector.enumerateLocalEndpoints(context);
            if (!endpoints.isEmpty()) {
                currentIp = endpoints.get(0).ip;
            }
        }
        String shareUrl = getShareUrl();
        AppLog.i(TAG, "LAN server started: url=" + shareUrl + ", mode=" + shareMode);

        if (currentIp == null || currentIp.isEmpty()) {
            if (callback != null) {
                callback.onError("未检测到可用的局域网或热点网络");
            }
            return;
        }

        if (callback != null && shareUrl != null) {
            callback.onServerStarted(currentIp, currentPort, shareUrl);
        }
    }

    @Override
    public void stop() {
        super.stop();
        AppLog.i(TAG, "LAN server stopped");
        if (callback != null) {
            callback.onServerStopped();
        }
    }

    @Override
    protected boolean useGzipWhenAccepted(Response r) {
        if (r == null || r.getStatus() == Response.Status.PARTIAL_CONTENT) {
            return false;
        }
        String mime = r.getMimeType();
        return mime != null && (mime.startsWith("text/") || mime.contains("json"));
    }

    @Override
    public Response serve(IHTTPSession session) {
        String rawUri = session.getUri() != null ? session.getUri() : "/";
        Method method = session.getMethod();

        if (Method.OPTIONS.equals(method)) {
            Response response = newFixedLengthResponse(Response.Status.NO_CONTENT, MIME_PLAINTEXT, "");
            addCorsHeaders(response);
            return response;
        }

        if ("/favicon.ico".equals(rawUri)) {
            return newFixedLengthResponse(Response.Status.NO_CONTENT, MIME_PLAINTEXT, "");
        }

        String route = stripSessionTokenPrefix(rawUri);
        if (route == null) {
            return notFound();
        }

        watchdog.touchActivity();

        try {
            if (route.isEmpty() || "/".equals(route) || "/index.html".equals(route)) {
                Response resp = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8",
                        LanWebAssetHtml.buildIndexPage(getShareBasePath(), shareMode == ShareMode.LIBRARY));
                resp.addHeader("Cache-Control", "no-store");
                addCorsHeaders(resp);
                return resp;
            }

            if ("/api/status".equals(route)) {
                return serveStatusJson();
            }

            if ("/api/files".equals(route)) {
                return serveFilesJson(session.getParms());
            }

            if (route.startsWith("/info/")) {
                return serveMediaInfo(decodeSegment(route.substring("/info/".length())));
            }

            if (route.startsWith("/t/")) {
                return serveThumbnail(decodeSegment(route.substring("/t/".length())));
            }

            if (route.startsWith("/p/")) {
                return servePlayerPage(decodeSegment(route.substring("/p/".length())));
            }

            if (route.startsWith("/m/")) {
                return serveMediaRange(session, decodeSegment(route.substring("/m/".length())), false);
            }

            if (route.startsWith("/d/")) {
                return serveMediaRange(session, decodeSegment(route.substring("/d/".length())), true);
            }

            if (route.startsWith("/download/")) {
                return serveMediaRange(session, decodeSegment(route.substring("/download/".length())), true);
            }

            return notFound();
        } catch (Exception e) {
            AppLog.e(TAG, "Error serving LAN request " + route, e);
            Response response = newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT,
                    "Internal Server Error");
            addCorsHeaders(response);
            return response;
        }
    }

    @Nullable
    private String stripSessionTokenPrefix(String rawUri) {
        if (!tokenRequired) {
            return rawUri;
        }
        String expectedPrefix = "/" + sessionToken;
        if (expectedPrefix.equals(rawUri)) {
            return "/";
        }
        if (rawUri.startsWith(expectedPrefix + "/")) {
            return rawUri.substring(expectedPrefix.length());
        }
        return null;
    }

    @Nullable
    private static String decodeSegment(String segment) {
        if (segment == null || segment.isEmpty()) {
            return null;
        }
        try {
            return java.net.URLDecoder.decode(segment, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return null;
        }
    }

    private Response serveStatusJson() {
        List<MediaItem> items = collectAuthorizedMediaItems();
        int lockedCount = 0;
        long totalBytes = 0L;
        for (MediaItem item : items) {
            if (item.locked) {
                lockedCount++;
            }
            totalBytes += item.file.length();
        }

        String storageSummary = computeStorageSummary();
        StringBuilder json = new StringBuilder();
        json.append('{');
        json.append("\"mode\":\"").append(shareMode.name().toLowerCase(Locale.US)).append("\",");
        json.append("\"totalCount\":").append(items.size()).append(',');
        json.append("\"lockedCount\":").append(lockedCount).append(',');
        json.append("\"totalBytes\":").append(totalBytes).append(',');
        json.append("\"activeStreams\":").append(watchdog.getActiveStreamCount()).append(',');
        json.append("\"idleRemainingSec\":").append(watchdog.getRemainingIdleSeconds()).append(',');
        json.append("\"storageSummary\":\"").append(escapeJson(storageSummary)).append("\"");
        json.append('}');

        Response resp = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json.toString());
        resp.addHeader("Cache-Control", "no-store");
        addCorsHeaders(resp);
        return resp;
    }

    private String computeStorageSummary() {
        if (shareMode == ShareMode.SELECTED_FILES) {
            return activeRootStorageHint;
        }
        List<File> roots = resolveLibraryRoots();
        if (roots.isEmpty()) {
            return "未检测到存储目录";
        }
        long avail = StorageHelper.getAvailableSpace(roots.get(0));
        long total = StorageHelper.getTotalSpace(roots.get(0));
        if (avail >= 0 && total > 0) {
            return "可用 " + StorageHelper.formatSize(avail) + " / " + StorageHelper.formatSize(total);
        }
        return "媒体库就绪";
    }

    private Response serveFilesJson(Map<String, String> parms) {
        String filter = parms != null ? parms.get("filter") : null;
        String todayStr = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());

        List<MediaItem> items = collectAuthorizedMediaItems();
        StringBuilder json = new StringBuilder();
        json.append('[');
        boolean first = true;
        for (MediaItem item : items) {
            if ("today".equals(filter) && !todayStr.equals(item.date)) {
                continue;
            }
            if ("locked".equals(filter) && !item.locked) {
                continue;
            }
            if ("video".equals(filter) && !item.isVideo) {
                continue;
            }
            if ("photo".equals(filter) && item.isVideo) {
                continue;
            }
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('{');
            json.append("\"id\":\"").append(escapeJson(item.id)).append("\",");
            json.append("\"name\":\"").append(escapeJson(item.file.getName())).append("\",");
            json.append("\"mediaType\":\"").append(item.isVideo ? "video" : "photo").append("\",");
            json.append("\"camera\":\"").append(escapeJson(item.camera)).append("\",");
            json.append("\"cameraLabel\":\"").append(escapeJson(item.cameraLabel)).append("\",");
            json.append("\"date\":\"").append(escapeJson(item.date)).append("\",");
            json.append("\"time\":\"").append(escapeJson(item.time)).append("\",");
            json.append("\"size\":").append(item.file.length()).append(',');
            json.append("\"sizeFormatted\":\"").append(escapeJson(formatFileSize(item.file.length()))).append("\",");
            json.append("\"locked\":").append(item.locked).append(',');
            json.append("\"thumbUrl\":\"").append(escapeJson("/t/" + item.id)).append("\",");
            json.append("\"infoUrl\":\"").append(escapeJson("/info/" + item.id)).append("\",");
            json.append("\"pageUrl\":\"").append(escapeJson("/p/" + item.id)).append("\",");
            json.append("\"playUrl\":\"").append(escapeJson("/m/" + item.id)).append("\",");
            json.append("\"downloadUrl\":\"").append(escapeJson("/d/" + item.id)).append("\"");
            json.append('}');
        }
        json.append(']');

        Response resp = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json.toString());
        resp.addHeader("Cache-Control", "no-store");
        addCorsHeaders(resp);
        return resp;
    }

    private Response servePlayerPage(@Nullable String fileId) {
        MediaItem item = resolveAuthorizedMediaItem(fileId);
        if (item == null) {
            return notFound();
        }
        Response resp = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8",
                LanWebAssetHtml.buildVideoPlayerPage(getShareBasePath(), item.id, item.file.getName(), item.isVideo));
        resp.addHeader("Cache-Control", "no-store");
        addCorsHeaders(resp);
        return resp;
    }

    private Response serveMediaInfo(@Nullable String fileId) {
        MediaItem item = resolveAuthorizedMediaItem(fileId);
        if (item == null) {
            return notFound();
        }
        long durationMs = 0L;
        int width = 0;
        int height = 0;
        if (item.isVideo) {
            boolean acquired = false;
            MediaMetadataRetriever retriever = null;
            try {
                mediaProbeSemaphore.acquire();
                acquired = true;
                retriever = new MediaMetadataRetriever();
                retriever.setDataSource(item.file.getAbsolutePath());
                String dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                String w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                String h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                if (dur != null) durationMs = Long.parseLong(dur);
                if (w != null) width = Integer.parseInt(w);
                if (h != null) height = Integer.parseInt(h);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable ignored) {
            } finally {
                if (retriever != null) {
                    try {
                        retriever.release();
                    } catch (Throwable ignored) {
                    }
                }
                if (acquired) {
                    mediaProbeSemaphore.release();
                }
            }
        }
        String json = "{\"durationMs\":" + durationMs + ",\"width\":" + width + ",\"height\":" + height + "}";
        Response resp = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json);
        resp.addHeader("Cache-Control", "max-age=60");
        addCorsHeaders(resp);
        return resp;
    }

    private Response serveThumbnail(@Nullable String fileId) {
        MediaItem item = resolveAuthorizedMediaItem(fileId);
        if (item == null) {
            return notFound();
        }
        byte[] jpegBytes = extractThumbnailJpeg(item);
        if (jpegBytes == null || jpegBytes.length == 0) {
            return notFound();
        }
        Response resp = newFixedLengthResponse(Response.Status.OK, "image/jpeg",
                new ByteArrayInputStream(jpegBytes), jpegBytes.length);
        resp.addHeader("Cache-Control", "max-age=60");
        addCorsHeaders(resp);
        return resp;
    }

    @Nullable
    private byte[] extractThumbnailJpeg(MediaItem item) {
        boolean acquired = false;
        Bitmap raw = null;
        Bitmap scaled = null;
        MediaMetadataRetriever retriever = null;
        try {
            mediaProbeSemaphore.acquire();
            acquired = true;
            if (item.isVideo) {
                retriever = new MediaMetadataRetriever();
                retriever.setDataSource(item.file.getAbsolutePath());
                raw = retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (raw == null) {
                    raw = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                }
            } else {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(item.file.getAbsolutePath(), bounds);
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, MAX_THUMB_EDGE_PX);
                raw = BitmapFactory.decodeFile(item.file.getAbsolutePath(), opts);
            }
            if (raw == null) {
                return null;
            }
            scaled = scaleToMaxEdge(raw, MAX_THUMB_EDGE_PX);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, THUMB_JPEG_QUALITY, baos);
            return baos.toByteArray();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Throwable t) {
            return null;
        } finally {
            if (scaled != null && scaled != raw) {
                scaled.recycle();
            }
            if (raw != null) {
                raw.recycle();
            }
            if (retriever != null) {
                try {
                    retriever.release();
                } catch (Throwable ignored) {
                }
            }
            if (acquired) {
                mediaProbeSemaphore.release();
            }
        }
    }

    private static int computeSampleSize(int width, int height, int maxEdge) {
        int sample = 1;
        int longest = Math.max(width, height);
        while (longest / (sample * 2) >= maxEdge) {
            sample *= 2;
        }
        return sample;
    }

    private static Bitmap scaleToMaxEdge(Bitmap src, int maxEdge) {
        int w = src.getWidth();
        int h = src.getHeight();
        int longest = Math.max(w, h);
        if (longest <= maxEdge || w <= 0 || h <= 0) {
            return src;
        }
        float ratio = (float) maxEdge / (float) longest;
        int dstW = Math.max(1, Math.round(w * ratio));
        int dstH = Math.max(1, Math.round(h * ratio));
        return Bitmap.createScaledBitmap(src, dstW, dstH, true);
    }

    private Response serveMediaRange(IHTTPSession session, @Nullable String fileId, boolean asAttachment) {
        MediaItem item = resolveAuthorizedMediaItem(fileId);
        if (item == null) {
            return notFound();
        }
        Method method = session.getMethod();
        if (!Method.GET.equals(method) && !Method.HEAD.equals(method)) {
            Response methodNotAllowed = newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Method Not Allowed");
            methodNotAllowed.addHeader("Allow", "GET, HEAD, OPTIONS");
            addCorsHeaders(methodNotAllowed);
            return methodNotAllowed;
        }

        File file = item.file;
        long totalLength = file.length();
        String rangeHeader = findHeaderIgnoreCase(session.getHeaders(), "range");
        HttpRangeParser.RangeSpec spec = HttpRangeParser.parse(rangeHeader, totalLength);

        if (spec.status == HttpRangeParser.Status.INVALID) {
            Response resp = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "");
            resp.addHeader("Content-Range", spec.toContentRangeHeader());
            resp.addHeader("Accept-Ranges", "bytes");
            resp.addHeader("Content-Length", "0");
            addCorsHeaders(resp);
            return resp;
        }

        String mimeType = getMimeType(file.getName());
        Response.Status status = spec.status == HttpRangeParser.Status.PARTIAL
                ? Response.Status.PARTIAL_CONTENT : Response.Status.OK;
        long sliceLength = spec.contentLength();

        if (Method.HEAD.equals(method)) {
            Response resp = newFixedLengthResponse(status, mimeType, null, 0L);
            applyMediaHeaders(resp, file.getName(), spec, sliceLength, asAttachment);
            return resp;
        }

        AppLog.d(TAG, "Serving LAN file=" + file.getName()
                + ", range=" + (rangeHeader != null ? rangeHeader : "full")
                + ", slice=" + spec.start + "-" + spec.end + "/" + totalLength);

        if (callback != null && (asAttachment || spec.start == 0L)) {
            callback.onFileRequested(file.getName());
        }

        try {
            BoundedRafInputStream stream = new BoundedRafInputStream(
                    file,
                    spec.start,
                    sliceLength,
                    new BoundedRafInputStream.StreamLifecycleObserver() {
                        @Override
                        public void onStreamOpened() {
                            watchdog.onStreamOpened();
                        }

                        @Override
                        public void onStreamActivity() {
                            watchdog.touchActivity();
                        }

                        @Override
                        public void onStreamClosed(boolean completedFully, long bytesServed) {
                            watchdog.onStreamClosed();
                            if (callback != null && (asAttachment || completedFully)) {
                                callback.onFileTransferred(file.getName(), completedFully);
                            }
                        }
                    });

            Response resp = newFixedLengthResponse(status, mimeType, stream, sliceLength);
            applyMediaHeaders(resp, file.getName(), spec, sliceLength, asAttachment);
            return resp;
        } catch (IOException e) {
            AppLog.e(TAG, "Failed opening LAN file stream: " + file.getName(), e);
            if (callback != null) {
                callback.onFileTransferred(file.getName(), false);
            }
            Response resp = newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "I/O Error");
            addCorsHeaders(resp);
            return resp;
        }
    }

    private void applyMediaHeaders(Response resp,
                                   String fileName,
                                   HttpRangeParser.RangeSpec spec,
                                   long sliceLength,
                                   boolean asAttachment) {
        resp.addHeader("Accept-Ranges", "bytes");
        resp.addHeader("Content-Length", String.valueOf(sliceLength));
        resp.addHeader("Cache-Control", "no-store");
        if (spec.status == HttpRangeParser.Status.PARTIAL) {
            resp.addHeader("Content-Range", spec.toContentRangeHeader());
        }
        if (asAttachment) {
            resp.addHeader("Content-Disposition", LanPathGuard.buildContentDispositionAttachment(fileName));
        }
        addCorsHeaders(resp);
    }

    private void addCorsHeaders(Response response) {
        response.addHeader("Access-Control-Allow-Origin", "*");
        response.addHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS");
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, Range");
        response.addHeader("Access-Control-Expose-Headers",
                "Content-Range, Content-Length, Accept-Ranges, Content-Disposition");
    }

    private Response notFound() {
        Response response = newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_HTML,
                "<html><body><h1>404 Not Found</h1></body></html>");
        addCorsHeaders(response);
        return response;
    }

    @Nullable
    private static String findHeaderIgnoreCase(@Nullable Map<String, String> headers, String targetKey) {
        if (headers == null) {
            return null;
        }
        String direct = headers.get(targetKey);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (targetKey.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    public void addFile(String fileId, File file) {
        if (fileId == null || !SAFE_KEY_PATTERN.matcher(fileId).matches() || fileId.contains("..")) {
            AppLog.w(TAG, "Rejected unsafe fileId registration: " + fileId);
            return;
        }
        if (file == null || !file.isFile() || !file.canRead() || !LanPathGuard.isAllowedMediaName(file.getName())) {
            return;
        }
        try {
            if (Files.isSymbolicLink(file.toPath())) {
                return;
            }
        } catch (Exception ignored) {
        }
        if (!pendingFiles.containsKey(fileId)) {
            pendingOrder.add(fileId);
        }
        pendingFiles.put(fileId, file);
    }

    public void removeFile(String fileId) {
        if (fileId != null) {
            pendingFiles.remove(fileId);
            pendingOrder.remove(fileId);
        }
    }

    public void clearFiles() {
        pendingFiles.clear();
        pendingOrder.clear();
    }

    public boolean isRunning() {
        return isAlive();
    }

    public int getPort() {
        return currentPort;
    }

    @Nullable
    public String getIp() {
        return currentIp;
    }

    private List<File> resolveLibraryRoots() {
        List<File> custom = customLibraryRoots;
        if (custom != null) {
            return custom;
        }
        Map<String, File> uniqueRoots = new LinkedHashMap<>();
        addRootIfValid(uniqueRoots, StorageHelper.getVideoDir(context, false));
        addRootIfValid(uniqueRoots, StorageHelper.getPhotoDir(context, false));
        if (StorageHelper.hasExternalSdCard(context)) {
            addRootIfValid(uniqueRoots, StorageHelper.getVideoDir(context, true));
            addRootIfValid(uniqueRoots, StorageHelper.getPhotoDir(context, true));
        }
        return new ArrayList<>(uniqueRoots.values());
    }

    private static void addRootIfValid(Map<String, File> map, @Nullable File dir) {
        if (dir == null || !dir.isDirectory() || !dir.canRead()) {
            return;
        }
        try {
            File canonical = dir.getCanonicalFile();
            map.putIfAbsent(canonical.getAbsolutePath(), canonical);
        } catch (IOException ignored) {
        }
    }

    @Nullable
    private MediaItem resolveAuthorizedMediaItem(@Nullable String fileId) {
        if (fileId == null || fileId.isEmpty()) {
            return null;
        }
        File selected = pendingFiles.get(fileId);
        if (selected != null && selected.isFile() && selected.canRead()
                && LanPathGuard.isAllowedMediaName(selected.getName())) {
            return MediaItem.fromFile(fileId, selected);
        }
        if (shareMode != ShareMode.LIBRARY) {
            return null;
        }
        Matcher matcher = LIB_ID_PATTERN.matcher(fileId);
        if (!matcher.matches()) {
            return null;
        }
        int rootIndex;
        try {
            rootIndex = Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
        List<File> roots = resolveLibraryRoots();
        if (rootIndex < 0 || rootIndex >= roots.size()) {
            return null;
        }
        String relPath;
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(matcher.group(2));
            relPath = new String(decoded, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
        File resolved = LanPathGuard.resolveAuthorizedMediaFile(roots.get(rootIndex), relPath);
        if (resolved == null) {
            return null;
        }
        return MediaItem.fromFile(fileId, resolved);
    }

    private List<MediaItem> collectAuthorizedMediaItems() {
        List<MediaItem> items = new ArrayList<>();
        if (shareMode == ShareMode.SELECTED_FILES) {
            List<String> keysSnapshot;
            synchronized (pendingOrder) {
                keysSnapshot = new ArrayList<>(pendingOrder);
            }
            for (String key : keysSnapshot) {
                File f = pendingFiles.get(key);
                if (f != null && f.isFile() && f.canRead() && LanPathGuard.isAllowedMediaName(f.getName())) {
                    items.add(MediaItem.fromFile(key, f));
                }
            }
            return items;
        }

        List<File> roots = resolveLibraryRoots();
        Set<String> seenPaths = new LinkedHashSet<>();
        for (int rootIndex = 0; rootIndex < roots.size(); rootIndex++) {
            File root = roots.get(rootIndex);
            scanLibraryDirectory(root, root, rootIndex, 0, seenPaths, items);
        }
        Collections.sort(items, Comparator
                .comparingLong((MediaItem m) -> m.sortTimestampMs)
                .reversed()
                .thenComparing(m -> m.file.getName()));
        return items;
    }

    private void scanLibraryDirectory(File root,
                                      File current,
                                      int rootIndex,
                                      int depth,
                                      Set<String> seenPaths,
                                      List<MediaItem> out) {
        if (depth > LIBRARY_SCAN_MAX_DEPTH || current == null || !current.isDirectory() || !current.canRead()) {
            return;
        }
        try {
            if (Files.isSymbolicLink(current.toPath())) {
                return;
            }
        } catch (Exception ignored) {
        }
        File[] children = current.listFiles();
        if (children == null) {
            return;
        }
        String rootPrefix = root.getPath().endsWith(File.separator)
                ? root.getPath() : root.getPath() + File.separator;

        for (File child : children) {
            if (child == null) {
                continue;
            }
            if (child.isDirectory()) {
                scanLibraryDirectory(root, child, rootIndex, depth + 1, seenPaths, out);
                continue;
            }
            if (!LanPathGuard.isAllowedMediaName(child.getName()) || !child.isFile() || child.length() <= 0L) {
                continue;
            }
            try {
                if (Files.isSymbolicLink(child.toPath())) {
                    continue;
                }
                File canonical = child.getCanonicalFile();
                if (!LanPathGuard.isSafeAuthorizedRegularFile(root, canonical)) {
                    continue;
                }
                if (!seenPaths.add(canonical.getAbsolutePath())) {
                    continue;
                }
                String abs = canonical.getPath();
                String rel = abs.startsWith(rootPrefix) ? abs.substring(rootPrefix.length()) : child.getName();
                String normalizedRel = rel.replace(File.separatorChar, '/');
                String encoded = Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(normalizedRel.getBytes(StandardCharsets.UTF_8));
                String fileId = "lib_" + rootIndex + "_" + encoded;
                out.add(MediaItem.fromFile(fileId, canonical));
            } catch (IOException ignored) {
            }
        }
    }

    private static String generateRandomToken() {
        byte[] bytes = new byte[8];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(16);
        for (byte b : bytes) {
            sb.append(String.format(Locale.US, "%02x", b & 0xFF));
        }
        return sb.toString();
    }

    private static String getMimeType(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".avi")) return "video/x-msvideo";
        if (lower.endsWith(".mov")) return "video/quicktime";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }

    private static String formatFileSize(long size) {
        if (size < 1024L) return size + " B";
        if (size < 1024L * 1024L) return String.format(Locale.US, "%.1f KB", size / 1024.0);
        if (size < 1024L * 1024L * 1024L) return String.format(Locale.US, "%.1f MB", size / (1024.0 * 1024.0));
        return String.format(Locale.US, "%.2f GB", size / (1024.0 * 1024.0 * 1024.0));
    }

    private static String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static final class MediaItem {
        final String id;
        final File file;
        final boolean isVideo;
        final boolean locked;
        final String camera;
        final String cameraLabel;
        final String date;
        final String time;
        final long sortTimestampMs;

        private MediaItem(String id, File file, boolean isVideo, boolean locked,
                          String camera, String cameraLabel, String date, String time, long sortTimestampMs) {
            this.id = id;
            this.file = file;
            this.isVideo = isVideo;
            this.locked = locked;
            this.camera = camera;
            this.cameraLabel = cameraLabel;
            this.date = date;
            this.time = time;
            this.sortTimestampMs = sortTimestampMs;
        }

        static MediaItem fromFile(String id, File file) {
            String name = file.getName();
            boolean isVideo = LanPathGuard.isVideoFileName(name);
            boolean locked = VideoLockStore.isLocked(file);
            String pos = VideoGroup.extractPosition(name);
            if (pos == null) {
                pos = inferPosition(name);
            }
            String camLabel = positionToLabel(pos);
            long tsMs = parseTimestampMs(name, file.lastModified());
            Date dateObj = new Date(tsMs);
            String date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(dateObj);
            String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(dateObj);
            return new MediaItem(id, file, isVideo, locked, pos, camLabel, date, time, tsMs);
        }

        private static long parseTimestampMs(String fileName, long fallbackMs) {
            Matcher m = TIMESTAMP_PREFIX_PATTERN.matcher(fileName);
            if (m.find()) {
                try {
                    Date parsed = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(m.group(1));
                    if (parsed != null) {
                        return parsed.getTime();
                    }
                } catch (Exception ignored) {
                }
            }
            return fallbackMs;
        }

        private static String inferPosition(String name) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.contains("_combined")) return VideoGroup.POSITION_COMBINED;
            if (lower.contains("_front")) return VideoGroup.POSITION_FRONT;
            if (lower.contains("_back")) return VideoGroup.POSITION_BACK;
            if (lower.contains("_left")) return VideoGroup.POSITION_LEFT;
            if (lower.contains("_right")) return VideoGroup.POSITION_RIGHT;
            return "media";
        }

        private static String positionToLabel(String pos) {
            switch (pos) {
                case VideoGroup.POSITION_FRONT: return "前";
                case VideoGroup.POSITION_BACK: return "后";
                case VideoGroup.POSITION_LEFT: return "左";
                case VideoGroup.POSITION_RIGHT: return "右";
                case VideoGroup.POSITION_COMBINED: return "合成";
                default: return "文件";
            }
        }
    }
}
