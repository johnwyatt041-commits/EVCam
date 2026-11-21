package com.kooo.evcam.feishu;

import org.junit.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.net.ssl.SSLException;

import static org.junit.Assert.*;

public class FeishuConnectionTesterTest {

    private static final class RecordedRequest {
        final String url;
        final Map<String, String> headers;
        final String body;

        RecordedRequest(String url, Map<String, String> headers, String body) {
            this.url = url;
            this.headers = headers;
            this.body = body;
        }
    }

    @Test
    public void emptyCredentialsFailLocallyWithoutHttpCalls() {
        List<RecordedRequest> requests = new ArrayList<>();
        FeishuConnectionTester tester = new FeishuConnectionTester((url, headers, body) -> {
            requests.add(new RecordedRequest(url, headers, body));
            return new FeishuConnectionTester.HttpResponse(200, "{}");
        });

        FeishuConnectionTester.TestResult r1 = tester.test("   ", "sec_123", "");
        assertEquals(FeishuConnectionTester.Status.FAILED, r1.status);
        assertFalse(r1.tokenOk);
        assertTrue(requests.isEmpty());

        FeishuConnectionTester.TestResult r2 = tester.test("cli_abc", "  ", "");
        assertEquals(FeishuConnectionTester.Status.FAILED, r2.status);
        assertFalse(r2.tokenOk);
        assertTrue(requests.isEmpty());
    }

    @Test
    public void bothTokenAndWebSocketSuccessReturnsSuccessAndChecksChineseCommaWarning() {
        List<RecordedRequest> requests = new ArrayList<>();
        FeishuConnectionTester tester = new FeishuConnectionTester((url, headers, body) -> {
            requests.add(new RecordedRequest(url, headers, body));
            if (url.equals(FeishuConnectionTester.TOKEN_URL)) {
                return new FeishuConnectionTester.HttpResponse(
                        200, "{\"code\":0,\"msg\":\"ok\",\"tenant_access_token\":\"t-xyz\",\"expire\":7200}");
            }
            if (url.equals(FeishuConnectionTester.WS_ENDPOINT_URL)) {
                return new FeishuConnectionTester.HttpResponse(
                        200, "{\"code\":0,\"msg\":\"ok\",\"data\":{\"URL\":\"wss://msg-frontier.feishu.cn/ws/v2?service_id=1\"}}");
            }
            return new FeishuConnectionTester.HttpResponse(404, "");
        });

        FeishuConnectionTester.TestResult res = tester.test(" cli_123 ", " sec_456 ", "ou_1，ou_2");
        assertEquals(FeishuConnectionTester.Status.SUCCESS, res.status);
        assertTrue(res.tokenOk);
        assertTrue(res.wsEndpointOk);
        assertEquals(0, res.feishuErrorCode);
        assertEquals(2, requests.size());
        assertTrue(requests.get(0).body.contains("\"app_id\":\"cli_123\""));
        assertTrue(requests.get(0).body.contains("\"app_secret\":\"sec_456\""));
        assertEquals("zh", requests.get(1).headers.get("locale"));
        assertTrue(requests.get(1).body.contains("\"AppID\":\"cli_123\""));
        assertTrue(requests.get(1).body.contains("\"AppSecret\":\"sec_456\""));
        assertTrue(res.detail.contains("中文逗号"));
    }

    @Test
    public void invalidSecretAtStep1StopsBeforeStep2AndShowsHint() {
        List<RecordedRequest> requests = new ArrayList<>();
        FeishuConnectionTester tester = new FeishuConnectionTester((url, headers, body) -> {
            requests.add(new RecordedRequest(url, headers, body));
            return new FeishuConnectionTester.HttpResponse(
                    400, "{\"code\":10014,\"msg\":\"app secret invalid\"}");
        });

        FeishuConnectionTester.TestResult res = tester.test("cli_123", "wrong_secret", "ou_1,ou_2");
        assertEquals(FeishuConnectionTester.Status.FAILED, res.status);
        assertFalse(res.tokenOk);
        assertFalse(res.wsEndpointOk);
        assertEquals(10014, res.feishuErrorCode);
        assertEquals(1, requests.size());
        assertTrue(res.detail.contains("10014"));
        assertTrue(res.detail.contains("App Secret"));
        assertFalse(res.detail.contains("wrong_secret"));
    }

    @Test
    public void tokenSuccessButWsEndpointFailureReturnsPartialResultWithGuidance() {
        List<RecordedRequest> requests = new ArrayList<>();
        FeishuConnectionTester tester = new FeishuConnectionTester((url, headers, body) -> {
            requests.add(new RecordedRequest(url, headers, body));
            if (url.equals(FeishuConnectionTester.TOKEN_URL)) {
                return new FeishuConnectionTester.HttpResponse(
                        200, "{\"code\":0,\"msg\":\"ok\",\"tenant_access_token\":\"t-xyz\",\"expire\":7200}");
            }
            return new FeishuConnectionTester.HttpResponse(
                    200, "{\"code\":1000040343,\"msg\":\"long connection not enabled\"}");
        });

        FeishuConnectionTester.TestResult res = tester.test("cli_123", "sec_456", "");
        assertEquals(FeishuConnectionTester.Status.PARTIAL_WS_FAILED, res.status);
        assertTrue(res.tokenOk);
        assertFalse(res.wsEndpointOk);
        assertEquals(1000040343, res.feishuErrorCode);
        assertEquals(2, requests.size());
        assertTrue(res.detail.contains("使用长连接接收事件"));
    }

    @Test
    public void networkAndMalformedJsonFailuresAreHandledCleanly() {
        FeishuConnectionTester dnsFail = new FeishuConnectionTester((url, headers, body) -> {
            throw new UnknownHostException("open.feishu.cn");
        });
        FeishuConnectionTester.TestResult rDns = dnsFail.test("cli_123", "sec_456", "");
        assertEquals(FeishuConnectionTester.Status.FAILED, rDns.status);
        assertTrue(rDns.detail.contains("open.feishu.cn"));

        FeishuConnectionTester timeoutFail = new FeishuConnectionTester((url, headers, body) -> {
            throw new SocketTimeoutException("timeout");
        });
        assertEquals(FeishuConnectionTester.Status.FAILED, timeoutFail.test("cli_123", "sec_456", "").status);

        FeishuConnectionTester sslFail = new FeishuConnectionTester((url, headers, body) -> {
            throw new SSLException("handshake failed");
        });
        assertEquals(FeishuConnectionTester.Status.FAILED, sslFail.test("cli_123", "sec_456", "").status);

        FeishuConnectionTester html500 = new FeishuConnectionTester((url, headers, body) ->
                new FeishuConnectionTester.HttpResponse(502, "<html>Bad Gateway</html>"));
        FeishuConnectionTester.TestResult rHtml = html500.test("cli_123", "sec_456", "");
        assertEquals(FeishuConnectionTester.Status.FAILED, rHtml.status);
        assertTrue(rHtml.detail.contains("502"));

        FeishuConnectionTester ioStep2 = new FeishuConnectionTester((url, headers, body) -> {
            if (url.equals(FeishuConnectionTester.TOKEN_URL)) {
                return new FeishuConnectionTester.HttpResponse(
                        200, "{\"code\":0,\"msg\":\"ok\",\"tenant_access_token\":\"t-xyz\",\"expire\":7200}");
            }
            throw new IOException("connection reset");
        });
        FeishuConnectionTester.TestResult rStep2 = ioStep2.test("cli_123", "sec_456", "");
        assertEquals(FeishuConnectionTester.Status.PARTIAL_WS_FAILED, rStep2.status);
        assertTrue(rStep2.tokenOk);
        assertFalse(rStep2.wsEndpointOk);
    }
}
