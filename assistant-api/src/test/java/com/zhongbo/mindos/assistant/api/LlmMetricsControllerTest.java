package com.zhongbo.mindos.assistant.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "mindos.security.metrics.require-admin-token=true",
        "mindos.security.risky-ops.admin-token-header=X-MindOS-Admin-Token",
        "mindos.security.risky-ops.admin-token=test-admin-token"
})
@AutoConfigureMockMvc
class LlmMetricsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldReturnLlmMetricsSummaryAndRecentCalls() throws Exception {
        mockMvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"metrics-user\",\"message\":\"请总结一下今天的任务\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("llm"));

        mockMvc.perform(get("/api/metrics/llm")
                        .header("X-MindOS-Admin-Token", "test-admin-token")
                        .param("windowMinutes", "120")
                        .param("includeRecent", "true")
                        .param("recentLimit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCalls").isNumber())
                .andExpect(jsonPath("$.totalCalls").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.byProvider.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.recentCalls.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.securityAudit.queueDepth").isNumber())
                .andExpect(jsonPath("$.securityAudit.enqueuedCount").isNumber())
                .andExpect(jsonPath("$.securityAudit.writtenCount").isNumber());
    }

    @Test
    void shouldRejectLlmMetricsWhenAdminTokenMissingOrInvalid() throws Exception {
        mockMvc.perform(get("/api/metrics/llm")
                        .param("windowMinutes", "60"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/metrics/llm")
                        .header("X-MindOS-Admin-Token", "wrong-token")
                        .param("windowMinutes", "60"))
                .andExpect(status().isForbidden());
    }
}

