package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillDsl;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.memory.MemoryWriteBatch;
import com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisResult;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

final class HermesDecisionPolicy {

    private final SemanticRoutingSupport semanticRoutingSupport;
    private final BehaviorRoutingSupport behaviorRoutingSupport;
    private final HermesRuntimePolicyStore runtimePolicyStore;

    HermesDecisionPolicy(SemanticRoutingSupport semanticRoutingSupport,
                         BehaviorRoutingSupport behaviorRoutingSupport) {
        this(semanticRoutingSupport, behaviorRoutingSupport, new HermesRuntimePolicyStore());
    }

    HermesDecisionPolicy(SemanticRoutingSupport semanticRoutingSupport,
                         BehaviorRoutingSupport behaviorRoutingSupport,
                         HermesRuntimePolicyStore runtimePolicyStore) {
        this.semanticRoutingSupport = semanticRoutingSupport;
        this.behaviorRoutingSupport = behaviorRoutingSupport;
        this.runtimePolicyStore = runtimePolicyStore == null ? new HermesRuntimePolicyStore() : runtimePolicyStore;
    }

    List<SemanticRoutingSupport.SemanticRoutingPlan> recommendSemanticRoutingPlans(String userId,
                                                                                   SemanticAnalysisResult semanticAnalysis,
                                                                                   String originalInput) {
        if (semanticRoutingSupport == null) {
            return List.of();
        }
        return semanticRoutingSupport.recommendSemanticRoutingPlans(userId, semanticAnalysis, originalInput);
    }

    boolean shouldAskSemanticClarification(SemanticAnalysisResult semanticAnalysis,
                                           String input,
                                           SemanticRoutingSupport.SemanticRoutingPlan semanticPlan) {
        return semanticRoutingSupport != null
                && semanticRoutingSupport.shouldAskSemanticClarification(semanticAnalysis, input, semanticPlan);
    }

    String buildSemanticClarifyReply(SemanticAnalysisResult semanticAnalysis,
                                     SemanticRoutingSupport.SemanticRoutingPlan semanticPlan) {
        if (semanticRoutingSupport == null) {
            return "";
        }
        return semanticRoutingSupport.buildSemanticClarifyReply(semanticAnalysis, semanticPlan);
    }

    Map<String, Object> completeSemanticPayload(String userId,
                                                SemanticAnalysisResult semanticAnalysis,
                                                String originalInput,
                                                String targetSkill) {
        if (semanticRoutingSupport == null) {
            return Map.of();
        }
        return semanticRoutingSupport.completeSemanticPayload(userId, semanticAnalysis, originalInput, targetSkill);
    }

    MemoryWriteBatch maybeStoreSemanticSummary(String userId,
                                               String userInput,
                                               SemanticAnalysisResult semanticAnalysis) {
        if (semanticRoutingSupport == null) {
            return MemoryWriteBatch.empty();
        }
        return semanticRoutingSupport.maybeStoreSemanticSummary(userId, userInput, semanticAnalysis);
    }

    List<DecisionSignal> recommendSkillsWithMemoryHabits(String userId,
                                                         String userInput,
                                                         Map<String, Object> profileContext,
                                                         Predicate<String> loopGuardBlocked) {
        if (behaviorRoutingSupport == null) {
            return List.of();
        }
        return behaviorRoutingSupport.recommendSkillsWithMemoryHabits(
                userId,
                userInput,
                profileContext,
                loopGuardBlocked
        );
    }

    Optional<SkillDsl> buildHabitSkillDsl(String userId,
                                          String userInput,
                                          Map<String, Object> profileContext,
                                          DecisionSignal candidate) {
        if (behaviorRoutingSupport == null) {
            return Optional.empty();
        }
        return behaviorRoutingSupport.buildHabitSkillDsl(userId, userInput, profileContext, candidate);
    }

    MemoryWriteBatch maybeStoreBehaviorProfile(String userId, SkillResult result) {
        if (behaviorRoutingSupport == null) {
            return MemoryWriteBatch.empty();
        }
        return behaviorRoutingSupport.maybeStoreBehaviorProfile(userId, result);
    }

    String sanitizeContinuationPrefix(String value) {
        if (behaviorRoutingSupport == null) {
            return value == null ? "" : value;
        }
        return behaviorRoutingSupport.sanitizeContinuationPrefix(value);
    }

    Optional<String> preferredSkillFromHistory(List<ProceduralMemoryEntry> history) {
        if (behaviorRoutingSupport == null) {
            return Optional.empty();
        }
        return behaviorRoutingSupport.preferredSkillFromHistory(history);
    }

    Optional<String> preferredSkillFromStats(String userId) {
        if (behaviorRoutingSupport == null) {
            return Optional.empty();
        }
        return behaviorRoutingSupport.preferredSkillFromStats(userId);
    }

    HermesRuntimePolicySnapshot runtimePolicySnapshot(HermesDecisionContext context) {
        if (context == null) {
            return runtimePolicyStore.effectiveSnapshot("");
        }
        return runtimePolicyStore.effectiveSnapshot(context.userId());
    }

    void deployRuntimePolicy(String userId, HermesRuntimePolicySnapshot snapshot) {
        runtimePolicyStore.deploy(userId, snapshot);
    }

    void clearRuntimePolicy(String userId) {
        runtimePolicyStore.clear(userId);
    }

    Optional<HermesRuntimePolicySnapshot> deployedRuntimePolicy(String userId) {
        return runtimePolicyStore.snapshotFor(userId);
    }

    HermesRuntimePolicyStore runtimePolicyStore() {
        return runtimePolicyStore;
    }
}
