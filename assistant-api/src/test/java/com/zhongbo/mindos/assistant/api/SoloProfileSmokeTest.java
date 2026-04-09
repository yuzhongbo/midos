package com.zhongbo.mindos.assistant.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("solo")
class SoloProfileSmokeTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldAllowMetricsWithoutAdminTokenInSoloProfile() throws Exception {
        mockMvc.perform(get("/api/metrics/llm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.windowMinutes").isNumber())
                .andExpect(jsonPath("$.contextCompression.requests").isNumber())
                .andExpect(jsonPath("$.skillPreAnalyze.requests").isNumber())
                .andExpect(jsonPath("$.memoryHits.requests").isNumber());
    }

    @Test
    void shouldKeepChatEndpointAvailableInSoloProfile() throws Exception {
        String userId = "solo-smoke-user-" + UUID.randomUUID();
        mockMvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + userId + "\",\"message\":\"echo hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("echo"));
    }
}

