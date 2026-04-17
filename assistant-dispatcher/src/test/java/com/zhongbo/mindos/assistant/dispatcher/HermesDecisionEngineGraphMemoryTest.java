package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.dto.PromptMemoryContextDto;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.InMemoryParamSchemaRegistry;
import com.zhongbo.mindos.assistant.skill.SkillCandidate;
import com.zhongbo.mindos.assistant.skill.SkillCatalogFacade;
import com.zhongbo.mindos.assistant.skill.SkillDescriptor;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HermesDecisionEngineGraphMemoryTest {

    @Test
    void shouldPreferGraphBackedSkillWhenSemanticCandidatesTie() {
        InMemoryParamSchemaRegistry schemaRegistry = new InMemoryParamSchemaRegistry();
        schemaRegistry.registerDefaults();
        SkillCatalogFacade skillCatalog = new TestSkillCatalog(List.of(
                new SkillDescriptor("todo.create", "Create todo item", List.of("待办", "任务")),
                new SkillDescriptor("file.search", "Search local files", List.of("找文件", "搜索文件"))
        ));
        HermesToolSchemaCatalog toolSchemaCatalog = new HermesToolSchemaCatalog(skillCatalog, schemaRegistry);
        HermesDecisionEngine engine = new HermesDecisionEngine(
                null,
                skillCatalog,
                toolSchemaCatalog,
                null,
                null,
                new LLMDecisionEngine(),
                new DispatchHeuristicsSupport(null, false, List.of(), false, true, Set.of("新闻", "天气")),
                DispatcherAnswerMode.BALANCED,
                null,
                List.of(),
                List.of()
        );
        SemanticAnalysisResult semanticAnalysis = new SemanticAnalysisResult(
                "semantic",
                "",
                "",
                "",
                Map.of(),
                List.of(),
                "",
                0.0d,
                List.of(
                        new SemanticAnalysisResult.CandidateIntent("todo.create", 0.70d),
                        new SemanticAnalysisResult.CandidateIntent("file.search", 0.70d)
                )
        );
        HermesDecisionContext context = new HermesDecisionContext(
                "u1",
                "继续推进这个任务",
                "继续推进这个任务",
                Map.of(),
                true,
                DispatcherAnswerMode.BALANCED,
                new PromptMemoryContextDto("", "", "", Map.of(), List.of()),
                "",
                List.of(),
                toolSchemaCatalog.listSchemas(Map.of()),
                semanticAnalysis,
                Map.of(),
                Map.of("todo.create", 0.82d),
                Map.of(),
                Map.of(),
                new SkillContext("u1", "继续推进这个任务", Map.of())
        );
        String expectedDecisionTarget = toolSchemaCatalog.decisionTargetForSkill("todo.create", Map.of());

        HermesDecisionEngine.DecisionPlan plan = engine.decide(context);

        assertEquals(expectedDecisionTarget, plan.decision().target());
        assertTrue(plan.reasons().stream().anyMatch(reason -> reason.startsWith("graph-memory=")));
    }

    @Test
    void shouldRouteShortContinuationFromGraphHintAndCompleteTaskParams() {
        InMemoryParamSchemaRegistry schemaRegistry = new InMemoryParamSchemaRegistry();
        schemaRegistry.registerDefaults();
        SkillCatalogFacade skillCatalog = new TestSkillCatalog(List.of(
                new SkillDescriptor("todo.create", "Create todo item", List.of("待办", "任务"))
        ));
        HermesToolSchemaCatalog toolSchemaCatalog = new HermesToolSchemaCatalog(skillCatalog, schemaRegistry);
        HermesDecisionEngine engine = new HermesDecisionEngine(
                null,
                skillCatalog,
                toolSchemaCatalog,
                null,
                null,
                new LLMDecisionEngine(),
                new DispatchHeuristicsSupport(null, false, List.of(), false, true, Set.of("新闻", "天气")),
                DispatcherAnswerMode.BALANCED,
                null,
                List.of(),
                List.of()
        );
        HermesDecisionContext context = new HermesDecisionContext(
                "u1",
                "开始吧",
                "开始吧",
                Map.of(),
                true,
                DispatcherAnswerMode.BALANCED,
                new PromptMemoryContextDto("", "", "", Map.of(), List.of()),
                "",
                List.of(),
                toolSchemaCatalog.listSchemas(Map.of()),
                SemanticAnalysisResult.empty(),
                Map.of(),
                Map.of("todo.create", 0.82d),
                Map.of(
                        "task", "提交周报",
                        "project", "运营周报",
                        "decisionTarget", "task.manage",
                        "executionTarget", "todo.create",
                        "canonicalSkill", "todo.create"
                ),
                Map.of(),
                new SkillContext("u1", "开始吧", Map.of())
        );
        String expectedDecisionTarget = toolSchemaCatalog.decisionTargetForSkill("todo.create", Map.of());

        HermesDecisionEngine.DecisionPlan plan = engine.decide(context);

        assertEquals(expectedDecisionTarget, plan.decision().target());
        assertEquals("提交周报", plan.decision().params().get("task"));
        assertEquals("运营周报", plan.decision().params().get("project"));
        assertTrue(plan.reasons().stream().anyMatch(reason -> reason.contains("continuation-like input matched recent execution memory")));
    }

    private static final class TestSkillCatalog implements SkillCatalogFacade {
        private final List<SkillDescriptor> descriptors;

        private TestSkillCatalog(List<SkillDescriptor> descriptors) {
            this.descriptors = descriptors == null ? List.of() : List.copyOf(descriptors);
        }

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
            return descriptors.stream().filter(descriptor -> descriptor.name().equals(skillName)).findFirst();
        }

        @Override
        public List<SkillDescriptor> listSkillDescriptors() {
            return descriptors;
        }

        @Override
        public String describeAvailableSkills() {
            return descriptors.stream().map(SkillDescriptor::name).reduce((left, right) -> left + ", " + right).orElse("");
        }

        @Override
        public List<String> listAvailableSkillSummaries() {
            return descriptors.stream()
                    .map(descriptor -> descriptor.name() + " - " + descriptor.description())
                    .toList();
        }
    }
}
