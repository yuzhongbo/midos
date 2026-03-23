package com.zhongbo.mindos.assistant.api;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "mindos.security.risky-ops.require-approval=true",
        "mindos.security.risky-ops.approval-header=X-MindOS-Approve",
        "mindos.security.risky-ops.approval-value=YES",
        "mindos.security.risky-ops.admin-token-header=X-MindOS-Admin-Token",
        "mindos.security.risky-ops.admin-token=test-admin-token"
})
@AutoConfigureMockMvc
class LongTaskSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldRejectTaskAutoRunWithoutApprovalHeaders() throws Exception {
        mockMvc.perform(post("/api/tasks/security-user/auto-run"))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldAllowTaskAutoRunWithApprovalHeaders() throws Exception {
        String challengeResponse = mockMvc.perform(post("/api/security/challenge")
                        .header("X-MindOS-Admin-Token", "test-admin-token")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"operation\":\"tasks.auto-run\",\"resource\":\"security-user\",\"actor\":\"security-user\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String token = JsonPath.read(challengeResponse, "$.token");

        mockMvc.perform(post("/api/tasks/security-user/auto-run")
                        .header("X-MindOS-Challenge-Token", token))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/tasks/security-user/auto-run")
                        .header("X-MindOS-Challenge-Token", token))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldRejectChallengeTokenWhenIpDoesNotMatch() throws Exception {
        String challengeResponse = mockMvc.perform(post("/api/security/challenge")
                        .with(request -> {
                            request.setRemoteAddr("10.0.0.10");
                            return request;
                        })
                        .header("X-MindOS-Admin-Token", "test-admin-token")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"operation\":\"tasks.auto-run\",\"resource\":\"security-user\",\"actor\":\"security-user\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String token = JsonPath.read(challengeResponse, "$.token");

        mockMvc.perform(post("/api/tasks/security-user/auto-run")
                        .with(request -> {
                            request.setRemoteAddr("10.0.0.11");
                            return request;
                        })
                        .header("X-MindOS-Challenge-Token", token))
                .andExpect(status().isForbidden());
    }
}

