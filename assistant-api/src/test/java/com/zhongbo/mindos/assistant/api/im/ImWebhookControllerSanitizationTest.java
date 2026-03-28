package com.zhongbo.mindos.assistant.api.im;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.TEXT_XML;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ImWebhookControllerSanitizationTest {

    private static final String FRIENDLY_FALLBACK = ImReplySanitizer.FRIENDLY_IM_FALLBACK_REPLY;
    private static final String BLANK_FALLBACK = ImReplySanitizer.BLANK_IM_FALLBACK_REPLY;

    private ImGatewayService imGatewayService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        imGatewayService = mock(ImGatewayService.class);
        ImWebhookController controller = new ImWebhookController(imGatewayService);
        ReflectionTestUtils.setField(controller, "imEnabled", true);
        ReflectionTestUtils.setField(controller, "feishuEnabled", true);
        ReflectionTestUtils.setField(controller, "dingtalkEnabled", true);
        ReflectionTestUtils.setField(controller, "wechatEnabled", true);
        ReflectionTestUtils.setField(controller, "feishuVerifySignature", false);
        ReflectionTestUtils.setField(controller, "dingtalkVerifySignature", false);
        ReflectionTestUtils.setField(controller, "wechatVerifySignature", false);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void shouldHideInternalLlmErrorDetailsForDingtalk() throws Exception {
        when(imGatewayService.chat(ImPlatform.DINGTALK, "ding-safe-user", "conv-safe", "帮我总结今天安排"))
                .thenReturn("[LLM error] Failed after 2 attempts. http_call_failed: timeout while calling provider");

        String payload = "{"
                + "\"senderId\":\"ding-safe-user\","
                + "\"conversationId\":\"conv-safe\","
                + "\"text\":{\"content\":\"帮我总结今天安排\"}"
                + "}";

        mockMvc.perform(post("/api/im/dingtalk/events")
                        .contentType(APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msgtype").value("text"))
                .andExpect(jsonPath("$.text.content").value(FRIENDLY_FALLBACK))
                .andExpect(jsonPath("$.text.content").value(not(containsString("[LLM"))))
                .andExpect(jsonPath("$.text.content").value(not(containsString("http_call_failed"))));
    }

    @Test
    void shouldHideSkeletonFallbackForFeishu() throws Exception {
        when(imGatewayService.chat(ImPlatform.FEISHU, "ou_safe", "oc_safe", "继续上一条"))
                .thenReturn("[LLM gemini] fallback mode active. endpoint=https://example.test/v1/chat/completions, apiKey=key***12");

        String payload = "{"
                + "\"header\":{\"event_type\":\"im.message.receive_v1\"},"
                + "\"event\":{"
                + "\"sender\":{\"sender_id\":{\"open_id\":\"ou_safe\"}},"
                + "\"message\":{"
                + "\"chat_id\":\"oc_safe\","
                + "\"message_type\":\"text\","
                + "\"content\":\"{\\\"text\\\":\\\"继续上一条\\\"}\""
                + "}}"
                + "}";

        mockMvc.perform(post("/api/im/feishu/events")
                        .contentType(APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msg_type").value("text"))
                .andExpect(jsonPath("$.content.text").value(FRIENDLY_FALLBACK))
                .andExpect(jsonPath("$.content.text").value(not(containsString("fallback mode active"))))
                .andExpect(jsonPath("$.content.text").value(not(containsString("apiKey="))));
    }

    @Test
    void shouldHideNestedSkeletonPromptLeakForDingtalk() throws Exception {
        when(imGatewayService.chat(ImPlatform.DINGTALK, "$:LWCP_v1:$BH2F4jdf+SOZIDe3ZmsHqA==", "conv-leak", "优化记忆"))
                .thenReturn("[LLM gemini] skeleton response for user im:dingtalk:$:LWCP_v1:$BH2F4jdf+SOZIDe3ZmsHqA==: "
                        + "Answer naturally using the context when helpful.\n"
                        + "Recent conversation:\n"
                        + "- user: 你好测试一下\n"
                        + "Relevant knowledge:\n"
                        + "- none\n"
                        + "User skill habits:\n"
                        + "- none\n"
                        + "User input: 优化记忆");

        String payload = "{"
                + "\"senderId\":\"$:LWCP_v1:$BH2F4jdf+SOZIDe3ZmsHqA==\","
                + "\"conversationId\":\"conv-leak\","
                + "\"text\":{\"content\":\"优化记忆\"}"
                + "}";

        mockMvc.perform(post("/api/im/dingtalk/events")
                        .contentType(APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text.content").value(FRIENDLY_FALLBACK))
                .andExpect(jsonPath("$.text.content").value(not(containsString("Recent conversation:"))))
                .andExpect(jsonPath("$.text.content").value(not(containsString("Relevant knowledge:"))))
                .andExpect(jsonPath("$.text.content").value(not(containsString("User skill habits:"))));
    }

    @Test
    void shouldHidePromptAndContextLeakForWechat() throws Exception {
        when(imGatewayService.chat(eq(ImPlatform.WECHAT), eq("fromUser"), eq("toUser"), anyString()))
                .thenReturn("Answer naturally using the context when helpful.\nmemoryContext=项目上下文\nUser input: 继续按之前方式");

        String xml = "<xml>"
                + "<ToUserName><![CDATA[toUser]]></ToUserName>"
                + "<FromUserName><![CDATA[fromUser]]></FromUserName>"
                + "<CreateTime>123</CreateTime>"
                + "<MsgType><![CDATA[text]]></MsgType>"
                + "<Content><![CDATA[继续按之前方式]]></Content>"
                + "</xml>";

        mockMvc.perform(post("/api/im/wechat/events")
                        .contentType(TEXT_XML)
                        .param("signature", "any")
                        .param("timestamp", "1")
                        .param("nonce", "1")
                        .content(xml))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String responseBody = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
                    assertThat(responseBody, containsString(FRIENDLY_FALLBACK));
                    assertThat(responseBody, not(containsString("Answer naturally using the context")));
                    assertThat(responseBody, not(containsString("memoryContext")));
                    assertThat(responseBody, not(containsString("User input:")));
                });
    }

    @Test
    void shouldReturnBlankFallbackWhenGatewayReplyIsEmpty() throws Exception {
        when(imGatewayService.chat(ImPlatform.DINGTALK, "ding-empty-user", "conv-empty", "你好"))
                .thenReturn("   ");

        String payload = "{"
                + "\"senderId\":\"ding-empty-user\","
                + "\"conversationId\":\"conv-empty\","
                + "\"text\":{\"content\":\"你好\"}"
                + "}";

        mockMvc.perform(post("/api/im/dingtalk/events")
                        .contentType(APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text.content").value(BLANK_FALLBACK));
    }
}

