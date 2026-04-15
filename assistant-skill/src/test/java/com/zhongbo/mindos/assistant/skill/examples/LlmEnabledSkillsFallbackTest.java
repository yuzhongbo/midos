package com.zhongbo.mindos.assistant.skill.examples;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmEnabledSkillsFallbackTest {

    @Test
    void echoSkillShouldFallbackWhenLlmFailsAndUserIdIsNull() {
        EchoSkill skill = new EchoSkill((prompt, context) -> {
            assertEquals("", context.get("userId"));
            throw new RuntimeException("boom");
        });

        SkillResult result = skill.run(new SkillContext(null, "echo hello", Map.of()));

        assertTrue(result.success());
        assertEquals("hello", result.output());
    }

    @Test
    void timeSkillShouldFallbackWhenLlmReturnsBlank() {
        TimeSkill skill = new TimeSkill((prompt, context) -> "   ");

        SkillResult result = skill.run(new SkillContext(null, "time", Map.of(
                "language", "en-US",
                "timezone", "UTC"
        )));

        assertTrue(result.success());
        assertTrue(result.output().startsWith("Current time: "));
        assertTrue(result.output().contains("UTC"));
    }

    @Test
    void timeSkillShouldFormatChineseLocaleAndTimezone() {
        TimeSkill skill = new TimeSkill((prompt, context) -> "");

        SkillResult result = skill.run(new SkillContext(null, "现在几点了", Map.of(
                "language", "zh-CN",
                "timezone", "Asia/Shanghai"
        )));

        assertTrue(result.success());
        assertTrue(result.output().startsWith("当前时间："));
        assertTrue(result.output().contains("202") || result.output().contains("年"));
    }

    @Test
    void codeGenerateSkillShouldFailWhenLlmFails() {
        CodeGenerateSkill skill = new CodeGenerateSkill((prompt, context) -> {
            throw new RuntimeException("boom");
        });

        SkillResult result = skill.run(new SkillContext(null, "", Map.of("task", "generate java dto")));

        assertTrue(!result.success());
        assertTrue(result.output().contains("code.generate 调用 LLM 失败"));
    }

    @Test
    void todoCreateSkillShouldFallbackWhenLlmReturnsBlank() {
        TodoCreateSkill skill = new TodoCreateSkill((prompt, context) -> "");

        SkillResult result = skill.run(new SkillContext(null, "", Map.of("task", "write tests", "dueDate", "tomorrow")));

        assertTrue(result.success());
        assertTrue(result.output().contains("待办"));
        assertTrue(result.output().contains("write tests"));
    }

    @Test
    void fileSearchSkillShouldFallbackWhenLlmFails() {
        FileSearchSkill skill = new FileSearchSkill((prompt, context) -> {
            assertEquals("", context.get("userId"));
            throw new RuntimeException("boom");
        });

        SkillResult result = skill.run(new SkillContext(null, "", Map.of("path", "./", "keyword", "README")));

        assertTrue(result.success());
        assertTrue(result.output().contains("缩小范围"));
    }
}
