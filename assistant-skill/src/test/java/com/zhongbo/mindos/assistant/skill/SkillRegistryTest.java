package com.zhongbo.mindos.assistant.skill;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillRegistryTest {

    @Test
    void shouldDetectSkillByConfiguredRoutingKeywords() {
        SkillRoutingProperties properties = new SkillRoutingProperties();
        properties.getKeywords().put("todo.create", "任务跟进路线,事项整理");
        SkillRegistry registry = new SkillRegistry(List.of(new FixedSkill("todo.create")), properties);

        String detected = registry.detect("帮我整理一个任务跟进路线")
                .map(Skill::name)
                .orElse("");

        assertEquals("todo.create", detected);
    }

    @Test
    void shouldMergeBuiltInAndConfiguredRoutingKeywords() {
        SkillRoutingProperties properties = new SkillRoutingProperties();
        properties.getKeywords().put("demo.skill", "自定义别名");
        SkillRegistry registry = new SkillRegistry(List.of(new KeywordSkill()), properties);

        List<String> keywords = registry.resolvedRoutingKeywords("demo.skill");

        assertTrue(keywords.contains("原始关键词"));
        assertTrue(keywords.contains("自定义别名"));
    }

    private record FixedSkill(String name) implements Skill {
        @Override
        public String description() {
            return name;
        }

        @Override
        public SkillResult run(SkillContext context) {
            return SkillResult.success(name, name);
        }
    }

    private static final class KeywordSkill implements Skill {
        @Override
        public String name() {
            return "demo.skill";
        }

        @Override
        public String description() {
            return "demo";
        }

        @Override
        public List<String> routingKeywords() {
            return List.of("原始关键词");
        }

        @Override
        public SkillResult run(SkillContext context) {
            return SkillResult.success(name(), "ok");
        }
    }
}

