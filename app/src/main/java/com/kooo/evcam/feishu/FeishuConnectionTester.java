package com.kooo.evcam.feishu;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLException;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 飞书连接配置在线测试器。
 *
 * <p>使用独立的短超时 OkHttpClient 对飞书开放平台依次执行两步只读校验：
 * 1) {@code /open-apis/auth/v3/tenant_access_token/internal} 校验 App ID / App Secret；
 * 2) {@code /callback/ws/endpoint} 校验是否已添加机器人并开启长连接接收事件。</p>
 *
 * <p>测试过程不写入 {@link FeishuConfig} 缓存 Token，也不初始化后台服务或 VHAL 租约。</p>
 */
public class FeishuConnectionTester {

    public static final String TOKEN_URL = "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal";
    public static final String WS_ENDPOINT_URL = "https://open.feishu.cn/callback/ws/endpoint";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    public interface HttpPostTransport {
        @NonNull
        HttpResponse postJson(
                @NonNull String url,
                @NonNull Map<String, String> headers,
                @NonNull String jsonBody) throws IOException;

        default void cancelAll() {
        }
    }

    public static final class HttpResponse {
        public final int httpCode;
        @NonNull
        public final String body;

        public HttpResponse(int httpCode, @Nullable String body) {
            this.httpCode = httpCode;
            this.body = body != null ? body : "";
        }

        public boolean isSuccessful() {
            return httpCode >= 200 && httpCode < 300;
        }
    }

    public enum Status {
        SUCCESS,
        PARTIAL_WS_FAILED,
        FAILED
    }

    public static final class TestResult {
        @NonNull
        public final Status status;
        @NonNull
        public final String summary;
        @NonNull
        public final String detail;
        public final long elapsedMs;
        public final boolean tokenOk;
        public final boolean wsEndpointOk;
        public final int feishuErrorCode;

        public TestResult(
                @NonNull Status status,
                @NonNull String summary,
                @NonNull String detail,
                long elapsedMs,
                boolean tokenOk,
                boolean wsEndpointOk,
                int feishuErrorCode) {
            this.status = status;
            this.summary = summary;
            this.detail = detail;
            this.elapsedMs = elapsedMs;
            this.tokenOk = tokenOk;
            this.wsEndpointOk = wsEndpointOk;
            this.feishuErrorCode = feishuErrorCode;
        }
    }

    private final HttpPostTransport transport;
    private final Gson gson = new Gson();
    private volatile boolean cancelled = false;

    public FeishuConnectionTester() {
        this(createDefaultTransport());
    }

    public FeishuConnectionTester(@NonNull HttpPostTransport transport) {
        this.transport = transport;
    }

    private static HttpPostTransport createDefaultTransport() {
        final OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
        return new HttpPostTransport() {
            @NonNull
            @Override
            public HttpResponse postJson(
                    @NonNull String url,
                    @NonNull Map<String, String> headers,
                    @NonNull String jsonBody) throws IOException {
                Request.Builder builder = new Request.Builder()
                        .url(url)
                        .post(RequestBody.create(JSON_MEDIA_TYPE, jsonBody));
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    builder.header(entry.getKey(), entry.getValue());
                }
                try (Response response = client.newCall(builder.build()).execute()) {
                    String bodyStr = response.body() != null ? response.body().string() : "";
                    return new HttpResponse(response.code(), bodyStr);
                }
            }

            @Override
            public void cancelAll() {
                client.dispatcher().cancelAll();
            }
        };
    }

    public void cancel() {
        cancelled = true;
        transport.cancelAll();
    }

    public boolean isCancelled() {
        return cancelled;
    }

    @NonNull
    public TestResult test(
            @Nullable String rawAppId,
            @Nullable String rawAppSecret,
            @Nullable String rawAllowedUserIds) {
        long startNs = System.nanoTime();
        String appId = rawAppId != null ? rawAppId.trim() : "";
        String appSecret = rawAppSecret != null ? rawAppSecret.trim() : "";
        String allowedUserIds = rawAllowedUserIds != null ? rawAllowedUserIds.trim() : "";

        if (appId.isEmpty()) {
            return new TestResult(
                    Status.FAILED,
                    "请先填写 App ID",
                    "✗ App ID 不能为空，请填写飞书自建应用的 App ID 后再测试。",
                    elapsedMs(startNs),
                    false,
                    false,
                    -1);
        }
        if (appSecret.isEmpty()) {
            return new TestResult(
                    Status.FAILED,
                    "请先填写 App Secret",
                    "✗ App Secret 不能为空，请填写飞书自建应用的 App Secret 后再测试。",
                    elapsedMs(startNs),
                    false,
                    false,
                    -1);
        }

        String whitelistHint = buildWhitelistNotice(allowedUserIds);

        // 第 1 步：校验 App ID / App Secret 获取 tenant_access_token
        JsonObject tokenReq = new JsonObject();
        tokenReq.addProperty("app_id", appId);
        tokenReq.addProperty("app_secret", appSecret);

        HttpResponse tokenResp;
        try {
            tokenResp = transport.postJson(TOKEN_URL, Collections.emptyMap(), gson.toJson(tokenReq));
        } catch (IOException e) {
            return new TestResult(
                    Status.FAILED,
                    "无法连接飞书服务器",
                    "✗ 第 1 步（凭据校验）网络请求失败：" + formatNetworkError(e),
                    elapsedMs(startNs),
                    false,
                    false,
                    -1);
        }

        JsonObject tokenJson = parseJsonSafely(tokenResp.body);
        if (tokenJson == null) {
            return new TestResult(
                    Status.FAILED,
                    "飞书返回格式异常",
                    "✗ 第 1 步（凭据校验）返回了无法解析的响应（HTTP " + tokenResp.httpCode + "）。",
                    elapsedMs(startNs),
                    false,
                    false,
                    -1);
        }

        int tokenCode = readInt(tokenJson, "code", -1);
        String tokenMsg = readString(tokenJson, "msg", "");
        String tenantToken = readString(tokenJson, "tenant_access_token", "");
        if (!tokenResp.isSuccessful() || tokenCode != 0 || tenantToken.isEmpty()) {
            String hint = describeTokenErrorHint(tokenCode);
            StringBuilder detail = new StringBuilder();
            detail.append("✗ 第 1 步（凭据校验）失败");
            if (tokenCode != -1) {
                detail.append("：code=").append(tokenCode);
                if (!tokenMsg.isEmpty()) {
                    detail.append("，msg=").append(tokenMsg);
                }
            } else {
                detail.append("：HTTP ").append(tokenResp.httpCode);
            }
            if (!hint.isEmpty()) {
                detail.append("\n排查建议：").append(hint);
            }
            if (!whitelistHint.isEmpty()) {
                detail.append("\n").append(whitelistHint);
            }
            return new TestResult(
                    Status.FAILED,
                    "App ID 或 App Secret 校验失败",
                    detail.toString(),
                    elapsedMs(startNs),
                    false,
                    false,
                    tokenCode);
        }

        // 第 2 步：校验长连接 WebSocket Endpoint（需在飞书后台开启机器人 + 长连接并发布）
        JsonObject wsReq = new JsonObject();
        wsReq.addProperty("AppID", appId);
        wsReq.addProperty("AppSecret", appSecret);

        HttpResponse wsResp;
        try {
            wsResp = transport.postJson(
                    WS_ENDPOINT_URL,
                    Collections.singletonMap("locale", "zh"),
                    gson.toJson(wsReq));
        } catch (IOException e) {
            return new TestResult(
                    Status.PARTIAL_WS_FAILED,
                    "凭据通过，但长连接地址获取失败",
                    "✓ 1. App ID / App Secret 凭据校验通过\n"
                            + "✗ 2. 获取长连接地址网络失败：" + formatNetworkError(e),
                    elapsedMs(startNs),
                    true,
                    false,
                    -1);
        }

        JsonObject wsJson = parseJsonSafely(wsResp.body);
        if (wsJson == null) {
            return new TestResult(
                    Status.PARTIAL_WS_FAILED,
                    "凭据通过，长连接接口返回异常",
                    "✓ 1. App ID / App Secret 凭据校验通过\n"
                            + "✗ 2. 获取长连接地址响应格式异常（HTTP " + wsResp.httpCode + "）。",
                    elapsedMs(startNs),
                    true,
                    false,
                    -1);
        }

        int wsCode = readInt(wsJson, "code", -1);
        String wsMsg = readString(wsJson, "msg", "");
        String wsUrl = "";
        if (wsJson.has("data") && wsJson.get("data").isJsonObject()) {
            wsUrl = readString(wsJson.getAsJsonObject("data"), "URL", "");
        }

        if (!wsResp.isSuccessful() || wsCode != 0 || wsUrl.isEmpty()) {
            StringBuilder detail = new StringBuilder();
            detail.append("✓ 1. App ID / App Secret 凭据校验通过\n");
            detail.append("✗ 2. 长连接通道校验失败");
            if (wsCode != -1) {
                detail.append("（code=").append(wsCode);
                if (!wsMsg.isEmpty()) {
                    detail.append("，msg=").append(wsMsg);
                }
                detail.append("）");
            } else {
                detail.append("（HTTP ").append(wsResp.httpCode).append("）");
            }
            detail.append("\n排查建议：请在飞书开放平台确认已启用「机器人」能力、在「事件与回调」选择「使用长连接接收事件」并发布最新版本应用。");
            if (!whitelistHint.isEmpty()) {
                detail.append("\n").append(whitelistHint);
            }
            return new TestResult(
                    Status.PARTIAL_WS_FAILED,
                    "凭据正确，但长连接未就绪",
                    detail.toString(),
                    elapsedMs(startNs),
                    true,
                    false,
                    wsCode);
        }

        long elapsed = elapsedMs(startNs);
        StringBuilder detail = new StringBuilder();
        detail.append("✓ 1. App ID / App Secret 凭据校验通过\n");
        detail.append("✓ 2. 长连接通道就绪（可获取 WebSocket 连接地址）\n");
        detail.append("总耗时：").append(elapsed).append(" ms");
        if (!whitelistHint.isEmpty()) {
            detail.append("\n").append(whitelistHint);
        }
        return new TestResult(
                Status.SUCCESS,
                "连接测试成功（" + elapsed + " ms）",
                detail.toString(),
                elapsed,
                true,
                true,
                0);
    }

    private static long elapsedMs(long startNs) {
        return Math.max(1L, (System.nanoTime() - startNs) / 1_000_000L);
    }

    @NonNull
    private static String buildWhitelistNotice(@NonNull String allowedUserIds) {
        if (allowedUserIds.isEmpty()) {
            return "白名单：未限制（所有有权发送消息的用户均可控车）";
        }
        if (allowedUserIds.contains("，")) {
            return "⚠ 注意：允许的用户 ID 中包含中文逗号「，」，可能导致匹配失败，请改用英文逗号「,」分隔。";
        }
        String[] parts = allowedUserIds.split(",");
        int count = 0;
        for (String p : parts) {
            if (!p.trim().isEmpty()) {
                count++;
            }
        }
        return "白名单：已配置 " + count + " 个允许的用户 ID";
    }

    @Nullable
    private static JsonObject parseJsonSafely(@Nullable String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            JsonElement element = JsonParser.parseString(raw);
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int readInt(@NonNull JsonObject obj, @NonNull String key, int fallback) {
        try {
            if (obj.has(key) && !obj.get(key).isJsonNull()) {
                return obj.get(key).getAsInt();
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    @NonNull
    private static String readString(@NonNull JsonObject obj, @NonNull String key, @NonNull String fallback) {
        try {
            if (obj.has(key) && !obj.get(key).isJsonNull()) {
                String v = obj.get(key).getAsString();
                return v != null ? v : fallback;
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    @NonNull
    private static String describeTokenErrorHint(int code) {
        switch (code) {
            case 10002:
                return "应用不存在或已被停用，请检查飞书后台应用状态。";
            case 10003:
                return "请求参数或 App ID 无效，请核对 App ID（通常以 cli_ 开头）。";
            case 10014:
                return "App Secret 错误或已在后台重置，请重新复制粘贴。";
            case 20001:
            case 99991663:
            case 99991664:
                return "应用认证凭据无效，请确认使用的是企业自建应用的 App ID 与 App Secret。";
            default:
                return "请核对 App ID 与 App Secret 是否与飞书开放平台一致且无多余空格。";
        }
    }

    @NonNull
    private static String formatNetworkError(@NonNull IOException e) {
        if (e instanceof UnknownHostException) {
            return "无法解析 open.feishu.cn，请检查车机网络连接或 DNS 设置";
        }
        if (e instanceof SocketTimeoutException) {
            return "请求飞书服务器超时，请检查当前网络信号";
        }
        if (e instanceof SSLException) {
            return "HTTPS 安全握手失败，请检查系统日期时间或网络代理";
        }
        String msg = e.getMessage();
        return (msg != null && !msg.trim().isEmpty()) ? msg : e.getClass().getSimpleName();
    }
}
