package com.zhongbo.mindos.assistant.api;

import com.zhongbo.mindos.assistant.api.testsupport.ApiTestSupport;
import com.zhongbo.mindos.assistant.common.LlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "mindos.dispatcher.llm-reply.max-chars=120",
        "mindos.dispatcher.prompt.max-chars=400",
        "mindos.dispatcher.memory-context.max-chars=220",
        "mindos.dispatcher.skill.guard.max-consecutive=5",
        "mindos.dispatcher.skill.guard.recent-window-size=6",
        "mindos.dispatcher.skill.guard.repeat-input-threshold=2",
        "mindos.dispatcher.skill.guard.cooldown-seconds=3600",
        "mindos.memory.file-repo.enabled=false",
        "mindos.llm.api-key=dummy-key"
})
@AutoConfigureMockMvc
class DispatcherBudgetGuardTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldCapLlmReplyLength() throws Exception {
        String userId = ApiTestSupport.uniqueUserId("budget-user");
        String longInput = "请你详细说明这段很长很长的输入并尽量展开：" + "x".repeat(400);

        mockMvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ApiTestSupport.chatRequest(userId, longInput)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("llm"))
                .andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("...[truncated]")));
    }

    @Test
    void shouldFallbackWhenRepeatedLlmDslAutoRouteHitsLoopGuard() throws Exception {
        String userId = ApiTestSupport.uniqueUserId("llm-dsl-guard-user");
        String request = ApiTestSupport.chatRequest(userId, "请详细分析并帮我自动处理这个请求");

        mockMvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("echo"));

        mockMvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("echo"));

        mockMvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("llm"))
                .andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("fallback")));
    }

    @Test
    void shouldNotBlockDifferentInputsForSameAutoRoutedSkill() throws Exception {
        String userId = ApiTestSupport.uniqueUserId("llm-dsl-guard-user-2");
        String first = ApiTestSupport.chatRequest(userId, "请帮我自动处理这个请求A");
        String second = ApiTestSupport.chatRequest(userId, "请帮我自动处理这个请求B");
        String third = ApiTestSupport.chatRequest(userId, "请帮我自动处理这个请求A");

        mockMvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("echo"));

        mockMvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(second))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("echo"));

        mockMvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(third))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("echo"));
    }

    @TestConfiguration
    static class LlmDslGuardTestConfig {

        @Bean
        @Primary
        LlmClient testLlmClient() {
            return new LlmClient() {
                @Override
                public String generateResponse(String prompt, Map<String, Object> context) {
                    String routeStage = context == null ? "" : String.valueOf(context.getOrDefault("routeStage", ""));
                    if ("llm-dsl".equals(routeStage)) {
                        String input = context == null ? "" : String.valueOf(context.getOrDefault("input", ""));
                        if (input.contains("自动处理")) {
                            return "{\"skill\":\"echo\",\"input\":{\"text\":\"auto-routed by llm-dsl\"}}";
                        }
                        return "NONE";
                    }
                    return "fallback from test llm: " + "y".repeat(240);
                }

                @Override
                public com.zhongbo.mindos.assistant.common.dsl.SkillDSL parseSkillCall(String userInput) {
                    return null;
                }
            };
        }
    }
}
