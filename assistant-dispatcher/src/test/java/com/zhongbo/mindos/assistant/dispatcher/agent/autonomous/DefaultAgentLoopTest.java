package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.common.dto.CritiqueReportDto;
import com.zhongbo.mindos.assistant.common.dto.ExecutionTraceDto;
import com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.MasterOrchestrationResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultAgentLoopTest {

    @Test
    void shouldRunMultipleAutonomousCycles() {
        AutonomousGoal goal = new AutonomousGoal(
                "loop-goal",
                AutonomousGoalType.MEMORY_REVIEW,
                "整理记忆",
                "归纳最近的执行结果",
                "llm.orchestrate",
                "loop-source",
                70,
                Map.of(),
                List.of("loop"),
                Instant.now()
        );
        MasterOrchestrationResult execution = new MasterOrchestrationResult(
                SkillResult.success("llm.orchestrate", "done"),
                new ExecutionTraceDto(
                        "multi-agent-master",
                        0,
                        new CritiqueReportDto(true, "multi-agent success", "learn-procedure"),
                        List.of()
                ),
                List.of(),
                Map.of()
        );

        AtomicInteger goalCalls = new AtomicInteger();
        GoalGenerator goalGenerator = (userId, limit) -> {
            goalCalls.incrementAndGet();
            return List.of(goal);
        };
        AutonomousTestFixtures.StubMasterOrchestrator masterOrchestrator = new AutonomousTestFixtures.StubMasterOrchestrator(execution);
        AtomicInteger evolutionCalls = new AtomicInteger();
        MemoryEvolution memoryEvolution = (userId, selectedGoal, result, evaluation, durationMs, tokenEstimate, workerId) -> {
            evolutionCalls.incrementAndGet();
            return new MemoryEvolutionResult(
                    selectedGoal.goalId(),
                    evaluation.reward(),
                    evaluation.success() ? 1.0 : 0.0,
                    true,
                    true,
                    false,
                    false,
                    evaluation.summary(),
                    List.of("stub-evolution"),
                    com.zhongbo.mindos.assistant.dispatcher.orchestrator.memory.MemoryWriteBatch.empty()
            );
        };

        DefaultAgentLoop loop = new DefaultAgentLoop(goalGenerator, masterOrchestrator, new DefaultEvaluatorAgent(), memoryEvolution);
        AutonomousLoopResult result = loop.runForCycles(new AutonomousLoopRequest("u-loop", Map.of(), 1, 2, 0L, "loop-worker"), 2);

        assertEquals(2, result.cycleCount());
        assertEquals("max-cycles", result.stopReason());
        assertFalse(result.stopped());
        assertEquals(2, goalCalls.get());
        assertEquals(2, evolutionCalls.get());
        assertTrue(result.successCount() >= 2);

        AutonomousCycleResult first = result.cycles().get(0);
        assertEquals(goal.goalId(), first.goal().goalId());
        assertTrue(first.success());
        assertNotNull(masterOrchestrator.lastDecision);
        assertEquals("llm.orchestrate", masterOrchestrator.lastDecision.target());
        assertSame(goal, first.goal());
    }
}
