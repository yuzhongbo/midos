package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.DAGExecutor;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraphExecutionResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskNode;
import com.zhongbo.mindos.assistant.dispatcher.agent.search.SearchCandidate;
import com.zhongbo.mindos.assistant.dispatcher.agent.search.SearchPlanner;
import com.zhongbo.mindos.assistant.dispatcher.agent.search.SearchPlanningRequest;
import com.zhongbo.mindos.assistant.dispatcher.agent.procedure.ProceduralMemory;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5.AgentRouter;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5.RecoveryAction;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5.PlannerLearningStore;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5.RecoveryManager;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5.TraceLogger;
import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillDsl;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.common.dto.CritiqueReportDto;
import com.zhongbo.mindos.assistant.common.dto.ExecutionTraceDto;
import com.zhongbo.mindos.assistant.common.dto.PlanStepDto;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.memory.MemoryGateway;
import com.zhongbo.mindos.assistant.memory.model.LongTask;
import com.zhongbo.mindos.assistant.memory.model.LongTaskStatus;
import com.zhongbo.mindos.assistant.memory.model.PreferenceProfile;
import com.zhongbo.mindos.assistant.skill.SkillExecutionGateway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Component
public class DefaultDecisionOrchestrator implements DecisionOrchestrator {
    private static final List<String> DEFAULT_PARALLEL_MCP_PRIORITY_ORDER = List.of(
            "mcp.serper.websearch",
            "mcp.serpapi.websearch",
            "mcp.bravesearch.websearch",
            "mcp.brave.websearch",
            "mcp.qwensearch.websearch",
            "mcp.qwen.websearch"
    );
    private static final double TASK_PLAN_LOW_CONFIDENCE_THRESHOLD = 0.70;

    private final CandidatePlanner candidatePlanner;
    private final ParamValidator paramValidator;
    private final ConversationLoop conversationLoop;
    private final FallbackPlan fallbackPlan;
    private final SkillExecutionGateway skillExecutionGateway;
    private final MemoryGateway memoryGateway;
    private final PostExecutionMemoryRecorder memoryRecorder;
    private final TaskExecutor taskExecutor;
    private final boolean mcpParallelEnabled;
    private final long mcpPerSkillTimeoutMs;
    private final long eqCoachImTimeoutMs;
    private final String eqCoachImTimeoutReply;
    private final List<String> mcpPriorityOrder;
    private final int maxLoops;
    private final DAGExecutor dagExecutor = new DAGExecutor();
    private SearchPlanner searchPlanner;
    private ProceduralMemory proceduralMemory;
    private PlannerLearningStore plannerLearningStore;
    private AgentRouter agentRouter;
    private RecoveryManager recoveryManager;
    private TraceLogger traceLogger;

    @Autowired
    public DefaultDecisionOrchestrator(CandidatePlanner candidatePlanner,
                                       ParamValidator paramValidator,
                                       ConversationLoop conversationLoop,
                                       FallbackPlan fallbackPlan,
                                       SkillExecutionGateway skillExecutionGateway,
                                       MemoryGateway memoryGateway,
                                       PostExecutionMemoryRecorder memoryRecorder,
                                       TaskExecutor taskExecutor,
                                       @Value("${mindos.dispatcher.parallel-routing.enabled:false}") boolean mcpParallelEnabled,
                                       @Value("${mindos.dispatcher.parallel-routing.per-skill-timeout-ms:2500}") long mcpPerSkillTimeoutMs,
                                       @Value("${mindos.dispatcher.skill.timeout.eq-coach-im-ms:12000}") long eqCoachImTimeoutMs,
                                       @Value("${mindos.dispatcher.skill.timeout.eq-coach-im-reply:我先给你一个简短结论：当前请求较复杂，我正在整理更完整建议，稍后继续发你。}") String eqCoachImTimeoutReply,
                                       @Value("${mindos.dispatcher.orchestrator.max-loops:3}") int maxLoops) {
        this.candidatePlanner = candidatePlanner;
        this.paramValidator = paramValidator;
        this.conversationLoop = conversationLoop;
        this.fallbackPlan = fallbackPlan;
        this.skillExecutionGateway = skillExecutionGateway;
        this.memoryGateway = memoryGateway;
        this.memoryRecorder = memoryRecorder;
        this.taskExecutor = taskExecutor;
        this.mcpParallelEnabled = mcpParallelEnabled;
        this.mcpPerSkillTimeoutMs = Math.max(250, mcpPerSkillTimeoutMs);
        this.eqCoachImTimeoutMs = Math.max(0L, eqCoachImTimeoutMs);
        this.eqCoachImTimeoutReply = eqCoachImTimeoutReply == null || eqCoachImTimeoutReply.isBlank()
                ? "我先给你一个简短结论：当前请求较复杂，我正在整理更完整建议，稍后继续发你。"
                : eqCoachImTimeoutReply;
        this.mcpPriorityOrder = DEFAULT_PARALLEL_MCP_PRIORITY_ORDER;
        this.maxLoops = Math.max(1, Math.min(3, maxLoops));
    }

    @Autowired(required = false)
    void setSearchPlanner(SearchPlanner searchPlanner) {
        this.searchPlanner = searchPlanner;
    }

    @Autowired(required = false)
    void setProceduralMemory(ProceduralMemory proceduralMemory) {
        this.proceduralMemory = proceduralMemory;
    }

    @Autowired(required = false)
    void setPlannerLearningStore(PlannerLearningStore plannerLearningStore) {
        this.plannerLearningStore = plannerLearningStore;
    }

    @Autowired(required = false)
    void setAgentRouter(AgentRouter agentRouter) {
        this.agentRouter = agentRouter;
    }

    @Autowired(required = false)
    void setRecoveryManager(RecoveryManager recoveryManager) {
        this.recoveryManager = recoveryManager;
    }

    @Autowired(required = false)
    void setTraceLogger(TraceLogger traceLogger) {
        this.traceLogger = traceLogger;
    }

    @Override
    public SkillResult execute(String userInput, String intent, Map<String, Object> params) {
        Map<String, Object> safeParams = params == null ? Map.of() : Map.copyOf(params);
        String resolvedTarget = resolveExecutionTarget(intent, safeParams);
        Decision decision = new Decision(intent, resolvedTarget, safeParams, 1.0, false);
        SkillContext context = new SkillContext("", userInput == null ? "" : userInput, safeParams);
        OrchestrationOutcome outcome = orchestrate(
                decision,
                new OrchestrationRequest("", userInput == null ? "" : userInput, context, Map.of())
        );
        if (outcome.hasResult() && outcome.result() != null && outcome.result().success()) {
            return outcome.result();
        }
        if (outcome.hasClarification()) {
            return outcome.clarification();
        }
        if (outcome.hasResult()) {
            List<String> attemptedCandidates = outcome.trace() == null || outcome.trace().steps() == null
                    ? List.of()
                    : outcome.trace().steps().stream()
                    .map(PlanStepDto::channel)
                    .filter(channel -> channel != null && !channel.isBlank())
                    .distinct()
                    .toList();
            return unifiedFailureResult(userInput, intent, attemptedCandidates, outcome.result());
        }
        return unifiedFailureResult(userInput, intent, List.of(), SkillResult.failure("decision.orchestrator", "all candidates failed"));
    }

    @Override
    public OrchestrationOutcome orchestrate(Decision decision, OrchestrationRequest request) {
        String traceId = startTrace(decision, request);
        OrchestrationOutcome outcome = null;
        try {
            if (decision == null || decision.target() == null || decision.target().isBlank()) {
                outcome = clarificationOutcome("", "missing target");
            } else if ("semantic.clarify".equalsIgnoreCase(decision.target()) || decision.requireClarify()) {
                outcome = clarificationOutcome(decision.target(), "clarification requested");
            } else {
                Map<String, Object> params = decision.params() == null ? Map.of() : decision.params();
                Map<String, Object> effectiveParams = buildEffectiveParams(params, request == null ? null : request.skillContext());
                TaskPlan taskPlan = TaskPlan.from(effectiveParams);
                if ("task.plan".equalsIgnoreCase(decision.target()) || !taskPlan.isEmpty()) {
                    traceEvent(traceId, "planner", "slow-path", Map.of("reason", "task-plan"));
                    outcome = slowPath(decision, request, traceId, null);
                } else if (shouldUseSlowPath(decision)) {
                    traceEvent(traceId, "planner", "slow-path", Map.of("reason", "decision-confidence"));
                    outcome = slowPath(decision, request, traceId, null);
                } else {
                    OrchestrationOutcome fastOutcome = fastPath(decision, request, traceId);
                    if (fastOutcome.hasClarification()) {
                        outcome = fastOutcome;
                    } else if (fastOutcome.hasResult() && fastOutcome.result() != null && fastOutcome.result().success()) {
                        outcome = fastOutcome;
                    } else if (shouldEscalateToSlowPath(fastOutcome)) {
                        traceEvent(traceId, "planner", "slow-path", Map.of("reason", "fast-path-failed"));
                        outcome = slowPath(decision, request, traceId, fastOutcome);
                    } else {
                        outcome = fastOutcome;
                    }
                }
            }
        } finally {
            finishTrace(traceId, decision, request, outcome);
        }
        return outcome;
    }

    @Override
    public OrchestrationOutcome fastPath(Decision decision, OrchestrationRequest request) {
        return fastPath(decision, request, null);
    }

    @Override
    public OrchestrationOutcome slowPath(Decision decision, OrchestrationRequest request) {
        return slowPath(decision, request, null, null);
    }

    @Override
    public void recordOutcome(String userId, String userInput, SkillResult result, ExecutionTraceDto trace) {
        memoryRecorder.record(userId, userInput, result, trace);
    }

    @Override
    public void appendUserConversation(String userId, String message) {
        if (memoryGateway != null) {
            memoryGateway.appendUserConversation(userId, message);
        }
    }

    @Override
    public void appendAssistantConversation(String userId, String message) {
        if (memoryGateway != null) {
            memoryGateway.appendAssistantConversation(userId, message);
        }
    }

    @Override
    public void writeSemantic(String userId, String text, List<Double> embedding, String bucket) {
        if (memoryGateway != null) {
            memoryGateway.writeSemantic(userId, text, embedding, bucket);
        }
    }

    @Override
    public PreferenceProfile updatePreferenceProfile(String userId, PreferenceProfile profile) {
        return memoryGateway == null ? PreferenceProfile.empty() : memoryGateway.updatePreferenceProfile(userId, profile);
    }

    @Override
    public LongTask createLongTask(String userId,
                                   String title,
                                   String objective,
                                   List<String> steps,
                                   Instant dueAt,
                                   Instant nextCheckAt) {
        return memoryGateway == null ? null : memoryGateway.createLongTask(userId, title, objective, steps, dueAt, nextCheckAt);
    }

    @Override
    public LongTask updateLongTaskProgress(String userId,
                                           String taskId,
                                           String workerId,
                                           String completedStep,
                                           String note,
                                           String blockedReason,
                                           Instant nextCheckAt,
                                           boolean markCompleted) {
        return memoryGateway == null
                ? null
                : memoryGateway.updateLongTaskProgress(userId, taskId, workerId, completedStep, note, blockedReason, nextCheckAt, markCompleted);
    }

    @Override
    public LongTask updateLongTaskStatus(String userId,
                                         String taskId,
                                         LongTaskStatus status,
                                         String note,
                                         Instant nextCheckAt) {
        return memoryGateway == null ? null : memoryGateway.updateLongTaskStatus(userId, taskId, status, note, nextCheckAt);
    }

    private OrchestrationOutcome fastPath(Decision decision,
                                          OrchestrationRequest request,
                                          String traceId) {
        if (decision == null || decision.target() == null || decision.target().isBlank()) {
            return clarificationOutcome("", "missing target");
        }
        Map<String, Object> effectiveParams = buildEffectiveParams(decision.params(), request == null ? null : request.skillContext());
        return orchestrateFastPath(decision, decision.target(), effectiveParams, request, true, traceId);
    }

    private OrchestrationOutcome slowPath(Decision decision,
                                          OrchestrationRequest request,
                                          String traceId,
                                          OrchestrationOutcome fastOutcome) {
        if (decision == null || decision.target() == null || decision.target().isBlank()) {
            return clarificationOutcome("", "missing target");
        }
        Map<String, Object> effectiveParams = buildEffectiveParams(decision.params(), request == null ? null : request.skillContext());
        if (TaskGraph.fromDsl(effectiveParams).isEmpty()) {
            java.util.Optional<ProceduralMemory.ReusableProcedure> reusableProcedure = matchReusableProcedure(decision, request, effectiveParams);
            if (reusableProcedure.isPresent() && !reusableProcedure.get().taskGraph().isEmpty()) {
                return orchestrateTaskGraph(
                        decision,
                        reusableProcedure.get().taskGraph(),
                        attachTaskGraph(effectiveParams, reusableProcedure.get().taskGraph()),
                        request,
                        traceId,
                        "procedural-memory",
                        decision.intent(),
                        request == null ? "" : request.userInput()
                );
            }
        }
        TaskGraph taskGraph = buildSlowPathTaskGraph(
                decision,
                effectiveParams,
                request,
                fastOutcome == null ? null : fastOutcome.selectedSkill(),
                fastOutcome == null
        );
        if (taskGraph.isEmpty()) {
            TaskPlan taskPlan = buildSlowPathTaskPlan(
                    decision,
                    effectiveParams,
                    request,
                    fastOutcome == null ? null : fastOutcome.selectedSkill(),
                    fastOutcome == null
            );
            if (taskPlan.isEmpty()) {
                return fastOutcome == null ? clarificationOutcome(decision.target(), "无法生成 task plan") : fastOutcome;
            }
            return orchestrateTaskPlan(decision, taskPlan, attachTaskPlan(effectiveParams, taskPlan), request, traceId, "slow-path", decision.intent(), request == null ? "" : request.userInput());
        }
         return orchestrateTaskGraph(decision, taskGraph, attachTaskGraph(effectiveParams, taskGraph), request, traceId, "slow-path", decision.intent(), request == null ? "" : request.userInput());
    }

    private OrchestrationOutcome orchestrateTaskPlan(Decision decision,
                                                     TaskPlan taskPlan,
                                                     Map<String, Object> effectiveParams,
                                                     OrchestrationRequest request,
                                                     String traceId,
                                                     String strategy,
                                                     String intent,
                                                     String trigger) {
        return orchestrateTaskGraph(decision, TaskGraph.fromDsl(effectiveParams), effectiveParams, request, traceId, strategy, intent, trigger);
    }

    private OrchestrationOutcome orchestrateTaskGraph(Decision decision,
                                                      TaskGraph taskGraph,
                                                      Map<String, Object> effectiveParams,
                                                      OrchestrationRequest request,
                                                      String traceId,
                                                      String strategy,
                                                      String intent,
                                                      String trigger) {
        if (taskGraph == null || taskGraph.isEmpty()) {
            return clarificationOutcome("task.graph", "缺少 task graph");
        }
        if (taskGraph.nodes().isEmpty()) {
            return clarificationOutcome("task.graph", "缺少 task nodes");
        }
        SkillContext baseContext = request == null
                ? new SkillContext("", "", effectiveParams)
                : new SkillContext(request.userId(), request.userInput(), buildEffectiveParams(effectiveParams, request.skillContext()));
        TaskGraphExecutionResult taskResult = executeTaskGraphAttempt(
                decision,
                taskGraph,
                baseContext,
                request,
                traceId,
                Map.of(),
                Map.of()
        );
        if (taskResult.finalResult() == null) {
            return clarificationOutcome("task.graph", "未能执行任何 task node");
        }
        RecoveryManager.RecoveryReport rollbackReport = null;
        if (!taskResult.finalResult().success()) {
            rollbackReport = recoveryManager == null
                    ? RecoveryManager.RecoveryReport.noop(traceId, "rollback", "recovery manager unavailable")
                    : recoveryManager.planRollback(traceId, taskGraph, taskResult, taskResult.contextAttributes());
            Map<String, Object> rollbackTrace = new LinkedHashMap<>();
            if (rollbackReport != null) {
                rollbackTrace.put("summary", rollbackReport.summary());
                rollbackTrace.put("clearKeys", rollbackReport.clearKeys());
                rollbackTrace.put("contextPatch", rollbackReport.contextPatch());
                rollbackTrace.put("retryNodeIds", rollbackReport.retryNodeIds());
                rollbackTrace.put("fallbackNodeIds", rollbackReport.fallbackNodeIds());
                rollbackTrace.put("skippedNodeIds", rollbackReport.skippedNodeIds());
            }
            traceEvent(traceId, "recovery", "rollback", rollbackTrace);
            if (rollbackReport != null && rollbackReport.shouldReexecute()) {
                Map<String, Object> recoveryAttributes = buildRecoveryContext(taskResult.contextAttributes(), rollbackReport);
                SkillContext recoveryBaseContext = new SkillContext(baseContext.userId(), baseContext.input(), recoveryAttributes);
                TaskGraphExecutionResult recoveredResult = executeTaskGraphAttempt(
                        decision,
                        rollbackReport.hasRecoveryGraph() ? rollbackReport.recoveryGraph() : taskGraph,
                        recoveryBaseContext,
                        request,
                        traceId,
                        rollbackReport.actionMap(),
                        indexNodeResults(taskResult)
                );
                traceEvent(traceId, "recovery", "reexecute", Map.of(
                        "success", recoveredResult.finalResult() != null && recoveredResult.finalResult().success(),
                        "actions", rollbackReport.actions().size(),
                        "summary", rollbackReport.summary()
                ));
                if (recoveredResult.finalResult() != null) {
                    taskResult = recoveredResult;
                }
            }
        }
        if (taskResult.finalResult().success() && proceduralMemory != null) {
            proceduralMemory.recordSuccess(
                    request == null ? "" : request.userId(),
                    intent == null || intent.isBlank() ? taskResult.finalResult().skillName() : intent,
                    trigger == null ? "" : trigger,
                    taskGraph,
                    taskResult.contextAttributes()
            );
        }
        List<PlanStepDto> steps = taskResult.nodeResults().stream()
                .map(node -> new PlanStepDto(
                        node.nodeId(),
                        node.status(),
                        node.result() == null ? node.target() : node.result().skillName(),
                        node.result() == null ? "no result" : node.result().output(),
                        Instant.now().minusMillis(1),
                        Instant.now()
                ))
                .toList();
        ExecutionTraceDto trace = new ExecutionTraceDto(
                strategy == null || strategy.isBlank() ? "task-graph" : strategy,
                Math.max(0, steps.size() - 1),
                new CritiqueReportDto(taskResult.finalResult().success(),
                        taskResult.finalResult().success() ? "task graph success" : taskResult.finalResult().output(),
                taskResult.nodeResults().stream().anyMatch(TaskGraphExecutionResult.NodeResult::usedFallback) ? "fallback" : "none"),
                steps
        );
        OrchestrationOutcome outcome = new OrchestrationOutcome(
                taskResult.finalResult(),
                new SkillDsl(taskResult.finalResult().skillName(), effectiveParams),
                null,
                trace,
                taskResult.finalResult().skillName(),
                taskResult.nodeResults().stream().anyMatch(TaskGraphExecutionResult.NodeResult::usedFallback)
        );
        return outcome;
    }

    private java.util.Optional<ProceduralMemory.ReusableProcedure> matchReusableProcedure(Decision decision,
                                                                                          OrchestrationRequest request,
                                                                                          Map<String, Object> effectiveParams) {
        if (proceduralMemory == null || decision == null) {
            return java.util.Optional.empty();
        }
        return proceduralMemory.matchReusableProcedure(
                request == null ? "" : request.userId(),
                request == null ? "" : request.userInput(),
                decision.intent() == null || decision.intent().isBlank() ? decision.target() : decision.intent(),
                effectiveParams
        );
    }

    private OrchestrationOutcome orchestrateFastPath(Decision decision,
                                                     String suggestedTarget,
                                                     Map<String, Object> params,
                                                     OrchestrationRequest request,
                                                     boolean allowParallelMcp,
                                                     String traceId) {
        List<ScoredCandidate> plannedCandidates = buildCandidateChain(suggestedTarget, request);
        if (plannedCandidates.isEmpty()) {
            plannedCandidates = List.of(new ScoredCandidate(suggestedTarget, 1.0, 0.0, 0.0, 0.5, List.of("explicit-target")));
        }
        traceEvent(traceId, "planner", "candidate-chain", Map.of(
                "suggestedTarget", suggestedTarget == null ? "" : suggestedTarget,
                "candidateCount", plannedCandidates.size()
        ));
        List<PlanStepDto> steps = new ArrayList<>();
        List<CandidateExecution> executableCandidates = new ArrayList<>();
        String lastFailure = null;
        for (ScoredCandidate candidate : plannedCandidates) {
            ParamValidator.ValidationResult namespaceValidation = validateNamespace(candidate.skillName());
            if (!namespaceValidation.valid()) {
                lastFailure = namespaceValidation.message();
                continue;
            }
            ParamValidator.ValidationResult validation = paramValidator.validate(candidate.skillName(), params, request);
            if (!validation.valid()) {
                lastFailure = validation.message();
                continue;
            }
            executableCandidates.add(new CandidateExecution(
                    candidate,
                    validation.normalizedParams().isEmpty() ? params : validation.normalizedParams(),
                    !Objects.equals(candidate.skillName(), suggestedTarget)
            ));
        }
        if (executableCandidates.isEmpty()) {
            return clarificationOutcome(suggestedTarget, lastFailure == null ? "缺少可执行候选" : lastFailure);
        }
        if (allowParallelMcp && shouldRunMcpParallel(executableCandidates)) {
            return orchestrateMcpInParallel(decision, executableCandidates, request, steps, 0, "fast-path", traceId);
        }
        CandidateExecution execution = executableCandidates.get(0);
        int replans = 0;
        String lastCandidate = execution.skillName();
        boolean usedFallback = execution.usedFallback();
        SkillResult result = executeSingleAttempt(decision, execution, request, steps, "fast-attempt-1", traceId);
        if (result.success()) {
            return successOutcome(result, execution.params(), steps, replans, execution.skillName(), usedFallback, "fast-path");
        }
        lastFailure = result.output();
        if (maxLoops > 1) {
            ParamValidator.ValidationResult repaired = paramValidator.repairAfterFailure(
                    execution.skillName(),
                    execution.params(),
                    result,
                    request
            );
            if (repaired.valid() && !Objects.equals(repaired.normalizedParams(), execution.params())) {
                replans++;
                CandidateExecution retriedExecution = new CandidateExecution(
                        execution.candidate(),
                        repaired.normalizedParams(),
                        execution.usedFallback()
                );
                SkillResult retryResult = executeSingleAttempt(decision, retriedExecution, request, steps, "fast-retry-" + replans, traceId);
                if (retryResult.success()) {
                    return successOutcome(retryResult, retriedExecution.params(), steps, replans, retriedExecution.skillName(), usedFallback, "fast-path");
                }
                result = retryResult;
                lastFailure = retryResult.output();
            }
        }
        ExecutionTraceDto trace = new ExecutionTraceDto(
                "fast-path",
                replans,
                new CritiqueReportDto(false, lastFailure == null ? "unknown" : lastFailure, "fallback"),
                steps
        );
        SkillResult failedResult = result == null
                ? SkillResult.failure(lastCandidate == null ? suggestedTarget : lastCandidate, lastFailure == null ? "unknown" : lastFailure)
                : result;
        return new OrchestrationOutcome(
                failedResult,
                lastCandidate == null ? null : new SkillDsl(lastCandidate, params),
                null,
                trace,
                lastCandidate,
                usedFallback
        );
    }

    private SkillResult executeSingleAttempt(Decision decision,
                                             CandidateExecution execution,
                                             OrchestrationRequest request,
                                             List<PlanStepDto> steps,
                                             String stepName,
                                             String traceId) {
        Instant startedAt = Instant.now();
        PreparedExecution prepared = prepareExecution(decision, execution, request, traceId);
        SkillResult result = executeWithTimeout(
                execution.skillName(),
                execution.params(),
                prepared.context()
        );
        Instant finishedAt = Instant.now();
        steps.add(new PlanStepDto(
                stepName,
                result.success() ? "success" : "failed",
                execution.skillName(),
                buildStepNote(execution.candidate(), result),
                startedAt,
                finishedAt
        ));
        if (!result.success()) {
            RecoveryManager.RecoveryReport retryReport = recoveryManager == null
                    ? RecoveryManager.RecoveryReport.noop(traceId, "retry", "recovery manager unavailable")
                    : recoveryManager.planRetry(traceId, execution.skillName(), result, prepared.context().attributes(), steps);
            Map<String, Object> retryTrace = new LinkedHashMap<>();
            if (retryReport != null) {
                retryTrace.put("summary", retryReport.summary());
                retryTrace.put("clearKeys", retryReport.clearKeys());
                retryTrace.put("contextPatch", retryReport.contextPatch());
            }
            traceEvent(traceId, "recovery", "retry", retryTrace);
            if (retryReport != null && retryReport.shouldReexecute()) {
                SkillContext retryContext = new SkillContext(
                        prepared.context().userId(),
                        prepared.context().input(),
                        buildRecoveryContext(prepared.context().attributes(), retryReport)
                );
                SkillResult retryResult = executeWithTimeout(
                        execution.skillName(),
                        execution.params(),
                        retryContext
                );
                Instant retryFinished = Instant.now();
                steps.add(new PlanStepDto(
                        stepName + ".retry",
                        retryResult.success() ? "success" : "failed",
                        execution.skillName(),
                        buildStepNote(execution.candidate(), retryResult),
                        finishedAt,
                        retryFinished
                ));
                result = retryResult;
                finishedAt = retryFinished;
                traceEvent(traceId, "recovery", "retry-result", Map.of(
                        "skill", execution.skillName(),
                        "success", retryResult.success(),
                        "retryCount", 1,
                        "summary", retryReport.summary()
                ));
            }
        }
        long durationMs = Math.max(0L, java.time.Duration.between(startedAt, finishedAt).toMillis());
        observeLearning(request, execution, prepared.routeDecision(), result, durationMs, traceId);
        traceEvent(traceId, "execute", "attempt", Map.of(
                "skill", execution.skillName(),
                "success", result.success(),
                "routeType", prepared.routeDecision().routeType().name(),
                "durationMs", durationMs,
                "retried", steps.stream().anyMatch(step -> step.stepName().equals(stepName + ".retry"))
        ));
        return result;
    }

    private OrchestrationOutcome orchestrateMcpInParallel(Decision decision,
                                                          List<CandidateExecution> candidates,
                                                          OrchestrationRequest request,
                                                          List<PlanStepDto> inheritedSteps,
                                                          int inheritedReplans,
                                                          String strategy,
                                                          String traceId) {
        List<CompletableFuture<SkillResult>> futures = new ArrayList<>();
        List<PreparedExecution> preparedExecutions = new ArrayList<>();
        List<Instant> startedTimes = new ArrayList<>();
        for (CandidateExecution candidate : candidates) {
            PreparedExecution prepared = prepareExecution(decision, candidate, request, traceId);
            SkillContext skillContext = prepared.context();
            CompletableFuture<SkillResult> execution = applySkillTimeoutIfNeeded(
                    candidate.skillName(),
                    skillContext,
                    skillExecutionGateway.executeDslAsync(new SkillDsl(candidate.skillName(), candidate.params()), skillContext)
            )
                    .completeOnTimeout(SkillResult.failure(candidate.skillName(), "timeout"), mcpPerSkillTimeoutMs, TimeUnit.MILLISECONDS)
                    .exceptionally(error -> SkillResult.failure(candidate.skillName(), String.valueOf(error.getMessage())));
            futures.add(execution);
            preparedExecutions.add(prepared);
            startedTimes.add(Instant.now());
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        List<PlanStepDto> steps = new ArrayList<>(inheritedSteps == null ? List.of() : inheritedSteps);
        List<SkillResult> successes = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            SkillResult result = futures.get(i).join();
            CandidateExecution candidate = candidates.get(i);
            PreparedExecution prepared = preparedExecutions.get(i);
            long durationMs = Math.max(0L, java.time.Duration.between(startedTimes.get(i), Instant.now()).toMillis());
            steps.add(new PlanStepDto(
                    "parallel-" + (i + 1),
                    result.success() ? "success" : "failed",
                    candidate.skillName(),
                    buildStepNote(candidate.candidate(), result),
                    Instant.now().minusMillis(1),
                    Instant.now()
            ));
            observeLearning(request, candidate, prepared.routeDecision(), result, durationMs, traceId);
            traceEvent(traceId, "execute", "mcp-step", Map.of(
                    "skill", candidate.skillName(),
                    "success", result.success(),
                    "routeType", prepared.routeDecision().routeType().name(),
                    "durationMs", durationMs
            ));
            if (result.success()) {
                successes.add(result);
            }
        }
        if (successes.isEmpty()) {
            SkillResult failedResult = candidates.isEmpty()
                    ? SkillResult.failure("unknown", "MCP 调用失败或超时")
                    : futures.get(0).join();
            ExecutionTraceDto trace = new ExecutionTraceDto(
                    strategy == null || strategy.isBlank() ? "decision-orchestrator" : strategy,
                    inheritedReplans,
                    new CritiqueReportDto(false, "all mcp candidates failed", "failed"),
                    steps
            );
            return new OrchestrationOutcome(
                    failedResult,
                    candidates.isEmpty() ? null : new SkillDsl(candidates.get(0).skillName(), candidates.get(0).params()),
                    null,
                    trace,
                    candidates.isEmpty() ? null : candidates.get(0).skillName(),
                    true
            );
        }
        SkillResult selected = successes.stream()
                .sorted((left, right) -> Integer.compare(priorityRank(left.skillName()), priorityRank(right.skillName())))
                .findFirst()
                .orElse(successes.get(0));
        ExecutionTraceDto trace = new ExecutionTraceDto(
                strategy == null || strategy.isBlank() ? "decision-orchestrator" : strategy,
                inheritedReplans,
                new CritiqueReportDto(true, "parallel mcp success", "none"),
                steps
        );
        return new OrchestrationOutcome(selected, new SkillDsl(selected.skillName(), candidates.get(0).params()), null, trace, selected.skillName(), true);
    }

    private OrchestrationOutcome successOutcome(SkillResult result,
                                                Map<String, Object> params,
                                                List<PlanStepDto> steps,
                                                int replans,
                                                String selectedSkill,
                                                boolean usedFallback,
                                                String strategy) {
        ExecutionTraceDto trace = new ExecutionTraceDto(
                strategy == null || strategy.isBlank() ? "decision-orchestrator" : strategy,
                replans,
                new CritiqueReportDto(true, "success", usedFallback ? "fallback" : "none"),
                steps
        );
        return new OrchestrationOutcome(result, new SkillDsl(selectedSkill, params), null, trace, selectedSkill, usedFallback);
    }

    private OrchestrationOutcome clarificationOutcome(String target, String message) {
        return new OrchestrationOutcome(
                null,
                null,
                conversationLoop.requestClarification(target, message),
                null,
                null,
                false
        );
    }

    private List<ScoredCandidate> buildCandidateChain(String suggestedTarget, OrchestrationRequest request) {
        Map<String, ScoredCandidate> ordered = new LinkedHashMap<>();
        if (suggestedTarget != null && !suggestedTarget.isBlank()) {
            ordered.put(suggestedTarget, new ScoredCandidate(suggestedTarget, 1.0, 0.0, 0.0, 0.5, List.of("explicit-target")));
        }
        candidatePlanner.plan(suggestedTarget, request).forEach(candidate -> ordered.putIfAbsent(candidate.skillName(), candidate));
        if (suggestedTarget != null && !suggestedTarget.isBlank()) {
            fallbackPlan.fallbacks(suggestedTarget).forEach(fallback ->
                    ordered.putIfAbsent(fallback, new ScoredCandidate(fallback, 0.35, 0.0, 0.0, 0.5, List.of("configured-fallback"))));
        }
        return List.copyOf(ordered.values());
    }

    private SkillResult unifiedFailureResult(String userInput,
                                             String intent,
                                             List<String> attemptedCandidates,
                                             SkillResult failure) {
        String safeIntent = escapeJson(intent);
        String safeInput = escapeJson(userInput);
        String safeMessage = escapeJson(failure == null ? "all candidates failed" : failure.output());
        String safeLastSkill = escapeJson(failure == null ? "decision.orchestrator" : failure.skillName());
        String candidatesJson = attemptedCandidates == null || attemptedCandidates.isEmpty()
                ? "[]"
                : attemptedCandidates.stream()
                .map(candidate -> "\"" + escapeJson(candidate) + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        String payload = "{"
                + "\"status\":\"failed\","
                + "\"intent\":\"" + safeIntent + "\","
                + "\"userInput\":\"" + safeInput + "\","
                + "\"attemptedCandidates\":" + candidatesJson + ","
                + "\"lastSkill\":\"" + safeLastSkill + "\","
                + "\"message\":\"" + safeMessage + "\""
                + "}";
        return SkillResult.failure("decision.orchestrator", payload);
    }

    private boolean shouldRunMcpParallel(List<CandidateExecution> candidates) {
        if (!mcpParallelEnabled) {
            return false;
        }
        if (candidates == null || candidates.size() < 2) {
            return false;
        }
        return candidates.stream().allMatch(candidate -> isMcpSkill(candidate.skillName()));
    }

    private boolean isMcpSkill(String target) {
        return target != null && target.startsWith("mcp.");
    }

    private SkillResult executeWithTimeout(String candidate,
                                           Map<String, Object> params,
                                           SkillContext skillContext) {
        try {
            CompletableFuture<SkillResult> future = applySkillTimeoutIfNeeded(
                    candidate,
                    skillContext,
                    skillExecutionGateway.executeDslAsync(new SkillDsl(candidate, params), skillContext)
            );
            if (isMcpSkill(candidate)) {
                future = future.completeOnTimeout(
                        SkillResult.failure(candidate, "timeout"),
                        mcpPerSkillTimeoutMs,
                        TimeUnit.MILLISECONDS
                );
            }
            return future.join();
        } catch (Exception ex) {
            return SkillResult.failure(candidate, ex.getMessage());
        }
    }

    private CompletableFuture<SkillResult> applySkillTimeoutIfNeeded(String skillName,
                                                                      SkillContext skillContext,
                                                                      CompletableFuture<SkillResult> executionFuture) {
        if (executionFuture == null
                || eqCoachImTimeoutMs <= 0L
                || !"eq.coach".equals(skillName)
                || skillContext == null
                || !isImInteractionContext(skillContext.attributes())) {
            return executionFuture;
        }
        SkillResult timeoutFallback = SkillResult.success(skillName, eqCoachImTimeoutReply);
        CompletableFuture<SkillResult> timeoutFuture = CompletableFuture.supplyAsync(
                () -> timeoutFallback,
                CompletableFuture.delayedExecutor(eqCoachImTimeoutMs, TimeUnit.MILLISECONDS)
        );
        return executionFuture.applyToEither(timeoutFuture, result -> result);
    }

    private boolean isImInteractionContext(Map<String, Object> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return false;
        }
        if (attributes.containsKey("imPlatform") || attributes.containsKey("imSenderId") || attributes.containsKey("imChatId")) {
            return true;
        }
        Object channel = attributes.get("interactionChannel");
        return channel != null && "im".equalsIgnoreCase(String.valueOf(channel));
    }

    private int priorityRank(String skillName) {
        if (skillName == null || skillName.isBlank() || mcpPriorityOrder.isEmpty()) {
            return Integer.MAX_VALUE;
        }
        String normalized = skillName.trim().toLowerCase();
        for (int i = 0; i < mcpPriorityOrder.size(); i++) {
            String configured = mcpPriorityOrder.get(i);
            if (normalized.equals(configured)) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    private ParamValidator.ValidationResult validateNamespace(String target) {
        if (target == null || target.isBlank() || !target.startsWith("mcp.")) {
            return ParamValidator.ValidationResult.ok();
        }
        String[] parts = target.split("\\.");
        if (parts.length < 3 || parts[1].isBlank() || parts[2].isBlank()) {
            return ParamValidator.ValidationResult.error("MCP 名称需为 mcp.<alias>.<tool>");
        }
        return ParamValidator.ValidationResult.ok();
    }

    private Map<String, Object> buildEffectiveParams(Map<String, Object> params, SkillContext skillContext) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (skillContext != null && skillContext.attributes() != null) {
            merged.putAll(skillContext.attributes());
        }
        if (params != null && !params.isEmpty()) {
            merged.putAll(params);
        }
        return merged.isEmpty() ? Map.of() : Map.copyOf(merged);
    }

    private String resolveExecutionTarget(String intent, Map<String, Object> params) {
        if (params != null) {
            Object explicitTarget = params.get("_target");
            if (explicitTarget instanceof String target && !target.isBlank()) {
                return target.trim();
            }
            if ((intent == null || intent.isBlank()) && params.get("target") instanceof String target && !target.isBlank()) {
                return target.trim();
            }
        }
        return intent == null ? "" : intent;
    }

    private boolean shouldUseSlowPath(Decision decision) {
        return decision != null && decision.confidence() < TASK_PLAN_LOW_CONFIDENCE_THRESHOLD;
    }

    private boolean shouldEscalateToSlowPath(OrchestrationOutcome fastOutcome) {
        return fastOutcome != null
                && fastOutcome.hasResult()
                && fastOutcome.result() != null
                && !fastOutcome.result().success();
    }

    private TaskPlan buildSlowPathTaskPlan(Decision decision,
                                           Map<String, Object> effectiveParams,
                                           OrchestrationRequest request,
                                           String excludeSkill,
                                           boolean requireMultipleSteps) {
        TaskPlan explicitPlan = TaskPlan.from(effectiveParams);
        if ("task.plan".equalsIgnoreCase(decision == null ? "" : decision.target()) || !explicitPlan.isEmpty()) {
            return explicitPlan;
        }
        if (decision == null
                || decision.target() == null
                || decision.target().isBlank()
                || isMcpSkill(decision.target())) {
            return new TaskPlan(List.of());
        }
        List<ScoredCandidate> candidates = buildCandidateChain(decision.target(), request).stream()
                .filter(candidate -> candidate != null && candidate.skillName() != null && !candidate.skillName().isBlank())
                .filter(candidate -> !isMcpSkill(candidate.skillName()))
                .filter(candidate -> excludeSkill == null || excludeSkill.isBlank() || !excludeSkill.equals(candidate.skillName()))
                .filter(candidate -> !requireMultipleSteps || !candidate.skillName().equals(decision.target()))
                .toList();
        if (candidates.isEmpty()) {
            return new TaskPlan(List.of());
        }
        List<TaskStep> steps = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        int index = 1;
        for (ScoredCandidate candidate : candidates) {
            if (!seen.add(candidate.skillName())) {
                continue;
            }
            Map<String, Object> stepParams = steps.isEmpty() ? (effectiveParams == null ? Map.of() : effectiveParams) : Map.of();
            steps.add(new TaskStep("auto-step-" + index, candidate.skillName(), stepParams, "auto" + index, false));
            index++;
            if (steps.size() >= 3) {
                break;
            }
        }
        if (requireMultipleSteps && steps.size() < 2) {
            return new TaskPlan(List.of());
        }
        return new TaskPlan(steps);
    }

    private TaskGraph buildSlowPathTaskGraph(Decision decision,
                                             Map<String, Object> effectiveParams,
                                             OrchestrationRequest request,
                                             String excludeSkill,
                                             boolean requireMultipleSteps) {
        TaskGraph explicitGraph = TaskGraph.fromDsl(effectiveParams);
        if (!explicitGraph.isEmpty()) {
            return explicitGraph;
        }
        TaskGraph searchGraph = buildSearchTaskGraph(decision, effectiveParams, request, excludeSkill, requireMultipleSteps);
        if (!searchGraph.isEmpty()) {
            return searchGraph;
        }
        if (decision == null
                || decision.target() == null
                || decision.target().isBlank()
                || isMcpSkill(decision.target())) {
            return new TaskGraph(List.of(), List.of());
        }
        List<ScoredCandidate> candidates = buildCandidateChain(decision.target(), request).stream()
                .filter(candidate -> candidate != null && candidate.skillName() != null && !candidate.skillName().isBlank())
                .filter(candidate -> !isMcpSkill(candidate.skillName()))
                .filter(candidate -> excludeSkill == null || excludeSkill.isBlank() || !excludeSkill.equals(candidate.skillName()))
                .filter(candidate -> !requireMultipleSteps || !candidate.skillName().equals(decision.target()))
                .toList();
        if (candidates.isEmpty()) {
            return new TaskGraph(List.of(), List.of());
        }
        List<TaskNode> nodes = new ArrayList<>();
        List<String> path = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (ScoredCandidate candidate : candidates) {
            if (!seen.add(candidate.skillName())) {
                continue;
            }
            path.add(candidate.skillName());
            if (path.size() >= 3) {
                break;
            }
        }
        if (requireMultipleSteps && path.size() < 2) {
            return new TaskGraph(List.of(), List.of());
        }
        TaskGraph linearGraph = TaskGraph.linear(path, effectiveParams);
        nodes.addAll(linearGraph.nodes());
        return new TaskGraph(nodes, linearGraph.edges());
    }

    private TaskGraph buildSearchTaskGraph(Decision decision,
                                           Map<String, Object> effectiveParams,
                                           OrchestrationRequest request,
                                           String excludeSkill,
                                           boolean requireMultipleSteps) {
        if (searchPlanner == null || decision == null || decision.target() == null || decision.target().isBlank()) {
            return new TaskGraph(List.of(), List.of());
        }
        List<SearchCandidate> candidates = searchPlanner.search(new SearchPlanningRequest(
                request == null ? "" : request.userId(),
                request == null ? "" : request.userInput(),
                decision.target(),
                effectiveParams,
                3,
                3
        ));
        if (candidates.isEmpty()) {
            return new TaskGraph(List.of(), List.of());
        }
        List<String> path = candidates.get(0).path().stream()
                .filter(skill -> excludeSkill == null || excludeSkill.isBlank() || !excludeSkill.equals(skill))
                .filter(skill -> !requireMultipleSteps || !decision.target().equals(skill))
                .distinct()
                .limit(3)
                .toList();
        if (path.isEmpty() || (requireMultipleSteps && path.size() < 2)) {
            return new TaskGraph(List.of(), List.of());
        }
        return TaskGraph.linear(path, effectiveParams);
    }

    private Map<String, Object> attachTaskGraph(Map<String, Object> effectiveParams, TaskGraph taskGraph) {
        Map<String, Object> enriched = new LinkedHashMap<>(effectiveParams == null ? Map.of() : effectiveParams);
        enriched.put("nodes", taskGraph.nodes().stream()
                .map(node -> Map.of(
                        "id", node.id(),
                        "target", node.target(),
                        "params", node.params(),
                        "saveAs", node.saveAs(),
                        "optional", node.optional(),
                        "dependsOn", node.dependsOn()))
                .toList());
        enriched.put("edges", taskGraph.edges().stream()
                .map(edge -> Map.of("from", edge.from(), "to", edge.to()))
                .toList());
        enriched.put("tasks", taskGraph.nodes().stream()
                .map(node -> Map.of(
                        "id", node.id(),
                        "target", node.target(),
                        "params", node.params(),
                        "saveAs", node.saveAs(),
                        "optional", node.optional(),
                        "dependsOn", node.dependsOn()))
                .toList());
        return Map.copyOf(enriched);
    }

    private Map<String, Object> attachTaskPlan(Map<String, Object> effectiveParams, TaskPlan taskPlan) {
        Map<String, Object> enriched = new LinkedHashMap<>(effectiveParams == null ? Map.of() : effectiveParams);
        enriched.put("tasks", taskPlan.steps().stream()
                .map(step -> Map.of(
                        "id", step.id(),
                        "target", step.target(),
                        "params", step.params(),
                        "saveAs", step.saveAs(),
                        "optional", step.optional()))
                .toList());
        return Map.copyOf(enriched);
    }

    private TaskGraphExecutionResult executeTaskGraphAttempt(Decision decision,
                                                             TaskGraph taskGraph,
                                                             SkillContext baseContext,
                                                             OrchestrationRequest request,
                                                             String traceId,
                                                             Map<String, RecoveryAction> recoveryActions,
                                                             Map<String, TaskGraphExecutionResult.NodeResult> cachedResults) {
        Map<String, RecoveryAction> safeActions = recoveryActions == null ? Map.of() : recoveryActions;
        Map<String, TaskGraphExecutionResult.NodeResult> safeCachedResults = cachedResults == null ? Map.of() : cachedResults;
        return dagExecutor.execute(taskGraph, baseContext, (node, nodeContext) ->
                executeTaskGraphNode(decision, node, nodeContext, request, traceId, safeActions, safeCachedResults));
    }

    private DAGExecutor.NodeExecution executeTaskGraphNode(Decision decision,
                                                           TaskNode node,
                                                           SkillContext nodeContext,
                                                           OrchestrationRequest request,
                                                           String traceId,
                                                           Map<String, RecoveryAction> recoveryActions,
                                                           Map<String, TaskGraphExecutionResult.NodeResult> cachedResults) {
        if (node == null) {
            return new DAGExecutor.NodeExecution(SkillResult.failure("unknown", "missing task node"), false);
        }
        RecoveryAction action = recoveryActions == null ? null : recoveryActions.get(node.id());
        if (action != null) {
            return switch (action.type()) {
                case RETRY_NODE -> executeRecoveredTarget(decision, node.target(), nodeContext, request, traceId, false);
                case FALLBACK_NODE -> {
                    if (!action.fallbackTarget().isBlank()) {
                        yield executeRecoveredTarget(decision, action.fallbackTarget(), nodeContext, request, traceId, true);
                    }
                    String syntheticOutput = action.syntheticOutput().isBlank() ? "fallback" : action.syntheticOutput();
                    yield new DAGExecutor.NodeExecution(SkillResult.success(action.target().isBlank() ? node.target() : action.target(), syntheticOutput), true);
                }
                case SKIP_NODE -> {
                    String syntheticOutput = action.syntheticOutput().isBlank() ? "skipped" : action.syntheticOutput();
                    yield new DAGExecutor.NodeExecution(SkillResult.success(node.target(), syntheticOutput), true);
                }
                case ROLLBACK_STEP, PATCH_CONTEXT -> executeTaskGraphCachedOrNormal(decision, node, nodeContext, request, traceId, cachedResults);
            };
        }
        return executeTaskGraphCachedOrNormal(decision, node, nodeContext, request, traceId, cachedResults);
    }

    private DAGExecutor.NodeExecution executeTaskGraphCachedOrNormal(Decision decision,
                                                                     TaskNode node,
                                                                     SkillContext nodeContext,
                                                                     OrchestrationRequest request,
                                                                     String traceId,
                                                                     Map<String, TaskGraphExecutionResult.NodeResult> cachedResults) {
        TaskGraphExecutionResult.NodeResult cachedResult = cachedResults == null ? null : cachedResults.get(node.id());
        if (cachedResult != null && cachedResult.result() != null && cachedResult.result().success()) {
            return new DAGExecutor.NodeExecution(cachedResult.result(), cachedResult.usedFallback());
        }
        if ("task.plan".equalsIgnoreCase(node.target())) {
            return new DAGExecutor.NodeExecution(
                    SkillResult.failure("task.plan", "nested task.plan is not supported"),
                    false
            );
        }
        return executeTaskNode(decision, node.target(), nodeContext, request, traceId, false);
    }

    private DAGExecutor.NodeExecution executeRecoveredTarget(Decision decision,
                                                             String target,
                                                             SkillContext nodeContext,
                                                             OrchestrationRequest request,
                                                             String traceId,
                                                             boolean usedFallback) {
        if (target == null || target.isBlank()) {
            return new DAGExecutor.NodeExecution(SkillResult.failure("unknown", "missing recovery target"), usedFallback);
        }
        return executeTaskNode(decision, target, nodeContext, request, traceId, usedFallback);
    }

    private DAGExecutor.NodeExecution executeTaskNode(Decision decision,
                                                      String target,
                                                      SkillContext nodeContext,
                                                      OrchestrationRequest request,
                                                      String traceId,
                                                      boolean usedFallback) {
        if ("task.plan".equalsIgnoreCase(target)) {
            return new DAGExecutor.NodeExecution(
                    SkillResult.failure("task.plan", "nested task.plan is not supported"),
                    usedFallback
            );
        }
        OrchestrationRequest nestedRequest = request == null
                ? new OrchestrationRequest(nodeContext.userId(), nodeContext.input(), nodeContext, Map.of())
                : new OrchestrationRequest(request.userId(), request.userInput(), nodeContext, request.safeProfileContext());
        OrchestrationOutcome outcome = orchestrateFastPath(
                decision,
                target,
                nodeContext.attributes(),
                nestedRequest,
                false,
                traceId
        );
        SkillResult stepResult = outcome.hasResult() ? outcome.result() : outcome.clarification();
        return new DAGExecutor.NodeExecution(stepResult, usedFallback || outcome.usedFallback());
    }

    private Map<String, Object> buildRecoveryContext(Map<String, Object> baseContext,
                                                     RecoveryManager.RecoveryReport report) {
        Map<String, Object> cleared = applyClearKeys(baseContext, report == null ? List.of() : report.clearKeys());
        return applyContextPatch(cleared, report == null ? Map.of() : report.contextPatch());
    }

    private Map<String, Object> applyClearKeys(Map<String, Object> baseContext, List<String> clearKeys) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (baseContext != null && !baseContext.isEmpty()) {
            merged.putAll(baseContext);
        }
        if (clearKeys != null) {
            for (String key : clearKeys) {
                if (key == null || key.isBlank()) {
                    continue;
                }
                merged.remove(key);
            }
        }
        return merged.isEmpty() ? Map.of() : Map.copyOf(merged);
    }

    private Map<String, TaskGraphExecutionResult.NodeResult> indexNodeResults(TaskGraphExecutionResult result) {
        if (result == null || result.nodeResults() == null || result.nodeResults().isEmpty()) {
            return Map.of();
        }
        Map<String, TaskGraphExecutionResult.NodeResult> indexed = new LinkedHashMap<>();
        for (TaskGraphExecutionResult.NodeResult nodeResult : result.nodeResults()) {
            if (nodeResult == null || nodeResult.nodeId() == null || nodeResult.nodeId().isBlank()) {
                continue;
            }
            indexed.put(nodeResult.nodeId(), nodeResult);
        }
        return indexed.isEmpty() ? Map.of() : Map.copyOf(indexed);
    }

    private PreparedExecution prepareExecution(Decision decision,
                                               CandidateExecution execution,
                                               OrchestrationRequest request,
                                               String traceId) {
        SkillContext baseContext = buildExecutionContext(request, execution.params());
        AgentRouter.AgentRouteDecision routeDecision = resolveRouteDecision(decision, execution, request, baseContext, traceId);
        Map<String, Object> routedAttributes = applyContextPatch(baseContext.attributes(), routeDecision.contextPatch());
        SkillContext routedContext = new SkillContext(baseContext.userId(), baseContext.input(), routedAttributes);
        traceEvent(traceId, "route", "selected", Map.of(
                "skill", execution.skillName(),
                "routeType", routeDecision.routeType().name(),
                "provider", routeDecision.provider(),
                "preset", routeDecision.preset(),
                "model", routeDecision.model(),
                "confidence", routeDecision.confidence()
        ));
        return new PreparedExecution(routedContext, routeDecision);
    }

    private AgentRouter.AgentRouteDecision resolveRouteDecision(Decision decision,
                                                                CandidateExecution execution,
                                                                OrchestrationRequest request,
                                                                SkillContext baseContext,
                                                                String traceId) {
        if (agentRouter != null) {
            return agentRouter.route(decision, request, execution.candidate(), baseContext == null ? Map.of() : baseContext.attributes());
        }
        List<String> reasons = List.of("fallback-router", execution.usedFallback() ? "fallback" : "direct");
        String candidateName = execution.skillName();
        if (isMcpSkill(candidateName)) {
            return AgentRouter.AgentRouteDecision.mcp("", "", "", execution.candidate() == null ? 0.5 : execution.candidate().finalScore(), reasons, Map.of("routeType", "mcp"));
        }
        double score = execution.candidate() == null ? 0.5 : execution.candidate().finalScore();
        if (decision != null && decision.confidence() >= TASK_PLAN_LOW_CONFIDENCE_THRESHOLD) {
            return AgentRouter.AgentRouteDecision.local("local", "cost", "", score, reasons, Map.of("routeType", "local", "llmProvider", "local", "llmPreset", "cost"));
        }
        return AgentRouter.AgentRouteDecision.remote("", "", "", score, reasons, Map.of("routeType", "remote"));
    }

    private Map<String, Object> applyContextPatch(Map<String, Object> baseContext, Map<String, Object> patch) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (baseContext != null && !baseContext.isEmpty()) {
            merged.putAll(baseContext);
        }
        if (patch != null && !patch.isEmpty()) {
            for (Map.Entry<String, Object> entry : patch.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) {
                    continue;
                }
                if (entry.getValue() == null) {
                    merged.remove(entry.getKey());
                } else {
                    merged.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return merged.isEmpty() ? Map.of() : Map.copyOf(merged);
    }

    private void observeLearning(OrchestrationRequest request,
                                 CandidateExecution execution,
                                 AgentRouter.AgentRouteDecision routeDecision,
                                 SkillResult result,
                                 long durationMs,
                                 String traceId) {
        if (plannerLearningStore == null || request == null || execution == null) {
            return;
        }
        int tokenEstimate = estimateTokens(request.userInput())
                + estimateTokens(execution.params() == null ? "" : execution.params().toString())
                + estimateTokens(result == null ? "" : result.output());
        plannerLearningStore.observe(
                request.userId(),
                execution.skillName(),
                routeDecision == null ? "unknown" : routeDecision.routeType().name(),
                result != null && result.success(),
                durationMs,
                tokenEstimate,
                execution.usedFallback()
        );
        traceEvent(traceId, "learning", "observe", Map.of(
                "skill", execution.skillName(),
                "success", result != null && result.success(),
                "durationMs", durationMs,
                "tokenEstimate", tokenEstimate,
                "routeType", routeDecision == null ? "unknown" : routeDecision.routeType().name()
        ));
    }

    private int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return Math.max(1, (text.length() + 3) / 4);
    }

    private String startTrace(Decision decision, OrchestrationRequest request) {
        String userId = request == null ? "" : request.userId();
        String intent = decision == null ? "" : decision.intent();
        String input = request == null ? "" : request.userInput();
        if (traceLogger == null) {
            return java.util.UUID.randomUUID().toString();
        }
        return traceLogger.start(userId, intent, input);
    }

    private void traceEvent(String traceId, String phase, String action, Map<String, Object> details) {
        if (traceLogger == null || traceId == null || traceId.isBlank()) {
            return;
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        if (details != null) {
            for (Map.Entry<String, Object> entry : details.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) {
                    continue;
                }
                safe.put(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
            }
        }
        traceLogger.event(traceId, phase, action, safe);
    }

    private void finishTrace(String traceId,
                             Decision decision,
                             OrchestrationRequest request,
                             OrchestrationOutcome outcome) {
        if (traceLogger == null || traceId == null || traceId.isBlank()) {
            return;
        }
        boolean success = outcome != null && outcome.hasResult() && outcome.result() != null && outcome.result().success();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("intent", decision == null ? "" : decision.intent());
        details.put("target", decision == null ? "" : decision.target());
        details.put("userId", request == null ? "" : request.userId());
        details.put("selectedSkill", outcome == null ? "" : outcome.selectedSkill());
        details.put("usedFallback", outcome != null && outcome.usedFallback());
        details.put("strategy", outcome != null && outcome.trace() != null ? outcome.trace().strategy() : "");
        traceLogger.finish(traceId, success, success ? "success" : "failure", details);
    }

    private SkillContext buildExecutionContext(OrchestrationRequest request, Map<String, Object> params) {
        SkillContext baseContext = request == null ? null : request.skillContext();
        String userId = request == null ? "" : request.userId();
        String input = request == null ? "" : request.userInput();
        Map<String, Object> merged = buildEffectiveParams(params, baseContext);
        return new SkillContext(
                baseContext == null ? userId : baseContext.userId(),
                baseContext == null ? input : baseContext.input(),
                merged
        );
    }

    private String buildStepNote(ScoredCandidate candidate, SkillResult result) {
        StringBuilder note = new StringBuilder();
        note.append("score=").append(candidate.finalScore());
        if (!candidate.reasons().isEmpty()) {
            note.append(", reasons=").append(candidate.reasons());
        }
        if (result != null && result.output() != null && !result.output().isBlank()) {
            note.append(", result=").append(result.output());
        }
        return note.toString();
    }

    private record PreparedExecution(SkillContext context, AgentRouter.AgentRouteDecision routeDecision) {
    }

    private record CandidateExecution(ScoredCandidate candidate, Map<String, Object> params, boolean usedFallback) {
        private String skillName() {
            return candidate.skillName();
        }
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
