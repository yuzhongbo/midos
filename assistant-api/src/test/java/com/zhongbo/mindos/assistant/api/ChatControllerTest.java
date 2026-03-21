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

@SpringBootTest(properties = "mindos.dispatcher.preference-reuse.enabled=true")
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

    @Test
    void shouldRouteTeachingPlanRequestToTeachingPlanSkill() throws Exception {
        mockMvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"test-user\",\"message\":\"给我一个Java学习计划，目标是准备面试，四周，每周六小时，薄弱点函数、概率，学习风格练习优先\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("teaching.plan"))
                .andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("学生ID: test-user")))
                .andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("主题: Java")))
                .andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("目标: 准备面试")))
                .andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("周期: 4 周")))
                .andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("每周投入: 6 小时")))
                .andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("薄弱点: 函数、概率")))
                .andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("学习风格: 练习优先")));
    }

    @Test
    void shouldAutoRouteContinuationDialogueByHabitForTeachingPlan() throws Exception {
        mockMvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"habit-user\",\"message\":\"给我一个数学学习计划，目标是期末提分，六周，每周八小时\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("teaching.plan"));

        mockMvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"habit-user\",\"message\":\"给我一个数学学习计划，目标是巩固提升，六周，每周八小时\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("teaching.plan"));

        mockMvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"habit-user\",\"message\":\"继续按之前方式，目标是冲刺提分\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("teaching.plan"))
                .andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("主题: 数学")))
                .andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("目标: 冲刺提分")));
    }

    @Test
    void shouldMergeProfilePreferencesWhenContinuationAutoRoutesTeachingPlan() throws Exception {
        String profileJson = "{\"assistantName\":\"MindOS\",\"role\":\"高一\",\"style\":\"练习优先\",\"language\":\"zh-CN\",\"timezone\":\"Asia/Shanghai\"}";
        String firstRequest = String.format(
                "{\"userId\":\"pref-user\",\"message\":\"给我一个数学学习计划，目标是期末提分，六周，每周八小时\",\"profile\":%s}",
                profileJson
        );
        String secondRequest = String.format(
                "{\"userId\":\"pref-user\",\"message\":\"继续按之前方式\",\"profile\":%s}",
                profileJson
        );

        mockMvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(firstRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("teaching.plan"));

        mockMvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(firstRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("teaching.plan"));

        mockMvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(secondRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("teaching.plan"))
                .andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("对象: 高一")))
                .andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("学习风格: 练习优先")))
                .andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("约束: 时区:Asia/Shanghai")));
    }

    @Test
    void shouldReuseLearnedProfilePreferencesWhenFollowUpHasNoProfile() throws Exception {
        String profileJson = "{\"assistantName\":\"MindOS\",\"role\":\"高一\",\"style\":\"练习优先\",\"language\":\"zh-CN\",\"timezone\":\"Asia/Shanghai\"}";
        String firstRequest = String.format(
                "{\"userId\":\"pref-learn-user\",\"message\":\"给我一个数学学习计划，目标是期末提分，六周，每周八小时\",\"profile\":%s}",
                profileJson
        );

        mockMvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(firstRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("teaching.plan"));

        mockMvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(firstRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("teaching.plan"));

        mockMvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"pref-learn-user\",\"message\":\"继续按之前方式\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("teaching.plan"))
                .andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("对象: 高一")))
                .andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("学习风格: 练习优先")));
    }

    @Test
    void shouldIgnorePlaceholderProfileValuesWhenLearningPersona() throws Exception {
        String stableProfile = "{\"assistantName\":\"MindOS\",\"role\":\"高一\",\"style\":\"练习优先\",\"language\":\"zh-CN\",\"timezone\":\"Asia/Shanghai\"}";
        String placeholderProfile = "{\"assistantName\":\"MindOS\",\"role\":\"unknown\",\"style\":\"unknown\",\"language\":\"zh-CN\",\"timezone\":\"Asia/Shanghai\"}";

        mockMvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"userId\":\"placeholder-user\",\"message\":\"给我一个数学学习计划，目标是期末提分，六周，每周八小时\",\"profile\":%s}", stableProfile)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("teaching.plan"));

        mockMvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"userId\":\"placeholder-user\",\"message\":\"给我一个数学学习计划，目标是冲刺提分，六周，每周八小时\",\"profile\":%s}", placeholderProfile)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("teaching.plan"));

        mockMvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"placeholder-user\",\"message\":\"继续按之前方式\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("teaching.plan"))
                .andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("对象: 高一")))
                .andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("学习风格: 练习优先")));
    }

    @Test
    void shouldAutoRouteContinuationToCodeGenerateWithHabitHint() throws Exception {
        mockMvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"code-habit-user\",\"message\":\"generate code for order entity\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("code.generate"));

        mockMvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"code-habit-user\",\"message\":\"generate code for order service\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("code.generate"));

        mockMvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"code-habit-user\",\"message\":\"继续按之前方式\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("code.generate"))
                .andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("[自动调度] 已按历史习惯调用 skill: code.generate")));
    }

    @Test
    void shouldAutoRouteContinuationToTodoCreateWithHabitHint() throws Exception {
        mockMvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"todo-habit-user\",\"message\":\"{\\\"skill\\\":\\\"todo.create\\\",\\\"input\\\":{\\\"task\\\":\\\"准备周会汇报\\\",\\\"dueDate\\\":\\\"周五\\\"}}\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("todo.create"));

        mockMvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"todo-habit-user\",\"message\":\"{\\\"skill\\\":\\\"todo.create\\\",\\\"input\\\":{\\\"task\\\":\\\"准备技术评审\\\",\\\"dueDate\\\":\\\"周六\\\"}}\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("todo.create"));

        mockMvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"todo-habit-user\",\"message\":\"继续按之前方式，截止明天\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("todo.create"))
                .andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("[自动调度] 已按历史习惯调用 skill: todo.create")));
    }

    @Test
    void shouldFallbackWhenHabitHistoryIsInsufficient() throws Exception {
        mockMvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"habit-threshold-user\",\"message\":\"generate code for member aggregate\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("code.generate"));

        mockMvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"habit-threshold-user\",\"message\":\"继续按之前方式\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("llm"));
    }

    @Test
    void shouldReplanToLlmAndExposeExecutionTraceWhenSkillFails() throws Exception {
        mockMvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"meta-user\",\"message\":\"{\\\"skill\\\":\\\"boom.fail\\\",\\\"input\\\":{}}\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("llm"))
                .andExpect(jsonPath("$.executionTrace.strategy").value("meta-replan"))
                .andExpect(jsonPath("$.executionTrace.replanCount").value(1));
    }

    @TestConfiguration
    static class McpSkillTestConfig {

        @Bean
        Skill boomFailSkill() {
            return new Skill() {
                @Override
                public String name() {
                    return "boom.fail";
                }

                @Override
                public String description() {
                    return "Always fails for meta-orchestrator fallback tests.";
                }

                @Override
                public com.zhongbo.mindos.assistant.common.SkillResult run(com.zhongbo.mindos.assistant.common.SkillContext context) {
                    throw new IllegalStateException("boom from boom.fail");
                }
            };
        }

        @Bean
        Skill mcpDocsSearchSkill() {
            return new McpToolSkill(
                    new McpToolDefinition("docs", "http://unused.local/mcp", "searchDocs", "Search docs"),
                    new McpJsonRpcClient() {
                        @Override
                        public String callTool(String serverUrl, String toolName, java.util.Map<String, Object> arguments) {
                            return "MCP docs result for " + arguments.getOrDefault("input", "");
                        }

                        @Override
                        public String callTool(String serverUrl,
                                               String toolName,
                                               java.util.Map<String, Object> arguments,
                                               java.util.Map<String, String> headers) {
                            return "MCP docs result for " + arguments.getOrDefault("input", "");
                        }
                    }
            );
        }
    }
}
