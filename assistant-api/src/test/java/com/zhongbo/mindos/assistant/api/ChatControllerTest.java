package com.zhongbo.mindos.assistant.api;

import com.zhongbo.mindos.assistant.skill.Skill;
import com.zhongbo.mindos.assistant.skill.mcp.McpJsonRpcClient;
import com.zhongbo.mindos.assistant.skill.mcp.McpToolDefinition;
import com.zhongbo.mindos.assistant.skill.mcp.McpToolSkill;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldRouteEchoMessageToEchoSkill() throws Exception {
        mockMvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"test-user\",\"message\":\"echo hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("hello"))
                .andExpect(jsonPath("$.channel").value("echo"));
    }

    @Test
    void shouldKeepApiChatPathCompatible() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"test-user\",\"message\":\"echo compatibility\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("compatibility"))
                .andExpect(jsonPath("$.channel").value("echo"));
    }

    @Test
    void shouldExecuteDslCodeGenerateSkill() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"test-user\",\"message\":\"skill:code.generate task=controller\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("code.generate"))
                .andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("Placeholder generated code")));
    }

    @Test
    void shouldExecuteSkillDslJsonMessage() throws Exception {
        String skillDslJson = "{\\\"skill\\\":\\\"code.generate\\\",\\\"input\\\":{\\\"task\\\":\\\"service\\\"}}";
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"test-user\",\"message\":\"" + skillDslJson + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("code.generate"))
                .andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("task: service")));
    }

    @Test
    void shouldReturnBadRequestForInvalidSkillDslJson() throws Exception {
        String invalidSkillDslJson = "{\\\"input\\\":{\\\"task\\\":\\\"service\\\"}}";
        mockMvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"test-user\",\"message\":\"" + invalidSkillDslJson + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SKILL_DSL"));
    }

    @Test
    void shouldAutoRouteNaturalLanguageToMcpSkill() throws Exception {
        mockMvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"test-user\",\"message\":\"search docs for auth guide\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("mcp.docs.searchDocs"))
                .andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("MCP docs result")));
    }

    @Test
    void shouldAnswerAvailableSkillsInNaturalLanguage() throws Exception {
        mockMvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"test-user\",\"message\":\"你有哪些技能？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("skills.help"))
                .andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("echo")))
                .andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("time")));
    }

    @Test
    void shouldAnswerLearnableSkillsInNaturalLanguage() throws Exception {
        mockMvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"test-user\",\"message\":\"你还可以学习哪些技能？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("skills.help"))
                .andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("JSON")))
                .andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("MCP")))
                .andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("JAR")));
    }

    @TestConfiguration
    static class McpSkillTestConfig {

        @Bean
        Skill mcpDocsSearchSkill() {
            return new McpToolSkill(
                    new McpToolDefinition("docs", "http://unused.local/mcp", "searchDocs", "Search docs"),
                    new McpJsonRpcClient() {
                        @Override
                        public String callTool(String serverUrl, String toolName, java.util.Map<String, Object> arguments) {
                            return "MCP docs result for " + arguments.getOrDefault("input", "");
                        }
                    }
            );
        }
    }
}
