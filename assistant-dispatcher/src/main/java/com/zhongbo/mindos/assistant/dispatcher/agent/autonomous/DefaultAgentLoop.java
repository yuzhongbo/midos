package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.common.dto.ExecutionTraceDto;
import com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.MasterOrchestrationResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.MasterOrchestrator;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.memory.OrchestratorMemoryWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class DefaultAgentLoop implements AgentLoop {

    private final GoalGenerator goalGenerator;
    private final MasterOrchestrator masterOrchestrator;
    private final EvaluatorAgent evaluatorAgent;
    private final MemoryEvolution memoryEvolution;
    private final OrchestratorMemoryWriter memoryWriter;

    public DefaultAgentLoop(GoalGenerator goalGenerator,
                            MasterOrchestrator masterOrchestrator,
                            EvaluatorAgent evaluatorAgent,
                            MemoryEvolution memoryEvolution) {
        this(goalGenerator, masterOrchestrator, evaluatorAgent, memoryEvolution, null);
    }

    @Autowired
    public DefaultAgentLoop(GoalGenerator goalGenerator,
                            MasterOrchestrator masterOrchestrator,
                            EvaluatorAgent evaluatorAgent,
                            MemoryEvolution memoryEvolution,
                            OrchestratorMemoryWriter memoryWriter) {
        this.goalGenerator = goalGenerator;
        this.masterOrchestrator = masterOrchestrator;
        this.evaluatorAgent = evaluatorAgent;
        this.memoryEvolution = memoryEvolution;
        this.memoryWriter = memoryWriter;
    }

    @Override
    public AutonomousCycleResult runOnce(AutonomousLoopRequest request) {
        AutonomousLoopRequest safeRequest = request == null ? AutonomousLoopRequest.of("") : request;
        Instant startedAt = Instant.now();
        List<AutonomousGoal> candidates = goalGenerator == null
                ? List.of()
                : goalGenerator.generate(safeRequest.userId(), safeRequest.goalLimit());
        AutonomousGoal selected = candidates.isEmpty() ? fallbackGoal(safeRequest.userId()) : candidates.get(0);
        com.zhongbo.mindos.assistant.dispatcher.decision.Decision decision = selected.toDecision();
        MasterOrchestrationResult execution = masterOrchestrator == null
                ? fallbackResult(selected)
                : masterOrchestrator.execute(safeRequest.userId(), selected.objective(), decision, safeRequest.profileContext());
        AutonomousEvaluation evaluation = evaluatorAgent == null
                ? fallbackEvaluation(selected, execution)
                : evaluatorAgent.evaluate(selected, execution);
        long durationMs = Math.max(0L, Duration.between(startedAt, Instant.now()).toMillis());
        int tokenEstimate = estimateTokens(selected.objective())
                + estimateTokens(execution == null || execution.result() == null ? "" : execution.result().output())
                + estimateTokens(evaluation.feedback());
        MemoryEvolutionResult evolution = memoryEvolution == null
                ? fallbackEvolution(selected, evaluation)
                : memoryEvolution.evolve(
                        safeRequest.userId(),
                        selected,
                        execution,
                        evaluation,
                        durationMs,
                        tokenEstimate,
                        safeRequest.workerId()
                );
        if (memoryWriter != null) {
            memoryWriter.commit(safeRequest.userId(), evolution.memoryWrites());
        }
        Instant finishedAt = Instant.now();
        return new AutonomousCycleResult(
                candidates,
                selected,
                execution,
                evaluation,
                evolution,
                durationMs,
                tokenEstimate,
                startedAt,
                finishedAt
        );
    }

    @Override
    public AutonomousLoopResult runUntilStopped(AutonomousLoopRequest request, AtomicBoolean stopSignal) {
        AutonomousLoopRequest safeRequest = request == null ? AutonomousLoopRequest.of("") : request;
        AtomicBoolean signal = stopSignal == null ? new AtomicBoolean(false) : stopSignal;
        List<AutonomousCycleResult> cycles = new ArrayList<>();
        Instant startedAt = Instant.now();
        String stopReason = "";
        int maxCycles = safeRequest.maxCycles();
        try {
            while (!signal.get() && !Thread.currentThread().isInterrupted()) {
                if (maxCycles > 0 && cycles.size() >= maxCycles) {
                    stopReason = "max-cycles";
                    break;
                }
                cycles.add(runOnce(safeRequest));
                if (safeRequest.pauseMillis() > 0L && !signal.get()) {
                    Thread.sleep(safeRequest.pauseMillis());
                }
                if (maxCycles <= 0 && signal.get()) {
                    stopReason = "stopped";
                    break;
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            stopReason = "interrupted";
        }
        if (stopReason.isBlank()) {
            stopReason = signal.get() ? "stopped" : "completed";
        }
        return new AutonomousLoopResult(
                safeRequest.userId(),
                cycles,
                signal.get(),
                stopReason,
                startedAt,
                Instant.now()
        );
    }

    private AutonomousGoal fallbackGoal(String userId) {
        return new AutonomousGoal(
                "loop-fallback:" + normalize(userId),
                AutonomousGoalType.FALLBACK,
                "自治回退目标",
                "回顾当前记忆并寻找下一步行动",
                "llm.orchestrate",
                "fallback",
                10,
                java.util.Map.of("userId", normalize(userId), "reason", "no-goals"),
                List.of("goal-generator-empty"),
                Instant.now()
        );
    }

    private MasterOrchestrationResult fallbackResult(AutonomousGoal goal) {
        SkillResult result = SkillResult.failure(goal == null ? "autonomous-loop" : goal.target(), "master orchestrator unavailable");
        return new MasterOrchestrationResult(
                result,
                new ExecutionTraceDto("autonomous-loop", 0, new com.zhongbo.mindos.assistant.common.dto.CritiqueReportDto(false, "master orchestrator unavailable", "replan"), List.of()),
                List.of(),
                java.util.Map.of()
        );
    }

    private AutonomousEvaluation fallbackEvaluation(AutonomousGoal goal, MasterOrchestrationResult execution) {
        SkillResult result = execution == null ? null : execution.result();
        boolean success = result != null && result.success();
        return new AutonomousEvaluation(
                goal == null ? "" : goal.goalId(),
                goal == null ? AutonomousGoalType.FALLBACK : goal.type(),
                success,
                success ? 1.0 : 0.0,
                success ? "目标完成" : "目标未完成",
                success ? "learn-procedure" : "replan",
                result == null ? "" : result.skillName(),
                result == null ? "" : result.output(),
                List.of("fallback-evaluator"),
                Instant.now()
        );
    }

    private MemoryEvolutionResult fallbackEvolution(AutonomousGoal goal, AutonomousEvaluation evaluation) {
        return new MemoryEvolutionResult(
                goal == null ? "" : goal.goalId(),
                evaluation == null || !evaluation.success() ? -1.0 : 1.0,
                evaluation == null ? 0.5 : evaluation.success() ? 1.0 : 0.0,
                false,
                false,
                false,
                false,
                evaluation == null ? "" : evaluation.summary(),
                List.of("fallback-memory-evolution"),
                com.zhongbo.mindos.assistant.dispatcher.orchestrator.memory.MemoryWriteBatch.empty()
        );
    }

    private int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return Math.max(1, (text.length() + 3) / 4);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
