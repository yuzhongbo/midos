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
        "mindos.im.wechat.verify-signature=false"
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

