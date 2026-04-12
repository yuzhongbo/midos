package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous;

import com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.MasterOrchestrationResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.procedure.ProceduralMemory;
import com.zhongbo.mindos.assistant.dispatcher.agent.procedure.Procedure;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryCommandService;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryFacade;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5.PolicyUpdater;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5.RewardModel;
import com.zhongbo.mindos.assistant.memory.MemoryGateway;
import com.zhongbo.mindos.assistant.memory.graph.GraphMemoryGateway;
import com.zhongbo.mindos.assistant.memory.graph.MemoryEdge;
import com.zhongbo.mindos.assistant.memory.graph.MemoryNode;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class DefaultMemoryEvolution implements MemoryEvolution {

    private final GraphMemoryGateway graphMemoryGateway;
    private final ProceduralMemory proceduralMemory;
    private final DispatcherMemoryFacade dispatcherMemoryFacade;
    private final DispatcherMemoryCommandService memoryCommandService;
    private final PolicyUpdater policyUpdater;
    private final ReflectionAgent reflectionAgent;
    private final String semanticBucket;
    private final long nextCheckDelaySeconds;
    private final long highLatencyThresholdMs;
    private final double lowSuccessRateThreshold;
    private final double highSuccessRateThreshold;
    private final double pruneRewardThreshold;
    private final int minProcedureReuseCount;

    public DefaultMemoryEvolution() {
        this((DispatcherMemoryFacade) null, null, null, null, null, null, "autonomous.evolution", 1800L, 1800L, 0.45d, 0.85d, 0.0d, 2);
    }

    public DefaultMemoryEvolution(MemoryGateway memoryGateway,
                                  GraphMemoryGateway graphMemoryGateway,
                                  ProceduralMemory proceduralMemory,
                                  PolicyUpdater policyUpdater) {
        this(new DispatcherMemoryFacade(memoryGateway, graphMemoryGateway, proceduralMemory),
                graphMemoryGateway,
                proceduralMemory,
                new DispatcherMemoryCommandService(memoryGateway, graphMemoryGateway, proceduralMemory),
                policyUpdater,
                null,
                "autonomous.evolution",
                1800L,
                1800L,
                0.45d,
                0.85d,
                0.0d,
                2);
    }

    public DefaultMemoryEvolution(MemoryGateway memoryGateway,
                                  GraphMemoryGateway graphMemoryGateway,
                                  ProceduralMemory proceduralMemory,
                                  PolicyUpdater policyUpdater,
                                  String semanticBucket,
                                  long nextCheckDelaySeconds,
                                  long highLatencyThresholdMs,
                                  double lowSuccessRateThreshold,
                                  double highSuccessRateThreshold,
                                  double pruneRewardThreshold,
                                  int minProcedureReuseCount) {
        this(new DispatcherMemoryFacade(memoryGateway, graphMemoryGateway, proceduralMemory),
                graphMemoryGateway,
                proceduralMemory,
                new DispatcherMemoryCommandService(memoryGateway, graphMemoryGateway, proceduralMemory),
                policyUpdater,
                null,
                semanticBucket,
                nextCheckDelaySeconds,
                highLatencyThresholdMs,
                lowSuccessRateThreshold,
                highSuccessRateThreshold,
                pruneRewardThreshold,
                minProcedureReuseCount);
    }

    @Autowired
    public DefaultMemoryEvolution(DispatcherMemoryFacade dispatcherMemoryFacade,
                                  GraphMemoryGateway graphMemoryGateway,
                                  ProceduralMemory proceduralMemory,
                                  DispatcherMemoryCommandService memoryCommandService,
                                  PolicyUpdater policyUpdater,
                                  ReflectionAgent reflectionAgent,
                                  @Value("${mindos.autonomous.memory.semantic-bucket:autonomous.evolution}") String semanticBucket,
                                  @Value("${mindos.autonomous.memory.next-check-delay-seconds:1800}") long nextCheckDelaySeconds,
                                  @Value("${mindos.autonomous.memory.high-latency-ms:1800}") long highLatencyThresholdMs,
                                  @Value("${mindos.autonomous.memory.low-success-rate-threshold:0.45}") double lowSuccessRateThreshold,
                                  @Value("${mindos.autonomous.memory.high-success-rate-threshold:0.85}") double highSuccessRateThreshold,
                                  @Value("${mindos.autonomous.memory.prune-reward-threshold:0.0}") double pruneRewardThreshold,
                                  @Value("${mindos.autonomous.memory.min-procedure-reuse-count:2}") int minProcedureReuseCount) {
        this(dispatcherMemoryFacade,
                graphMemoryGateway,
                proceduralMemory,
                memoryCommandService,
                policyUpdater,
                reflectionAgent,
                semanticBucket,
                nextCheckDelaySeconds,
                highLatencyThresholdMs,
                Double.valueOf(lowSuccessRateThreshold),
                Double.valueOf(highSuccessRateThreshold),
                Double.valueOf(pruneRewardThreshold),
                Integer.valueOf(minProcedureReuseCount));
    }

    public DefaultMemoryEvolution(DispatcherMemoryFacade dispatcherMemoryFacade,
                                  GraphMemoryGateway graphMemoryGateway,
                                  ProceduralMemory proceduralMemory,
                                  PolicyUpdater policyUpdater,
                                  ReflectionAgent reflectionAgent,
                                  String semanticBucket,
                                  long nextCheckDelaySeconds,
                                  long highLatencyThresholdMs,
                                  Double lowSuccessRateThreshold,
                                  Double highSuccessRateThreshold,
                                  Double pruneRewardThreshold,
                                  Integer minProcedureReuseCount) {
        this(dispatcherMemoryFacade,
                graphMemoryGateway,
                proceduralMemory,
                null,
                policyUpdater,
                reflectionAgent,
                semanticBucket,
                nextCheckDelaySeconds,
                highLatencyThresholdMs,
                lowSuccessRateThreshold,
                highSuccessRateThreshold,
                pruneRewardThreshold,
                minProcedureReuseCount);
    }

    public DefaultMemoryEvolution(DispatcherMemoryFacade dispatcherMemoryFacade,
                                  GraphMemoryGateway graphMemoryGateway,
                                  ProceduralMemory proceduralMemory,
                                  DispatcherMemoryCommandService memoryCommandService,
                                  PolicyUpdater policyUpdater,
                                  ReflectionAgent reflectionAgent,
                                  String semanticBucket,
                                  long nextCheckDelaySeconds,
                                  long highLatencyThresholdMs,
                                  Double lowSuccessRateThreshold,
                                  Double highSuccessRateThreshold,
                                  Double pruneRewardThreshold,
                                   Integer minProcedureReuseCount) {
        this.graphMemoryGateway = graphMemoryGateway;
        this.proceduralMemory = proceduralMemory;
        this.dispatcherMemoryFacade = dispatcherMemoryFacade == null
                ? new DispatcherMemoryFacade(null, graphMemoryGateway, proceduralMemory)
                : dispatcherMemoryFacade;
        this.memoryCommandService = memoryCommandService == null
                ? new DispatcherMemoryCommandService(this.dispatcherMemoryFacade, proceduralMemory)
                : memoryCommandService;
        this.policyUpdater = policyUpdater;
        this.reflectionAgent = reflectionAgent;
        this.semanticBucket = semanticBucket == null || semanticBucket.isBlank() ? "autonomous.evolution" : semanticBucket.trim();
        this.nextCheckDelaySeconds = Math.max(60L, nextCheckDelaySeconds);
        this.highLatencyThresholdMs = Math.max(1L, highLatencyThresholdMs);
        this.lowSuccessRateThreshold = clampThreshold(lowSuccessRateThreshold == null ? 0.45d : lowSuccessRateThreshold);
        this.highSuccessRateThreshold = clampThreshold(highSuccessRateThreshold == null ? 0.85d : highSuccessRateThreshold);
        this.pruneRewardThreshold = pruneRewardThreshold == null ? 0.0d : pruneRewardThreshold;
        this.minProcedureReuseCount = Math.max(1, minProcedureReuseCount == null ? 2 : minProcedureReuseCount);
    }

    @Override
    public MemoryEvolutionResult evolve(String userId,
                                        AutonomousGoal goal,
                                        MasterOrchestrationResult execution,
                                        AutonomousEvaluation evaluation,
                                        long durationMs,
                                        int tokenEstimate,
                                        String workerId) {
        String safeUserId = normalizeUserId(userId);
        AutonomousGoal safeGoal = goal == null
                ? new AutonomousGoal("fallback:llm.orchestrate", AutonomousGoalType.FALLBACK, "自治回退目标", "回顾当前记忆并寻找下一步行动", "llm.orchestrate", "fallback", 10, Map.of(), List.of("fallback"), Instant.now())
                : goal;
        AutonomousEvaluation safeEvaluation = evaluation == null
                ? new AutonomousEvaluation(safeGoal.goalId(), safeGoal.type(), false, 0.0, "missing evaluation", "replan", "", "", List.of("missing-evaluation"), Instant.now())
                : evaluation;

        boolean usedFallback = execution != null && Boolean.TRUE.equals(execution.sharedState().get("multiAgent.lastUsedFallback"));
        RewardModel rewardModel = policyUpdater == null
                ? RewardModel.evaluate(
                        safeGoal.target(),
                        safeGoal.type().name(),
                        safeEvaluation.success(),
                        durationMs,
                        tokenEstimate,
                        usedFallback,
                        highLatencyThresholdMs
                )
                : policyUpdater.update(
                        safeUserId,
                        safeGoal.target(),
                        safeGoal.type().name(),
                        safeEvaluation.success(),
                        durationMs,
                        tokenEstimate,
                        usedFallback
                );

        Map<String, Object> sharedState = execution == null || execution.sharedState() == null ? Map.of() : execution.sharedState();
        ReflectionResult reflection = reflect(safeUserId, safeGoal, execution, safeEvaluation, sharedState);
        TaskGraph taskGraph = objectValue(sharedState.get("multiAgent.plan.graph"), TaskGraph.class);
        boolean procedureRecorded = false;
        if (safeEvaluation.success() && proceduralMemory != null && taskGraph != null && !taskGraph.isEmpty()) {
            Procedure strengthenedProcedure = memoryCommandService.recordProcedureSuccess(
                    safeUserId,
                    safeGoal.title(),
                    safeGoal.objective(),
                    taskGraph,
                    sharedState
            );
            procedureRecorded = strengthenedProcedure != null && !strengthenedProcedure.id().isBlank();
        }

        boolean taskUpdated = updateLongTask(safeUserId, safeGoal, safeEvaluation, workerId);
        int prunedProcedures = pruneLowEfficiencyProcedures(safeUserId, safeGoal, safeEvaluation, rewardModel);
        boolean graphUpdated = writeGraph(safeUserId, safeGoal, safeEvaluation, rewardModel, durationMs, tokenEstimate, sharedState, taskGraph, prunedProcedures);
        boolean semanticWritten = writeSemanticSummary(safeUserId, safeGoal, safeEvaluation, rewardModel, durationMs, tokenEstimate, prunedProcedures);

        List<String> reasons = new ArrayList<>();
        reasons.add("reward=" + round(rewardModel.reward()));
        reasons.add("normalizedReward=" + round(rewardModel.normalizedReward()));
        reasons.add("procedureRecorded=" + procedureRecorded);
        reasons.add("proceduresPruned=" + prunedProcedures);
        reasons.add("procedureStrengthened=" + (procedureRecorded && safeEvaluation.score() >= highSuccessRateThreshold));
        reasons.add("reflectionPattern=" + (reflection == null ? "none" : reflection.pattern()));
        reasons.add("taskUpdated=" + taskUpdated);
        reasons.add("graphUpdated=" + graphUpdated);
        reasons.add("semanticWritten=" + semanticWritten);

        return new MemoryEvolutionResult(
                safeGoal.goalId(),
                rewardModel.reward(),
                rewardModel.normalizedReward(),
                semanticWritten,
                procedureRecorded,
                taskUpdated,
                graphUpdated,
                buildSummary(safeGoal, safeEvaluation, rewardModel, durationMs, tokenEstimate, prunedProcedures),
                reasons
        );
    }

    private ReflectionResult reflect(String userId,
                                     AutonomousGoal goal,
                                     MasterOrchestrationResult execution,
                                     AutonomousEvaluation evaluation,
                                     Map<String, Object> sharedState) {
        if (reflectionAgent == null) {
            return null;
        }
        return reflectionAgent.reflect(
                userId,
                goal == null ? "" : firstNonBlank(goal.objective(), goal.title()),
                execution == null ? null : execution.trace(),
                execution == null ? null : execution.result(),
                goal == null ? Map.of() : goal.params(),
                sharedState == null ? Map.of() : sharedState
        );
    }

    private boolean writeSemanticSummary(String userId,
                                         AutonomousGoal goal,
                                         AutonomousEvaluation evaluation,
                                         RewardModel rewardModel,
                                         long durationMs,
                                         int tokenEstimate,
                                         int prunedProcedures) {
        if (!dispatcherMemoryFacade.hasRuntimeMemory()) {
            return false;
        }
        String summary = buildSummary(goal, evaluation, rewardModel, durationMs, tokenEstimate, prunedProcedures);
        memoryCommandService.writeSemantic(userId, summary, List.of(), semanticBucket);
        return true;
    }

    private boolean updateLongTask(String userId,
                                   AutonomousGoal goal,
                                   AutonomousEvaluation evaluation,
                                   String workerId) {
        if (!dispatcherMemoryFacade.hasRuntimeMemory() || goal == null || goal.type() != AutonomousGoalType.LONG_TASK) {
            return false;
        }
        String taskId = stringValue(goal.params().get("taskId"));
        if (taskId.isBlank()) {
            return false;
        }
        String focusStep = stringValue(goal.params().get("taskFocusStep"));
        boolean taskCompletable = booleanValue(goal.params().get("taskCompletable"));
        String note = evaluation == null ? "" : evaluation.feedback();
        String blockedReason = evaluation != null && evaluation.success()
                ? ""
                : firstNonBlank(evaluation == null ? "" : evaluation.nextAction(), note, "autonomous-loop-failure");
        Instant nextCheckAt = Instant.now().plusSeconds(nextCheckDelaySeconds);
        return memoryCommandService.updateLongTaskProgress(
                userId,
                taskId,
                firstNonBlank(workerId, "autonomous-loop"),
                focusStep,
                note,
                blockedReason,
                nextCheckAt,
                taskCompletable && evaluation != null && evaluation.success()
        ) != null;
    }

    private boolean writeGraph(String userId,
                               AutonomousGoal goal,
                               AutonomousEvaluation evaluation,
                               RewardModel rewardModel,
                               long durationMs,
                               int tokenEstimate,
                               Map<String, Object> sharedState,
                               TaskGraph taskGraph,
                               int prunedProcedures) {
        if (!dispatcherMemoryFacade.hasGraphMemory() || goal == null || evaluation == null) {
            return false;
        }
        String goalNodeId = "autonomous:goal:" + sanitizeId(goal.goalId());
        String evaluationNodeId = goalNodeId + ":evaluation:" + evaluation.evaluatedAt().toEpochMilli();
        String strategyNodeId = "autonomous:strategy:" + goal.type().name().toLowerCase(Locale.ROOT) + ":" + sanitizeId(goal.target());
        String dagNodeId = "autonomous:dag:" + sanitizeId(goal.goalId());

        boolean success = evaluation.success();
        memoryCommandService.upsertGraphNode(userId, new MemoryNode(
                goalNodeId,
                "autonomous.goal",
                goalData(goal, rewardModel, sharedState),
                Instant.now(),
                Instant.now()
        ));
        memoryCommandService.upsertGraphNode(userId, new MemoryNode(
                evaluationNodeId,
                "autonomous.evaluation",
                evaluationData(goal, evaluation, rewardModel, durationMs, tokenEstimate),
                Instant.now(),
                Instant.now()
        ));
        memoryCommandService.linkGraph(
                userId,
                goalNodeId,
                "evaluated-by",
                evaluationNodeId,
                Math.max(0.0, Math.min(1.0, evaluation.score())),
                Map.of("reward", rewardModel.reward(), "success", success)
        );
        memoryCommandService.upsertGraphNode(userId, new MemoryNode(
                strategyNodeId,
                "autonomous.strategy",
                strategyData(goal, evaluation, rewardModel),
                Instant.now(),
                Instant.now()
        ));
        memoryCommandService.upsertGraphNode(userId, new MemoryNode(
                dagNodeId,
                "autonomous.dag",
                dagData(goal, evaluation, rewardModel, taskGraph, prunedProcedures),
                Instant.now(),
                Instant.now()
        ));
        memoryCommandService.linkGraph(
                userId,
                evaluationNodeId,
                success ? "reinforces" : "weakens",
                strategyNodeId,
                Math.max(0.1, rewardModel.normalizedReward()),
                Map.of("goalId", goal.goalId(), "feedback", evaluation.feedback(), "routeType", rewardModel.routeType())
        );
        memoryCommandService.linkGraph(
                userId,
                goalNodeId,
                success ? "optimizes-dag" : "prunes-dag",
                dagNodeId,
                Math.max(0.1, rewardModel.normalizedReward()),
                Map.of("nodeCount", taskGraph == null ? 0 : taskGraph.nodes().size(), "edgeCount", taskGraph == null ? 0 : taskGraph.edges().size(), "prunedProcedures", prunedProcedures)
        );
        return true;
    }

    private int pruneLowEfficiencyProcedures(String userId,
                                             AutonomousGoal goal,
                                             AutonomousEvaluation evaluation,
                                             RewardModel rewardModel) {
        if (proceduralMemory == null) {
            return 0;
        }
        boolean shouldPrune = rewardModel.reward() <= pruneRewardThreshold
                || (evaluation != null && !evaluation.success())
                || (evaluation != null && evaluation.score() < lowSuccessRateThreshold);
        if (!shouldPrune) {
            return 0;
        }

        List<Procedure> procedures = proceduralMemory.listProcedures(userId);
        if (procedures.isEmpty()) {
            return 0;
        }

        String goalHint = firstNonBlank(
                goal == null ? "" : goal.target(),
                goal == null ? "" : goal.objective(),
                goal == null ? "" : goal.title()
        );
        int pruned = 0;
        for (Procedure procedure : procedures) {
            if (procedure == null || procedure.id().isBlank()) {
                continue;
            }
            boolean lowEfficiency = procedure.successRate() < lowSuccessRateThreshold && procedure.reuseCount() >= minProcedureReuseCount;
            boolean matchesGoal = matchesGoal(procedure, goalHint);
            boolean failureMatched = evaluation != null && !evaluation.success() && matchesGoal;
            if (!lowEfficiency && !failureMatched) {
                continue;
            }
            if (memoryCommandService.deleteProcedure(userId, procedure.id())) {
                pruned++;
            }
        }
        return pruned;
    }

    private boolean matchesGoal(Procedure procedure, String goalHint) {
        if (procedure == null || goalHint == null || goalHint.isBlank()) {
            return false;
        }
        String normalizedGoal = goalHint.trim().toLowerCase(Locale.ROOT);
        return containsIgnoreCase(procedure.id(), normalizedGoal)
                || containsIgnoreCase(procedure.intent(), normalizedGoal)
                || containsIgnoreCase(procedure.trigger(), normalizedGoal)
                || procedure.steps().stream().anyMatch(step -> containsIgnoreCase(step, normalizedGoal));
    }

    private boolean containsIgnoreCase(String text, String needle) {
        if (text == null || needle == null || needle.isBlank()) {
            return false;
        }
        return text.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private Map<String, Object> goalData(AutonomousGoal goal,
                                          RewardModel rewardModel,
                                          Map<String, Object> sharedState) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", goal.title());
        data.put("goalId", goal.goalId());
        data.put("goalType", goal.type().name());
        data.put("objective", goal.objective());
        data.put("target", goal.target());
        data.put("priority", goal.priority());
        data.put("sourceId", goal.sourceId());
        data.put("reward", rewardModel.reward());
        data.put("normalizedReward", rewardModel.normalizedReward());
        data.put("reasons", goal.reasons());
        if (sharedState != null && !sharedState.isEmpty()) {
            data.put("sharedStateKeys", sharedState.keySet().stream().toList());
        }
        return Map.copyOf(data);
    }

    private Map<String, Object> evaluationData(AutonomousGoal goal,
                                               AutonomousEvaluation evaluation,
                                               RewardModel rewardModel,
                                               long durationMs,
                                               int tokenEstimate) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", evaluation == null ? goal.title() : evaluation.feedback());
        data.put("goalId", goal.goalId());
        data.put("goalType", goal.type().name());
        data.put("success", evaluation != null && evaluation.success());
        data.put("score", evaluation == null ? 0.0 : evaluation.score());
        data.put("feedback", evaluation == null ? "" : evaluation.feedback());
        data.put("nextAction", evaluation == null ? "" : evaluation.nextAction());
        data.put("resultSkill", evaluation == null ? "" : evaluation.resultSkill());
        data.put("reward", rewardModel.reward());
        data.put("normalizedReward", rewardModel.normalizedReward());
        data.put("durationMs", durationMs);
        data.put("tokenEstimate", tokenEstimate);
        return Map.copyOf(data);
    }

    private Map<String, Object> strategyData(AutonomousGoal goal,
                                              AutonomousEvaluation evaluation,
                                              RewardModel rewardModel) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", goal.target());
        data.put("goalId", goal.goalId());
        data.put("goalType", goal.type().name());
        data.put("routeType", rewardModel.routeType());
        data.put("successRate", evaluation != null && evaluation.success() ? 1.0 : 0.0);
        data.put("reward", rewardModel.reward());
        data.put("normalizedReward", rewardModel.normalizedReward());
        data.put("feedback", evaluation == null ? "" : evaluation.feedback());
        data.put("nextAction", evaluation == null ? "" : evaluation.nextAction());
        data.put("state", evaluation != null && evaluation.success() && evaluation.score() >= highSuccessRateThreshold ? "preferred" : evaluation != null && evaluation.success() ? "learning" : "degraded");
        return Map.copyOf(data);
    }

    private Map<String, Object> dagData(AutonomousGoal goal,
                                        AutonomousEvaluation evaluation,
                                        RewardModel rewardModel,
                                        TaskGraph taskGraph,
                                        int prunedProcedures) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", goal.title());
        data.put("goalId", goal.goalId());
        data.put("goalType", goal.type().name());
        data.put("state", evaluation != null && evaluation.success()
                ? (evaluation.score() >= highSuccessRateThreshold ? "reinforced" : "stabilized")
                : "pruned");
        data.put("success", evaluation != null && evaluation.success());
        data.put("score", evaluation == null ? 0.0 : evaluation.score());
        data.put("reward", rewardModel.reward());
        data.put("normalizedReward", rewardModel.normalizedReward());
        data.put("nodeCount", taskGraph == null ? 0 : taskGraph.nodes().size());
        data.put("edgeCount", taskGraph == null ? 0 : taskGraph.edges().size());
        data.put("targets", taskGraph == null ? List.of() : taskGraph.nodes().stream().map(node -> node.target()).toList());
        data.put("edges", taskGraph == null ? List.of() : taskGraph.edges().stream().map(edge -> Map.of("from", edge.from(), "to", edge.to())).toList());
        data.put("prunedProcedures", prunedProcedures);
        return Map.copyOf(data);
    }

    private String buildSummary(AutonomousGoal goal,
                                AutonomousEvaluation evaluation,
                                RewardModel rewardModel,
                                long durationMs,
                                int tokenEstimate,
                                int prunedProcedures) {
        return "自治目标="
                + (goal == null ? "" : goal.title())
                + " | 目标="
                + (goal == null ? "" : goal.objective())
                + " | 结果="
                + (evaluation != null && evaluation.success() ? "成功" : "失败")
                + " | 反馈="
                + (evaluation == null ? "" : evaluation.feedback())
                + " | 下一步="
                + (evaluation == null ? "" : evaluation.nextAction())
                + " | reward="
                + round(rewardModel.reward())
                + " | prunedProcedures="
                + prunedProcedures
                + " | durationMs="
                + durationMs
                + " | tokenEstimate="
                + tokenEstimate;
    }

    private String normalizeUserId(String userId) {
        return userId == null || userId.isBlank() ? "local-user" : userId.trim();
    }

    private String sanitizeId(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.trim().replaceAll("[^A-Za-z0-9._:-]", "_");
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private double clampThreshold(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.5d;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private <T> T objectValue(Object value, Class<T> type) {
        if (type == null || value == null || !type.isInstance(value)) {
            return null;
        }
        return type.cast(value);
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
