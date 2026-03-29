package com.zhongbo.mindos.assistant.api.im;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

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
        "mindos.im.dingtalk.reply-max-chars=80",
        "mindos.memory.file-repo.enabled=false"
})
@AutoConfigureMockMvc
class ImWebhookControllerTest {

    @Autowired
    private MockMvc mockMvc;

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

        mockMvc.perform(get("/api/memory/ding-user/retrieve-preview")
                        .param("query", "echo hi")
                        .param("maxChars", "900")
                        .param("language", "zh-CN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recentConversation").isString())
                .andExpect(jsonPath("$.semanticContext").isString())
                .andExpect(jsonPath("$.proceduralHints").isString())
                .andExpect(jsonPath("$.debugTopItems").isArray())
                .andExpect(jsonPath("$.personaSnapshot.language").value("zh-CN"));
    }

    @Test
    void shouldCapDingtalkWebhookReplyLength() throws Exception {
        String longPayload = "{" +
                "\"senderId\":\"ding-cap-user\"," +
                "\"conversationId\":\"conv-cap\"," +
                "\"text\":{\"content\":\"echo 12345678901234567890123456789012345678901234567890123456789012345678901234567890\"}" +
                "}";

        mockMvc.perform(post("/api/im/dingtalk/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(longPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msgtype").value("text"))
                .andExpect(jsonPath("$.text.content").value(org.hamcrest.Matchers.matchesPattern("^.{1,80}$")));
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
    void shouldReturnFriendlyFallbackWhenImLlmIsUnavailable() throws Exception {
        String payload = "{" +
                "\"senderId\":\"ding-llm-user\"," +
                "\"conversationId\":\"conv-llm\"," +
                "\"text\":{\"content\":\"继续按之前方式\"}" +
                "}";

        mockMvc.perform(post("/api/im/dingtalk/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msgtype").value("text"))
                .andExpect(jsonPath("$.text.content").value(ImReplySanitizer.AUTH_IM_FALLBACK_REPLY))
                .andExpect(jsonPath("$.text.content").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("[LLM"))))
                .andExpect(jsonPath("$.text.content").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("继续按之前方式"))));
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
}

