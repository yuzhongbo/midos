package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillDsl;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.common.dto.CritiqueReportDto;
import com.zhongbo.mindos.assistant.common.dto.ExecutionTraceDto;
import com.zhongbo.mindos.assistant.common.dto.PlanStepDto;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.DecisionOrchestrator.OrchestrationOutcome;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.DecisionOrchestrator.OrchestrationRequest;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5.AgentRouter;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5.PlannerLearningStore;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5.PolicyUpdater;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5.RecoveryManager;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5.RewardModel;
import com.zhongbo.mindos.assistant.skill.SkillExecutionGateway;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

final class FastPathCoordinator {

    private static final double DEFAULT_ROUTE_SCORE = 0.5;

    private final CandidateChainBuilder candidateChainBuilder;
    private final ParamValidator paramValidator;
    private final SkillExecutionGateway skillExecutionGateway;
    private final Supplier<AgentRouter> agentRouterSupplier;
    private final Supplier<PlannerLearningStore> plannerLearningStoreSupplier;
    private final Supplier<PolicyUpdater> policyUpdaterSupplier;
    private final Supplier<RecoveryManager> recoveryManagerSupplier;
    private final FastPathBridge bridge;
    private final double slowPathConfidenceThreshold;
    private final boolean mcpParallelEnabled;
    private final long mcpPerSkillTimeoutMs;
    private final long eqCoachImTimeoutMs;
    private final String eqCoachImTimeoutReply;
    private final int maxLoops;
    private final List<String> mcpPriorityOrder;

    FastPathCoordinator(CandidateChainBuilder candidateChainBuilder,
                        ParamValidator paramValidator,
                        SkillExecutionGateway skillExecutionGateway,
                        Supplier<AgentRouter> agentRouterSupplier,
                        Supplier<PlannerLearningStore> plannerLearningStoreSupplier,
                        Supplier<PolicyUpdater> policyUpdaterSupplier,
                        Supplier<RecoveryManager> recoveryManagerSupplier,
                        FastPathBridge bridge,
                        double slowPathConfidenceThreshold,
                        boolean mcpParallelEnabled,
                        long mcpPerSkillTimeoutMs,
                        long eqCoachImTimeoutMs,
                        String eqCoachImTimeoutReply,
                        int maxLoops,
                        List<String> mcpPriorityOrder) {
        this.candidateChainBuilder = candidateChainBuilder;
        this.paramValidator = paramValidator;
        this.skillExecutionGateway = skillExecutionGateway;
        this.agentRouterSupplier = agentRouterSupplier;
        this.plannerLearningStoreSupplier = plannerLearningStoreSupplier;
        this.policyUpdaterSupplier = policyUpdaterSupplier;
        this.recoveryManagerSupplier = recoveryManagerSupplier;
        this.bridge = bridge;
        this.slowPathConfidenceThreshold = slowPathConfidenceThreshold;
        this.mcpParallelEnabled = mcpParallelEnabled;
        this.mcpPerSkillTimeoutMs = mcpPerSkillTimeoutMs;
        this.eqCoachImTimeoutMs = eqCoachImTimeoutMs;
        this.eqCoachImTimeoutReply = eqCoachImTimeoutReply;
        this.maxLoops = maxLoops;
        this.mcpPriorityOrder = mcpPriorityOrder == null ? List.of() : List.copyOf(mcpPriorityOrder);
    }

    OrchestrationOutcome orchestrate(Decision decision,
                                     String suggestedTarget,
                                     Map<String, Object> params,
                                     OrchestrationRequest request,
                                     boolean allowParallelMcp,
                                     String traceId) {
        List<ScoredCandidate> plannedCandidates = candidateChainBuilder.build(suggestedTarget, request);
        if (plannedCandidates.isEmpty()) {
            plannedCandidates = List.of(candidateChainBuilder.explicitTarget(suggestedTarget));
        }
        bridge.traceEvent(traceId, "planner", "candidate-chain", Map.of(
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
            return bridge.clarificationOutcome(suggestedTarget, lastFailure == null ? "缺少可执行候选" : lastFailure);
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

    interface FastPathBridge {
        OrchestrationOutcome clarificationOutcome(String target, String message);

        Map<String, Object> buildEffectiveParams(Map<String, Object> params, SkillContext skillContext);

        Map<String, Object> applyContextPatch(Map<String, Object> baseContext, Map<String, Object> patch);

        void traceEvent(String traceId, String phase, String action, Map<String, Object> details);
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
            RecoveryManager recoveryManager = recoveryManagerSupplier.get();
            RecoveryManager.RecoveryReport retryReport = recoveryManager == null
                    ? RecoveryManager.RecoveryReport.noop(traceId, "retry", "recovery manager unavailable")
                    : recoveryManager.planRetry(traceId, execution.skillName(), result, prepared.context().attributes(), steps);
            Map<String, Object> retryTrace = new LinkedHashMap<>();
            if (retryReport != null) {
                retryTrace.put("summary", retryReport.summary());
                retryTrace.put("clearKeys", retryReport.clearKeys());
                retryTrace.put("contextPatch", retryReport.contextPatch());
            }
            bridge.traceEvent(traceId, "recovery", "retry", retryTrace);
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
                bridge.traceEvent(traceId, "recovery", "retry-result", Map.of(
                        "skill", execution.skillName(),
                        "success", retryResult.success(),
                        "retryCount", 1,
                        "summary", retryReport.summary()
                ));
            }
        }
        long durationMs = Math.max(0L, java.time.Duration.between(startedAt, finishedAt).toMillis());
        observeLearning(request, execution, prepared.routeDecision(), result, durationMs, traceId);
        bridge.traceEvent(traceId, "execute", "attempt", Map.of(
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
            bridge.traceEvent(traceId, "execute", "mcp-step", Map.of(
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
        return new OrchestrationOutcome(
                selected,
                new SkillDsl(selected.skillName(), candidates.get(0).params()),
                null,
                trace,
                selected.skillName(),
                true
        );
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

    private boolean shouldRunMcpParallel(List<CandidateExecution> candidates) {
        if (!mcpParallelEnabled) {
            return false;
        }
        if (candidates == null || candidates.size() < 2) {
            return false;
        }
        return candidates.stream().allMatch(candidate -> isMcpSkill(candidate.skillName()));
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

    private PreparedExecution prepareExecution(Decision decision,
                                               CandidateExecution execution,
                                               OrchestrationRequest request,
                                               String traceId) {
        SkillContext baseContext = buildExecutionContext(request, execution.params());
        AgentRouter.AgentRouteDecision routeDecision = resolveRouteDecision(decision, execution, request, baseContext);
        Map<String, Object> routedAttributes = bridge.applyContextPatch(baseContext.attributes(), routeDecision.contextPatch());
        SkillContext routedContext = new SkillContext(baseContext.userId(), baseContext.input(), routedAttributes);
        bridge.traceEvent(traceId, "route", "selected", Map.of(
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
                                                                SkillContext baseContext) {
        AgentRouter agentRouter = agentRouterSupplier.get();
        if (agentRouter != null) {
            return agentRouter.route(decision, request, execution.candidate(), baseContext == null ? Map.of() : baseContext.attributes());
        }
        List<String> reasons = List.of("fallback-router", execution.usedFallback() ? "fallback" : "direct");
        String candidateName = execution.skillName();
        if (isMcpSkill(candidateName)) {
            return AgentRouter.AgentRouteDecision.mcp(
                    "",
                    "",
                    "",
                    execution.candidate() == null ? DEFAULT_ROUTE_SCORE : execution.candidate().finalScore(),
                    reasons,
                    Map.of("routeType", "mcp")
            );
        }
        double score = execution.candidate() == null ? DEFAULT_ROUTE_SCORE : execution.candidate().finalScore();
        if (decision != null && decision.confidence() >= slowPathConfidenceThreshold) {
            return AgentRouter.AgentRouteDecision.local(
                    "local",
                    "cost",
                    "",
                    score,
                    reasons,
                    Map.of("routeType", "local", "llmProvider", "local", "llmPreset", "cost")
            );
        }
        return AgentRouter.AgentRouteDecision.remote("", "", "", score, reasons, Map.of("routeType", "remote"));
    }

    private void observeLearning(OrchestrationRequest request,
                                 CandidateExecution execution,
                                 AgentRouter.AgentRouteDecision routeDecision,
                                 SkillResult result,
                                 long durationMs,
                                 String traceId) {
        if (request == null || execution == null) {
            return;
        }
        int tokenEstimate = estimateTokens(request.userInput())
                + estimateTokens(execution.params() == null ? "" : execution.params().toString())
                + estimateTokens(result == null ? "" : result.output());
        String routeType = routeDecision == null ? "unknown" : routeDecision.routeType().name();
        boolean success = result != null && result.success();
        RewardModel rewardModel;
        PolicyUpdater policyUpdater = policyUpdaterSupplier.get();
        if (policyUpdater != null) {
            rewardModel = policyUpdater.update(
                    request.userId(),
                    execution.skillName(),
                    routeType,
                    success,
                    durationMs,
                    tokenEstimate,
                    execution.usedFallback()
            );
        } else {
            rewardModel = RewardModel.evaluate(
                    execution.skillName(),
                    routeType,
                    success,
                    durationMs,
                    tokenEstimate,
                    execution.usedFallback(),
                    1800L
            );
            PlannerLearningStore plannerLearningStore = plannerLearningStoreSupplier.get();
            if (plannerLearningStore != null) {
                plannerLearningStore.observe(
                        request.userId(),
                        execution.skillName(),
                        routeType,
                        success,
                        durationMs,
                        tokenEstimate,
                        execution.usedFallback(),
                        rewardModel.reward()
                );
            }
        }
        bridge.traceEvent(traceId, "learning", "observe", Map.of(
                "skill", execution.skillName(),
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

    private SkillContext buildExecutionContext(OrchestrationRequest request, Map<String, Object> params) {
        SkillContext baseContext = request == null ? null : request.skillContext();
        String userId = request == null ? "" : request.userId();
        String input = request == null ? "" : request.userInput();
        Map<String, Object> merged = bridge.buildEffectiveParams(params, baseContext);
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

    private boolean isMcpSkill(String target) {
        return target != null && target.startsWith("mcp.");
    }

    private record PreparedExecution(SkillContext context, AgentRouter.AgentRouteDecision routeDecision) {
    }

    private record CandidateExecution(ScoredCandidate candidate, Map<String, Object> params, boolean usedFallback) {
        private String skillName() {
            return candidate.skillName();
        }
    }
}
