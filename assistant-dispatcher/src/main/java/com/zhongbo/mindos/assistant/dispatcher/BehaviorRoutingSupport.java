package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillDsl;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryCommandService;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryFacade;
import com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

final class BehaviorRoutingSupport {

    private final HabitSkillSelector habitSkillSelector;
    private final BehaviorProfileRecorder behaviorProfileRecorder;

    BehaviorRoutingSupport(SkillDslParser skillDslParser,
                           DispatcherMemoryFacade dispatcherMemoryFacade,
                           DispatcherMemoryCommandService memoryCommandService,
                           boolean preferenceReuseEnabled,
                           boolean habitRoutingEnabled,
                           int habitRoutingMinTotalCount,
                           double habitRoutingMinSuccessRate,
                           int habitContinuationInputMaxLength,
                           int habitRoutingRecentWindowSize,
                           int habitRoutingRecentMinSuccessCount,
                           double habitRoutingRecentMaxAgeHours,
                           boolean behaviorLearningEnabled,
                           int behaviorLearningWindowSize,
                           double behaviorLearningDefaultParamThreshold) {
        this.habitSkillSelector = new HabitSkillSelector(
                skillDslParser,
                dispatcherMemoryFacade,
                preferenceReuseEnabled,
                habitRoutingEnabled,
                habitRoutingMinTotalCount,
                habitRoutingMinSuccessRate,
                habitContinuationInputMaxLength,
                habitRoutingRecentWindowSize,
                habitRoutingRecentMinSuccessCount,
                habitRoutingRecentMaxAgeHours
        );
        this.behaviorProfileRecorder = new BehaviorProfileRecorder(
                skillDslParser,
                dispatcherMemoryFacade,
                memoryCommandService,
                behaviorLearningEnabled,
                behaviorLearningWindowSize,
                behaviorLearningDefaultParamThreshold,
                this::isHabitEligibleSkill
        );
    }

    Optional<SkillDsl> detectSkillWithMemoryHabits(String userId,
                                                   String userInput,
                                                   Map<String, Object> profileContext,
                                                   Predicate<String> loopGuardBlocked) {
        return habitSkillSelector.detectSkillWithMemoryHabits(userId, userInput, profileContext, loopGuardBlocked);
    }

    Optional<String> preferredSkillFromHistory(List<ProceduralMemoryEntry> history) {
        return habitSkillSelector.preferredSkillFromHistory(history);
    }

    Optional<String> preferredSkillFromStats(String userId) {
        return habitSkillSelector.preferredSkillFromStats(userId);
    }

    Map<String, Object> extractTeachingPlanPayload(String userInput) {
        return habitSkillSelector.extractTeachingPlanPayload(userInput);
    }

    void applyBehaviorLearnedDefaults(String userId, String skillName, Map<String, Object> payload) {
        behaviorProfileRecorder.applyBehaviorLearnedDefaults(userId, skillName, payload);
    }

    void maybeStoreBehaviorProfile(String userId, SkillResult result) {
        behaviorProfileRecorder.maybeStoreBehaviorProfile(userId, result);
    }

    boolean isHabitEligibleSkill(String skillName) {
        return habitSkillSelector.isHabitEligibleSkill(skillName);
    }

    String sanitizeContinuationPrefix(String value) {
        return habitSkillSelector.sanitizeContinuationPrefix(value);
    }

    boolean isContinuationOnlyInput(String userInput) {
        return habitSkillSelector.isContinuationOnlyInput(userInput);
    }

    boolean isContinuationIntent(String normalized) {
        return habitSkillSelector.isContinuationIntent(normalized);
    }
}
