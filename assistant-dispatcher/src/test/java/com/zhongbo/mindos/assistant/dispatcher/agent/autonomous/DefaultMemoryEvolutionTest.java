package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.common.dto.CritiqueReportDto;
import com.zhongbo.mindos.assistant.common.dto.ExecutionTraceDto;
import com.zhongbo.mindos.assistant.common.dto.PlanStepDto;
import com.zhongbo.mindos.assistant.dispatcher.agent.procedure.ProceduralMemory;
import com.zhongbo.mindos.assistant.dispatcher.agent.procedure.Procedure;
import com.zhongbo.mindos.assistant.dispatcher.agent.procedure.ProcedureMatch;
import com.zhongbo.mindos.assistant.dispatcher.agent.procedure.ProcedureMemoryEngine;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.MasterOrchestrationResult;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5.DefaultPolicyUpdater;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5.InMemoryPlannerLearningStore;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5.PlannerLearningStore;
import com.zhongbo.mindos.assistant.memory.model.LongTaskStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultMemoryEvolutionTest {

    @Test
    void shouldWriteSemanticGraphAndProcedureSignals() {
        AutonomousTestFixtures.MemoryGatewayStub memoryGateway = new AutonomousTestFixtures.MemoryGatewayStub(List.of(), List.of());
        AutonomousTestFixtures.RecordingGraphMemoryGateway graphGateway = new AutonomousTestFixtures.RecordingGraphMemoryGateway();
        InMemoryPlannerLearningStore learningStore = new InMemoryPlannerLearningStore();
        DefaultPolicyUpdater policyUpdater = new DefaultPolicyUpdater(learningStore, memoryGateway, 1800L);
        ProceduralMemory proceduralMemory = new ProceduralMemory(new com.zhongbo.mindos.assistant.dispatcher.agent.procedure.InMemoryProcedureMemoryEngine());
        DefaultMemoryEvolution evolution = new DefaultMemoryEvolution(
                memoryGateway,
                graphGateway,
                proceduralMemory,
                policyUpdater
        );

        AutonomousGoal goal = new AutonomousGoal(
                "task:task-1",
                AutonomousGoalType.LONG_TASK,
                "推进长期任务",
                "完成自治循环的记忆固化",
                "llm.orchestrate",
                "task-1",
                95,
                Map.of(
                        "taskId", "task-1",
                        "taskFocusStep", "llm.orchestrate",
                        "taskCompletable", true
                ),
                List.of("blocked task"),
                Instant.now()
        );
        TaskGraph graph = TaskGraph.linear(List.of("llm.orchestrate", "semantic.analyze"), Map.of("taskId", "task-1"));
        MasterOrchestrationResult execution = new MasterOrchestrationResult(
                SkillResult.success("llm.orchestrate", "done"),
                new ExecutionTraceDto(
                        "multi-agent-master",
                        0,
                        new CritiqueReportDto(true, "multi-agent success", "learn-procedure"),
                        List.of(new PlanStepDto("planner", "success", "planner-agent", "ok", Instant.now(), Instant.now()))
                ),
                List.of(),
                Map.of("multiAgent.plan.graph", graph)
        );
        AutonomousEvaluation evaluation = new DefaultEvaluatorAgent().evaluate(goal, execution);

        MemoryEvolutionResult result = evolution.evolve("u1", goal, execution, evaluation, 420L, 128, "worker-1");

        assertTrue(result.semanticWritten());
        assertTrue(result.procedureRecorded());
        assertTrue(result.taskUpdated());
        assertTrue(result.graphUpdated());
        assertEquals(goal.goalId(), result.goalId());
        assertTrue(result.reasons().stream().anyMatch(reason -> reason.contains("reward=")));

        assertFalse(memoryGateway.semanticTexts.isEmpty());
        assertTrue(memoryGateway.semanticTexts.get(0).contains("自治目标"));
        assertNotNull(memoryGateway.lastLongTaskProgress);
        assertEquals("task-1", memoryGateway.lastLongTaskProgress.taskId());
        assertEquals("worker-1", memoryGateway.lastLongTaskProgress.workerId());
        assertTrue(memoryGateway.lastLongTaskProgress.markCompleted());

        PlannerLearningStore.LearningSnapshot snapshot = learningStore.snapshot("u1", "llm.orchestrate");
        assertEquals(1.0, snapshot.successRate());
        assertTrue(snapshot.rewardScore() > 0.5);
        assertEquals(1L, snapshot.sampleCount());

        assertTrue(graphGateway.nodes.containsKey("autonomous:goal:task:task-1"));
        assertTrue(graphGateway.nodes.containsKey("autonomous:strategy:long_task:llm.orchestrate"));
        assertTrue(graphGateway.nodes.containsKey("autonomous:dag:task:task-1"));
        assertTrue(graphGateway.edges.values().stream().anyMatch(edge -> "evaluated-by".equals(edge.relation())));
    }

    @Test
    void shouldPruneLowEfficiencyProcedures() {
        AutonomousTestFixtures.MemoryGatewayStub memoryGateway = new AutonomousTestFixtures.MemoryGatewayStub(List.of(), List.of());
        AutonomousTestFixtures.RecordingGraphMemoryGateway graphGateway = new AutonomousTestFixtures.RecordingGraphMemoryGateway();
        InMemoryPlannerLearningStore learningStore = new InMemoryPlannerLearningStore();
        AtomicBoolean deleted = new AtomicBoolean(false);
        Procedure lowProcedure = new Procedure("low-flow", "student.plan", "生成排课", List.of("student.get", "student.analyze"), 0.20, 4);
        ProceduralMemory proceduralMemory = new ProceduralMemory(new ProcedureMemoryEngine() {
            @Override
            public Procedure recordSuccessfulGraph(String userId, String intent, String trigger, TaskGraph graph, Map<String, Object> contextAttributes) {
                return lowProcedure;
            }

            @Override
            public List<ProcedureMatch> matchTemplates(String userId, String userInput, String suggestedTarget, int limit) {
                return List.of();
            }

            @Override
            public List<Procedure> listProcedures(String userId) {
                return List.of(lowProcedure);
            }

            @Override
            public boolean deleteProcedure(String userId, String procedureId) {
                deleted.set(true);
                graphGateway.deleteNode(userId, "procedure:" + procedureId);
                return true;
            }
        });
        DefaultMemoryEvolution evolution = new DefaultMemoryEvolution(
                memoryGateway,
                graphGateway,
                proceduralMemory,
                new DefaultPolicyUpdater(learningStore, memoryGateway, 1500L),
                "autonomous.evolution",
                1800L,
                1800L,
                0.45d,
                0.85d,
                0.0d,
                2
        );

        AutonomousGoal goal = new AutonomousGoal(
                "task:student.plan",
                AutonomousGoalType.LONG_TASK,
                "修复低效流程",
                "优化学生排课流程",
                "student.plan",
                "student.plan",
                90,
                Map.of("taskId", "task-9", "taskFocusStep", "student.plan", "taskCompletable", false),
                List.of("prune"),
                Instant.now()
        );
        TaskGraph graph = TaskGraph.linear(List.of("student.get", "student.analyze"), Map.of("taskId", "task-9"));
        MasterOrchestrationResult execution = new MasterOrchestrationResult(
                com.zhongbo.mindos.assistant.common.SkillResult.failure("student.plan", "failed"),
                new ExecutionTraceDto(
                        "multi-agent-master",
                        1,
                        new CritiqueReportDto(false, "流程低效", "prune-procedure"),
                        List.of(new PlanStepDto("executor", "failed", "executor-agent", "failed", Instant.now(), Instant.now()))
                ),
                List.of(),
                Map.of("multiAgent.plan.graph", graph)
        );
        AutonomousEvaluation evaluation = new DefaultEvaluatorAgent().evaluate(goal, execution);

        MemoryEvolutionResult result = evolution.evolve("u1", goal, execution, evaluation, 900L, 220, "worker-2");

        assertTrue(result.reasons().stream().anyMatch(reason -> reason.contains("proceduresPruned=1")));
        assertTrue(deleted.get());
        assertTrue(graphGateway.deletedNodeIds.contains("procedure:low-flow"));
        PlannerLearningStore.LearningSnapshot snapshot = learningStore.snapshot("u1", "student.plan");
        assertEquals(1L, snapshot.sampleCount());
        assertTrue(snapshot.rewardScore() < 0.5);
    }
}
