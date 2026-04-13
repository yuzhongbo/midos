package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillDsl;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryFacade;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.ParamValidator;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.memory.MemoryWriteBatch;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.memory.MemoryWriteOperation;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisResult;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

final class SemanticRoutingSupport {

    private static final double SEMANTIC_SUMMARY_MIN_CONFIDENCE = 0.60;

    private final DispatcherMemoryFacade dispatcherMemoryFacade;
    private final SemanticPayloadCompleter semanticPayloadCompleter;
    private final SemanticSkillResolver semanticSkillResolver;
    private final SemanticClarifyPolicy semanticClarifyPolicy;

    SemanticRoutingSupport(DispatcherMemoryFacade dispatcherMemoryFacade,
                           com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryCommandService memoryCommandService,
                           BehaviorRoutingSupport behaviorRoutingSupport,
                           SkillCommandAssembler skillCommandAssembler,
                           ParamValidator paramValidator,
                           Predicate<String> knownSkillNameChecker,
                           Function<String, String> memoryBucketResolver,
                           double semanticAnalysisRouteMinConfidence,
                           double semanticAnalysisClarifyMinConfidence,
                           boolean preferSuggestedSkillEnabled,
                           double preferSuggestedSkillMinConfidence) {
        this.dispatcherMemoryFacade = dispatcherMemoryFacade;
        this.semanticPayloadCompleter = new SemanticPayloadCompleter(
                dispatcherMemoryFacade,
                behaviorRoutingSupport,
                skillCommandAssembler,
                memoryBucketResolver
        );
        this.semanticSkillResolver = new SemanticSkillResolver(
                knownSkillNameChecker,
                semanticAnalysisRouteMinConfidence,
                preferSuggestedSkillEnabled,
                preferSuggestedSkillMinConfidence,
                semanticPayloadCompleter
        );
        this.semanticClarifyPolicy = new SemanticClarifyPolicy(
                paramValidator,
                behaviorRoutingSupport,
                semanticAnalysisClarifyMinConfidence
        );
    }

    Optional<SkillDsl> toSemanticSkillDsl(SemanticRoutingPlan semanticPlan) {
        if (semanticPlan == null || !semanticPlan.routable() || semanticPlan.skillName().isBlank()) {
            return Optional.empty();
        }
        Map<String, Object> payload = new java.util.LinkedHashMap<>(
                semanticPlan.effectivePayload() == null ? Map.of() : semanticPlan.effectivePayload()
        );
        return payload.isEmpty()
                ? Optional.of(SkillDsl.of(semanticPlan.skillName()))
                : Optional.of(new SkillDsl(semanticPlan.skillName(), payload));
    }

    MemoryWriteBatch maybeStoreSemanticSummary(String userId,
                                               String userInput,
                                               SemanticAnalysisResult semanticAnalysis) {
        if (semanticAnalysis == null || semanticAnalysis.summary() == null || semanticAnalysis.summary().isBlank()) {
            return MemoryWriteBatch.empty();
        }
        if (semanticSkillResolver.resolveSemanticAnalysisConfidence(semanticAnalysis) < SEMANTIC_SUMMARY_MIN_CONFIDENCE) {
            return MemoryWriteBatch.empty();
        }
        String summary = capText(semanticAnalysis.summary(), 220);
        if (summary.isBlank()) {
            return MemoryWriteBatch.empty();
        }
        String paramsDigest = semanticPayloadCompleter.summarizeSemanticParams(semanticAnalysis.payload());
        String memoryText = "semantic-summary intent="
                + capText(semanticAnalysis.intent() == null ? "" : semanticAnalysis.intent(), 48)
                + ", skill="
                + capText(semanticAnalysis.suggestedSkill() == null ? "" : semanticAnalysis.suggestedSkill(), 48)
                + ", summary="
                + summary
                + (paramsDigest.isBlank() ? "" : ", params=" + paramsDigest);
        List<Double> embedding = List.of(
                (double) memoryText.length(),
                Math.abs(memoryText.hashCode() % 1000) / 1000.0
        );
        return MemoryWriteBatch.of(new MemoryWriteOperation.WriteSemantic(
                memoryText,
                embedding,
                semanticPayloadCompleter.resolveMemoryBucket(userInput)
        ));
    }

    boolean shouldAskSemanticClarification(SemanticAnalysisResult semanticAnalysis,
                                           String input,
                                           SemanticRoutingPlan semanticPlan) {
        return semanticClarifyPolicy.shouldAskSemanticClarification(semanticAnalysis, input, semanticPlan);
    }

    List<SemanticRoutingPlan> recommendSemanticRoutingPlans(String userId,
                                                            SemanticAnalysisResult semanticAnalysis,
                                                            String originalInput) {
        SemanticSkillResolver.RecommendationInput input = new SemanticSkillResolver.RecommendationInput(
                userId,
                semanticAnalysis,
                originalInput
        );
        return semanticSkillResolver.recommend(input).stream()
                .map(signal -> new SemanticRoutingPlan(
                        signal,
                        semanticPayloadCompleter.buildEffectiveSemanticPayload(
                                userId,
                                semanticAnalysis,
                                originalInput,
                                signal.target()
                        ),
                        semanticSkillResolver.isRoutable(signal, input)
                ))
                .toList();
    }

    String buildSemanticClarifyReply(SemanticAnalysisResult semanticAnalysis,
                                     SemanticRoutingPlan semanticPlan) {
        return semanticClarifyPolicy.buildSemanticClarifyReply(semanticAnalysis, semanticPlan);
    }

    private String capText(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(0, maxChars - 14)) + "\n...[truncated]";
    }

    record SemanticRoutingPlan(DecisionSignal signal,
                               Map<String, Object> effectivePayload,
                               boolean routable) {
        static SemanticRoutingPlan empty() {
            return new SemanticRoutingPlan(new DecisionSignal("", 0.0, "semantic"), Map.of(), false);
        }

        String skillName() {
            return signal == null ? "" : signal.target();
        }

        double confidence() {
            return signal == null ? 0.0 : signal.score();
        }
    }
}
