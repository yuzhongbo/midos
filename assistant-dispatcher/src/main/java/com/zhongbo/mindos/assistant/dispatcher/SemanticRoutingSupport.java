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
    private static final String INTENT_SUMMARY_MARKER = "[意图摘要]";

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
        String memoryText = buildHumanizedSemanticSummary(semanticAnalysis, summary, paramsDigest);
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

    private String buildHumanizedSemanticSummary(SemanticAnalysisResult semanticAnalysis,
                                                 String summary,
                                                 String paramsDigest) {
        StringBuilder builder = new StringBuilder(INTENT_SUMMARY_MARKER)
                .append(' ')
                .append("用户当前想要：")
                .append(summary);
        String intent = capText(semanticAnalysis.intent() == null ? "" : semanticAnalysis.intent(), 48);
        if (!intent.isBlank() && !summary.contains(intent)) {
            builder.append("；意图类型：").append(intent);
        }
        String scope = humanizedContextScope(semanticAnalysis.contextScope());
        if (!scope.isBlank()) {
            builder.append("；上下文：").append(scope);
        }
        if (semanticAnalysis.toolRequired()) {
            String skill = capText(semanticAnalysis.suggestedSkill() == null ? "" : semanticAnalysis.suggestedSkill(), 48);
            if (!skill.isBlank()) {
                builder.append("；可用执行方式：").append(skill);
            }
        }
        String memoryOperation = humanizedMemoryOperation(semanticAnalysis.memoryOperation());
        if (!memoryOperation.isBlank()) {
            builder.append("；记忆动作：").append(memoryOperation);
        }
        if (!paramsDigest.isBlank()) {
            builder.append("；已确认信息：").append(paramsDigest);
        }
        return capText(builder.toString(), 280);
    }

    private String humanizedContextScope(String contextScope) {
        if (contextScope == null || contextScope.isBlank()) {
            return "";
        }
        return switch (contextScope) {
            case "continuation" -> "延续上一个任务";
            case "realtime" -> "需要最新信息";
            case "memory" -> "围绕历史内容";
            case "standalone" -> "当前独立请求";
            default -> "";
        };
    }

    private String humanizedMemoryOperation(String memoryOperation) {
        if (memoryOperation == null || memoryOperation.isBlank() || "none".equals(memoryOperation)) {
            return "";
        }
        return switch (memoryOperation) {
            case "recall" -> "回顾历史";
            case "write" -> "记录新信息";
            case "suppress" -> "暂停记忆";
            case "resume" -> "恢复记忆";
            default -> "";
        };
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

    Map<String, Object> completeSemanticPayload(String userId,
                                                SemanticAnalysisResult semanticAnalysis,
                                                String originalInput,
                                                String targetSkill) {
        if (semanticAnalysis == null || targetSkill == null || targetSkill.isBlank()) {
            return Map.of();
        }
        return semanticPayloadCompleter.buildEffectiveSemanticPayload(
                userId,
                semanticAnalysis,
                originalInput,
                targetSkill
        );
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
