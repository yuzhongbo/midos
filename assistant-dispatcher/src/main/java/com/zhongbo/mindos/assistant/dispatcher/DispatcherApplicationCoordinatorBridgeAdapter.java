package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.common.dto.PromptMemoryContextDto;
import com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.MasterOrchestrator;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.dispatcher.routing.RoutingCoordinator;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisResult;

import java.time.Instant;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

final class DispatcherApplicationCoordinatorBridgeAdapter implements DispatchApplicationCoordinator.CoordinatorBridge {

    @FunctionalInterface
    interface DrainingResultBuilder {
        DispatchResult build(String userInput);
    }

    @FunctionalInterface
    interface DispatchCompletionLogger {
        void log(String userId,
                 DispatchResult result,
                 DispatchExecutionState executionState,
                 boolean streamMode,
                 Instant startTime,
                 Throwable error);
    }

    @FunctionalInterface
    interface FirstNonBlankResolver {
        String resolve(String... values);
    }

    private final DispatchHeuristicsSupport heuristicsSupport;
    private final DispatchLlmSupport llmSupport;
    private final Supplier<MasterOrchestrator> masterOrchestratorSupplier;
    private final Supplier<RoutingCoordinator> routingCoordinatorSupplier;
    private final Function<String, String> clipper;
    private final Function<String, String> normalizer;
    private final DrainingResultBuilder drainingResultBuilder;
    private final DispatchCompletionLogger completionLogger;
    private final FirstNonBlankResolver firstNonBlankResolver;

    DispatcherApplicationCoordinatorBridgeAdapter(DispatchHeuristicsSupport heuristicsSupport,
                                                  DispatchLlmSupport llmSupport,
                                                  Supplier<MasterOrchestrator> masterOrchestratorSupplier,
                                                  Supplier<RoutingCoordinator> routingCoordinatorSupplier,
                                                  Function<String, String> clipper,
                                                  Function<String, String> normalizer,
                                                  DrainingResultBuilder drainingResultBuilder,
                                                  DispatchCompletionLogger completionLogger,
                                                  FirstNonBlankResolver firstNonBlankResolver) {
        this.heuristicsSupport = heuristicsSupport;
        this.llmSupport = llmSupport;
        this.masterOrchestratorSupplier = masterOrchestratorSupplier;
        this.routingCoordinatorSupplier = routingCoordinatorSupplier;
        this.clipper = clipper;
        this.normalizer = normalizer;
        this.drainingResultBuilder = drainingResultBuilder;
        this.completionLogger = completionLogger;
        this.firstNonBlankResolver = firstNonBlankResolver;
    }

    @Override
    public String clip(String value) {
        return clipper.apply(value);
    }

    @Override
    public String normalize(String value) {
        return normalizer.apply(value);
    }

    @Override
    public boolean isConversationalBypassInput(String normalizedInput) {
        return heuristicsSupport.isConversationalBypassInput(normalizedInput);
    }

    @Override
    public DispatchResult handleConversationalBypass(String userId, String normalizedInput) {
        return heuristicsSupport.handleConversationalBypass(userId, normalizedInput);
    }

    @Override
    public boolean isPromptInjectionAttempt(String userInput) {
        return heuristicsSupport.isPromptInjectionAttempt(userInput);
    }

    @Override
    public boolean isRealtimeLikeInput(String userInput, SemanticAnalysisResult semanticAnalysis) {
        return heuristicsSupport.isRealtimeLikeInput(userInput, semanticAnalysis);
    }

    @Override
    public boolean shouldUseMasterOrchestrator(Map<String, Object> profileContext) {
        MasterOrchestrator masterOrchestrator = masterOrchestratorSupplier.get();
        RoutingCoordinator routingCoordinator = routingCoordinatorSupplier.get();
        return masterOrchestrator != null
                && routingCoordinator != null
                && routingCoordinator.shouldUseMasterOrchestrator(profileContext);
    }

    @Override
    public Decision buildMultiAgentDecision(String userInput,
                                            SemanticAnalysisResult semanticAnalysis,
                                            com.zhongbo.mindos.assistant.common.SkillContext context) {
        RoutingCoordinator routingCoordinator = routingCoordinatorSupplier.get();
        return routingCoordinator == null
                ? null
                : routingCoordinator.buildMultiAgentDecision(userInput, semanticAnalysis, context);
    }

    @Override
    public SkillResult buildFallbackResult(String memoryContext,
                                           PromptMemoryContextDto promptMemoryContext,
                                           String userInput,
                                           Map<String, Object> llmContext,
                                           boolean realtimeIntentInput) {
        return llmSupport.buildFallbackResult(memoryContext, promptMemoryContext, userInput, llmContext, realtimeIntentInput);
    }

    @Override
    public SkillResult buildLlmFallbackStreamResult(String memoryContext,
                                                    PromptMemoryContextDto promptMemoryContext,
                                                    String userInput,
                                                    Map<String, Object> llmContext,
                                                    boolean realtimeIntentInput,
                                                    Consumer<String> deltaConsumer) {
        return llmSupport.buildLlmFallbackStreamResult(
                memoryContext,
                promptMemoryContext,
                userInput,
                llmContext,
                realtimeIntentInput,
                deltaConsumer
        );
    }

    @Override
    public DispatchResult buildDrainingResult(String userInput) {
        return drainingResultBuilder.build(userInput);
    }

    @Override
    public void logDispatchCompletion(String userId,
                                      DispatchResult result,
                                      DispatchExecutionState executionState,
                                      boolean streamMode,
                                      Instant startTime,
                                      Throwable error) {
        completionLogger.log(userId, result, executionState, streamMode, startTime, error);
    }

    @Override
    public String firstNonBlank(String... values) {
        return firstNonBlankResolver.resolve(values);
    }
}
