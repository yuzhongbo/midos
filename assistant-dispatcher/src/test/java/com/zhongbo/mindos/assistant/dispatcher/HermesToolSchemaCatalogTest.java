package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.InMemoryParamSchemaRegistry;
import com.zhongbo.mindos.assistant.skill.DefaultSkillCatalog;
import com.zhongbo.mindos.assistant.skill.Skill;
import com.zhongbo.mindos.assistant.skill.SkillDescriptor;
import com.zhongbo.mindos.assistant.skill.SkillDescriptorProvider;
import com.zhongbo.mindos.assistant.skill.SkillRegistry;
import com.zhongbo.mindos.assistant.skill.SkillRoutingProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HermesToolSchemaCatalogTest {

    @Test
    void shouldExposeCapabilitySchemaInsteadOfRawExecutionSkill() {
        SkillRegistry registry = new SkillRegistry(List.of(new DescriptorSkill(
                "code.generate",
                "Generate or fix code on explicit request",
                List.of("修复代码", "生成代码")
        )));
        InMemoryParamSchemaRegistry paramSchemaRegistry = new InMemoryParamSchemaRegistry();
        paramSchemaRegistry.registerDefaults();
        HermesToolSchemaCatalog catalog = new HermesToolSchemaCatalog(
                new DefaultSkillCatalog(registry, null, new SkillRoutingProperties()),
                paramSchemaRegistry
        );

        List<String> schemaNames = catalog.listSchemas().stream()
                .map(HermesToolSchema::name)
                .toList();

        assertTrue(schemaNames.contains("code.assist"));
        assertFalse(schemaNames.contains("code.generate"));
        assertTrue(catalog.isKnownDecisionTarget("code.assist"));
        assertEquals("code.generate", catalog.executionTargetForDecision("code.assist"));
        assertFalse(catalog.isDecisionEligible("code.generate"));
    }

    @Test
    void shouldDetectFromCapabilitySurfaceInsteadOfHiddenExecutionKeywords() {
        SkillRegistry registry = new SkillRegistry(List.of(new DescriptorSkill(
                "code.generate",
                "Generate or fix code on explicit request",
                List.of("内部调试暗号")
        )));
        InMemoryParamSchemaRegistry paramSchemaRegistry = new InMemoryParamSchemaRegistry();
        paramSchemaRegistry.registerDefaults();
        HermesToolSchemaCatalog catalog = new HermesToolSchemaCatalog(
                new DefaultSkillCatalog(registry, null, new SkillRoutingProperties()),
                paramSchemaRegistry
        );

        List<String> capabilityCandidates = catalog.detectDecisionCandidates("请帮我修复代码", 3).stream()
                .map(candidate -> candidate.skillName())
                .toList();
        List<String> rawExecutionCandidates = catalog.detectDecisionCandidates("内部调试暗号", 3).stream()
                .map(candidate -> candidate.skillName())
                .toList();

        assertTrue(capabilityCandidates.contains("code.assist"));
        assertFalse(rawExecutionCandidates.contains("code.assist"));
    }

    private record DescriptorSkill(String name, String description, List<String> routingKeywords)
            implements Skill, SkillDescriptorProvider {

        @Override
        public SkillResult run(SkillContext context) {
            return SkillResult.success(name, "ok");
        }

        @Override
        public SkillDescriptor skillDescriptor() {
            return new SkillDescriptor(name, description, routingKeywords);
        }
    }
}
