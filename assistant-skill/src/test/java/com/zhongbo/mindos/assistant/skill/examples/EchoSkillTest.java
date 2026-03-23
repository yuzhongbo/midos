package com.zhongbo.mindos.assistant.skill.examples;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EchoSkillTest {

    @Test
    void shouldPreferDslTextAttributeWhenPresent() {
        EchoSkill skill = new EchoSkill();

        SkillResult result = skill.run(new SkillContext(
                "u1",
                "请帮我自动处理这个请求",
                Map.of("text", "auto-routed by llm-dsl")
        ));

        assertTrue(result.success());
        assertEquals("echo", result.skillName());
        assertEquals("auto-routed by llm-dsl", result.output());
    }
}

