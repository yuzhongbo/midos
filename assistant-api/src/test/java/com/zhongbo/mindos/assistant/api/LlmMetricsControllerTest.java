package com.zhongbo.mindos.assistant.api;

import com.zhongbo.mindos.assistant.api.testsupport.ApiTestSupport;
import com.zhongbo.mindos.assistant.common.LlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    @Autowired
    private LlmClient llmClient;

    @Test
    void shouldReturnLlmMetricsSummaryAndRecentCalls() throws Exception {
        String userId = ApiTestSupport.uniqueUserId("metrics-user");
        llmClient.generateResponse("请详细分析 hello", Map.of(
                "userId", userId,
                "routeStage", "llm-fallback"
        ));

        mockMvc.perform(ApiTestSupport.withAdminToken(get("/api/metrics/llm"))
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
                .andExpect(jsonPath("$.skillPreAnalyze.detectedSkillLoopSkipBlocked").isNumber())
                .andExpect(jsonPath("$.skillPreAnalyze.skillTimeoutTriggered").isNumber())
                .andExpect(jsonPath("$.memoryHits.requests").isNumber())
                .andExpect(jsonPath("$.memoryHits.approximateHitRate").isNumber())
                .andExpect(jsonPath("$.memoryContribution.requests").isNumber())
                .andExpect(jsonPath("$.memoryContribution.semanticTagged").isNumber())
                .andExpect(jsonPath("$.localEscalation.localAttempts").isNumber())
                .andExpect(jsonPath("$.localEscalation.localHitRate").isNumber())
                .andExpect(jsonPath("$.localEscalation.localHitRate").value(org.hamcrest.Matchers.greaterThanOrEqualTo(0.0)))
                .andExpect(jsonPath("$.localEscalation.localHitRate").value(org.hamcrest.Matchers.lessThanOrEqualTo(1.0)))
                .andExpect(jsonPath("$.localEscalation.fallbackChainHitRate").isNumber())
                .andExpect(jsonPath("$.localEscalation.fallbackChainHitRate").value(org.hamcrest.Matchers.greaterThanOrEqualTo(0.0)))
                .andExpect(jsonPath("$.localEscalation.fallbackChainHitRate").value(org.hamcrest.Matchers.lessThanOrEqualTo(1.0)))
                .andExpect(jsonPath("$.localEscalation.escalationReasons.timeout").isNumber())
                .andExpect(jsonPath("$.localEscalation.escalationReasons.manual").isNumber())
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
        String userId = ApiTestSupport.uniqueUserId("metrics-replay-user");
        llmClient.generateResponse("谢谢", Map.of(
                "userId", userId,
                "routeStage", "llm-fallback"
        ));

        mockMvc.perform(ApiTestSupport.withAdminToken(get("/api/metrics/llm/routing-replay"))
                        .param("limit", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(200))
                .andExpect(jsonPath("$.totalCaptured").isNumber())
                .andExpect(jsonPath("$.samples").isArray())
                .andExpect(jsonPath("$.byRoute").isMap())
                .andExpect(jsonPath("$.byFinalChannel").isMap());
    }
}

