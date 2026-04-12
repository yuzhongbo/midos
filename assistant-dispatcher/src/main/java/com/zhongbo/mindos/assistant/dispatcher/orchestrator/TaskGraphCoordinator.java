package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillDsl;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.common.dto.CritiqueReportDto;
import com.zhongbo.mindos.assistant.common.dto.ExecutionTraceDto;
import com.zhongbo.mindos.assistant.common.dto.PlanStepDto;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.DAGExecutor;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.StructuredExecutionRuntime;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraphExecutionResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskNode;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryCommandService;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryFacade;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.DecisionOrchestrator.OrchestrationOutcome;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.DecisionOrchestrator.OrchestrationRequest;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5.AgentRouter;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5.PlannerLearningStore;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5.PolicyUpdater;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5.RewardModel;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5.RecoveryAction;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5.RecoveryManager;
import com.zhongbo.mindos.assistant.skill.SkillExecutionGateway;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

final class TaskGraphCoordinator {
    private static final double DEFAULT_ROUTE_SCORE = 0.5;

    private final Supplier<DispatcherMemoryFacade> memoryFacadeSupplier;
    private final Supplier<RecoveryManager> recoveryManagerSupplier;
    private final Supplier<AgentRouter> agentRouterSupplier;
    private final Supplier<PlannerLearningStore> plannerLearningStoreSupplier;
    private final Supplier<PolicyUpdater> policyUpdaterSupplier;
    private final ParamValidator paramValidator;
    private final SkillExecutionGateway skillExecutionGateway;
    private final TaskGraphBridge bridge;
    private final double localRoutingConfidenceThreshold;
    private final long mcpPerSkillTimeoutMs;
    private final long eqCoachImTimeoutMs;
    private final String eqCoachImTimeoutReply;
    private final StructuredExecutionRuntime structuredExecutionRuntime = new StructuredExecutionRuntime();

    TaskGraphCoordinator(Supplier<DispatcherMemoryFacade> memoryFacadeSupplier,
                         Supplier<RecoveryManager> recoveryManagerSupplier,
                         Supplier<AgentRouter> agentRouterSupplier,
                         Supplier<PlannerLearningStore> plannerLearningStoreSupplier,
                         Supplier<PolicyUpdater> policyUpdaterSupplier,
                         ParamValidator paramValidator,
                         SkillExecutionGateway skillExecutionGateway,
                         TaskGraphBridge bridge,
                         double localRoutingConfidenceThreshold,
                         long mcpPerSkillTimeoutMs,
                         long eqCoachImTimeoutMs,
                         String eqCoachImTimeoutReply) {
        this.memoryFacadeSupplier = memoryFacadeSupplier;
        this.recoveryManagerSupplier = recoveryManagerSupplier;
        this.agentRouterSupplier = agentRouterSupplier;
        this.plannerLearningStoreSupplier = plannerLearningStoreSupplier;
        this.policyUpdaterSupplier = policyUpdaterSupplier;
        this.paramValidator = paramValidator;
        this.skillExecutionGateway = skillExecutionGateway;
        this.bridge = bridge;
        this.localRoutingConfidenceThreshold = localRoutingConfidenceThreshold;
        this.mcpPerSkillTimeoutMs = Math.max(250L, mcpPerSkillTimeoutMs);
        this.eqCoachImTimeoutMs = Math.max(0L, eqCoachImTimeoutMs);
        this.eqCoachImTimeoutReply = eqCoachImTimeoutReply == null || eqCoachImTimeoutReply.isBlank()
                ? "我先给你一个简短结论：当前请求较复杂，我正在整理更完整建议，稍后继续发你。"
                : eqCoachImTimeoutReply;
    }

    OrchestrationOutcome orchestrateTaskPlan(Decision decision,
                                             TaskPlan taskPlan,
                                             Map<String, Object> effectiveParams,
                                             OrchestrationRequest request,
                                             String traceId,
                                             String strategy,
                                             String intent,
                                             String trigger) {
        return orchestrateTaskGraph(
                decision,
                TaskGraph.fromDsl(effectiveParams),
                effectiveParams,
                request,
                traceId,
                strategy,
                intent,
                trigger
        );
    }

    OrchestrationOutcome orchestrateTaskGraph(Decision decision,
                                              TaskGraph taskGraph,
                                              Map<String, Object> effectiveParams,
                                              OrchestrationRequest request,
                                              String traceId,
                                              String strategy,
                                              String intent,
                                              String trigger) {
        if (taskGraph == null || taskGraph.isEmpty()) {
            return bridge.clarificationOutcome("task.graph", "缺少 task graph");
        }
        if (taskGraph.nodes().isEmpty()) {
            return bridge.clarificationOutcome("task.graph", "缺少 task nodes");
        }
        SkillContext baseContext = request == null
                ? new SkillContext("", "", effectiveParams)
                : new SkillContext(request.userId(), request.userInput(), bridge.buildEffectiveParams(effectiveParams, request.skillContext()));
        OrchestrationOutcome preflightOutcome = preflightClarification(taskGraph, baseContext, request);
        if (preflightOutcome != null) {
            return preflightOutcome;
        }
        ExecutionTraceCollector traceCollector = new ExecutionTraceCollector();
        TaskGraphExecutionResult taskResult = executeTaskGraphAttempt(
                decision,
                taskGraph,
                baseContext,
                request,
                traceId,
                traceCollector,
                Map.of(),
                Map.of()
        );
        if (taskResult.finalResult() == null) {
            return bridge.clarificationOutcome("task.graph", "未能执行任何 task node");
        }
        RecoveryManager recoveryManager = recoveryManagerSupplier.get();
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
            bridge.traceEvent(traceId, "recovery", "rollback", rollbackTrace);
            if (rollbackReport != null && rollbackReport.shouldReexecute()) {
                Map<String, Object> recoveryAttributes = buildRecoveryContext(taskResult.contextAttributes(), rollbackReport);
                SkillContext recoveryBaseContext = new SkillContext(baseContext.userId(), baseContext.input(), recoveryAttributes);
                TaskGraphExecutionResult recoveredResult = executeTaskGraphAttempt(
                        decision,
                        rollbackReport.hasRecoveryGraph() ? rollbackReport.recoveryGraph() : taskGraph,
                        recoveryBaseContext,
                        request,
                        traceId,
                        traceCollector,
                        rollbackReport.actionMap(),
                        indexNodeResults(taskResult)
                );
                bridge.traceEvent(traceId, "recovery", "reexecute", Map.of(
                        "success", recoveredResult.finalResult() != null && recoveredResult.finalResult().success(),
                        "actions", rollbackReport.actions().size(),
                        "summary", rollbackReport.summary()
                ));
                if (recoveredResult.finalResult() != null) {
                    taskResult = recoveredResult;
                }
            }
        }
        DispatcherMemoryFacade dispatcherMemoryFacade = memoryFacadeSupplier.get();
        if (taskResult.finalResult().success() && dispatcherMemoryFacade != null) {
            new DispatcherMemoryCommandService(dispatcherMemoryFacade, null).recordProcedureSuccess(
                    request == null ? "" : request.userId(),
                    intent == null || intent.isBlank() ? taskResult.finalResult().skillName() : intent,
                    trigger == null ? "" : trigger,
                    taskGraph,
                    taskResult.contextAttributes()
            );
        }
        List<PlanStepDto> steps = traceCollector.steps().isEmpty()
                ? taskResult.nodeResults().stream()
                .map(node -> new PlanStepDto(
                        node.nodeId(),
                        node.status(),
                        node.result() == null ? node.target() : node.result().skillName(),
                        node.result() == null ? "no result" : node.result().output(),
                        Instant.now().minusMillis(1),
                        Instant.now()
                ))
                .toList()
                : traceCollector.steps();
        boolean usedFallback = taskResult.nodeResults().stream().anyMatch(TaskGraphExecutionResult.NodeResult::usedFallback);
        ExecutionTraceDto trace = new ExecutionTraceDto(
                strategy == null || strategy.isBlank() ? "task-graph" : strategy,
                traceCollector.replanCount(),
                new CritiqueReportDto(
                        taskResult.finalResult().success(),
                        taskResult.finalResult().success() ? "task graph success" : taskResult.finalResult().output(),
                        usedFallback ? "fallback" : "none"
                ),
                steps
        );
        return new OrchestrationOutcome(
                taskResult.finalResult(),
                new SkillDsl(taskResult.finalResult().skillName(), effectiveParams),
                null,
                trace,
                taskResult.finalResult().skillName(),
                usedFallback
        );
    }

    interface TaskGraphBridge {
        OrchestrationOutcome clarificationOutcome(String target, String message);

        Map<String, Object> buildEffectiveParams(Map<String, Object> params, SkillContext skillContext);

        Map<String, Object> applyContextPatch(Map<String, Object> baseContext, Map<String, Object> patch);

        void traceEvent(String traceId, String phase, String action, Map<String, Object> details);
    }

    private TaskGraphExecutionResult executeTaskGraphAttempt(Decision decision,
                                                             TaskGraph taskGraph,
                                                             SkillContext baseContext,
                                                             OrchestrationRequest request,
                                                             String traceId,
                                                             ExecutionTraceCollector traceCollector,
                                                             Map<String, RecoveryAction> recoveryActions,
                                                             Map<String, TaskGraphExecutionResult.NodeResult> cachedResults) {
        Map<String, RecoveryAction> safeActions = recoveryActions == null ? Map.of() : recoveryActions;
        Map<String, TaskGraphExecutionResult.NodeResult> safeCachedResults = cachedResults == null ? Map.of() : cachedResults;
        return structuredExecutionRuntime.execute(taskGraph, baseContext, (node, nodeContext) ->
                executeTaskGraphNode(decision, node, nodeContext, request, traceId, traceCollector, safeActions, safeCachedResults));
    }

    private DAGExecutor.NodeExecution executeTaskGraphNode(Decision decision,
                                                            TaskNode node,
                                                            SkillContext nodeContext,
                                                            OrchestrationRequest request,
                                                            String traceId,
                                                            ExecutionTraceCollector traceCollector,
                                                            Map<String, RecoveryAction> recoveryActions,
                                                            Map<String, TaskGraphExecutionResult.NodeResult> cachedResults) {
        if (node == null) {
            return new DAGExecutor.NodeExecution(SkillResult.failure("unknown", "missing task node"), false);
        }
        RecoveryAction action = recoveryActions == null ? null : recoveryActions.get(node.id());
        if (action != null) {
            return switch (action.type()) {
                case RETRY_NODE -> executeRecoveredTarget(decision, node.id(), node.target(), nodeContext, request, traceId, traceCollector, false);
                case FALLBACK_NODE -> {
                    if (!action.fallbackTarget().isBlank()) {
                        yield executeRecoveredTarget(decision, node.id(), action.fallbackTarget(), nodeContext, request, traceId, traceCollector, true);
                    }
                    String syntheticOutput = action.syntheticOutput().isBlank() ? "fallback" : action.syntheticOutput();
                    yield new DAGExecutor.NodeExecution(
                            SkillResult.success(action.target().isBlank() ? node.target() : action.target(), syntheticOutput),
                            true
                    );
                }
                case SKIP_NODE -> {
                    String syntheticOutput = action.syntheticOutput().isBlank() ? "skipped" : action.syntheticOutput();
                    yield new DAGExecutor.NodeExecution(SkillResult.success(node.target(), syntheticOutput), true);
                }
                case ROLLBACK_STEP, PATCH_CONTEXT -> executeTaskGraphCachedOrNormal(decision, node, nodeContext, request, traceId, traceCollector, cachedResults);
            };
        }
        return executeTaskGraphCachedOrNormal(decision, node, nodeContext, request, traceId, traceCollector, cachedResults);
    }

    private DAGExecutor.NodeExecution executeTaskGraphCachedOrNormal(Decision decision,
                                                                      TaskNode node,
                                                                      SkillContext nodeContext,
                                                                      OrchestrationRequest request,
                                                                      String traceId,
                                                                      ExecutionTraceCollector traceCollector,
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
        return executeTaskNode(decision, node.id(), node.target(), nodeContext, request, traceId, traceCollector, false);
    }

    private DAGExecutor.NodeExecution executeRecoveredTarget(Decision decision,
                                                             String nodeId,
                                                              String target,
                                                              SkillContext nodeContext,
                                                              OrchestrationRequest request,
                                                              String traceId,
                                                              ExecutionTraceCollector traceCollector,
                                                              boolean usedFallback) {
        if (target == null || target.isBlank()) {
            return new DAGExecutor.NodeExecution(SkillResult.failure("unknown", "missing recovery target"), usedFallback);
        }
        return executeTaskNode(decision, nodeId, target, nodeContext, request, traceId, traceCollector, usedFallback);
    }

    private DAGExecutor.NodeExecution executeTaskNode(Decision decision,
                                                      String nodeId,
                                                      String target,
                                                      SkillContext nodeContext,
                                                      OrchestrationRequest request,
                                                      String traceId,
                                                      ExecutionTraceCollector traceCollector,
                                                      boolean usedFallback) {
        if (target == null || target.isBlank()) {
            traceCollector.record(nodeId, "failed", "unknown", "missing task target");
            return new DAGExecutor.NodeExecution(
                    SkillResult.failure("unknown", "missing task target"),
                    usedFallback
            );
        }
        if ("task.plan".equalsIgnoreCase(target)) {
            traceCollector.record(nodeId, "failed", "task.plan", "nested task.plan is not supported");
            return new DAGExecutor.NodeExecution(
                    SkillResult.failure("task.plan", "nested task.plan is not supported"),
                    usedFallback
            );
        }
        OrchestrationRequest nestedRequest = request == null
                ? new OrchestrationRequest(nodeContext.userId(), nodeContext.input(), nodeContext, Map.of())
                : new OrchestrationRequest(request.userId(), request.userInput(), nodeContext, request.safeProfileContext());
        Instant startedAt = Instant.now();
        ParamValidator.ValidationResult validation = paramValidator.validate(target, nodeContext.attributes(), nestedRequest);
        if (!validation.valid()) {
            traceCollector.record(nodeId, "failed", target, validation.message());
            return new DAGExecutor.NodeExecution(
                    SkillResult.failure(target, validation.message()),
                    usedFallback
            );
        }
        Map<String, Object> normalizedParams = validation.normalizedParams().isEmpty()
                ? nodeContext.attributes()
                : validation.normalizedParams();
        SkillContext executionContext = new SkillContext(nodeContext.userId(), nodeContext.input(), normalizedParams);
        AgentRouter.AgentRouteDecision routeDecision = resolveRouteDecision(
                decision,
                target,
                nestedRequest,
                executionContext,
                usedFallback
        );
        Map<String, Object> routedAttributes = bridge.applyContextPatch(executionContext.attributes(), routeDecision.contextPatch());
        SkillContext routedContext = new SkillContext(executionContext.userId(), executionContext.input(), routedAttributes);
        bridge.traceEvent(traceId, "route", "selected", Map.of(
                "skill", target,
                "routeType", routeDecision.routeType().name(),
                "provider", routeDecision.provider(),
                "preset", routeDecision.preset(),
                "model", routeDecision.model(),
                "confidence", routeDecision.confidence()
        ));
        SkillResult result = executeWithTimeout(target, normalizedParams, routedContext);
        Instant finishedAt = Instant.now();
        long durationMs = Math.max(0L, java.time.Duration.between(startedAt, finishedAt).toMillis());
        traceCollector.record(nodeId, result.success() ? "success" : "failed", target, result.output(), startedAt, finishedAt);
        if (!result.success()) {
            ParamValidator.ValidationResult repaired = paramValidator.repairAfterFailure(
                    target,
                    normalizedParams,
                    result,
                    nestedRequest
            );
            if (repaired.valid() && !repaired.normalizedParams().equals(normalizedParams)) {
                traceCollector.incrementReplan();
                Instant repairedStartedAt = Instant.now();
                SkillContext repairedBaseContext = new SkillContext(nodeContext.userId(), nodeContext.input(), repaired.normalizedParams());
                AgentRouter.AgentRouteDecision repairedRoute = resolveRouteDecision(
                        decision,
                        target,
                        nestedRequest,
                        repairedBaseContext,
                        usedFallback
                );
                Map<String, Object> repairedAttributes = bridge.applyContextPatch(repairedBaseContext.attributes(), repairedRoute.contextPatch());
                SkillContext repairedContext = new SkillContext(repairedBaseContext.userId(), repairedBaseContext.input(), repairedAttributes);
                result = executeWithTimeout(target, repaired.normalizedParams(), repairedContext);
                Instant repairedFinishedAt = Instant.now();
                durationMs = Math.max(0L, java.time.Duration.between(repairedStartedAt, repairedFinishedAt).toMillis());
                traceCollector.record(nodeId + ".repair", result.success() ? "success" : "failed", target, result.output(), repairedStartedAt, repairedFinishedAt);
                routeDecision = repairedRoute;
                normalizedParams = repaired.normalizedParams();
                routedContext = repairedContext;
            }
        }
        if (!result.success()) {
            RecoveryManager recoveryManager = recoveryManagerSupplier.get();
            RecoveryManager.RecoveryReport retryReport = recoveryManager == null
                    ? RecoveryManager.RecoveryReport.noop(traceId, "retry", "recovery manager unavailable")
                    : recoveryManager.planRetry(traceId, target, result, routedContext.attributes(), traceCollector.steps());
            Map<String, Object> retryTrace = new LinkedHashMap<>();
            if (retryReport != null) {
                retryTrace.put("summary", retryReport.summary());
                retryTrace.put("clearKeys", retryReport.clearKeys());
                retryTrace.put("contextPatch", retryReport.contextPatch());
            }
            bridge.traceEvent(traceId, "recovery", "retry", retryTrace);
            if (retryReport != null && retryReport.shouldReexecute()) {
                traceCollector.incrementReplan();
                Instant retryStartedAt = Instant.now();
                SkillContext retryContext = new SkillContext(
                        routedContext.userId(),
                        routedContext.input(),
                        buildRecoveryContext(routedContext.attributes(), retryReport)
                );
                result = executeWithTimeout(target, normalizedParams, retryContext);
                Instant retryFinishedAt = Instant.now();
                durationMs = Math.max(0L, java.time.Duration.between(retryStartedAt, retryFinishedAt).toMillis());
                traceCollector.record(nodeId + ".retry", result.success() ? "success" : "failed", target, result.output(), retryStartedAt, retryFinishedAt);
                routedContext = retryContext;
                bridge.traceEvent(traceId, "recovery", "retry-result", Map.of(
                        "skill", target,
                        "success", result.success(),
                        "retryCount", 1,
                        "summary", retryReport.summary()
                ));
            }
        }
        observeLearning(nestedRequest, target, normalizedParams, routeDecision, result, durationMs, usedFallback, traceId);
        bridge.traceEvent(traceId, "execute", "node", Map.of(
                "skill", target,
                "success", result.success(),
                "routeType", routeDecision.routeType().name(),
                "durationMs", durationMs
        ));
        return new DAGExecutor.NodeExecution(result, usedFallback);
    }

    private OrchestrationOutcome preflightClarification(TaskGraph taskGraph,
                                                        SkillContext baseContext,
                                                        OrchestrationRequest request) {
        if (taskGraph == null || taskGraph.nodes().size() != 1) {
            return null;
        }
        TaskNode node = taskGraph.nodes().get(0);
        if (node == null || node.target() == null || node.target().isBlank()) {
            return bridge.clarificationOutcome("task.graph", "缺少可执行候选");
        }
        Map<String, Object> params = bridge.buildEffectiveParams(node.params(), baseContext);
        OrchestrationRequest nestedRequest = request == null
                ? new OrchestrationRequest(baseContext.userId(), baseContext.input(), new SkillContext(baseContext.userId(), baseContext.input(), params), Map.of())
                : new OrchestrationRequest(request.userId(), request.userInput(), new SkillContext(baseContext.userId(), baseContext.input(), params), request.safeProfileContext());
        ParamValidator.ValidationResult validation = paramValidator.validate(node.target(), params, nestedRequest);
        if (!validation.valid()) {
            return bridge.clarificationOutcome(node.target(), validation.message());
        }
        return null;
    }

    private Map<String, Object> buildRecoveryContext(Map<String, Object> baseContext,
                                                     RecoveryManager.RecoveryReport report) {
        Map<String, Object> cleared = applyClearKeys(baseContext, report == null ? List.of() : report.clearKeys());
        return bridge.applyContextPatch(cleared, report == null ? Map.of() : report.contextPatch());
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

    private AgentRouter.AgentRouteDecision resolveRouteDecision(Decision decision,
                                                                String skillName,
                                                                OrchestrationRequest request,
                                                                SkillContext baseContext,
                                                                boolean usedFallback) {
        AgentRouter agentRouter = agentRouterSupplier.get();
        if (agentRouter != null) {
            ScoredCandidate candidate = new ScoredCandidate(
                    skillName,
                    DEFAULT_ROUTE_SCORE,
                    DEFAULT_ROUTE_SCORE,
                    DEFAULT_ROUTE_SCORE,
                    DEFAULT_ROUTE_SCORE,
                    List.of("task-graph")
            );
            return agentRouter.route(decision, request, candidate, baseContext == null ? Map.of() : baseContext.attributes());
        }
        List<String> reasons = List.of("task-graph", usedFallback ? "fallback" : "direct");
        if (isMcpSkill(skillName)) {
            return AgentRouter.AgentRouteDecision.mcp(
                    "",
                    "",
                    "",
                    DEFAULT_ROUTE_SCORE,
                    reasons,
                    Map.of("routeType", "mcp")
            );
        }
        if (decision != null && decision.confidence() >= localRoutingConfidenceThreshold) {
            return AgentRouter.AgentRouteDecision.local(
                    "local",
                    "cost",
                    "",
                    DEFAULT_ROUTE_SCORE,
                    reasons,
                    Map.of("routeType", "local", "llmProvider", "local", "llmPreset", "cost")
            );
        }
        return AgentRouter.AgentRouteDecision.remote("", "", "", DEFAULT_ROUTE_SCORE, reasons, Map.of("routeType", "remote"));
    }

    private void observeLearning(OrchestrationRequest request,
                                 String skillName,
                                 Map<String, Object> params,
                                 AgentRouter.AgentRouteDecision routeDecision,
                                 SkillResult result,
                                 long durationMs,
                                 boolean usedFallback,
                                 String traceId) {
        if (request == null || skillName == null || skillName.isBlank()) {
            return;
        }
        int tokenEstimate = estimateTokens(request.userInput())
                + estimateTokens(params == null ? "" : params.toString())
                + estimateTokens(result == null ? "" : result.output());
        String routeType = routeDecision == null ? "unknown" : routeDecision.routeType().name();
        boolean success = result != null && result.success();
        RewardModel rewardModel;
        PolicyUpdater policyUpdater = policyUpdaterSupplier.get();
        if (policyUpdater != null) {
            rewardModel = policyUpdater.update(
                    request.userId(),
                    skillName,
                    routeType,
                    success,
                    durationMs,
                    tokenEstimate,
                    usedFallback
            );
        } else {
            rewardModel = RewardModel.evaluate(
                    skillName,
                    routeType,
                    success,
                    durationMs,
                    tokenEstimate,
                    usedFallback,
                    1800L
            );
            PlannerLearningStore plannerLearningStore = plannerLearningStoreSupplier.get();
            if (plannerLearningStore != null) {
                plannerLearningStore.observe(
                        request.userId(),
                        skillName,
                        routeType,
                        success,
                        durationMs,
                        tokenEstimate,
                        usedFallback,
                        rewardModel.reward()
                );
            }
        }
        bridge.traceEvent(traceId, "learning", "observe", Map.of(
                "skill", skillName,
                "success", success,
                "durationMs", durationMs,
                "tokenEstimate", tokenEstimate,
                "routeType", routeType,
                "reward", rewardModel.reward(),
                "rewardScore", rewardModel.normalizedReward(),
                "rewardReasons", rewardModel.reasons()
        ));
    }

    private int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return Math.max(1, (text.length() + 3) / 4);
    }

    private SkillResult executeWithTimeout(String target,
                                           Map<String, Object> params,
                                           SkillContext skillContext) {
        try {
            CompletableFuture<SkillResult> future = applySkillTimeoutIfNeeded(
                    target,
                    skillContext,
                    skillExecutionGateway.executeDslAsync(new SkillDsl(target, params), skillContext)
            );
            if (isMcpSkill(target)) {
                future = future.completeOnTimeout(
                        SkillResult.failure(target, "timeout"),
                        mcpPerSkillTimeoutMs,
                        TimeUnit.MILLISECONDS
                );
            }
            return future.join();
        } catch (Exception ex) {
            return SkillResult.failure(target, ex.getMessage());
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

    private boolean isMcpSkill(String target) {
        return target != null && target.startsWith("mcp.");
    }

    private static final class ExecutionTraceCollector {
        private final List<PlanStepDto> steps = Collections.synchronizedList(new ArrayList<>());
        private volatile int replanCount;

        void record(String stepName, String status, String channel, String note) {
            Instant now = Instant.now();
            record(stepName, status, channel, note, now, now);
        }

        void record(String stepName,
                    String status,
                    String channel,
                    String note,
                    Instant startedAt,
                    Instant finishedAt) {
            steps.add(new PlanStepDto(
                    stepName == null ? "" : stepName,
                    status == null ? "" : status,
                    channel == null ? "" : channel,
                    note == null ? "" : note,
                    startedAt == null ? Instant.now() : startedAt,
                    finishedAt == null ? Instant.now() : finishedAt
            ));
        }

        void incrementReplan() {
            replanCount++;
        }

        int replanCount() {
            return replanCount;
        }

        List<PlanStepDto> steps() {
            return List.copyOf(steps);
        }
    }
}
