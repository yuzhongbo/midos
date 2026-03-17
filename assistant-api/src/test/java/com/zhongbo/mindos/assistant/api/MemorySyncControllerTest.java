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

@SpringBootTest
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
                .andExpect(jsonPath("$.skippedCount").value(0));

        mockMvc.perform(post("/api/memory/local-user/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedCount").value(0))
                .andExpect(jsonPath("$.skippedCount").value(3));

        mockMvc.perform(get("/api/memory/local-user/sync")
                        .param("since", "0")
                        .param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.episodic[0].content").value("hello from terminal A"))
                .andExpect(jsonPath("$.semantic[0].text").value("order api design"))
                .andExpect(jsonPath("$.procedural[0].skillName").value("code.generate"));
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
                "\"sourceText\":\"第一步先梳理目标。第二步拆分任务。第三步每天复盘。\"" +
                "}";

        mockMvc.perform(post("/api/memory/plan-user/compress-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(planPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.style.styleName").value("action"))
                .andExpect(jsonPath("$.steps.length()").value(4))
                .andExpect(jsonPath("$.steps[0].stage").value("RAW"))
                .andExpect(jsonPath("$.steps[3].stage").value("STYLED"));

        mockMvc.perform(get("/api/memory/plan-user/style"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.styleName").value("action"));
    }
}

