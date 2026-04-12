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
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5.RecoveryAction;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5.RecoveryManager;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

final class TaskGraphCoordinator {

    private final Supplier<DispatcherMemoryFacade> memoryFacadeSupplier;
    private final Supplier<RecoveryManager> recoveryManagerSupplier;
    private final TaskGraphBridge bridge;
    private final StructuredExecutionRuntime structuredExecutionRuntime = new StructuredExecutionRuntime();

    TaskGraphCoordinator(Supplier<DispatcherMemoryFacade> memoryFacadeSupplier,
                         Supplier<RecoveryManager> recoveryManagerSupplier,
                         TaskGraphBridge bridge) {
        this.memoryFacadeSupplier = memoryFacadeSupplier;
        this.recoveryManagerSupplier = recoveryManagerSupplier;
        this.bridge = bridge;
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
        boolean usedFallback = taskResult.nodeResults().stream().anyMatch(TaskGraphExecutionResult.NodeResult::usedFallback);
        ExecutionTraceDto trace = new ExecutionTraceDto(
                strategy == null || strategy.isBlank() ? "task-graph" : strategy,
                Math.max(0, steps.size() - 1),
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

        OrchestrationOutcome orchestrateFastPath(Decision decision,
                                                String target,
                                                Map<String, Object> params,
                                                OrchestrationRequest request,
                                                boolean allowParallelMcp,
                                                String traceId);

        Map<String, Object> buildEffectiveParams(Map<String, Object> params, SkillContext skillContext);

        Map<String, Object> applyContextPatch(Map<String, Object> baseContext, Map<String, Object> patch);

        void traceEvent(String traceId, String phase, String action, Map<String, Object> details);
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
        return structuredExecutionRuntime.execute(taskGraph, baseContext, (node, nodeContext) ->
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
                    yield new DAGExecutor.NodeExecution(
                            SkillResult.success(action.target().isBlank() ? node.target() : action.target(), syntheticOutput),
                            true
                    );
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
        OrchestrationOutcome outcome = bridge.orchestrateFastPath(
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
}
