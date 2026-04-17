package com.zhongbo.mindos.assistant.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SkillControllerMutationPolicyTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldBlockReloadWhenLiveMutationIsDisabled() throws Exception {
        mockMvc.perform(post("/api/skills/reload"))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldBlockGenerateWhenLiveMutationIsDisabled() throws Exception {
        mockMvc.perform(post("/api/skills/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"request\":\"抓取某网站数据\"}"))
                .andExpect(status().isForbidden());
    }
}
