package com.zhongbo.mindos.assistant.api;

import com.zhongbo.mindos.assistant.api.testsupport.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

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
        String userId = ApiTestSupport.uniqueUserId("security-user");
        mockMvc.perform(post("/api/tasks/" + userId + "/auto-run"))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldAllowTaskAutoRunWithApprovalHeaders() throws Exception {
        String userId = ApiTestSupport.uniqueUserId("security-user");
        MvcResult challengeResult = mockMvc.perform(ApiTestSupport.withAdminToken(post("/api/security/challenge"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\":\"tasks.auto-run\",\"resource\":\"" + userId + "\",\"actor\":\"" + userId + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String token = ApiTestSupport.readString(challengeResult, "$.token");

        mockMvc.perform(post("/api/tasks/" + userId + "/auto-run")
                        .header("X-MindOS-Challenge-Token", token))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/tasks/" + userId + "/auto-run")
                        .header("X-MindOS-Challenge-Token", token))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldRejectChallengeTokenWhenIpDoesNotMatch() throws Exception {
        String userId = ApiTestSupport.uniqueUserId("security-user");
        MvcResult challengeResult = mockMvc.perform(ApiTestSupport.withAdminToken(post("/api/security/challenge"))
                        .with(request -> {
                            request.setRemoteAddr("10.0.0.10");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\":\"tasks.auto-run\",\"resource\":\"" + userId + "\",\"actor\":\"" + userId + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String token = ApiTestSupport.readString(challengeResult, "$.token");

        mockMvc.perform(post("/api/tasks/" + userId + "/auto-run")
                        .with(request -> {
                            request.setRemoteAddr("10.0.0.11");
                            return request;
                        })
                        .header("X-MindOS-Challenge-Token", token))
                .andExpect(status().isForbidden());
    }
}

