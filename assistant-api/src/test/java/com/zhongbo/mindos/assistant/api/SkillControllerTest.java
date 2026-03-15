package com.zhongbo.mindos.assistant.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SkillControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldListRegisteredSkills() throws Exception {
        mockMvc.perform(get("/api/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", hasItem("echo")))
                .andExpect(jsonPath("$[*].name", hasItem("time")));
    }

    @Test
    void shouldReloadCustomSkills() throws Exception {
        mockMvc.perform(post("/api/skills/reload"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.reloaded").isNumber());
    }

    @Test
    void shouldRejectLoadJarRequestWithoutUrl() throws Exception {
        mockMvc.perform(post("/api/skills/load-jar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.error").value("Field 'url' is required."));
    }

    @Test
    void shouldReloadMcpSkills() throws Exception {
        mockMvc.perform(post("/api/skills/reload-mcp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.reloaded").isNumber());
    }

    @Test
    void shouldRejectLoadMcpRequestWithoutAlias() throws Exception {
        mockMvc.perform(post("/api/skills/load-mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"http://localhost:8081/mcp\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.error").value("Field 'alias' is required."));
    }

    @Test
    void shouldRejectLoadMcpRequestWithoutUrl() throws Exception {
        mockMvc.perform(post("/api/skills/load-mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alias\":\"docs\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.error").value("Field 'url' is required."));
    }
}

