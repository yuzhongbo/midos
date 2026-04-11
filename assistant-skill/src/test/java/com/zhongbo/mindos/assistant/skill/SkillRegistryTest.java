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

    @Test
    void shouldExposeSkillDescriptorsWithMergedRoutingKeywords() {
        SkillRoutingProperties properties = new SkillRoutingProperties();
        properties.getKeywords().put("todo.create", "事项整理");
        SkillRegistry registry = new SkillRegistry(List.of(
                new DescriptorSkill("alpha.clean", "Clean alpha"),
                new DescriptorSkill("todo.create", "Create todo")
        ), properties);

        SkillDescriptor descriptor = registry.describeSkill("todo.create").orElseThrow();
        List<SkillDescriptor> descriptors = registry.listSkillDescriptors();

        assertEquals("todo.create", descriptor.name());
        assertEquals("Create todo", descriptor.description());
        assertTrue(descriptor.routingKeywords().contains("事项整理"));
        assertTrue(descriptor.routingKeywords().contains("todo create"));
        assertEquals(List.of("alpha.clean", "todo.create"), descriptors.stream().map(SkillDescriptor::name).toList());
        assertEquals(descriptor, descriptors.get(1));
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

    private static final class KeywordSkill implements Skill, SkillDescriptorProvider {
        @Override
        public String name() {
            return "demo.skill";
        }

        @Override
        public String description() {
            return "demo";
        }

        @Override
        public SkillDescriptor skillDescriptor() {
            return new SkillDescriptor(name(), description(), List.of("原始关键词"));
        }

        @Override
        public SkillResult run(SkillContext context) {
            return SkillResult.success(name(), "ok");
        }
    }

    private record DescriptorSkill(String name, String description) implements Skill, SkillDescriptorProvider {
        @Override
        public SkillResult run(SkillContext context) {
            return SkillResult.success(name, name);
        }

        @Override
        public SkillDescriptor skillDescriptor() {
            return new SkillDescriptor(name, description, List.of("todo", name.replace('.', ' ')));
        }
    }
}
