package com.zhongbo.mindos.assistant.dispatcher.routing;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.skill.Skill;
import com.zhongbo.mindos.assistant.skill.SkillCatalogFacade;
import com.zhongbo.mindos.assistant.skill.SkillDescriptor;
import com.zhongbo.mindos.assistant.skill.SkillRegistry;
import com.zhongbo.mindos.assistant.skill.SkillRoutingProperties;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutingCoordinatorTest {

    @Test
    void shouldPrepareMultiAgentDispatchPlanFromSemanticAnalysis() {
        List<SkillDescriptor> descriptors = List.of(
                new SkillDescriptor("todo.create", "Create todo", List.of("todo", "create"))
        );
        SkillCatalogFacade skillEngine = new SkillCatalogFacade() {
            @Override
            public Optional<String> detectSkillName(String input) {
                return Optional.empty();
            }

            @Override
            public List<com.zhongbo.mindos.assistant.skill.SkillCandidate> detectSkillCandidates(String input, int limit) {
                return List.of();
            }

            @Override
            public Optional<SkillDescriptor> describeSkill(String skillName) {
                return Optional.empty();
            }

            @Override
            public List<SkillDescriptor> listSkillDescriptors() {
                return descriptors;
            }

            @Override
            public String describeAvailableSkills() {
                return String.join(", ", listAvailableSkillSummaries());
            }

            @Override
            public List<String> listAvailableSkillSummaries() {
                return List.of("todo.create - Create todo");
            }
        };

        RoutingCoordinator coordinator = new RoutingCoordinator(skillEngine);
        SemanticAnalysisResult semantic = new SemanticAnalysisResult(
                "semantic",
                "organize",
                "rewritten input",
                "todo.create",
                Map.of("priority", "high"),
                List.of("task"),
                0.61
        );
        SkillContext context = new SkillContext("u1", "帮我整理任务", Map.of("seed", "value"));

        DispatchPlan plan = coordinator.preparePlan(
                "帮我整理任务",
                semantic,
                context,
                Map.of("multiAgent", true, "orchestrationMode", "master-orchestrator")
        );

        assertEquals(List.of("todo.create - Create todo"), coordinator.skillSummaries());
        assertEquals(descriptors, coordinator.skillDescriptors());
        assertEquals(RoutingStage.MULTI_AGENT, plan.stage());
        assertTrue(plan.usesMultiAgent());
        assertEquals(Map.of("multiAgent", true, "orchestrationMode", "master-orchestrator"), plan.profileContext());

        Decision decision = plan.decision();
        assertNotNull(decision);
        assertEquals("organize", decision.intent());
        assertEquals("todo.create", decision.target());
        assertEquals(0.75d, decision.confidence(), 0.0001d);
        assertEquals("帮我整理任务", decision.params().get("input"));
        assertEquals(Boolean.TRUE, decision.params().get("multiAgent"));
        assertEquals("multi-agent", decision.params().get("orchestrationMode"));
        assertEquals(Map.of("priority", "high"), decision.params().get("semanticPayload"));
        assertEquals(List.of("task"), decision.params().get("semanticKeywords"));
    }

    private record TestSkill(String name) implements Skill {
        @Override
        public String description() {
            return name;
        }

        @Override
        public com.zhongbo.mindos.assistant.common.SkillResult run(SkillContext context) {
            return com.zhongbo.mindos.assistant.common.SkillResult.success(name, name);
        }
    }
}
