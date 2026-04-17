package com.zhongbo.mindos.assistant.api;

import com.zhongbo.mindos.assistant.dispatcher.orchestrator.InMemoryParamSchemaRegistry;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.ParamSchema;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.ParamSchemaRegistry;
import com.zhongbo.mindos.assistant.skill.examples.EchoSkill;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "mindos.security.skill.capability.guard.enabled=true",
        "mindos.security.skill.allowed-capabilities=fs.read",
        "mindos.security.skill.capability-map=echo:exec"
})
@AutoConfigureMockMvc
@Import(SkillCapabilityPolicyTest.TestConfig.class)
class SkillCapabilityPolicyTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldBlockSkillWhenCapabilityIsNotAllowlisted() throws Exception {
        mockMvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"cap-user\",\"message\":\"echo hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("security.guard"))
                .andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("缺少能力权限")));
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        EchoSkill testEchoSkill() {
            return new EchoSkill();
        }

        @Bean
        @Primary
        ParamSchemaRegistry testParamSchemaRegistry() {
            InMemoryParamSchemaRegistry registry = new InMemoryParamSchemaRegistry();
            registry.registerDefaults();
            registry.register("echo", ParamSchema.atLeastOne("text", "input"));
            return registry;
        }
    }
}
