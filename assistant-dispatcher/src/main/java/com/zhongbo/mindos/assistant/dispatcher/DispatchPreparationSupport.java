package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.dto.PromptMemoryContextDto;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryFacade;
import com.zhongbo.mindos.assistant.dispatcher.routing.RoutingCoordinator;
import com.zhongbo.mindos.assistant.skill.SkillEngineFacade;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisResult;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalyzer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class DispatchPreparationSupport {

    private static final int SEMANTIC_SUMMARY_MIN_CHARS = 120;
    private static final double SEMANTIC_CONTEXT_MIN_CONFIDENCE = 0.45;

    private final DispatcherMemoryFacade dispatcherMemoryFacade;
    private final SkillEngineFacade skillEngine;
    private final PersonaCoreService personaCoreService;
    private final SemanticAnalyzer semanticAnalyzer;
    private final SemanticRoutingSupport semanticRoutingSupport;
    private final IntentModelRoutingPolicy intentModelRoutingPolicy;
    private final PreparationBridge bridge;
    private final int memoryContextMaxChars;
    private final boolean realtimeIntentMemoryShrinkEnabled;
    private final boolean realtimeIntentMemoryShrinkIncludePersona;
    private final int realtimeIntentMemoryShrinkMaxChars;

    DispatchPreparationSupport(DispatcherMemoryFacade dispatcherMemoryFacade,
                               SkillEngineFacade skillEngine,
                               PersonaCoreService personaCoreService,
                               SemanticAnalyzer semanticAnalyzer,
                               SemanticRoutingSupport semanticRoutingSupport,
                               IntentModelRoutingPolicy intentModelRoutingPolicy,
                               PreparationBridge bridge,
                               int memoryContextMaxChars,
                               boolean realtimeIntentMemoryShrinkEnabled,
                               boolean realtimeIntentMemoryShrinkIncludePersona,
                               int realtimeIntentMemoryShrinkMaxChars) {
        this.dispatcherMemoryFacade = dispatcherMemoryFacade;
        this.skillEngine = skillEngine;
        this.personaCoreService = personaCoreService;
        this.semanticAnalyzer = semanticAnalyzer;
        this.semanticRoutingSupport = semanticRoutingSupport;
        this.intentModelRoutingPolicy = intentModelRoutingPolicy;
        this.bridge = bridge;
        this.memoryContextMaxChars = memoryContextMaxChars;
        this.realtimeIntentMemoryShrinkEnabled = realtimeIntentMemoryShrinkEnabled;
        this.realtimeIntentMemoryShrinkIncludePersona = realtimeIntentMemoryShrinkIncludePersona;
        this.realtimeIntentMemoryShrinkMaxChars = realtimeIntentMemoryShrinkMaxChars;
    }

    PreparedDispatch prepare(String userId,
                             String userInput,
                             Map<String, Object> profileContext,
                             RoutingCoordinator routingCoordinator) {
        Map<String, Object> resolvedProfileContext = personaCoreService.resolveProfileContext(
                userId,
                profileContext == null ? Map.of() : profileContext
        );
        List<String> availableSkillSummaries = routingCoordinator == null
                ? skillEngine.listAvailableSkillSummaries()
                : routingCoordinator.skillSummaries();
        String memoryContext = dispatcherMemoryFacade.buildMemoryContext(
                userId,
                userInput,
                memoryContextMaxChars,
                stats -> bridge.recordContextCompressionMetrics(
                        stats.rawChars(),
                        stats.finalChars(),
                        stats.compressed(),
                        stats.summarizedTurns()
                )
        );
        PromptMemoryContextDto promptMemoryContext = dispatcherMemoryFacade.buildPromptMemoryContext(
                userId,
                userInput,
                memoryContextMaxChars,
                resolvedProfileContext
        );
        SemanticAnalysisResult semanticAnalysis = bridge.shouldSkipSemanticAnalysis(userInput)
                ? SemanticAnalysisResult.empty()
                : semanticAnalyzer.analyze(
                        userId,
                        userInput,
                        memoryContext,
                        resolvedProfileContext,
                        availableSkillSummaries
                );
        semanticRoutingSupport.maybeStoreSemanticSummary(userId, userInput, semanticAnalysis);
        boolean realtimeIntentInput = bridge.isRealtimeIntent(userInput, semanticAnalysis);
        boolean realtimeLookup = realtimeIntentInput || bridge.isRealtimeLikeInput(userInput, semanticAnalysis);
        String routingInput = semanticAnalysis.routingInput(userInput);
        String effectiveMemoryContext = dispatcherMemoryFacade.enrichMemoryContextWithSemanticAnalysis(
                memoryContext,
                semanticAnalysis,
                SEMANTIC_CONTEXT_MIN_CONFIDENCE,
                memoryContextMaxChars,
                SEMANTIC_SUMMARY_MIN_CHARS
        );
        List<Map<String, Object>> chatHistory = dispatcherMemoryFacade.buildChatHistory(userId);
        SkillContext context = dispatcherMemoryFacade.buildSkillContext(
                userId,
                routingInput,
                userInput,
                resolvedProfileContext,
                memoryContext,
                chatHistory,
                semanticAnalysis
        );
        Map<String, Object> llmContext = new LinkedHashMap<>(dispatcherMemoryFacade.buildFallbackLlmContext(
                userId,
                routingInput,
                userInput,
                resolvedProfileContext,
                semanticAnalysis,
                effectiveMemoryContext,
                promptMemoryContext,
                chatHistory,
                realtimeIntentInput,
                realtimeIntentMemoryShrinkEnabled,
                realtimeIntentMemoryShrinkIncludePersona,
                realtimeIntentMemoryShrinkMaxChars,
                "llm-fallback"
        ));
        bridge.copyEscalationHints(profileContext, llmContext);
        bridge.copyEscalationHints(resolvedProfileContext, llmContext);
        bridge.copyInteractionContext(resolvedProfileContext, llmContext);
        bridge.applyStageLlmRoute("llm-fallback", resolvedProfileContext, llmContext);
        intentModelRoutingPolicy.applyForFallback(
                userInput,
                promptMemoryContext,
                realtimeIntentInput,
                resolvedProfileContext,
                llmContext
        );
        return new PreparedDispatch(
                resolvedProfileContext,
                promptMemoryContext,
                semanticAnalysis,
                realtimeIntentInput,
                realtimeLookup,
                routingInput,
                effectiveMemoryContext,
                context,
                llmContext
        );
    }

    interface PreparationBridge {
        void recordContextCompressionMetrics(int rawChars, int finalChars, boolean compressed, int summarizedTurns);

        boolean shouldSkipSemanticAnalysis(String userInput);

        boolean isRealtimeIntent(String userInput, SemanticAnalysisResult semanticAnalysis);

        boolean isRealtimeLikeInput(String userInput, SemanticAnalysisResult semanticAnalysis);

        void copyEscalationHints(Map<String, Object> source, Map<String, Object> llmContext);

        void copyInteractionContext(Map<String, Object> profileContext, Map<String, Object> llmContext);

        void applyStageLlmRoute(String stage, Map<String, Object> profileContext, Map<String, Object> llmContext);
    }

    record PreparedDispatch(
            Map<String, Object> resolvedProfileContext,
            PromptMemoryContextDto promptMemoryContext,
            SemanticAnalysisResult semanticAnalysis,
            boolean realtimeIntentInput,
            boolean realtimeLookup,
            String routingInput,
            String effectiveMemoryContext,
            SkillContext context,
            Map<String, Object> llmContext) {
    }
}
