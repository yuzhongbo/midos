package com.zhongbo.mindos.assistant.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhongbo.mindos.assistant.skill.SkillRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "mindos.skills.live-mutation.enabled=true")
@AutoConfigureMockMvc
class SkillControllerDraftPolicyTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SkillRegistry skillRegistry;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void shouldGenerateDraftWithoutRegisteringByDefault() throws Exception {
        String body = mockMvc.perform(post("/api/skills/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"request\":\"抓取某网站数据\",\"skillName\":\"web.scrape\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("draft"))
                .andExpect(jsonPath("$.registered").value(false))
                .andExpect(jsonPath("$.skillName").isString())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Map<String, Object> response = objectMapper.readValue(body, new TypeReference<>() {});
        String skillName = String.valueOf(response.get("skillName"));
        assertTrue(skillName.startsWith("generated.web.scrape."));
        assertFalse(skillRegistry.containsSkill(skillName));
    }
}
