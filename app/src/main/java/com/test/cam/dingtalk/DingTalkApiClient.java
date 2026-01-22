package com.test.cam.dingtalk;

import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 钉钉 API 客户端
 * 负责与钉钉服务器进行 HTTP 通信
 */
public class DingTalkApiClient {
    private static final String TAG = "DingTalkApiClient";
    private static final String BASE_URL = "https://api.dingtalk.com";
    private static final String OAPI_URL = "https://oapi.dingtalk.com";

    private final OkHttpClient httpClient;
    private final Gson gson;
    private final DingTalkConfig config;

    public DingTalkApiClient(DingTalkConfig config) {
        this.config = config;
        this.gson = new Gson();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 获取 Access Token (新版 OAuth 2.0)
     */
    public String getAccessToken() throws IOException {
        // 检查缓存的 token 是否有效
        if (config.isTokenValid()) {
            String cachedToken = config.getAccessToken();
            Log.d(TAG, "使用缓存的 Access Token");
            return cachedToken;
        }

        // 获取新的 token
        String url = BASE_URL + "/v1.0/oauth2/accessToken";

        JsonObject body = new JsonObject();
        body.addProperty("clientId", config.getClientId());
        body.addProperty("clientSecret", config.getClientSecret());
        body.addProperty("grantType", "client_credentials");

        Log.d(TAG, "正在获取新的 Access Token...");

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(
                        MediaType.parse("application/json"),
                        gson.toJson(body)
                ))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body().string();
            Log.d(TAG, "Access Token 响应: " + responseBody);

            if (!response.isSuccessful()) {
                throw new IOException("获取 Access Token 失败: " + response.code() + " - " + responseBody);
            }

            JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);

            if (jsonResponse.has("accessToken")) {
                String accessToken = jsonResponse.get("accessToken").getAsString();
                long expireIn = jsonResponse.get("expireIn").getAsLong();

                // 提前 5 分钟过期
                long expireTime = System.currentTimeMillis() + (expireIn - 300) * 1000;
                config.saveAccessToken(accessToken, expireTime);

                Log.d(TAG, "Access Token 获取成功");
                return accessToken;
            } else {
                throw new IOException("响应中没有 accessToken: " + responseBody);
            }
        }
    }

    /**
     * 发送文本消息到群聊
     */
    public void sendTextMessage(String conversationId, String text) throws IOException {
        String accessToken = getAccessToken();
        String url = BASE_URL + "/v1.0/robot/oToMessages/batchSend";

        // 构建消息参数 - 正确的格式
        JsonObject msgParam = new JsonObject();
        msgParam.addProperty("content", text);

        JsonObject body = new JsonObject();
        body.addProperty("robotCode", config.getClientId());
        body.addProperty("userIds", "[]");  // 空数组表示发送给群聊
        body.addProperty("msgKey", "sampleText");
        body.addProperty("msgParam", gson.toJson(msgParam));

        // 如果有会话ID，添加到请求中
        if (conversationId != null && !conversationId.isEmpty()) {
            body.addProperty("openConversationId", conversationId);
        }

        String requestJson = gson.toJson(body);
        Log.d(TAG, "发送消息请求: " + requestJson);

        Request request = new Request.Builder()
                .url(url)
                .header("x-acs-dingtalk-access-token", accessToken)
                .post(RequestBody.create(
                        MediaType.parse("application/json"),
                        requestJson
                ))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                Log.e(TAG, "发送消息失败，响应: " + responseBody);
                throw new IOException("发送消息失败: " + response.code() + ", " + responseBody);
            }
            Log.d(TAG, "消息发送成功，响应: " + responseBody);
        }
    }

    /**
     * 上传文件到钉钉
     */
    public String uploadFile(File file) throws IOException {
        String accessToken = getAccessToken();
        String url = OAPI_URL + "/media/upload?access_token=" + accessToken + "&type=file";

        RequestBody fileBody = RequestBody.create(
                MediaType.parse("application/octet-stream"),
                file
        );

        MultipartBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("media", file.getName(), fileBody)
                .build();

        Request request = new Request.Builder()
                .url(url)
                .post(requestBody)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("上传文件失败: " + response.code());
            }

            String responseBody = response.body().string();
            JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);

            if (jsonResponse.has("media_id")) {
                String mediaId = jsonResponse.get("media_id").getAsString();
                Log.d(TAG, "文件上传成功，media_id: " + mediaId);
                return mediaId;
            } else {
                throw new IOException("响应中没有 media_id: " + responseBody);
            }
        }
    }

    /**
     * 发送文件消息到群聊
     */
    public void sendFileMessage(String conversationId, String mediaId, String fileName) throws IOException {
        String accessToken = getAccessToken();
        String url = BASE_URL + "/v1.0/robot/oToMessages/batchSend";

        JsonObject msg = new JsonObject();
        msg.addProperty("msgKey", "sampleFile");
        msg.addProperty("msgParam", "{\"mediaId\":\"" + mediaId + "\",\"fileName\":\"" + fileName + "\"}");

        JsonObject body = new JsonObject();
        body.addProperty("robotCode", config.getClientId());
        body.addProperty("conversationId", conversationId);
        body.add("msgParam", msg);

        Request request = new Request.Builder()
                .url(url)
                .header("x-acs-dingtalk-access-token", accessToken)
                .post(RequestBody.create(
                        MediaType.parse("application/json"),
                        gson.toJson(body)
                ))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("发送文件消息失败: " + response.code());
            }
            Log.d(TAG, "文件消息发送成功");
        }
    }

    /**
     * Stream 连接信息
     */
    public static class StreamConnection {
        public final String endpoint;
        public final String ticket;

        public StreamConnection(String endpoint, String ticket) {
            this.endpoint = endpoint;
            this.ticket = ticket;
        }
    }

    /**
     * 获取 Stream 连接信息
     */
    public StreamConnection getStreamConnection() throws IOException {
        String url = BASE_URL + "/v1.0/gateway/connections/open";

        // 构建 subscriptions 数组
        // 订阅机器人消息事件
        com.google.gson.JsonArray subscriptions = new com.google.gson.JsonArray();

        // 订阅所有事件（如果开放平台已配置具体事件）
        JsonObject subscription1 = new JsonObject();
        subscription1.addProperty("type", "CALLBACK");
        subscription1.addProperty("topic", "/v1.0/im/bot/messages/get");
        subscriptions.add(subscription1);

        // 也订阅通用回调
        JsonObject subscription2 = new JsonObject();
        subscription2.addProperty("type", "CALLBACK");
        subscription2.addProperty("topic", "*");
        subscriptions.add(subscription2);

        JsonObject body = new JsonObject();
        body.addProperty("clientId", config.getClientId());
        body.addProperty("clientSecret", config.getClientSecret());
        body.add("subscriptions", subscriptions);

        String requestJson = gson.toJson(body);
        Log.d(TAG, "Stream 请求: " + requestJson);

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(
                        MediaType.parse("application/json"),
                        requestJson
                ))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body().string();
            Log.d(TAG, "Stream 响应: " + responseBody);

            if (!response.isSuccessful()) {
                throw new IOException("获取 Stream 连接信息失败: " + response.code() + " - " + responseBody);
            }

            JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);

            if (jsonResponse.has("endpoint") && jsonResponse.has("ticket")) {
                String endpoint = jsonResponse.get("endpoint").getAsString();
                String ticket = jsonResponse.get("ticket").getAsString();
                Log.d(TAG, "Stream 连接信息获取成功: " + endpoint);
                return new StreamConnection(endpoint, ticket);
            } else {
                throw new IOException("响应中缺少 endpoint 或 ticket: " + responseBody);
            }
        }
    }
}
