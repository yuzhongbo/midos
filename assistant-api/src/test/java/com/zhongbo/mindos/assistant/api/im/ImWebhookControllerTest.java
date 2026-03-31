package com.zhongbo.mindos.assistant.api.im;

import com.sun.net.httpserver.HttpServer;
import com.zhongbo.mindos.assistant.memory.MemoryManager;
import com.zhongbo.mindos.assistant.memory.model.LongTask;
import com.zhongbo.mindos.assistant.memory.model.LongTaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "mindos.im.enabled=true",
        "mindos.im.feishu.enabled=true",
        "mindos.im.dingtalk.enabled=true",
        "mindos.im.wechat.enabled=true",
        "mindos.im.feishu.verify-signature=false",
        "mindos.im.dingtalk.verify-signature=false",
        "mindos.im.wechat.verify-signature=false",
        "mindos.im.dingtalk.async-reply.allow-insecure-localhost-http=true",
        "mindos.im.dingtalk.reply-max-chars=80",
        "mindos.memory.file-repo.enabled=false"
})
@AutoConfigureMockMvc
class ImWebhookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MemoryManager memoryManager;

    @Test
    void shouldHandleFeishuChallengeAndTextEvent() throws Exception {
        mockMvc.perform(post("/api/im/feishu/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challenge\":\"challenge-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.challenge").value("challenge-token"));

        String payload = "{" +
                "\"header\":{\"event_type\":\"im.message.receive_v1\"}," +
                "\"event\":{" +
                "\"sender\":{\"sender_id\":{\"open_id\":\"ou_123\"}}," +
                "\"message\":{" +
                "\"chat_id\":\"oc_abc\"," +
                "\"message_type\":\"text\"," +
                "\"content\":\"{\\\"text\\\":\\\"echo hello\\\"}\"" +
                "}}" +
                "}";

        mockMvc.perform(post("/api/im/feishu/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msg_type").value("text"))
                .andExpect(jsonPath("$.content.text").isNotEmpty());
    }

    @Test
    void shouldHandleDingtalkTextEvent() throws Exception {
        String payload = "{" +
                "\"senderId\":\"ding-user\"," +
                "\"conversationId\":\"conv-1\"," +
                "\"text\":{\"content\":\"echo hi\"}" +
                "}";

        mockMvc.perform(post("/api/im/dingtalk/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msgtype").value("text"))
                .andExpect(jsonPath("$.text.content").isNotEmpty());
    }

    @Test
    void shouldHandleDingtalkTextEventWithOpenConversationId() throws Exception {
        String payload = "{" +
                "\"senderId\":\"ding-open-user\"," +
                "\"openConversationId\":\"open-conv-1\"," +
                "\"text\":{\"content\":\"echo hi open\"}" +
                "}";

        mockMvc.perform(post("/api/im/dingtalk/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msgtype").value("text"))
                .andExpect(jsonPath("$.text.content").isNotEmpty());
    }

    @Test
    void shouldAcknowledgeAndPushAsyncDingtalkReplyWhenSessionWebhookProvided() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> callbackBody = new AtomicReference<>("");
        HttpServer callbackServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        callbackServer.createContext("/callback", exchange -> {
            callbackBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"errcode\":0}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
            latch.countDown();
        });
        callbackServer.start();
        try {
            long expiresAt = System.currentTimeMillis() + 60_000L;
            String payload = "{" +
                    "\"senderId\":\"ding-async-user\"," +
                    "\"conversationId\":\"conv-async\"," +
                    "\"sessionWebhook\":\"http://127.0.0.1:" + callbackServer.getAddress().getPort() + "/callback\"," +
                    "\"sessionWebhookExpiredTime\":" + expiresAt + "," +
                    "\"text\":{\"content\":\"echo async hi\"}" +
                    "}";

            mockMvc.perform(post("/api/im/dingtalk/events")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.msgtype").value("text"))
                    .andExpect(jsonPath("$.text.content").value(org.hamcrest.Matchers.containsString("正在处理中")))
                    .andExpect(jsonPath("$.text.content").value(org.hamcrest.Matchers.containsString("任务ID")));

            org.junit.jupiter.api.Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
            org.junit.jupiter.api.Assertions.assertTrue(callbackBody.get().contains("\"msgtype\":\"text\""));
            org.junit.jupiter.api.Assertions.assertTrue(callbackBody.get().contains("你刚才的请求已处理完成："));
            org.junit.jupiter.api.Assertions.assertTrue(callbackBody.get().contains("async hi"));
        } finally {
            callbackServer.stop(0);
        }
    }

    @Test
    void shouldSupportDingtalkProgressAndResultQueriesAfterAsyncDeliveryFailure() throws Exception {
        int unusedPort = reserveUnusedPort();
        String payload = "{" +
                "\"senderId\":\"ding-query-user\"," +
                "\"conversationId\":\"conv-query\"," +
                "\"sessionWebhook\":\"http://127.0.0.1:" + unusedPort + "/callback\"," +
                "\"sessionWebhookExpiredTime\":" + (System.currentTimeMillis() + 60_000L) + "," +
                "\"text\":{\"content\":\"echo query result\"}" +
                "}";

        mockMvc.perform(post("/api/im/dingtalk/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text.content").value(org.hamcrest.Matchers.containsString("正在处理中")));

        LongTask blockedTask = awaitTask("im:dingtalk:ding-query-user", LongTaskStatus.BLOCKED, 5);
        org.junit.jupiter.api.Assertions.assertNotNull(blockedTask);

        String progressPayload = "{" +
                "\"senderId\":\"ding-query-user\"," +
                "\"conversationId\":\"conv-query\"," +
                "\"text\":{\"content\":\"查进度 " + blockedTask.taskId() + "\"}" +
                "}";

        mockMvc.perform(post("/api/im/dingtalk/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(progressPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msgtype").value("text"))
                .andExpect(jsonPath("$.text.content").value(org.hamcrest.Matchers.containsString(blockedTask.taskId())))
                .andExpect(jsonPath("$.text.content").value(org.hamcrest.Matchers.containsString("未成功送达")))
                .andExpect(jsonPath("$.text.content").value(org.hamcrest.Matchers.containsString("query result")));

        String resultPayload = "{" +
                "\"senderId\":\"ding-query-user\"," +
                "\"conversationId\":\"conv-query\"," +
                "\"text\":{\"content\":\"查看结果 " + blockedTask.taskId() + "\"}" +
                "}";

        mockMvc.perform(post("/api/im/dingtalk/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resultPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msgtype").value("text"))
                .andExpect(jsonPath("$.text.content").value(org.hamcrest.Matchers.containsString(blockedTask.taskId())))
                .andExpect(jsonPath("$.text.content").value(org.hamcrest.Matchers.containsString("query result")));
    }

    @Test
    void shouldCompensateUndeliveredAsyncResultOnNextDingtalkMessage() throws Exception {
        int unusedPort = reserveUnusedPort();
        String payload = "{" +
                "\"senderId\":\"ding-comp-user\"," +
                "\"conversationId\":\"conv-comp\"," +
                "\"sessionWebhook\":\"http://127.0.0.1:" + unusedPort + "/callback\"," +
                "\"sessionWebhookExpiredTime\":" + (System.currentTimeMillis() + 60_000L) + "," +
                "\"text\":{\"content\":\"echo compensation result\"}" +
                "}";

        mockMvc.perform(post("/api/im/dingtalk/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text.content").value(org.hamcrest.Matchers.containsString("正在处理中")));

        LongTask blockedTask = awaitTask("im:dingtalk:ding-comp-user", LongTaskStatus.BLOCKED, 5);
        org.junit.jupiter.api.Assertions.assertNotNull(blockedTask);

        String nextPayload = "{" +
                "\"senderId\":\"ding-comp-user\"," +
                "\"conversationId\":\"conv-comp\"," +
                "\"text\":{\"content\":\"echo after compensation\"}" +
                "}";

        mockMvc.perform(post("/api/im/dingtalk/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(nextPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msgtype").value("text"))
                .andExpect(jsonPath("$.text.content").value(org.hamcrest.Matchers.containsString("补偿通知")))
                .andExpect(jsonPath("$.text.content").value(org.hamcrest.Matchers.containsString(blockedTask.taskId())))
                .andExpect(jsonPath("$.text.content").value(org.hamcrest.Matchers.containsString("compensation result")))
                .andExpect(jsonPath("$.text.content").value(org.hamcrest.Matchers.containsString("after compensation")));
    }

    @Test
    void shouldHandleDingtalkMemoryCompressionFocusIntent() throws Exception {
        String payload = "{" +
                "\"senderId\":\"ding-memory-user\"," +
                "\"conversationId\":\"conv-memory\"," +
                "\"text\":{\"content\":\"按我的风格压缩这段记忆：先拆任务再执行，按任务聚焦\"}" +
                "}";

        mockMvc.perform(post("/api/im/dingtalk/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msgtype").value("text"))
                .andExpect(jsonPath("$.text.content").value(org.hamcrest.Matchers.containsString("[任务聚焦]")))
                .andExpect(jsonPath("$.text.content").value(org.hamcrest.Matchers.containsString("我已帮你完成记忆整理")));
    }

    @Test
    void shouldHandleDingtalkMemoryStyleUpdateAndAutoTuneIntent() throws Exception {
        String updatePayload = "{" +
                "\"senderId\":\"ding-style-user\"," +
                "\"conversationId\":\"conv-style\"," +
                "\"text\":{\"content\":\"把记忆风格改成 action，语气 warm，格式 bullet\"}" +
                "}";

        mockMvc.perform(post("/api/im/dingtalk/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updatePayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msgtype").value("text"))
                .andExpect(jsonPath("$.text.content").value(org.hamcrest.Matchers.containsString("记忆风格已更新")));

        String tunePayload = "{" +
                "\"senderId\":\"ding-style-user\"," +
                "\"conversationId\":\"conv-style\"," +
                "\"text\":{\"content\":\"根据这段话微调记忆风格：请帮我按步骤拆分任务清单\"}" +
                "}";

        mockMvc.perform(post("/api/im/dingtalk/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tunePayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msgtype").value("text"))
                .andExpect(jsonPath("$.text.content").value(org.hamcrest.Matchers.containsString("记忆风格已微调")));
    }

    @Test
    void shouldReturnCompressionReplyWhenKeyConstraintsArePreserved() throws Exception {
        String compressPayload = "{" +
                "\"senderId\":\"ding-review-user\"," +
                "\"conversationId\":\"conv-review\"," +
                "\"text\":{\"content\":\"按我的风格压缩这段记忆：今天18:30前必须提交合同，不要遗漏附件\"}" +
                "}";

        mockMvc.perform(post("/api/im/dingtalk/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(compressPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msgtype").value("text"))
                .andExpect(jsonPath("$.text.content").value(org.hamcrest.Matchers.containsString("关键约束已保留")));
    }

    @Test
    void shouldHandleWechatVerifyAndTextEvent() throws Exception {
        mockMvc.perform(get("/api/im/wechat/events")
                        .param("signature", "any")
                        .param("timestamp", "1")
                        .param("nonce", "1")
                        .param("echostr", "wechat-ok"))
                .andExpect(status().isOk())
                .andExpect(content().string("wechat-ok"));

        String xml = "<xml>"
                + "<ToUserName><![CDATA[toUser]]></ToUserName>"
                + "<FromUserName><![CDATA[fromUser]]></FromUserName>"
                + "<CreateTime>123</CreateTime>"
                + "<MsgType><![CDATA[text]]></MsgType>"
                + "<Content><![CDATA[echo wechat]]></Content>"
                + "</xml>";

        mockMvc.perform(post("/api/im/wechat/events")
                        .contentType(MediaType.TEXT_XML)
                        .param("signature", "any")
                        .param("timestamp", "1")
                        .param("nonce", "1")
                        .content(xml))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<MsgType><![CDATA[text]]></MsgType>")));
    }

    private LongTask awaitTask(String userId, LongTaskStatus expectedStatus, int timeoutSeconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            LongTask latest = memoryManager.listLongTasks(userId, null).stream().findFirst().orElse(null);
            if (latest != null && latest.status() == expectedStatus) {
                return latest;
            }
            Thread.sleep(50L);
        }
        return memoryManager.listLongTasks(userId, null).stream().findFirst().orElse(null);
    }

    private int reserveUnusedPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
