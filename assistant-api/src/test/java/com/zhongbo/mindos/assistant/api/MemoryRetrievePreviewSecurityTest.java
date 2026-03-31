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
        "mindos.memory.file-repo.enabled=false",
        "mindos.security.memory.retrieve-preview.require-admin-token=true",
        "mindos.security.risky-ops.admin-token-header=X-MindOS-Admin-Token",
        "mindos.security.risky-ops.admin-token=test-admin-token"
})
@AutoConfigureMockMvc
class MemoryRetrievePreviewSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldRejectRetrievePreviewWithoutAdminToken() throws Exception {
        mockMvc.perform(get("/api/memory/secure-user/retrieve-preview")
                        .param("query", "echo"))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldAllowRetrievePreviewWithAdminToken() throws Exception {
        mockMvc.perform(post("/api/memory/secure-user/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"evt-sec\",\"episodic\":[{\"role\":\"user\",\"content\":\"echo secure\"}],\"semantic\":[],\"procedural\":[]}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/memory/secure-user/retrieve-preview")
                        .header("X-MindOS-Admin-Token", "test-admin-token")
                        .param("query", "echo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recentConversation").isString());
    }
}

