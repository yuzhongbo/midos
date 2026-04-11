package com.zhongbo.mindos.assistant.dispatcher.agent.runtime;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.procedure.InMemoryProcedureMemoryEngine;
import com.zhongbo.mindos.assistant.dispatcher.agent.search.SearchCandidate;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.DecisionOrchestrator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DualProcessCoordinatorTest {

    @Test
    void shouldUseSystem2WhenConfidenceIsLow() {
        DecisionOrchestrator orchestrator = new DecisionOrchestrator() {
            @Override
            public SkillResult execute(String userInput, String intent, Map<String, Object> params) {
                return SkillResult.success(intent, "ok");
            }

            @Override
            public OrchestrationOutcome orchestrate(Decision decision, OrchestrationRequest request) {
                return new OrchestrationOutcome(
                        SkillResult.success(decision.target(), "done:" + decision.target()),
                        null,
                        null,
                        null,
                        decision.target(),
                        false
                );
            }

            @Override public void recordOutcome(String userId, String userInput, SkillResult result, com.zhongbo.mindos.assistant.common.dto.ExecutionTraceDto trace) {}
        };

        DualProcessCoordinator coordinator = new DualProcessCoordinator(
                new DefaultSystem1Gate(0.9),
                request -> new System2Planner.PlanResult(
                        TaskGraph.linear(List.of("student.get", "student.analyze", "teaching.plan"), request.decision().params()),
                        new SearchCandidate(List.of("student.get", "student.analyze", "teaching.plan"), 0.92, List.of("search-plan"), Map.of()),
                        "search-plan"
                ),
                new com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraphExecutor(),
                new InMemoryProcedureMemoryEngine(),
                orchestrator
        );

        AgentDispatchResult result = coordinator.dispatch(new AgentDispatchRequest(
                new Decision("student.plan", "student.plan", Map.of("studentId", "stu-42"), 0.55, false),
                new DecisionOrchestrator.OrchestrationRequest("u1", "请给学生生成计划", new SkillContext("u1", "请给学生生成计划", Map.of()), Map.of())
        ));

        assertEquals(AgentMode.SYSTEM2, result.mode());
        assertEquals("teaching.plan", result.outcome().selectedSkill());
    }
}
