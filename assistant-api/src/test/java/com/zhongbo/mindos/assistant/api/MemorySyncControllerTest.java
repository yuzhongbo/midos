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

@SpringBootTest(properties = "mindos.memory.file-repo.enabled=false")
@AutoConfigureMockMvc
class MemorySyncControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldApplyAndFetchIncrementalMemoryUpdates() throws Exception {
        String payload = "{" +
                "\"eventId\":\"evt-1\"," +
                "\"episodic\":[{\"role\":\"user\",\"content\":\"hello from terminal A\"}]," +
                "\"semantic\":[{\"text\":\"order api design\",\"embedding\":[1.0,0.2]}]," +
                "\"procedural\":[{\"skillName\":\"code.generate\",\"input\":\"create order api\",\"success\":true}]" +
                "}";

        mockMvc.perform(post("/api/memory/local-user/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cursor").isNumber())
                .andExpect(jsonPath("$.acceptedCount").value(3))
                .andExpect(jsonPath("$.skippedCount").value(0))
                .andExpect(jsonPath("$.deduplicatedCount").value(0));

        mockMvc.perform(post("/api/memory/local-user/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedCount").value(0))
                .andExpect(jsonPath("$.skippedCount").value(3))
                .andExpect(jsonPath("$.deduplicatedCount").value(3));

        mockMvc.perform(get("/api/memory/local-user/sync")
                        .param("since", "0")
                        .param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.episodic[0].content").value("hello from terminal A"))
                .andExpect(jsonPath("$.semantic[0].text").value("order api design"))
                .andExpect(jsonPath("$.procedural[0].skillName").value("code.generate"));

        mockMvc.perform(get("/api/memory/local-user/retrieve-preview")
                        .param("query", "order api")
                        .param("maxChars", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recentConversation").isNotEmpty())
                .andExpect(jsonPath("$.semanticContext").isNotEmpty())
                .andExpect(jsonPath("$.proceduralHints").isNotEmpty())
                .andExpect(jsonPath("$.debugTopItems").isArray())
                .andExpect(jsonPath("$.debugTopItems[0].type").isNotEmpty())
                .andExpect(jsonPath("$.debugTopItems[0].finalScore").isNumber());
    }

    @Test
    void shouldTrimRetrievePreviewByMaxChars() throws Exception {
        String payload = "{" +
                "\"eventId\":\"evt-preview-trim\"," +
                "\"episodic\":[{" +
                "\"role\":\"user\",\"content\":\"请记住这是一段很长很长的上下文内容用于测试长度控制\"}]," +
                "\"semantic\":[{" +
                "\"text\":\"这是一条较长的语义记忆文本，用于观察 retrieve-preview 的 budget 裁剪行为\"}]," +
                "\"procedural\":[{" +
                "\"skillName\":\"todo.create\",\"input\":\"准备周报\",\"success\":true}]" +
                "}";

        mockMvc.perform(post("/api/memory/preview-user/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/memory/preview-user/retrieve-preview")
                        .param("query", "上下文")
                        .param("maxChars", "420"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recentConversation").isString())
                .andExpect(jsonPath("$.semanticContext").isString())
                .andExpect(jsonPath("$.proceduralHints").isString())
                .andExpect(jsonPath("$.personaSnapshot").isMap());
    }

    @Test
    void shouldExposeStructuredMemoryAfterChatTurn() throws Exception {
        mockMvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"preview-chat-user\",\"message\":\"给我一个数学学习计划，目标是期末提分，六周，每周八小时\",\"profile\":{\"language\":\"zh-CN\",\"timezone\":\"Asia/Shanghai\"}}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/memory/preview-chat-user/retrieve-preview")
                        .param("query", "继续按之前方式")
                        .param("maxChars", "1200")
                        .param("language", "zh-CN")
                        .param("timezone", "Asia/Shanghai"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recentConversation").isNotEmpty())
                .andExpect(jsonPath("$.semanticContext").isString())
                .andExpect(jsonPath("$.proceduralHints").isString())
                .andExpect(jsonPath("$.personaSnapshot.language").value("zh-CN"))
                .andExpect(jsonPath("$.personaSnapshot.timezone").value("Asia/Shanghai"));
    }

    @Test
    void shouldConsolidateDuplicateSemanticEntriesOnApply() throws Exception {
        String payload = "{" +
                "\"eventId\":\"evt-dup\"," +
                "\"episodic\":[]," +
                "\"semantic\":[" +
                "{\"text\":\"  Spring   Boot  \"}," +
                "{\"text\":\"Spring Boot\"}" +
                "]," +
                "\"procedural\":[]" +
                "}";

        mockMvc.perform(post("/api/memory/local-user-consolidated/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedCount").value(1))
                .andExpect(jsonPath("$.skippedCount").value(0))
                .andExpect(jsonPath("$.semantic.length()").value(1))
                .andExpect(jsonPath("$.semantic[0].text").value("Spring Boot"));
    }

    @Test
    void shouldSupportMemoryCompressionPlanWithStyleProfile() throws Exception {
        String stylePayload = "{" +
                "\"styleName\":\"action\"," +
                "\"tone\":\"warm\"," +
                "\"outputFormat\":\"bullet\"" +
                "}";

        mockMvc.perform(post("/api/memory/plan-user/style")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(stylePayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.styleName").value("action"))
                .andExpect(jsonPath("$.tone").value("warm"))
                .andExpect(jsonPath("$.outputFormat").value("bullet"));

        String planPayload = "{" +
                "\"sourceText\":\"第一步先梳理目标。第二步拆分任务。第三步每天复盘。\"," +
                "\"focus\":\"task\"" +
                "}";

        mockMvc.perform(post("/api/memory/plan-user/compress-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(planPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.style.styleName").value("action"))
                .andExpect(jsonPath("$.steps.length()").value(4))
                .andExpect(jsonPath("$.steps[0].stage").value("RAW"))
                .andExpect(jsonPath("$.steps[3].stage").value("STYLED"))
                .andExpect(jsonPath("$.steps[3].content").value(org.hamcrest.Matchers.containsString("[任务聚焦]")));

        mockMvc.perform(get("/api/memory/plan-user/style"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.styleName").value("action"));
    }

    @Test
    void shouldFallbackToRecentConversationWhenCompressionSourceIsBlank() throws Exception {
        String syncPayload = "{" +
                "\"eventId\":\"evt-chat\"," +
                "\"episodic\":[{" +
                "\"role\":\"user\",\"content\":\"今天先整理目标，再拆任务\"},{" +
                "\"role\":\"assistant\",\"content\":\"建议分成三步执行\"}]," +
                "\"semantic\":[]," +
                "\"procedural\":[]" +
                "}";

        mockMvc.perform(post("/api/memory/fallback-user/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(syncPayload))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/memory/fallback-user/compress-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceText\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].stage").value("RAW"))
                .andExpect(jsonPath("$.steps[0].content").value(org.hamcrest.Matchers.containsString("今天先整理目标，再拆任务")))
                .andExpect(jsonPath("$.steps[0].content").value(org.hamcrest.Matchers.containsString("assistant: 建议分成三步执行")));
    }

    @Test
    void shouldAutoTuneStyleWhenRequestedByQuery() throws Exception {
        String stylePayload = "{" +
                "\"styleName\":\"\"," +
                "\"tone\":\"\"," +
                "\"outputFormat\":\"\"" +
                "}";

        mockMvc.perform(post("/api/memory/tune-user/style")
                        .param("autoTune", "true")
                        .param("sampleText", "请帮我按步骤拆分任务清单")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(stylePayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.styleName").value("action"))
                .andExpect(jsonPath("$.tone").value("warm"));
    }

    @Test
    void shouldExposeConfirmedPersonaProfile() throws Exception {
        String profileJson = "{\"assistantName\":\"MindOS\",\"role\":\"高一\",\"style\":\"练习优先\",\"language\":\"zh-CN\",\"timezone\":\"Asia/Shanghai\"}";

        mockMvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"userId\":\"persona-user\",\"message\":\"给我一个数学学习计划，目标是期末提分，六周，每周八小时\",\"profile\":%s}", profileJson)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/memory/persona-user/persona"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assistantName").value("MindOS"))
                .andExpect(jsonPath("$.role").value("高一"))
                .andExpect(jsonPath("$.style").value("练习优先"))
                .andExpect(jsonPath("$.language").value("zh-CN"))
                .andExpect(jsonPath("$.timezone").value("Asia/Shanghai"));
    }

    @Test
    void shouldHidePendingConflictingPersonaValueUntilConfirmed() throws Exception {
        String stableProfile = "{\"assistantName\":\"MindOS\",\"role\":\"高一\",\"style\":\"练习优先\",\"language\":\"zh-CN\",\"timezone\":\"Asia/Shanghai\"}";
        String conflictingProfile = "{\"assistantName\":\"MindOS\",\"role\":\"程序员\",\"style\":\"直接\",\"language\":\"zh-CN\",\"timezone\":\"Asia/Shanghai\"}";

        mockMvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"userId\":\"persona-conflict-user\",\"message\":\"给我一个数学学习计划，目标是期末提分，六周，每周八小时\",\"profile\":%s}", stableProfile)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"userId\":\"persona-conflict-user\",\"message\":\"给我一个数学学习计划，目标是冲刺提分，六周，每周八小时\",\"profile\":%s}", conflictingProfile)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/memory/persona-conflict-user/persona"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("高一"))
                .andExpect(jsonPath("$.style").value("练习优先"));

        mockMvc.perform(get("/api/memory/persona-conflict-user/persona/explain"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmed.role").value("高一"))
                .andExpect(jsonPath("$.pendingOverrides.length()").value(2))
                .andExpect(jsonPath("$.pendingOverrides[0].confirmThreshold").value(2));
    }

    @Test
    void shouldPromoteConflictAfterThresholdInPersonaExplain() throws Exception {
        String stableProfile = "{\"assistantName\":\"MindOS\",\"role\":\"高一\",\"style\":\"练习优先\",\"language\":\"zh-CN\",\"timezone\":\"Asia/Shanghai\"}";
        String conflictingProfile = "{\"assistantName\":\"MindOS\",\"role\":\"程序员\",\"style\":\"直接\",\"language\":\"zh-CN\",\"timezone\":\"Asia/Shanghai\"}";

        mockMvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"userId\":\"persona-promote-user\",\"message\":\"给我一个数学学习计划，目标是期末提分，六周，每周八小时\",\"profile\":%s}", stableProfile)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"userId\":\"persona-promote-user\",\"message\":\"给我一个数学学习计划，目标是冲刺提分，六周，每周八小时\",\"profile\":%s}", conflictingProfile)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"userId\":\"persona-promote-user\",\"message\":\"给我一个数学学习计划，目标是模拟冲刺，六周，每周八小时\",\"profile\":%s}", conflictingProfile)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/memory/persona-promote-user/persona/explain"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmed.role").value("程序员"))
                .andExpect(jsonPath("$.confirmed.style").value("直接"))
                .andExpect(jsonPath("$.pendingOverrides.length()").value(0));
    }
}

