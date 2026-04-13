package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.skill.SkillCandidate;
import com.zhongbo.mindos.assistant.skill.SkillCatalogFacade;
import com.zhongbo.mindos.assistant.skill.SkillDescriptor;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultDecisionPlannerTest {

    @Test
    void shouldMapLegacyIntentToCanonicalTarget() {
        DefaultDecisionPlanner planner = new DefaultDecisionPlanner(new StubSkillCatalogFacade());

        Decision decision = planner.plan("帮我安排今天的任务", "task", Map.of(), new SkillContext("u1", "帮我安排今天的任务", Map.of()));

        assertEquals("task", decision.intent());
        assertEquals("todo.create", decision.target());
    }

    @Test
    void shouldPreserveDirectSkillIntentAsTarget() {
        DefaultDecisionPlanner planner = new DefaultDecisionPlanner(new StubSkillCatalogFacade());

        Decision decision = planner.plan("建个待办", "todo.create", Map.of(), new SkillContext("u1", "建个待办", Map.of()));

        assertEquals("todo.create", decision.intent());
        assertEquals("todo.create", decision.target());
    }

    @Test
    void shouldMapSemanticIntentWhenSuggestedSkillMissing() {
        DefaultDecisionPlanner planner = new DefaultDecisionPlanner(new StubSkillCatalogFacade());
        SkillContext context = new SkillContext("u1", "做个学习计划", Map.of(
                SemanticAnalysisResult.ATTR_INTENT, "learning",
                SemanticAnalysisResult.ATTR_CONFIDENCE, 0.82
        ));

        Decision decision = planner.plan("做个学习计划", "", Map.of(), context);

        assertEquals("learning", decision.intent());
        assertEquals("teaching.plan", decision.target());
        assertEquals(0.82, decision.confidence());
    }

    @Test
    void shouldMoveEchoRuleFallbackIntoPlanner() {
        DefaultDecisionPlanner planner = new DefaultDecisionPlanner(new StubSkillCatalogFacade());

        Decision decision = planner.plan("echo hello planner", "", Map.of(), new SkillContext("u1", "echo hello planner", Map.of()));

        assertEquals("echo", decision.target());
        assertEquals("hello planner", decision.params().get("text"));
        assertEquals("rule-fallback", decision.params().get("_plannerRouteSource"));
        assertEquals(0.75, decision.confidence());
    }

    @Test
    void shouldMoveTeachingPlanRuleFallbackIntoPlanner() {
        DefaultDecisionPlanner planner = new DefaultDecisionPlanner(new StubSkillCatalogFacade());

        Decision decision = planner.plan("帮我做一个六周数学学习计划", "", Map.of(), new SkillContext("u1", "帮我做一个六周数学学习计划", Map.of()));

        assertEquals("teaching.plan", decision.target());
        assertEquals("rule-fallback", decision.params().get("_plannerRouteSource"));
        assertTrue(decision.params().containsKey("topic"));
    }

    private static final class StubSkillCatalogFacade implements SkillCatalogFacade {
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
            return Optional.empty();
        }

        @Override
        public List<SkillDescriptor> listSkillDescriptors() {
            return List.of();
        }

        @Override
        public String describeAvailableSkills() {
            return "";
        }

        @Override
        public List<String> listAvailableSkillSummaries() {
            return List.of();
        }
    }
}
