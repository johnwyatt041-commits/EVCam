package com.kooo.evcam.update;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * GitHub 更新安全主机与凭据防泄漏策略（纯 Java）。
 * 规则：
 * 1. 仅允许 HTTPS（单元测试允许显式本地 loopback http://127.0.0.1 或 http://localhost）；
 * 2. Authorization Bearer PAT 仅发送给配置的 GitHub API 主机（默认 api.github.com），
 *    302 重定向到对象存储 CDN（如 objects.githubusercontent.com）时绝不携带 PAT；
 * 3. 日志脱敏，避免 PAT 明文落盘。
 */
public final class GitHubHostPolicy {

    private GitHubHostPolicy() {
    }

    public static boolean isAllowedRequestUrl(String url, String configuredApiBaseUrl, boolean allowLoopbackHttp) {
        URI uri = parseUri(url);
        if (uri == null || uri.getScheme() == null || uri.getHost() == null) {
            return false;
        }
        String scheme = uri.getScheme().toLowerCase(Locale.US);
        String host = uri.getHost().toLowerCase(Locale.US);

        if ("http".equals(scheme)) {
            return allowLoopbackHttp && isLoopbackHost(host);
        }
        if (!"https".equals(scheme)) {
            return false;
        }
        return isTrustedGitHubOrCdnHost(host, configuredApiBaseUrl);
    }

    public static boolean shouldAttachPatHeader(String requestUrl, String configuredApiBaseUrl, boolean allowLoopbackHttp) {
        URI reqUri = parseUri(requestUrl);
        if (reqUri == null || reqUri.getHost() == null || reqUri.getScheme() == null) {
            return false;
        }
        String reqScheme = reqUri.getScheme().toLowerCase(Locale.US);
        String reqHost = reqUri.getHost().toLowerCase(Locale.US);
        if ("http".equals(reqScheme)) {
            if (!allowLoopbackHttp || !isLoopbackHost(reqHost)) {
                return false;
            }
            URI baseUri = parseUri(configuredApiBaseUrl);
            if (baseUri == null || baseUri.getHost() == null) {
                return false;
            }
            return reqHost.equals(baseUri.getHost().toLowerCase(Locale.US))
                    && effectivePort(reqUri) == effectivePort(baseUri);
        }
        if (!"https".equals(reqScheme)) {
            return false;
        }

        URI apiUri = parseUri(configuredApiBaseUrl);
        String apiHost = apiUri != null && apiUri.getHost() != null
                ? apiUri.getHost().toLowerCase(Locale.US)
                : "api.github.com";

        return reqHost.equals(apiHost)
                || "api.github.com".equals(reqHost)
                || "uploads.github.com".equals(reqHost);
    }

    public static boolean isTrustedGitHubOrCdnHost(String host, String configuredApiBaseUrl) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        String lower = host.toLowerCase(Locale.US);
        if ("api.github.com".equals(lower)
                || "github.com".equals(lower)
                || "uploads.github.com".equals(lower)
                || "codeload.github.com".equals(lower)
                || "objects.githubusercontent.com".equals(lower)
                || "release-assets.githubusercontent.com".equals(lower)
                || lower.endsWith(".githubusercontent.com")) {
            return true;
        }
        URI apiUri = parseUri(configuredApiBaseUrl);
        if (apiUri != null && apiUri.getHost() != null) {
            return lower.equals(apiUri.getHost().toLowerCase(Locale.US));
        }
        return false;
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() > 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static boolean isLoopbackHost(String host) {
        return "127.0.0.1".equals(host) || "localhost".equals(host) || "::1".equals(host);
    }

    private static URI parseUri(String url) {
        if (url == null || url.trim().isEmpty()) {
            return null;
        }
        try {
            return new URI(url.trim());
        } catch (URISyntaxException e) {
            return null;
        }
    }

    public static String redactSecret(String text, String secret) {
        if (text == null || secret == null || secret.isEmpty()) {
            return text;
        }
        return text.replace(secret, "***REDACTED***");
    }
}
