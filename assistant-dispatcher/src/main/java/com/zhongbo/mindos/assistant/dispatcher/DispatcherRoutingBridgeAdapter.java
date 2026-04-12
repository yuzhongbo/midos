package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisResult;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

final class DispatcherRoutingBridgeAdapter implements DispatchRoutingPipeline.RoutingBridge {

    @FunctionalInterface
    interface SkillGuard {
        boolean test(String userId, String skillName, String userInput);
    }

    @FunctionalInterface
    interface UserInputGate {
        boolean test(String userId, String userInput);
    }

    @FunctionalInterface
    interface LlmDetector {
        LlmDetectionResult detect(String userId,
                                  String userInput,
                                  String memoryContext,
                                  SkillContext context,
                                  Map<String, Object> profileContext);
    }

    @FunctionalInterface
    interface MemoryHabitEnricher {
        SkillResult enrich(SkillResult result, String routedSkill, Map<String, Object> profileContext);
    }

    private final DispatchHeuristicsSupport heuristicsSupport;
    private final Function<String, Optional<SkillResult>> capabilityBlocker;
    private final SkillGuard preExecuteGuard;
    private final SkillGuard skillLoopGuard;
    private final SkillGuard semanticRouteLoopGuard;
    private final UserInputGate skillPreAnalyzeGate;
    private final LlmDetector llmDetector;
    private final MemoryHabitEnricher memoryHabitEnricher;

    DispatcherRoutingBridgeAdapter(DispatchHeuristicsSupport heuristicsSupport,
                                   Function<String, Optional<SkillResult>> capabilityBlocker,
                                   SkillGuard preExecuteGuard,
                                   SkillGuard skillLoopGuard,
                                   SkillGuard semanticRouteLoopGuard,
                                   UserInputGate skillPreAnalyzeGate,
                                   LlmDetector llmDetector,
                                   MemoryHabitEnricher memoryHabitEnricher) {
        this.heuristicsSupport = heuristicsSupport;
        this.capabilityBlocker = capabilityBlocker;
        this.preExecuteGuard = preExecuteGuard;
        this.skillLoopGuard = skillLoopGuard;
        this.semanticRouteLoopGuard = semanticRouteLoopGuard;
        this.skillPreAnalyzeGate = skillPreAnalyzeGate;
        this.llmDetector = llmDetector;
        this.memoryHabitEnricher = memoryHabitEnricher;
    }

    @Override
    public Optional<SkillResult> maybeBlockByCapability(String skillName) {
        return capabilityBlocker.apply(skillName);
    }

    @Override
    public boolean isSkillPreExecuteGuardBlocked(String userId, String skillName, String userInput) {
        return preExecuteGuard.test(userId, skillName, userInput);
    }

    @Override
    public boolean isSkillLoopGuardBlocked(String userId, String skillName, String userInput) {
        return skillLoopGuard.test(userId, skillName, userInput);
    }

    @Override
    public boolean isSemanticRouteLoopGuardBlocked(String userId, String skillName, String userInput) {
        return semanticRouteLoopGuard.test(userId, skillName, userInput);
    }

    @Override
    public boolean isRealtimeIntent(String userInput, SemanticAnalysisResult semanticAnalysis) {
        return heuristicsSupport.isRealtimeIntent(userInput, semanticAnalysis);
    }

    @Override
    public boolean isRealtimeLikeInput(String userInput, SemanticAnalysisResult semanticAnalysis) {
        return heuristicsSupport.isRealtimeLikeInput(userInput, semanticAnalysis);
    }

    @Override
    public boolean shouldRunSkillPreAnalyze(String userId, String userInput) {
        return skillPreAnalyzeGate.test(userId, userInput);
    }

    @Override
    public LlmDetectionResult detectSkillWithLlm(String userId,
                                                 String userInput,
                                                 String memoryContext,
                                                 SkillContext context,
                                                 Map<String, Object> profileContext) {
        return llmDetector.detect(userId, userInput, memoryContext, context, profileContext);
    }

    @Override
    public SkillResult enrichMemoryHabitResult(SkillResult result, String routedSkill, Map<String, Object> profileContext) {
        return memoryHabitEnricher.enrich(result, routedSkill, profileContext);
    }
}
