package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.dispatcher.DecisionSignal;
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

        Decision decision = planner.plan(
                new DecisionOrchestrator.UserInput("u1", "帮我安排今天的任务", new SkillContext("u1", "帮我安排今天的任务", Map.of()), Map.of()),
                List.of(new DecisionSignal("task", 0.96, "intent"))
        );

        assertEquals("task", decision.intent());
        assertEquals("todo.create", decision.target());
    }

    @Test
    void shouldPreserveDirectSkillIntentAsTarget() {
        DefaultDecisionPlanner planner = new DefaultDecisionPlanner(new StubSkillCatalogFacade());

        Decision decision = planner.plan(
                new DecisionOrchestrator.UserInput("u1", "建个待办", new SkillContext("u1", "建个待办", Map.of()), Map.of()),
                List.of(new DecisionSignal("todo.create", 1.0, "explicit"))
        );

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

        Decision decision = planner.plan(
                new DecisionOrchestrator.UserInput("u1", "做个学习计划", context, Map.of()),
                List.of(new DecisionSignal("learning", 0.82, "semantic"))
        );

        assertEquals("learning", decision.intent());
        assertEquals("teaching.plan", decision.target());
        assertEquals(0.82, decision.confidence());
    }

    @Test
    void shouldClarifyWhenNoSignalsSelectEcho() {
        DefaultDecisionPlanner planner = new DefaultDecisionPlanner(new StubSkillCatalogFacade());

        Decision decision = planner.plan(
                new DecisionOrchestrator.UserInput("u1", "echo hello planner", new SkillContext("u1", "echo hello planner", Map.of()), Map.of()),
                List.of()
        );

        assertTrue(decision.requireClarify());
        assertEquals("", decision.target());
        assertEquals("需要更多信息才能确定执行目标。", decision.params().get("_planner.clarifyMessage"));
    }

    @Test
    void shouldClarifyWhenNoSignalsSelectTeachingPlan() {
        DefaultDecisionPlanner planner = new DefaultDecisionPlanner(new StubSkillCatalogFacade());

        Decision decision = planner.plan(
                new DecisionOrchestrator.UserInput("u1", "帮我做一个六周数学学习计划", new SkillContext("u1", "帮我做一个六周数学学习计划", Map.of()), Map.of()),
                List.of()
        );

        assertTrue(decision.requireClarify());
        assertEquals("", decision.target());
        assertEquals("需要更多信息才能确定执行目标。", decision.params().get("_planner.clarifyMessage"));
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
