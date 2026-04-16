package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.common.dto.CritiqueReportDto;
import com.zhongbo.mindos.assistant.common.dto.ExecutionTraceDto;
import com.zhongbo.mindos.assistant.common.dto.PlanStepDto;
import com.zhongbo.mindos.assistant.common.dto.RoutingDecisionDto;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryFacade;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.DecisionOrchestrator;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.ParamValidator;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

final class HermesAssistantRuntime {

    private static final String CLARIFY_PREFIX = "我理解你的意思了，但还缺少关键参数：";

    private final DispatchHeuristicsSupport heuristicsSupport;
    private final HermesDecisionContextFactory contextFactory;
    private final HermesDecisionEngine decisionEngine;
    private final ParamValidator paramValidator;
    private final HermesSkillRouter skillRouter;
    private final HermesMemoryRecorder memoryRecorder;
    private final DispatchLlmSupport llmSupport;
    private final DispatcherMemoryFacade dispatcherMemoryFacade;
    private final HermesExecutionGuard executionGuard;
    private final HermesProactiveAssistant proactiveAssistant;
    private final String promptInjectionSafeReply;
    private final ConversationMemoryModeService conversationMemoryModeService;
    private final AtomicBoolean acceptingRequests = new AtomicBoolean(true);
    private final AtomicLong activeDispatchCount = new AtomicLong();

    HermesAssistantRuntime(DispatchHeuristicsSupport heuristicsSupport,
                           HermesDecisionContextFactory contextFactory,
                           HermesDecisionEngine decisionEngine,
                           ParamValidator paramValidator,
                           HermesSkillRouter skillRouter,
                           HermesMemoryRecorder memoryRecorder,
                           DispatchLlmSupport llmSupport,
                           DispatcherMemoryFacade dispatcherMemoryFacade,
                           HermesExecutionGuard executionGuard,
                           String promptInjectionSafeReply,
                           ConversationMemoryModeService conversationMemoryModeService) {
        this.heuristicsSupport = heuristicsSupport;
        this.contextFactory = contextFactory;
        this.decisionEngine = decisionEngine;
        this.paramValidator = paramValidator;
        this.skillRouter = skillRouter;
        this.memoryRecorder = memoryRecorder;
        this.llmSupport = llmSupport;
        this.dispatcherMemoryFacade = dispatcherMemoryFacade;
        this.executionGuard = executionGuard;
        this.proactiveAssistant = new HermesProactiveAssistant();
        this.promptInjectionSafeReply = promptInjectionSafeReply == null || promptInjectionSafeReply.isBlank()
                ? "为了安全，我不能执行这类请求。"
                : promptInjectionSafeReply;
        this.conversationMemoryModeService = conversationMemoryModeService == null
                ? new ConversationMemoryModeService()
                : conversationMemoryModeService;
    }

    DispatchResult dispatch(String userId, String userInput, Map<String, Object> profileContext) {
        return dispatchInternal(userId, userInput, profileContext, null, false);
    }

    CompletableFuture<DispatchResult> dispatchAsync(String userId, String userInput, Map<String, Object> profileContext) {
        return CompletableFuture.supplyAsync(() -> dispatch(userId, userInput, profileContext));
    }

    CompletableFuture<DispatchResult> dispatchStream(String userId,
                                                     String userInput,
                                                     Map<String, Object> profileContext,
                                                     Consumer<String> deltaConsumer) {
        return CompletableFuture.supplyAsync(() -> dispatchInternal(userId, userInput, profileContext, deltaConsumer, true));
    }

    void beginDrain() {
        acceptingRequests.set(false);
    }

    void resumeAcceptingRequests() {
        acceptingRequests.set(true);
    }

    boolean isAcceptingRequests() {
        return acceptingRequests.get();
    }

    long getActiveDispatchCount() {
        return activeDispatchCount.get();
    }

    boolean waitForActiveDispatches(long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        while (System.currentTimeMillis() <= deadline) {
            if (activeDispatchCount.get() <= 0L) {
                return true;
            }
            try {
                Thread.sleep(25L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return activeDispatchCount.get() <= 0L;
            }
        }
        return activeDispatchCount.get() <= 0L;
    }

    private DispatchResult dispatchInternal(String userId,
                                            String userInput,
                                            Map<String, Object> profileContext,
                                            Consumer<String> deltaConsumer,
                                            boolean streamMode) {
        if (!acceptingRequests.get()) {
            return buildDrainingResult();
        }

        activeDispatchCount.incrementAndGet();
        Instant startedAt = Instant.now();
        try {
            String safeUserId = safeText(userId);
            String safeInput = userInput == null ? "" : userInput.trim();
            Map<String, Object> safeProfileContext = profileContext == null ? Map.of() : Map.copyOf(profileContext);

            if (isPromptInjectionAttempt(safeInput)) {
                SkillResult guarded = SkillResult.success("security.guard", promptInjectionSafeReply);
                emit(deltaConsumer, guarded.output());
                DispatchResult result = buildDispatchResult(
                        guarded.output(),
                        guarded.skillName(),
                        "security.guard",
                        guarded.skillName(),
                        1.0d,
                        List.of("prompt injection guard rejected the input"),
                        List.of(),
                        true,
                        List.of(primaryStep("success", guarded.skillName(), guarded.output(), startedAt, Instant.now()))
                );
                result = enrichObservability(result, safeInput, SemanticAnalysisResult.empty(), false);
                memoryRecorder.record(safeUserId, safeInput, guarded, safeProfileContext, SemanticAnalysisResult.empty(), null, null, true);
                return result;
            }

            Optional<ConversationMemoryModeService.MemoryModeDirective> memoryDirective = conversationMemoryModeService.detectDirective(safeInput);
            if (memoryDirective.isPresent()) {
                ConversationMemoryModeService.MemoryModeDirective directive = memoryDirective.get();
                conversationMemoryModeService.apply(safeUserId, directive);
                SkillResult modeResult = SkillResult.success(directive.channel(), directive.reply());
                emit(deltaConsumer, modeResult.output());
                return buildDispatchResult(
                        modeResult.output(),
                        modeResult.skillName(),
                        "memory-mode",
                        modeResult.skillName(),
                        1.0d,
                        List.of(directive.suppressed() ? "conversation memory has been disabled" : "conversation memory has been re-enabled"),
                        List.of(),
                        true,
                        List.of(primaryStep("success", modeResult.skillName(), modeResult.output(), startedAt, Instant.now()))
                );
            }

            boolean memoryEnabled = !conversationMemoryModeService.isMemorySuppressed(safeUserId);
            if (!memoryEnabled && conversationMemoryModeService.isExplicitMemoryRecallRequest(safeInput)) {
                SkillResult disabledRecall = SkillResult.success("memory.mode", conversationMemoryModeService.disabledRecallReply());
                emit(deltaConsumer, disabledRecall.output());
                return buildDispatchResult(
                        disabledRecall.output(),
                        disabledRecall.skillName(),
                        "memory-disabled",
                        disabledRecall.skillName(),
                        1.0d,
                        List.of("memory recall requested while conversation memory is disabled"),
                        List.of(),
                        true,
                        List.of(primaryStep("success", disabledRecall.skillName(), disabledRecall.output(), startedAt, Instant.now()))
                );
            }

            HermesDecisionContext decisionContext = contextFactory.create(safeUserId, safeInput, safeProfileContext, memoryEnabled);
            HermesDecisionEngine.DecisionPlan decisionPlan = decisionEngine.decide(decisionContext);
            Decision decision = decisionPlan.decision();

            if (decision != null && decision.needClarify() && decisionPlan.clarifyReply() != null && !decisionPlan.clarifyReply().isBlank()) {
                SkillResult clarifyResult = SkillResult.success("semantic.clarify", decisionPlan.clarifyReply());
                emit(deltaConsumer, clarifyResult.output());
                DispatchResult result = buildDispatchResult(
                        clarifyResult.output(),
                        clarifyResult.skillName(),
                        "semantic-clarify",
                        decision.target(),
                        decision.confidence(),
                        decisionPlan.reasons(),
                        decisionPlan.rejectedReasons(),
                        true,
                        List.of(primaryStep("success", clarifyResult.skillName(), clarifyResult.output(), startedAt, Instant.now()))
                );
                result = enrichObservability(result, safeInput, decisionContext.semanticAnalysis(), false);
                memoryRecorder.record(safeUserId, safeInput, clarifyResult, decisionContext.profileContext(), decisionContext.semanticAnalysis(), null, null, decisionContext.memoryEnabled());
                return result;
            }

            if (decision == null || decision.target() == null || decision.target().isBlank()) {
                SkillResult invalidDecision = SkillResult.failure("decision.invalid", "未能确定执行目标，请补充更多信息。");
                emit(deltaConsumer, invalidDecision.output());
                DispatchResult result = buildDispatchResult(
                        invalidDecision.output(),
                        invalidDecision.skillName(),
                        "decision-invalid",
                        invalidDecision.skillName(),
                        0.0d,
                        decisionPlan.reasons(),
                        decisionPlan.rejectedReasons(),
                        false,
                        List.of(primaryStep("failed", invalidDecision.skillName(), invalidDecision.output(), startedAt, Instant.now()))
                );
                result = enrichObservability(result, safeInput, decisionContext.semanticAnalysis(), false);
                memoryRecorder.record(safeUserId, safeInput, invalidDecision, decisionContext.profileContext(), decisionContext.semanticAnalysis(), null, null, decisionContext.memoryEnabled());
                return result;
            }

            if ("memory.direct".equalsIgnoreCase(decision.target())) {
                SkillResult memoryResult = llmSupport.buildMemoryDirectResult(decisionContext.promptMemoryContext(), safeInput);
                emit(deltaConsumer, memoryResult.output());
                DispatchResult result = buildDispatchResult(
                        memoryResult.output(),
                        memoryResult.skillName(),
                        decisionPlan.route(),
                        memoryResult.skillName(),
                        decision.confidence(),
                        decisionPlan.reasons(),
                        decisionPlan.rejectedReasons(),
                        memoryResult.success(),
                        List.of(primaryStep(memoryResult.success() ? "success" : "failed", memoryResult.skillName(), memoryResult.output(), startedAt, Instant.now()))
                );
                result = enrichObservability(result, safeInput, decisionContext.semanticAnalysis(), false);
                memoryRecorder.record(safeUserId, safeInput, memoryResult, decisionContext.profileContext(), decisionContext.semanticAnalysis(), null, null, decisionContext.memoryEnabled());
                return result;
            }

            if ("llm".equalsIgnoreCase(decision.target())) {
                SkillResult llmResult = executeLlmDecision(decisionContext, safeInput, safeProfileContext, deltaConsumer, streamMode);
                HermesProactiveAssistant.Augmentation proactive = maybeApplyProactiveAssistant(safeInput, decisionContext, llmResult);
                SkillResult resultToReturn = proactive.result();
                if (streamMode && proactive.applied()) {
                    emit(deltaConsumer, proactive.appendedSuffix());
                }
                DispatchResult result = buildDispatchResult(
                        resultToReturn.output(),
                        resultToReturn.skillName(),
                        decisionPlan.route(),
                        resultToReturn.skillName(),
                        decision == null ? 0.0d : decision.confidence(),
                        augmentReasons(decisionPlan.reasons(), proactive),
                        decisionPlan.rejectedReasons(),
                        resultToReturn.success(),
                        List.of(primaryStep(resultToReturn.success() ? "success" : "failed", resultToReturn.skillName(), resultToReturn.output(), startedAt, Instant.now()))
                );
                result = enrichObservability(result, safeInput, decisionContext.semanticAnalysis(), false);
                memoryRecorder.record(safeUserId, safeInput, resultToReturn, decisionContext.profileContext(), decisionContext.semanticAnalysis(), null, null, decisionContext.memoryEnabled());
                return result;
            }

            return executeRoutedDecision(
                    safeUserId,
                    safeInput,
                    safeProfileContext,
                    decisionContext,
                    decisionPlan,
                    decision,
                    deltaConsumer,
                    streamMode,
                    startedAt
            );
        } finally {
            activeDispatchCount.decrementAndGet();
        }
    }

    private DispatchResult executeRoutedDecision(String userId,
                                                 String userInput,
                                                 Map<String, Object> profileContext,
                                                 HermesDecisionContext decisionContext,
                                                 HermesDecisionEngine.DecisionPlan decisionPlan,
                                                 Decision decision,
                                                 Consumer<String> deltaConsumer,
                                                 boolean streamMode,
                                                 Instant startedAt) {
        String requestedTarget = decision == null ? "" : safeText(decision.target());
        String executionTarget = skillRouter == null ? requestedTarget : skillRouter.resolveExecutionTarget(requestedTarget);
        Optional<SkillResult> capabilityBlocked = maybeBlockedByCapability(executionTarget);
        if (capabilityBlocked.isPresent()) {
            SkillResult blocked = capabilityBlocked.get();
            emit(deltaConsumer, blocked.output());
            DispatchResult result = buildDispatchResult(
                    blocked.output(),
                    blocked.skillName(),
                    "security.guard",
                    executionTarget.isBlank() ? blocked.skillName() : executionTarget,
                    decision == null ? 0.0d : decision.confidence(),
                    List.of("capability guard blocked skill execution"),
                    List.of(),
                    true,
                    List.of(primaryStep("success", blocked.skillName(), blocked.output(), startedAt, Instant.now()))
            );
            result = enrichObservability(result, userInput, decisionContext.semanticAnalysis(), false);
            memoryRecorder.record(userId, userInput, blocked, decisionContext.profileContext(), decisionContext.semanticAnalysis(), null, null, decisionContext.memoryEnabled());
            return result;
        }
        if (isLoopGuardBlocked(userId, executionTarget, userInput)) {
            SkillResult loopGuardResult = SkillResult.failure(
                    "loop.guard",
                    "检测到重复执行风险，已阻止继续调用 " + executionTarget + "。请补充新的输入或换个目标。"
            );
            emit(deltaConsumer, loopGuardResult.output());
            DispatchResult result = buildDispatchResult(
                    loopGuardResult.output(),
                    loopGuardResult.skillName(),
                    "loop-guard",
                    executionTarget.isBlank() ? loopGuardResult.skillName() : executionTarget,
                    decision == null ? 0.0d : decision.confidence(),
                    decisionPlan.reasons(),
                    List.of("skill blocked by loop guard"),
                    false,
                    List.of(primaryStep("failed", loopGuardResult.skillName(), loopGuardResult.output(), startedAt, Instant.now()))
            );
            result = enrichObservability(result, userInput, decisionContext.semanticAnalysis(), false);
            memoryRecorder.record(userId, userInput, loopGuardResult, decisionContext.profileContext(), decisionContext.semanticAnalysis(), null, null, decisionContext.memoryEnabled());
            return result;
        }

        ParamValidator.ValidationResult validation = paramValidator.validate(
                executionTarget,
                decision.params(),
                new DecisionOrchestrator.OrchestrationRequest(
                        userId,
                        userInput,
                        decisionContext.skillContext(),
                        decisionContext.profileContext()
                )
        );

        if (!validation.valid()) {
            if (validation.needsClarification() && clarificationAttempts(userId) < 2) {
                String reply = decisionPlan.clarifyReply() != null && !decisionPlan.clarifyReply().isBlank()
                        ? decisionPlan.clarifyReply()
                        : buildClarifyReply(decision, validation);
                SkillResult clarifyResult = SkillResult.success("semantic.clarify", reply);
                emit(deltaConsumer, clarifyResult.output());
                List<String> reasons = new ArrayList<>(decisionPlan.reasons());
                reasons.add(validation.message());
                DispatchResult result = buildDispatchResult(
                        clarifyResult.output(),
                        clarifyResult.skillName(),
                        "semantic-clarify",
                        decision.target(),
                        decision.confidence(),
                        reasons,
                        decisionPlan.rejectedReasons(),
                        true,
                        List.of(primaryStep("success", clarifyResult.skillName(), clarifyResult.output(), startedAt, Instant.now()))
                );
                result = enrichObservability(result, userInput, decisionContext.semanticAnalysis(), false);
                memoryRecorder.record(userId, userInput, clarifyResult, decisionContext.profileContext(), decisionContext.semanticAnalysis(), null, null, decisionContext.memoryEnabled());
                return result;
            }

            List<String> rejectedReasons = new ArrayList<>(decisionPlan.rejectedReasons());
            rejectedReasons.add(validation.message());
            SkillResult validationFailure = SkillResult.failure(decision.target(), validation.message());
            emit(deltaConsumer, validationFailure.output());
            DispatchResult result = buildDispatchResult(
                    validationFailure.output(),
                    validationFailure.skillName(),
                    "param-validation",
                    decision.target(),
                    decision.confidence(),
                    decisionPlan.reasons(),
                    rejectedReasons,
                    false,
                    List.of(primaryStep("failed", validationFailure.skillName(), validationFailure.output(), startedAt, Instant.now()))
            );
            result = enrichObservability(result, userInput, decisionContext.semanticAnalysis(), false);
            memoryRecorder.record(userId, userInput, validationFailure, decisionContext.profileContext(), decisionContext.semanticAnalysis(), null, null, decisionContext.memoryEnabled());
            return result;
        }

        Decision validatedDecision = validation.applyTo(decision);
        validatedDecision = new Decision(
                validatedDecision.intent(),
                executionTarget,
                validatedDecision.params(),
                validatedDecision.confidence(),
                validatedDecision.needClarify()
        );
        String attemptedSkill = validatedDecision.target();
        SkillResult routedResult = skillRouter.execute(validatedDecision, decisionContext.skillContext());
        boolean attemptedSuccess = routedResult != null && routedResult.success();

        if (attemptedSuccess) {
            if ("memory-habit".equals(decisionPlan.route()) && executionGuard != null) {
                routedResult = executionGuard.decorateMemoryHabitResult(routedResult, attemptedSkill, decisionContext.profileContext());
            }
            SkillFinalizeOutcome finalized = llmSupport.maybeFinalizeSkillResultWithLlm(
                    userInput,
                    routedResult,
                    new LinkedHashMap<>(decisionContext.llmContext())
            );
            HermesProactiveAssistant.Augmentation proactive = maybeApplyProactiveAssistant(
                    userInput,
                    decisionContext,
                    finalized.result()
            );
            SkillResult resultToReturn = proactive.result();
            emit(deltaConsumer, resultToReturn.output());
            DispatchResult result = buildDispatchResult(
                    resultToReturn.output(),
                    resultToReturn.skillName(),
                    decisionPlan.route(),
                    resultToReturn.skillName(),
                    validatedDecision.confidence(),
                    augmentReasons(decisionPlan.reasons(), proactive),
                    decisionPlan.rejectedReasons(),
                    true,
                    List.of(primaryStep("success", resultToReturn.skillName(), resultToReturn.output(), startedAt, Instant.now()))
            );
            result = enrichObservability(result, userInput, decisionContext.semanticAnalysis(), finalized.applied());
            memoryRecorder.record(userId,
                    userInput,
                    resultToReturn,
                    decisionContext.profileContext(),
                    decisionContext.semanticAnalysis(),
                    attemptedSkill,
                    true,
                    validatedDecision.params(),
                    decisionContext.memoryEnabled());
            return result;
        }

        SkillResult failedResult = routedResult == null
                ? SkillResult.failure(attemptedSkill, "skill execution failed")
                : routedResult;
        List<String> rejectedReasons = new ArrayList<>(decisionPlan.rejectedReasons());
        rejectedReasons.add(failedResult.output() != null && !failedResult.output().isBlank()
                ? "skill execution failed: " + clip(failedResult.output())
                : "skill execution failed");
        emit(deltaConsumer, failedResult.output());
        DispatchResult result = buildDispatchResult(
                failedResult.output(),
                failedResult.skillName(),
                decisionPlan.route(),
                attemptedSkill,
                validatedDecision.confidence(),
                decisionPlan.reasons(),
                rejectedReasons,
                false,
                List.of(primaryStep("failed", attemptedSkill, failedResult.output(), startedAt, Instant.now()))
        );
        result = enrichObservability(result, userInput, decisionContext.semanticAnalysis(), false);
        memoryRecorder.record(userId,
                userInput,
                failedResult,
                decisionContext.profileContext(),
                decisionContext.semanticAnalysis(),
                attemptedSkill,
                false,
                validatedDecision.params(),
                decisionContext.memoryEnabled());
        return result;
    }

    private SkillResult executeLlmDecision(HermesDecisionContext context,
                                           String userInput,
                                           Map<String, Object> profileContext,
                                           Consumer<String> deltaConsumer,
                                           boolean streamMode) {
        Map<String, Object> llmContext = new LinkedHashMap<>(context.llmContext());
        llmContext.put("routeStage", "llm-fallback");
        llmSupport.applyStageLlmRoute("llm-fallback", profileContext, llmContext);
        boolean realtimeIntent = heuristicsSupport != null
                && heuristicsSupport.isRealtimeIntent(userInput, context.semanticAnalysis());
        if (streamMode) {
            SkillResult result = llmSupport.buildLlmFallbackStreamResult(
                    context.memoryContext(),
                    context.promptMemoryContext(),
                    userInput,
                    llmContext,
                    realtimeIntent,
                    deltaConsumer
            );
            return capLlmResult(result);
        }
        SkillResult result = llmSupport.buildFallbackResult(
                context.memoryContext(),
                context.promptMemoryContext(),
                userInput,
                llmContext,
                realtimeIntent
        );
        return capLlmResult(result);
    }

    private DispatchResult enrichObservability(DispatchResult result,
                                               String userInput,
                                               SemanticAnalysisResult semanticAnalysis,
                                               boolean skillPostprocessSent) {
        if (result == null || result.executionTrace() == null || result.executionTrace().routing() == null) {
            return result;
        }
        RoutingDecisionDto routing = result.executionTrace().routing();
        List<String> reasons = new ArrayList<>(routing.reasons() == null ? List.of() : routing.reasons());
        boolean realtimeLookup = heuristicsSupport != null && heuristicsSupport.isRealtimeIntent(userInput, semanticAnalysis);
        boolean memoryDirectBypassed = realtimeLookup && !"memory.direct".equalsIgnoreCase(result.channel());
        String actualSearchSource = resolveActualSearchSource(firstNonBlank(routing.selectedSkill(), result.channel()),
                result.reply());
        upsertReason(reasons, "realtimeLookup", String.valueOf(realtimeLookup));
        upsertReason(reasons, "memoryDirectBypassed", String.valueOf(memoryDirectBypassed));
        upsertReason(reasons, "actualSearchSource", actualSearchSource);
        upsertReason(reasons, "skillPostprocessSent", String.valueOf(skillPostprocessSent));
        RoutingDecisionDto updatedRouting = new RoutingDecisionDto(
                routing.route(),
                routing.selectedSkill(),
                routing.confidence(),
                List.copyOf(reasons),
                routing.rejectedReasons()
        );
        ExecutionTraceDto updatedTrace = new ExecutionTraceDto(
                result.executionTrace().strategy(),
                result.executionTrace().replanCount(),
                result.executionTrace().critique(),
                result.executionTrace().steps(),
                updatedRouting
        );
        return new DispatchResult(result.reply(), result.channel(), updatedTrace);
    }

    private String resolveActualSearchSource(String selectedSkill, String reply) {
        String classified = llmSupport.classifyMcpSearchSource(selectedSkill);
        if (!classified.isBlank()) {
            return classified;
        }
        if (!"news_search".equalsIgnoreCase(selectedSkill)) {
            return "";
        }
        return extractNewsSearchSource(reply);
    }

    private String extractNewsSearchSource(String reply) {
        if (reply == null || reply.isBlank()) {
            return "";
        }
        LinkedHashSet<String> sources = new LinkedHashSet<>();
        for (String line : reply.split("\\R")) {
            if (line == null) {
                continue;
            }
            String trimmed = line.trim();
            if (!trimmed.startsWith("来源:")) {
                continue;
            }
            String normalized = trimmed.substring("来源:".length()).trim().toLowerCase(Locale.ROOT);
            if (normalized.contains("serper")) {
                sources.add("serper");
            }
            if (normalized.contains("36kr")) {
                sources.add("36kr");
            }
            if (normalized.contains("brave")) {
                sources.add("brave");
            }
            if (normalized.contains("serpapi")) {
                sources.add("serpapi");
            }
            break;
        }
        return sources.isEmpty() ? "" : String.join("+", sources);
    }

    private void upsertReason(List<String> reasons, String key, String value) {
        String prefix = key + "=";
        for (int index = 0; index < reasons.size(); index++) {
            if (reasons.get(index) != null && reasons.get(index).startsWith(prefix)) {
                reasons.set(index, prefix + value);
                return;
            }
        }
        reasons.add(prefix + value);
    }

    private String buildClarifyReply(Decision decision, ParamValidator.ValidationResult validation) {
        StringBuilder reply = new StringBuilder(CLARIFY_PREFIX);
        if (validation.missingParams() != null && !validation.missingParams().isEmpty()) {
            reply.append(String.join("、", validation.missingParams()));
        } else if (validation.message() != null && !validation.message().isBlank()) {
            reply.append(validation.message());
        } else {
            reply.append("请补充执行所需参数");
        }
        if (decision != null && decision.target() != null && !decision.target().isBlank()) {
            reply.append("。目标能力：").append(decision.target());
        }
        reply.append("。请一次性补充这些信息，我再继续执行。");
        return reply.toString();
    }

    private int clarificationAttempts(String userId) {
        if (dispatcherMemoryFacade == null || userId == null || userId.isBlank()) {
            return 0;
        }
        return (int) dispatcherMemoryFacade.recentHistory(userId).stream()
                .filter(turn -> turn != null
                        && "assistant".equalsIgnoreCase(safeText(turn.role()))
                        && safeText(turn.content()).startsWith(CLARIFY_PREFIX))
                .count();
    }

    private boolean isPromptInjectionAttempt(String userInput) {
        return heuristicsSupport != null && heuristicsSupport.isPromptInjectionAttempt(userInput);
    }

    private DispatchResult buildDispatchResult(String reply,
                                               String channel,
                                               String route,
                                               String selectedSkill,
                                               double confidence,
                                               List<String> reasons,
                                               List<String> rejectedReasons,
                                               boolean success,
                                               List<PlanStepDto> steps) {
        return buildDispatchResult(reply, channel, route, selectedSkill, confidence, reasons, rejectedReasons, success,
                "hermes-single-decision", 0, steps);
    }

    private DispatchResult buildDispatchResult(String reply,
                                               String channel,
                                               String route,
                                               String selectedSkill,
                                               double confidence,
                                               List<String> reasons,
                                               List<String> rejectedReasons,
                                               boolean success,
                                               String strategy,
                                               int replanCount,
                                               List<PlanStepDto> steps) {
        RoutingDecisionDto routingDecision = new RoutingDecisionDto(
                route == null || route.isBlank() ? "decision-engine" : route,
                selectedSkill == null || selectedSkill.isBlank() ? channel : selectedSkill,
                Math.max(0.0d, Math.min(1.0d, confidence)),
                reasons == null ? List.of() : List.copyOf(reasons),
                rejectedReasons == null ? List.of() : List.copyOf(rejectedReasons)
        );
        ExecutionTraceDto trace = new ExecutionTraceDto(
                strategy == null || strategy.isBlank() ? "hermes-single-decision" : strategy,
                Math.max(0, replanCount),
                new CritiqueReportDto(success, success ? "completed" : "failed", "none"),
                steps == null ? List.of() : List.copyOf(steps),
                routingDecision
        );
        return new DispatchResult(reply == null ? "" : reply, channel == null ? "" : channel, trace);
    }

    private PlanStepDto primaryStep(String status,
                                    String channel,
                                    String details,
                                    Instant startedAt,
                                    Instant finishedAt) {
        return new PlanStepDto(
                "primary",
                status,
                channel == null ? "" : channel,
                safeText(details),
                startedAt,
                finishedAt
        );
    }

    private DispatchResult buildDrainingResult() {
        Instant now = Instant.now();
        return buildDispatchResult(
                "系统正在升级维护，请稍后重试。",
                "system.draining",
                "system.draining",
                "system.draining",
                1.0d,
                List.of("dispatcher is currently draining and rejecting new requests"),
                List.of(),
                true,
                List.of(primaryStep("success", "system.draining", "系统正在升级维护，请稍后重试。", now, now))
        );
    }

    private void emit(Consumer<String> deltaConsumer, String text) {
        if (deltaConsumer != null && text != null && !text.isBlank()) {
            deltaConsumer.accept(text);
        }
    }

    private String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    private String clip(String value) {
        if (value == null) {
            return "";
        }
        int max = 160;
        return value.length() <= max ? value : value.substring(0, max) + "...";
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

    private HermesProactiveAssistant.Augmentation maybeApplyProactiveAssistant(String userInput,
                                                                               HermesDecisionContext decisionContext,
                                                                               SkillResult result) {
        return proactiveAssistant == null
                ? HermesProactiveAssistant.Augmentation.unchanged(result)
                : proactiveAssistant.maybeAugment(userInput, decisionContext, result);
    }

    private List<String> augmentReasons(List<String> reasons, HermesProactiveAssistant.Augmentation proactive) {
        if (proactive == null || !proactive.applied()) {
            return reasons;
        }
        List<String> updated = new ArrayList<>(reasons == null ? List.of() : reasons);
        updated.add("proactiveHint=" + proactive.hintType());
        return List.copyOf(updated);
    }

    private Optional<SkillResult> maybeBlockedByCapability(String skillName) {
        return executionGuard == null ? Optional.empty() : executionGuard.maybeBlockByCapability(skillName);
    }

    private boolean isLoopGuardBlocked(String userId, String skillName, String userInput) {
        return executionGuard != null
                && skillName != null
                && !skillName.isBlank()
                && executionGuard.isSkillLoopGuardBlocked(userId, skillName, userInput);
    }

    private SkillResult capLlmResult(SkillResult result) {
        if (result == null || result.skillName() == null || !"llm".equalsIgnoreCase(result.skillName())) {
            return result;
        }
        return SkillResult.success(result.skillName(), llmSupport.capLlmReply(result.output()));
    }
}
