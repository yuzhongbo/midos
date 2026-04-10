package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillDsl;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.common.dto.CritiqueReportDto;
import com.zhongbo.mindos.assistant.common.dto.ExecutionTraceDto;
import com.zhongbo.mindos.assistant.common.dto.PlanStepDto;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.skill.SkillEngine;
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

    private final CandidatePlanner candidatePlanner;
    private final ParamValidator paramValidator;
    private final ConversationLoop conversationLoop;
    private final FallbackPlan fallbackPlan;
    private final SkillEngine skillEngine;
    private final PostExecutionMemoryRecorder memoryRecorder;
    private final TaskExecutor taskExecutor;
    private final boolean mcpParallelEnabled;
    private final long mcpPerSkillTimeoutMs;
    private final long eqCoachImTimeoutMs;
    private final String eqCoachImTimeoutReply;
    private final List<String> mcpPriorityOrder;
    private final int maxLoops;

    public DefaultDecisionOrchestrator(CandidatePlanner candidatePlanner,
                                       ParamValidator paramValidator,
                                       ConversationLoop conversationLoop,
                                       FallbackPlan fallbackPlan,
                                       SkillEngine skillEngine,
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
        this.skillEngine = skillEngine;
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

    @Override
    public SkillResult execute(String userInput, String intent, Map<String, Object> params) {
        Decision decision = new Decision(intent, intent, params == null ? Map.of() : Map.copyOf(params), 1.0, false);
        SkillContext context = new SkillContext("", userInput == null ? "" : userInput, params == null ? Map.of() : Map.copyOf(params));
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
        if (decision == null || decision.target() == null || decision.target().isBlank()) {
            return clarificationOutcome("", "missing target");
        }
        if ("semantic.clarify".equalsIgnoreCase(decision.target()) || decision.requireClarify()) {
            return clarificationOutcome(decision.target(), "clarification requested");
        }
        Map<String, Object> params = decision.params() == null ? Map.of() : decision.params();
        Map<String, Object> effectiveParams = buildEffectiveParams(params, request == null ? null : request.skillContext());
        TaskPlan taskPlan = TaskPlan.from(effectiveParams);
        if ("task.plan".equalsIgnoreCase(decision.target()) || !taskPlan.isEmpty()) {
            return orchestrateTaskPlan(taskPlan, effectiveParams, request);
        }
        return orchestrateCandidateChain(decision.target(), effectiveParams, request, maxLoops, true, List.of(), 0);
    }

    @Override
    public void recordOutcome(String userId, String userInput, SkillResult result, ExecutionTraceDto trace) {
        memoryRecorder.record(userId, userInput, result, trace);
    }

    private OrchestrationOutcome orchestrateTaskPlan(TaskPlan taskPlan,
                                                     Map<String, Object> effectiveParams,
                                                     OrchestrationRequest request) {
        if (taskPlan == null || taskPlan.isEmpty()) {
            return clarificationOutcome("task.plan", "缺少 task steps");
        }
        SkillContext baseContext = request == null
                ? new SkillContext("", "", effectiveParams)
                : new SkillContext(request.userId(), request.userInput(), buildEffectiveParams(effectiveParams, request.skillContext()));
        TaskExecutor.TaskExecutionResult taskResult = taskExecutor.execute(taskPlan, baseContext, (step, stepContext) -> {
            if ("task.plan".equalsIgnoreCase(step.target())) {
                return new TaskExecutor.StepExecutionResult(
                        SkillResult.failure("task.plan", "nested task.plan is not supported"),
                        "task.plan",
                        false
                );
            }
            OrchestrationRequest nestedRequest = request == null
                    ? new OrchestrationRequest(stepContext.userId(), stepContext.input(), stepContext, Map.of())
                    : new OrchestrationRequest(request.userId(), request.userInput(), stepContext, request.safeProfileContext());
            OrchestrationOutcome outcome = orchestrateCandidateChain(
                    step.target(),
                    stepContext.attributes(),
                    nestedRequest,
                    maxLoops,
                    false,
                    List.of(),
                    0
            );
            SkillResult stepResult = outcome.hasResult() ? outcome.result() : outcome.clarification();
            return new TaskExecutor.StepExecutionResult(stepResult, outcome.selectedSkill(), outcome.usedFallback());
        });
        if (taskResult.result() == null) {
            return clarificationOutcome("task.plan", "未能执行任何 task step");
        }
        ExecutionTraceDto trace = new ExecutionTraceDto(
                "task-plan",
                Math.max(0, taskResult.steps().size() - 1),
                new CritiqueReportDto(taskResult.result().success(),
                        taskResult.result().success() ? "task plan success" : taskResult.result().output(),
                        taskResult.usedFallback() ? "fallback" : "none"),
                taskResult.steps()
        );
        return new OrchestrationOutcome(
                taskResult.result(),
                new SkillDsl(taskResult.selectedSkill() == null ? "task.plan" : taskResult.selectedSkill(), effectiveParams),
                null,
                trace,
                taskResult.selectedSkill(),
                taskResult.usedFallback()
        );
    }

    private OrchestrationOutcome orchestrateCandidateChain(String suggestedTarget,
                                                           Map<String, Object> params,
                                                           OrchestrationRequest request,
                                                           int loopsRemaining,
                                                           boolean allowParallelMcp,
                                                           List<PlanStepDto> inheritedSteps,
                                                           int inheritedReplans) {
        List<ScoredCandidate> plannedCandidates = buildCandidateChain(suggestedTarget, request);
        if (plannedCandidates.isEmpty()) {
            plannedCandidates = List.of(new ScoredCandidate(suggestedTarget, 1.0, 0.0, 0.0, 0.5, List.of("explicit-target")));
        }
        List<PlanStepDto> steps = new ArrayList<>(inheritedSteps == null ? List.of() : inheritedSteps);
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
            return orchestrateMcpInParallel(executableCandidates, request, steps, inheritedReplans);
        }
        int replans = inheritedReplans;
        SkillResult lastResult = null;
        String lastCandidate = suggestedTarget;
        boolean usedFallback = false;
        int rounds = Math.min(maxLoops, executableCandidates.size());
        for (int i = 0; i < rounds; i++) {
            CandidateExecution execution = executableCandidates.get(i);
            lastCandidate = execution.skillName();
            usedFallback = usedFallback || execution.usedFallback();
            SkillResult result = executeSingleAttempt(execution, request, steps, "attempt-" + (steps.size() + 1));
            lastResult = result;
            if (result.success()) {
                return successOutcome(result, execution.params(), steps, replans, execution.skillName(), usedFallback);
            }
            if (loopsRemaining > 1) {
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
                    SkillResult retryResult = executeSingleAttempt(retriedExecution, request, steps, "retry-" + replans);
                    lastResult = retryResult;
                    if (retryResult.success()) {
                        return successOutcome(retryResult, retriedExecution.params(), steps, replans, retriedExecution.skillName(), usedFallback);
                    }
                }
            }
            lastFailure = result.output();
        }
        ExecutionTraceDto trace = new ExecutionTraceDto(
                "decision-orchestrator",
                replans,
                new CritiqueReportDto(false, lastFailure == null ? "unknown" : lastFailure, "fallback"),
                steps
        );
        SkillResult failedResult = lastResult == null
                ? SkillResult.failure(lastCandidate == null ? suggestedTarget : lastCandidate, lastFailure == null ? "unknown" : lastFailure)
                : lastResult;
        return new OrchestrationOutcome(
                failedResult,
                lastCandidate == null ? null : new SkillDsl(lastCandidate, params),
                null,
                trace,
                lastCandidate,
                usedFallback
        );
    }

    private SkillResult executeSingleAttempt(CandidateExecution execution,
                                             OrchestrationRequest request,
                                             List<PlanStepDto> steps,
                                             String stepName) {
        Instant startedAt = Instant.now();
        SkillResult result = executeWithTimeout(
                execution.skillName(),
                execution.params(),
                buildExecutionContext(request, execution.params())
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
        return result;
    }

    private OrchestrationOutcome orchestrateMcpInParallel(List<CandidateExecution> candidates,
                                                          OrchestrationRequest request,
                                                          List<PlanStepDto> inheritedSteps,
                                                          int inheritedReplans) {
        List<CompletableFuture<SkillResult>> futures = new ArrayList<>();
        for (CandidateExecution candidate : candidates) {
            SkillContext skillContext = buildExecutionContext(request, candidate.params());
            CompletableFuture<SkillResult> execution = applySkillTimeoutIfNeeded(
                    candidate.skillName(),
                    skillContext,
                    skillEngine.executeDslAsync(new SkillDsl(candidate.skillName(), candidate.params()), skillContext)
            )
                    .completeOnTimeout(SkillResult.failure(candidate.skillName(), "timeout"), mcpPerSkillTimeoutMs, TimeUnit.MILLISECONDS)
                    .exceptionally(error -> SkillResult.failure(candidate.skillName(), String.valueOf(error.getMessage())));
            futures.add(execution);
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        List<PlanStepDto> steps = new ArrayList<>(inheritedSteps == null ? List.of() : inheritedSteps);
        List<SkillResult> successes = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            SkillResult result = futures.get(i).join();
            CandidateExecution candidate = candidates.get(i);
            steps.add(new PlanStepDto(
                    "parallel-" + (i + 1),
                    result.success() ? "success" : "failed",
                    candidate.skillName(),
                    buildStepNote(candidate.candidate(), result),
                    Instant.now().minusMillis(1),
                    Instant.now()
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
                    "decision-orchestrator",
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
                "decision-orchestrator",
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
                                                boolean usedFallback) {
        ExecutionTraceDto trace = new ExecutionTraceDto(
                "decision-orchestrator",
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
        candidatePlanner.plan(suggestedTarget, request).forEach(candidate -> ordered.put(candidate.skillName(), candidate));
        if (suggestedTarget != null && !suggestedTarget.isBlank()) {
            ordered.putIfAbsent(suggestedTarget, new ScoredCandidate(suggestedTarget, 1.0, 0.0, 0.0, 0.5, List.of("explicit-target")));
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
                    skillEngine.executeDslAsync(new SkillDsl(candidate, params), skillContext)
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
