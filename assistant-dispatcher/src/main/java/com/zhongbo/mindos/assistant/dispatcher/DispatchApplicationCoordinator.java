package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.common.dto.CritiqueReportDto;
import com.zhongbo.mindos.assistant.common.dto.ExecutionTraceDto;
import com.zhongbo.mindos.assistant.common.dto.PromptMemoryContextDto;
import com.zhongbo.mindos.assistant.common.dto.RoutingDecisionDto;
import com.zhongbo.mindos.assistant.common.dto.ExecutionTraceDto;
import com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.MasterOrchestrationResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.MasterOrchestrator;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.dispatcher.MetaOrchestratorService.MetaOrchestrationResult;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.DecisionOrchestrator;
import com.zhongbo.mindos.assistant.dispatcher.routing.DispatchPlan;
import com.zhongbo.mindos.assistant.dispatcher.routing.RoutingCoordinator;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisResult;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;

final class DispatchApplicationCoordinator {

    private static final Logger LOGGER = Logger.getLogger(DispatchApplicationCoordinator.class.getName());

    private final DispatchMemoryLifecycle dispatchMemoryLifecycle;
    private final DispatchPreparationSupport dispatchPreparationSupport;
    private final DispatchRoutingPipeline dispatchRoutingPipeline;
    private final DecisionOrchestrator decisionOrchestrator;
    private final MetaOrchestratorService metaOrchestratorService;
    private final DispatchResultFinalizer dispatchResultFinalizer;
    private final Supplier<MasterOrchestrator> masterOrchestratorSupplier;
    private final Supplier<RoutingCoordinator> routingCoordinatorSupplier;
    private final CoordinatorBridge bridge;
    private final boolean semanticAnalysisSkipShortSimpleEnabled;
    private final String promptInjectionSafeReply;
    private final AtomicBoolean acceptingRequests = new AtomicBoolean(true);
    private final AtomicLong activeDispatchCount = new AtomicLong();

    DispatchApplicationCoordinator(DispatchMemoryLifecycle dispatchMemoryLifecycle,
                                   DispatchPreparationSupport dispatchPreparationSupport,
                                   DispatchRoutingPipeline dispatchRoutingPipeline,
                                   DecisionOrchestrator decisionOrchestrator,
                                   MetaOrchestratorService metaOrchestratorService,
                                   DispatchResultFinalizer dispatchResultFinalizer,
                                   Supplier<MasterOrchestrator> masterOrchestratorSupplier,
                                   Supplier<RoutingCoordinator> routingCoordinatorSupplier,
                                   CoordinatorBridge bridge,
                                   boolean semanticAnalysisSkipShortSimpleEnabled,
                                   String promptInjectionSafeReply) {
        this.dispatchMemoryLifecycle = dispatchMemoryLifecycle;
        this.dispatchPreparationSupport = dispatchPreparationSupport;
        this.dispatchRoutingPipeline = dispatchRoutingPipeline;
        this.decisionOrchestrator = decisionOrchestrator;
        this.metaOrchestratorService = metaOrchestratorService;
        this.dispatchResultFinalizer = dispatchResultFinalizer;
        this.masterOrchestratorSupplier = masterOrchestratorSupplier;
        this.routingCoordinatorSupplier = routingCoordinatorSupplier;
        this.bridge = bridge;
        this.semanticAnalysisSkipShortSimpleEnabled = semanticAnalysisSkipShortSimpleEnabled;
        this.promptInjectionSafeReply = promptInjectionSafeReply;
    }

    DispatchResult dispatch(String userId, String userInput, Map<String, Object> profileContext) {
        return dispatchAsync(userId, userInput, profileContext).join();
    }

    CompletableFuture<DispatchResult> dispatchAsync(String userId, String userInput, Map<String, Object> profileContext) {
        if (!acceptingRequests.get()) {
            return CompletableFuture.completedFuture(bridge.buildDrainingResult(userInput));
        }
        try {
            Instant startTime = Instant.now();
            LOGGER.info("Dispatcher input: userId=" + userId + ", input=" + bridge.clip(userInput));
            DispatchExecutionState executionState = new DispatchExecutionState();
            RoutingReplayProbe replayProbe = new RoutingReplayProbe();

            executionState.mergeMemoryWrites(dispatchMemoryLifecycle.recordUserInput(userId, userInput));

            String normalizedInputForBypass = bridge.normalize(userInput);
            if (semanticAnalysisSkipShortSimpleEnabled && bridge.isConversationalBypassInput(normalizedInputForBypass)) {
                flushPendingMemoryWrites(userId, executionState);
                return CompletableFuture.completedFuture(bridge.handleConversationalBypass(userId, normalizedInputForBypass));
            }
            if (bridge.isPromptInjectionAttempt(userInput)) {
                flushPendingMemoryWrites(userId, executionState);
                return CompletableFuture.completedFuture(promptInjectionResult(userId, executionState));
            }

            activeDispatchCount.incrementAndGet();
            RoutingCoordinator routingCoordinator = routingCoordinatorSupplier.get();
            DispatchPreparationSupport.PreparedDispatch preparedDispatch = dispatchPreparationSupport.prepare(
                    userId,
                    userInput,
                    profileContext,
                    routingCoordinator
            );
            Map<String, Object> resolvedProfileContext = preparedDispatch.resolvedProfileContext();
            PromptMemoryContextDto promptMemoryContext = preparedDispatch.promptMemoryContext();
            SemanticAnalysisResult semanticAnalysis = preparedDispatch.semanticAnalysis();
            boolean realtimeIntentInput = preparedDispatch.realtimeIntentInput();
            executionState.mergeMemoryWrites(preparedDispatch.memoryWrites());
            executionState.setRealtimeLookup(preparedDispatch.realtimeLookup());
            String routingInput = preparedDispatch.routingInput();
            String effectiveMemoryContext = preparedDispatch.effectiveMemoryContext();
            SkillContext context = preparedDispatch.context();
            Map<String, Object> llmContext = preparedDispatch.llmContext();

            DispatchPlan routingPlan = routingCoordinator == null
                    ? null
                    : routingCoordinator.preparePlan(userInput, semanticAnalysis, context, resolvedProfileContext);
            boolean useMasterOrchestrator = routingPlan == null
                    ? bridge.shouldUseMasterOrchestrator(resolvedProfileContext)
                    : routingPlan.usesMultiAgent();
            Decision multiAgentDecision = routingPlan == null
                    ? bridge.buildMultiAgentDecision(userInput, semanticAnalysis, context)
                    : routingPlan.decision();
            if (useMasterOrchestrator) {
                return attachDispatchCompletion(
                        executeMasterOrchestratorDispatch(
                                userId,
                                userInput,
                                promptMemoryContext,
                                llmContext,
                                realtimeIntentInput,
                                executionState,
                                semanticAnalysis,
                                replayProbe,
                                multiAgentDecision,
                                resolvedProfileContext
                        ),
                        userId,
                        startTime,
                        executionState,
                        false
                );
            }

            CompletableFuture<DispatchResult> future;
            if (shouldAttemptPrimaryPass(userInput, context)) {
                future = metaOrchestratorService.orchestrate(
                                () -> executePrimaryPass(
                                        userId,
                                        userInput,
                                        context,
                                        executionState,
                                        resolvedProfileContext
                                ),
                                () -> executeCompatibilityPass(
                                        userId,
                                        userInput,
                                        context,
                                        effectiveMemoryContext,
                                        promptMemoryContext,
                                        llmContext,
                                        realtimeIntentInput,
                                        executionState,
                                        semanticAnalysis,
                                        replayProbe
                                )
                        )
                        .thenApply(orchestration -> dispatchResultFinalizer.finalizeMetaOrchestration(
                                userId,
                                userInput,
                                orchestration,
                                llmContext,
                                resolvedProfileContext,
                                promptMemoryContext,
                                replayProbe,
                                executionState
                        ));
            } else {
                future = executeCompatibilityPass(
                                userId,
                                userInput,
                                context,
                                effectiveMemoryContext,
                                promptMemoryContext,
                                llmContext,
                                realtimeIntentInput,
                                executionState,
                                semanticAnalysis,
                                replayProbe
                        )
                        .thenApply(result -> dispatchResultFinalizer.finalizeMetaOrchestration(
                                userId,
                                userInput,
                                new MetaOrchestrationResult(
                                        result,
                                        new ExecutionTraceDto(
                                                "compatibility-single-pass",
                                                compatibilityReplanCount(executionState.routingDecision(), result),
                                                null,
                                                List.of()
                                        )
                                ),
                                llmContext,
                                resolvedProfileContext,
                                promptMemoryContext,
                                replayProbe,
                                executionState
                        ));
            }
            return attachDispatchCompletion(future, userId, startTime, executionState, false);
        } catch (RuntimeException | Error ex) {
            activeDispatchCount.decrementAndGet();
            throw ex;
        }
    }

    CompletableFuture<DispatchResult> dispatchStream(String userId,
                                                     String userInput,
                                                     Map<String, Object> profileContext,
                                                     Consumer<String> deltaConsumer) {
        if (!acceptingRequests.get()) {
            return CompletableFuture.completedFuture(bridge.buildDrainingResult(userInput));
        }
        try {
            Instant startTime = Instant.now();
            LOGGER.info("Dispatcher(stream) input: userId=" + userId + ", input=" + bridge.clip(userInput));
            DispatchExecutionState executionState = new DispatchExecutionState();
            RoutingReplayProbe replayProbe = new RoutingReplayProbe();

            executionState.mergeMemoryWrites(dispatchMemoryLifecycle.recordUserInput(userId, userInput));

            String normalizedInputForBypass = bridge.normalize(userInput);
            if (semanticAnalysisSkipShortSimpleEnabled && bridge.isConversationalBypassInput(normalizedInputForBypass)) {
                flushPendingMemoryWrites(userId, executionState);
                return CompletableFuture.completedFuture(bridge.handleConversationalBypass(userId, normalizedInputForBypass));
            }
            if (bridge.isPromptInjectionAttempt(userInput)) {
                flushPendingMemoryWrites(userId, executionState);
                return CompletableFuture.completedFuture(promptInjectionStreamResult(userId));
            }

            activeDispatchCount.incrementAndGet();
            RoutingCoordinator routingCoordinator = routingCoordinatorSupplier.get();
            DispatchPreparationSupport.PreparedDispatch preparedDispatch = dispatchPreparationSupport.prepare(
                    userId,
                    userInput,
                    profileContext,
                    routingCoordinator
            );
            Map<String, Object> resolvedProfileContext = preparedDispatch.resolvedProfileContext();
            PromptMemoryContextDto promptMemoryContext = preparedDispatch.promptMemoryContext();
            SemanticAnalysisResult semanticAnalysis = preparedDispatch.semanticAnalysis();
            boolean realtimeIntentInput = preparedDispatch.realtimeIntentInput();
            executionState.mergeMemoryWrites(preparedDispatch.memoryWrites());
            executionState.setRealtimeLookup(preparedDispatch.realtimeLookup());
            String routingInput = preparedDispatch.routingInput();
            String effectiveMemoryContext = preparedDispatch.effectiveMemoryContext();
            SkillContext context = preparedDispatch.context();
            Map<String, Object> llmContext = preparedDispatch.llmContext();

            CompletableFuture<DispatchResult> future;
            if (shouldAttemptPrimaryPass(userInput, context)) {
                future = executePrimaryPass(
                                userId,
                                userInput,
                                context,
                                executionState,
                                resolvedProfileContext
                        )
                        .thenCompose(primaryResult -> {
                            if (primaryResult != null && primaryResult.success()) {
                                return CompletableFuture.completedFuture(primaryResult);
                            }
                            return dispatchRoutingPipeline.routeToSkillAsync(
                                            userId,
                                            userInput,
                                            context,
                                            effectiveMemoryContext,
                                            semanticAnalysis,
                                            replayProbe
                                    )
                                    .thenApply(routingOutcome -> {
                                        executionState.setRoutingDecision(routingOutcome.routingDecision());
                                        SkillResult routedResult = routingOutcome.result().orElse(null);
                                        if (!shouldFallbackFromCompatibilityResult(routedResult)) {
                                            return routedResult;
                                        }
                                        return bridge.buildLlmFallbackStreamResult(
                                                effectiveMemoryContext,
                                                promptMemoryContext,
                                                routingInput,
                                                llmContext,
                                                realtimeIntentInput,
                                                deltaConsumer
                                        );
                                    });
                        })
                        .thenApply(result -> dispatchResultFinalizer.finalizeStreamResult(
                                userId,
                                userInput,
                                result,
                                llmContext,
                                resolvedProfileContext,
                                promptMemoryContext,
                                replayProbe,
                                executionState
                        ));
            } else {
                future = dispatchRoutingPipeline.routeToSkillAsync(
                                userId,
                                userInput,
                                context,
                                effectiveMemoryContext,
                                semanticAnalysis,
                                replayProbe
                        )
                        .thenApply(routingOutcome -> {
                            executionState.setRoutingDecision(routingOutcome.routingDecision());
                            SkillResult routedResult = routingOutcome.result().orElse(null);
                            if (!shouldFallbackFromCompatibilityResult(routedResult)) {
                                return routedResult;
                            }
                            return bridge.buildLlmFallbackStreamResult(
                                    effectiveMemoryContext,
                                    promptMemoryContext,
                                    routingInput,
                                    llmContext,
                                    realtimeIntentInput,
                                    deltaConsumer
                            );
                        })
                        .thenApply(result -> dispatchResultFinalizer.finalizeStreamResult(
                                userId,
                                userInput,
                                result,
                                llmContext,
                                resolvedProfileContext,
                                promptMemoryContext,
                                replayProbe,
                                executionState
                        ));
            }
            return attachDispatchCompletion(future, userId, startTime, executionState, true);
        } catch (RuntimeException | Error ex) {
            activeDispatchCount.decrementAndGet();
            throw ex;
        }
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
        return Math.max(0L, activeDispatchCount.get());
    }

    boolean waitForActiveDispatches(long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        while (System.currentTimeMillis() < deadline) {
            if (activeDispatchCount.get() <= 0L) {
                return true;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return activeDispatchCount.get() <= 0L;
    }

    private void flushPendingMemoryWrites(String userId, DispatchExecutionState executionState) {
        dispatchResultFinalizer.flushPendingMemoryWrites(userId, executionState);
    }

    private CompletableFuture<SkillResult> executePrimaryPass(String userId,
                                                              String userInput,
                                                              SkillContext context,
                                                              DispatchExecutionState executionState,
                                                              Map<String, Object> resolvedProfileContext) {
        return CompletableFuture.supplyAsync(() -> {
            DecisionOrchestrator.OrchestrationOutcome outcome = decisionOrchestrator.planAndExecute(
                    new DecisionOrchestrator.OrchestrationRequest(userId, userInput, context, resolvedProfileContext),
                    "",
                    context == null || context.attributes() == null ? Map.of() : context.attributes()
            );
            executionState.setRoutingDecision(buildPrimaryRoutingDecision(outcome));
            if (outcome == null) {
                return SkillResult.failure("decision.orchestrator", "primary orchestrator produced no result");
            }
            if (outcome.hasClarification()) {
                if (outcome.selectedSkill() == null || outcome.selectedSkill().isBlank()) {
                    return SkillResult.failure("decision.orchestrator", "primary planner produced no target");
                }
                return outcome.clarification();
            }
            if (outcome.hasResult() && outcome.result().success()) {
                return outcome.result();
            }
            return SkillResult.failure("decision.orchestrator", "primary orchestrator failed");
        });
    }

    private boolean shouldAttemptPrimaryPass(String userInput, SkillContext context) {
        String normalizedInput = userInput == null ? "" : userInput.trim();
        if (normalizedInput.startsWith("skill:")) {
            return true;
        }
        Map<String, Object> attributes = context == null || context.attributes() == null
                ? Map.of()
                : context.attributes();
        Object explicitRoute = attributes.get("dispatcherPrimaryRoute");
        if (explicitRoute instanceof Boolean booleanValue && booleanValue) {
            return true;
        }
        String explicitTarget = attributeText(attributes, "explicitTarget");
        if (explicitTarget != null) {
            return true;
        }
        return attributeText(attributes, "explicitSkill") != null;
    }

    private String attributeText(Map<String, Object> attributes, String key) {
        if (attributes == null || attributes.isEmpty()) {
            return null;
        }
        Object value = attributes.get(key);
        if (value == null) {
            return null;
        }
        String normalized = String.valueOf(value).trim();
        return normalized.isBlank() ? null : normalized;
    }

    private int compatibilityReplanCount(RoutingDecisionDto routingDecision, SkillResult result) {
        if (routingDecision == null || result == null || result.skillName() == null || result.skillName().isBlank()) {
            return 0;
        }
        String selectedSkill = routingDecision.selectedSkill();
        if (selectedSkill == null || selectedSkill.isBlank()) {
            return 0;
        }
        return selectedSkill.equals(result.skillName()) ? 0 : 1;
    }

    private boolean shouldFallbackFromCompatibilityResult(SkillResult routedResult) {
        if (routedResult == null) {
            return true;
        }
        if (routedResult.success()) {
            return false;
        }
        String skillName = routedResult.skillName();
        return skillName == null
                || skillName.isBlank()
                || "decision.orchestrator".equals(skillName);
    }

    private CompletableFuture<SkillResult> executeCompatibilityPass(String userId,
                                                                    String userInput,
                                                                    SkillContext context,
                                                                    String memoryContext,
                                                                    PromptMemoryContextDto promptMemoryContext,
                                                                    Map<String, Object> llmContext,
                                                                    boolean realtimeIntentInput,
                                                                    DispatchExecutionState executionState,
                                                                    SemanticAnalysisResult semanticAnalysis,
                                                                    RoutingReplayProbe replayProbe) {
        return dispatchRoutingPipeline.routeToSkillAsync(userId, userInput, context, memoryContext, semanticAnalysis, replayProbe)
                .thenApply(routingOutcome -> {
                    executionState.setRoutingDecision(routingOutcome.routingDecision());
                    SkillResult routedResult = routingOutcome.result().orElse(null);
                    if (!shouldFallbackFromCompatibilityResult(routedResult)) {
                        return routedResult;
                    }
                    return bridge.buildFallbackResult(
                            memoryContext,
                            promptMemoryContext,
                            context.input(),
                            llmContext,
                            realtimeIntentInput
                    );
                });
    }

    private RoutingDecisionDto buildPrimaryRoutingDecision(DecisionOrchestrator.OrchestrationOutcome outcome) {
        if (outcome == null) {
            return new RoutingDecisionDto(
                    "orchestrator-primary",
                    "",
                    0.0,
                    List.of("primary orchestrator returned no outcome"),
                    List.of()
            );
        }
        String selectedSkill = outcome.selectedSkill();
        if ((selectedSkill == null || selectedSkill.isBlank()) && outcome.result() != null) {
            selectedSkill = outcome.result().skillName();
        }
        if ((selectedSkill == null || selectedSkill.isBlank()) && outcome.clarification() != null) {
            selectedSkill = outcome.clarification().skillName();
        }
        java.util.ArrayList<String> reasons = new java.util.ArrayList<>();
        reasons.add("primary route executed through orchestrator planner");
        if (outcome.usedFallback()) {
            reasons.add("orchestrator-used-fallback=true");
        }
        if (outcome.hasClarification()) {
            reasons.add("clarification requested by planner/executor");
        }
        if (outcome.hasResult() && !outcome.result().success()) {
            reasons.add("primary orchestrator execution failed");
        }
        return new RoutingDecisionDto(
                "orchestrator-primary",
                selectedSkill == null ? "" : selectedSkill,
                outcome.trace() == null ? 0.0 : 1.0,
                List.copyOf(reasons),
                List.of()
        );
    }

    private CompletableFuture<DispatchResult> executeMasterOrchestratorDispatch(String userId,
                                                                                String userInput,
                                                                                PromptMemoryContextDto promptMemoryContext,
                                                                                Map<String, Object> llmContext,
                                                                                boolean realtimeIntentInput,
                                                                                DispatchExecutionState executionState,
                                                                                SemanticAnalysisResult semanticAnalysis,
                                                                                RoutingReplayProbe replayProbe,
                                                                                Decision decision,
                                                                                Map<String, Object> resolvedProfileContext) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> multiAgentProfileContext = new LinkedHashMap<>(resolvedProfileContext == null ? Map.of() : resolvedProfileContext);
            multiAgentProfileContext.put("multiAgent", true);
            multiAgentProfileContext.put("orchestrationMode", "multi-agent");
            multiAgentProfileContext.put("multiAgent.skipMemoryWrite", true);
            MasterOrchestrator masterOrchestrator = masterOrchestratorSupplier.get();
            if (decision == null || masterOrchestrator == null) {
                return new DispatchResult("multi-agent orchestrator unavailable", "multi-agent.master",
                        new ExecutionTraceDto("multi-agent-master", 0,
                                new CritiqueReportDto(false, "multi-agent orchestrator unavailable", "replan"),
                                List.of()));
            }
            replayProbe.setRuleCandidate("multi-agent-master");
            llmContext.put("routeStage", "multi-agent-master");
            executionState.setRealtimeLookup(realtimeIntentInput || bridge.isRealtimeLikeInput(userInput, semanticAnalysis));

            MasterOrchestrationResult orchestration = masterOrchestrator.execute(
                    userId,
                    userInput,
                    decision,
                    multiAgentProfileContext
            );
            SkillResult result = orchestration == null || orchestration.result() == null
                    ? SkillResult.failure(bridge.firstNonBlank(decision.target(), "multi-agent.master"), "master orchestrator produced no result")
                    : orchestration.result();
            return dispatchResultFinalizer.finalizeMasterResult(
                    userId,
                    userInput,
                    decision,
                    orchestration,
                    result,
                    llmContext,
                    resolvedProfileContext,
                    promptMemoryContext,
                    replayProbe,
                    executionState
            );
        });
    }

    private CompletableFuture<DispatchResult> attachDispatchCompletion(CompletableFuture<DispatchResult> future,
                                                                       String userId,
                                                                       Instant startTime,
                                                                       DispatchExecutionState executionState,
                                                                       boolean streamMode) {
        return future.whenComplete((result, error) -> {
            activeDispatchCount.decrementAndGet();
            bridge.logDispatchCompletion(userId, result, executionState, streamMode, startTime, error);
        });
    }

    private DispatchResult promptInjectionResult(String userId, DispatchExecutionState executionState) {
        dispatchMemoryLifecycle.recordAssistantReply(userId, promptInjectionSafeReply);
        executionState.setRoutingDecision(new RoutingDecisionDto(
                "security.guard",
                "security.guard",
                1.0,
                List.of("prompt injection guard matched configured risky terms"),
                List.of()
        ));
        return new DispatchResult(
                promptInjectionSafeReply,
                "security.guard",
                new ExecutionTraceDto("single-pass", 0, null, List.of(), executionState.routingDecision())
        );
    }

    private DispatchResult promptInjectionStreamResult(String userId) {
        dispatchMemoryLifecycle.recordAssistantReply(userId, promptInjectionSafeReply);
        RoutingDecisionDto decision = new RoutingDecisionDto(
                "security.guard",
                "security.guard",
                1.0,
                List.of("prompt injection guard matched configured risky terms"),
                List.of()
        );
        ExecutionTraceDto trace = new ExecutionTraceDto("stream-single-pass", 0, null, List.of(), decision);
        return new DispatchResult(promptInjectionSafeReply, "security.guard", trace);
    }

    interface CoordinatorBridge {
        String clip(String value);

        String normalize(String value);

        boolean isConversationalBypassInput(String normalizedInput);

        DispatchResult handleConversationalBypass(String userId, String normalizedInput);

        boolean isPromptInjectionAttempt(String userInput);

        boolean isRealtimeLikeInput(String userInput, SemanticAnalysisResult semanticAnalysis);

        boolean shouldUseMasterOrchestrator(Map<String, Object> profileContext);

        Decision buildMultiAgentDecision(String userInput, SemanticAnalysisResult semanticAnalysis, SkillContext context);

        SkillResult buildFallbackResult(String memoryContext,
                                        PromptMemoryContextDto promptMemoryContext,
                                        String userInput,
                                        Map<String, Object> llmContext,
                                        boolean realtimeIntentInput);

        SkillResult buildLlmFallbackStreamResult(String memoryContext,
                                                 PromptMemoryContextDto promptMemoryContext,
                                                 String userInput,
                                                 Map<String, Object> llmContext,
                                                 boolean realtimeIntentInput,
                                                 Consumer<String> deltaConsumer);

        DispatchResult buildDrainingResult(String userInput);

        void logDispatchCompletion(String userId,
                                   DispatchResult result,
                                   DispatchExecutionState executionState,
                                   boolean streamMode,
                                   Instant startTime,
                                   Throwable error);

        String firstNonBlank(String... values);
    }
}
