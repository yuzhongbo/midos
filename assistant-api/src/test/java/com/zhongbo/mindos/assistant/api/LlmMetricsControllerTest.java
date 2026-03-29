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
                .andExpect(jsonPath("$.securityAudit.writtenCount").isNumber())
                .andExpect(jsonPath("$.llmCache.enabled").isBoolean())
                .andExpect(jsonPath("$.llmCache.hitRate").isNumber())
                .andExpect(jsonPath("$.memoryWriteGate.secondaryDuplicateGateEnabled").isBoolean())
                .andExpect(jsonPath("$.memoryWriteGate.secondaryDuplicateInterceptRate").isNumber())
                .andExpect(jsonPath("$.contextCompression.requests").isNumber())
                .andExpect(jsonPath("$.contextCompression.totalInputChars").isNumber())
                .andExpect(jsonPath("$.contextCompression.totalOutputChars").isNumber())
                .andExpect(jsonPath("$.contextCompression.avgCompressionRatio").isNumber())
                .andExpect(jsonPath("$.skillPreAnalyze.mode").isString())
                .andExpect(jsonPath("$.skillPreAnalyze.requests").isNumber())
                .andExpect(jsonPath("$.memoryHits.requests").isNumber())
                .andExpect(jsonPath("$.memoryHits.approximateHitRate").isNumber())
                .andExpect(jsonPath("$.memoryContribution.requests").isNumber())
                .andExpect(jsonPath("$.memoryContribution.semanticTagged").isNumber())
                .andExpect(jsonPath("$.llmCacheWindowHitRate").isNumber())
                .andExpect(jsonPath("$.llmCacheWindowHits").isNumber())
                .andExpect(jsonPath("$.llmCacheWindowMisses").isNumber())
                .andExpect(jsonPath("$.llmCacheWindowLowSample").isBoolean())
                .andExpect(jsonPath("$.llmCacheWindowLowSample").value(true));
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

    @Test
    void shouldReturnRoutingReplayDatasetWhenAuthorized() throws Exception {
        mockMvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"metrics-replay-user\",\"message\":\"请继续按之前方式\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/metrics/llm/routing-replay")
                        .header("X-MindOS-Admin-Token", "test-admin-token")
                        .param("limit", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(200))
                .andExpect(jsonPath("$.totalCaptured").isNumber())
                .andExpect(jsonPath("$.samples").isArray())
                .andExpect(jsonPath("$.byRoute").isMap())
                .andExpect(jsonPath("$.byFinalChannel").isMap());
    }
}

