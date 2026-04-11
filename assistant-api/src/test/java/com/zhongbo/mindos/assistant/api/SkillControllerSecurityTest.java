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
        MvcResult challengeResult = mockMvc.perform(ApiTestSupport.withAdminToken(post("/api/security/challenge"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\":\"skills.load-mcp\",\"resource\":\"docs@https://example.com/mcp\",\"actor\":\"system\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String token = ApiTestSupport.readString(challengeResult, "$.token");

        mockMvc.perform(post("/api/skills/load-mcp")
                        .header("X-MindOS-Challenge-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alias\":\"docs\",\"url\":\"https://example.com/mcp\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldRejectSkillGenerationWithoutApprovalHeaders() throws Exception {
        mockMvc.perform(post("/api/skills/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"request\":\"抓取某网站数据\"}"))
                .andExpect(status().isForbidden());
    }
}
