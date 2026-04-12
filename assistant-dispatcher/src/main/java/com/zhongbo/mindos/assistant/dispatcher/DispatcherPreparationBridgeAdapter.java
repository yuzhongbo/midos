package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisResult;

import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

final class DispatcherPreparationBridgeAdapter implements DispatchPreparationSupport.PreparationBridge {

    @FunctionalInterface
    interface ContextCompressionRecorder {
        void record(int rawChars, int finalChars, boolean compressed, int summarizedTurns);
    }

    @FunctionalInterface
    interface StageRouteApplier {
        void apply(String stage, Map<String, Object> profileContext, Map<String, Object> llmContext);
    }

    private final ContextCompressionRecorder contextCompressionRecorder;
    private final Predicate<String> semanticSkipChecker;
    private final BiPredicate<String, SemanticAnalysisResult> realtimeIntentChecker;
    private final BiPredicate<String, SemanticAnalysisResult> realtimeLikeChecker;
    private final BiConsumer<Map<String, Object>, Map<String, Object>> escalationHintCopier;
    private final BiConsumer<Map<String, Object>, Map<String, Object>> interactionContextCopier;
    private final StageRouteApplier stageRouteApplier;

    DispatcherPreparationBridgeAdapter(ContextCompressionRecorder contextCompressionRecorder,
                                       Predicate<String> semanticSkipChecker,
                                       BiPredicate<String, SemanticAnalysisResult> realtimeIntentChecker,
                                       BiPredicate<String, SemanticAnalysisResult> realtimeLikeChecker,
                                       BiConsumer<Map<String, Object>, Map<String, Object>> escalationHintCopier,
                                       BiConsumer<Map<String, Object>, Map<String, Object>> interactionContextCopier,
                                       StageRouteApplier stageRouteApplier) {
        this.contextCompressionRecorder = contextCompressionRecorder;
        this.semanticSkipChecker = semanticSkipChecker;
        this.realtimeIntentChecker = realtimeIntentChecker;
        this.realtimeLikeChecker = realtimeLikeChecker;
        this.escalationHintCopier = escalationHintCopier;
        this.interactionContextCopier = interactionContextCopier;
        this.stageRouteApplier = stageRouteApplier;
    }

    @Override
    public void recordContextCompressionMetrics(int rawChars, int finalChars, boolean compressed, int summarizedTurns) {
        contextCompressionRecorder.record(rawChars, finalChars, compressed, summarizedTurns);
    }

    @Override
    public boolean shouldSkipSemanticAnalysis(String userInput) {
        return semanticSkipChecker.test(userInput);
    }

    @Override
    public boolean isRealtimeIntent(String userInput, SemanticAnalysisResult semanticAnalysis) {
        return realtimeIntentChecker.test(userInput, semanticAnalysis);
    }

    @Override
    public boolean isRealtimeLikeInput(String userInput, SemanticAnalysisResult semanticAnalysis) {
        return realtimeLikeChecker.test(userInput, semanticAnalysis);
    }

    @Override
    public void copyEscalationHints(Map<String, Object> source, Map<String, Object> llmContext) {
        escalationHintCopier.accept(source, llmContext);
    }

    @Override
    public void copyInteractionContext(Map<String, Object> profileContext, Map<String, Object> llmContext) {
        interactionContextCopier.accept(profileContext, llmContext);
    }

    @Override
    public void applyStageLlmRoute(String stage, Map<String, Object> profileContext, Map<String, Object> llmContext) {
        stageRouteApplier.apply(stage, profileContext, llmContext);
    }
}
