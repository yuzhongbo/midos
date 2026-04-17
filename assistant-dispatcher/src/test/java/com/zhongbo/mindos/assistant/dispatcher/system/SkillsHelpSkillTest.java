package com.zhongbo.mindos.assistant.dispatcher.system;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.skill.SkillCandidate;
import com.zhongbo.mindos.assistant.skill.SkillCatalogFacade;
import com.zhongbo.mindos.assistant.skill.SkillDescriptor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillsHelpSkillTest {

    @Test
    void shouldRenderCapabilityLedHelpSurfaceAndHideRawDiagnostics() {
        SkillCatalogFacade catalog = new StubSkillCatalog(
                List.of(
                        new SkillDescriptor("time", "Current time lookup", List.of("现在几点")),
                        new SkillDescriptor("teaching.plan", "Build a learning plan", List.of("学习计划")),
                        new SkillDescriptor("code.generate", "Generate or fix code", List.of("修复代码")),
                        new SkillDescriptor("file.search", "Search files in workspace", List.of("找文件")),
                        new SkillDescriptor("echo", "Echo input", List.of("echo")),
                        new SkillDescriptor("semantic.analyze", "Semantic diagnostic", List.of("semantic")),
                        new SkillDescriptor("llm.orchestrate", "Legacy orchestration", List.of("调用模型"))
                ),
                List.of(
                        "time - Current time lookup",
                        "teaching.plan - Build a learning plan",
                        "code.generate - Generate or fix code",
                        "file.search - Search files in workspace",
                        "echo - Echo input",
                        "semantic.analyze - Semantic diagnostic",
                        "llm.orchestrate - Legacy orchestration",
                        "mcp.docs.searchDocs - Search official docs"
                )
        );
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("skillCatalog", catalog);
        SkillsHelpSkill skill = new SkillsHelpSkill(beanFactory.getBeanProvider(SkillCatalogFacade.class));

        String output = skill.run(new SkillContext("u1", "你有哪些技能？", Map.of())).output();

        assertTrue(output.contains("learning.plan"));
        assertTrue(output.contains("echo"));
        assertTrue(output.contains("time.lookup"));
        assertTrue(output.contains("docs.lookup"));
        assertTrue(output.contains("默认展示的是能力面"));
        assertTrue(output.contains("开发相关能力不需要你先切换角色"));
        assertFalse(output.contains("code.assist"));
        assertFalse(output.contains("workspace.search"));
        assertFalse(output.contains("semantic.analyze"));
        assertFalse(output.contains("llm.orchestrate"));
        assertFalse(output.contains("mcp.docs.searchDocs"));
    }

    @Test
    void shouldRevealDeveloperCapabilitiesForProgrammerRole() {
        SkillCatalogFacade catalog = new StubSkillCatalog(
                List.of(
                        new SkillDescriptor("time", "Current time lookup", List.of("现在几点")),
                        new SkillDescriptor("teaching.plan", "Build a learning plan", List.of("学习计划")),
                        new SkillDescriptor("code.generate", "Generate or fix code", List.of("修复代码")),
                        new SkillDescriptor("file.search", "Search files in workspace", List.of("找文件"))
                ),
                List.of(
                        "time - Current time lookup",
                        "teaching.plan - Build a learning plan",
                        "code.generate - Generate or fix code",
                        "file.search - Search files in workspace",
                        "mcp.docs.searchDocs - Search official docs"
                )
        );
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("skillCatalog", catalog);
        SkillsHelpSkill skill = new SkillsHelpSkill(beanFactory.getBeanProvider(SkillCatalogFacade.class));

        String output = skill.run(new SkillContext("u1", "你有哪些技能？", Map.of("role", "programmer"))).output();

        assertTrue(output.contains("time.lookup"));
        assertTrue(output.contains("docs.lookup"));
        assertTrue(output.contains("code.assist"));
        assertTrue(output.contains("workspace.search"));
        assertFalse(output.contains("开发相关能力不需要你先切换角色"));
    }

    private record StubSkillCatalog(List<SkillDescriptor> descriptors, List<String> summaries) implements SkillCatalogFacade {

        @Override
        public Optional<String> detectSkillName(String input) {
            return Optional.empty();
        }

        @Override
        public List<SkillCandidate> detectSkillCandidates(String input, int limit) {
            return List.of();
        }

        @Override
        public Optional<SkillDescriptor> describeSkill(String skillName) {
            return descriptors.stream()
                    .filter(descriptor -> descriptor.name().equals(skillName))
                    .findFirst();
        }

        @Override
        public List<SkillDescriptor> listSkillDescriptors() {
            return descriptors;
        }

        @Override
        public String describeAvailableSkills() {
            return String.join(", ", summaries);
        }

        @Override
        public List<String> listAvailableSkillSummaries() {
            return summaries;
        }
    }
}
