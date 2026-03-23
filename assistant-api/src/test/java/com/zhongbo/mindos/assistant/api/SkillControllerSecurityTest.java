package com.zhongbo.mindos.assistant.api;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "mindos.security.risky-ops.require-approval=true",
        "mindos.security.risky-ops.approval-header=X-MindOS-Approve",
        "mindos.security.risky-ops.approval-value=YES",
        "mindos.security.risky-ops.admin-token-header=X-MindOS-Admin-Token",
        "mindos.security.risky-ops.admin-token=test-admin-token",
        "mindos.security.skill.load-jar.allowed-hosts=localhost,127.0.0.1",
        "mindos.security.skill.load-mcp.allowed-hosts=localhost,127.0.0.1"
})
@AutoConfigureMockMvc
class SkillControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldRejectRiskySkillLoadWithoutApprovalHeaders() throws Exception {
        mockMvc.perform(post("/api/skills/load-jar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/skill.jar\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldRejectLoadMcpWhenHostIsNotAllowlisted() throws Exception {
        String challengeResponse = mockMvc.perform(post("/api/security/challenge")
                        .header("X-MindOS-Admin-Token", "test-admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\":\"skills.load-mcp\",\"resource\":\"docs@https://example.com/mcp\",\"actor\":\"system\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String token = JsonPath.read(challengeResponse, "$.token");

        mockMvc.perform(post("/api/skills/load-mcp")
                        .header("X-MindOS-Challenge-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alias\":\"docs\",\"url\":\"https://example.com/mcp\"}"))
                .andExpect(status().isForbidden());
    }
}

