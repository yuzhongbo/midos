package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryFacade;
import com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class HermesExecutionGuard {

    private final DispatcherMemoryFacade dispatcherMemoryFacade;
    private final SkillCapabilityPolicy skillCapabilityPolicy;
    private final BehaviorRoutingSupport behaviorRoutingSupport;
    private final int skillGuardMaxConsecutive;
    private final int skillGuardRecentWindowSize;
    private final int skillGuardRepeatInputThreshold;
    private final long skillGuardCooldownSeconds;
    private final boolean habitExplainHintEnabled;
    private final boolean preferenceReuseEnabled;

    HermesExecutionGuard(DispatcherMemoryFacade dispatcherMemoryFacade,
                         SkillCapabilityPolicy skillCapabilityPolicy,
                         BehaviorRoutingSupport behaviorRoutingSupport,
                         int skillGuardMaxConsecutive,
                         int skillGuardRecentWindowSize,
                         int skillGuardRepeatInputThreshold,
                         long skillGuardCooldownSeconds,
                         boolean habitExplainHintEnabled,
                         boolean preferenceReuseEnabled) {
        this.dispatcherMemoryFacade = dispatcherMemoryFacade;
        this.skillCapabilityPolicy = skillCapabilityPolicy;
        this.behaviorRoutingSupport = behaviorRoutingSupport;
        this.skillGuardMaxConsecutive = Math.max(1, skillGuardMaxConsecutive);
        this.skillGuardRecentWindowSize = Math.max(2, skillGuardRecentWindowSize);
        this.skillGuardRepeatInputThreshold = Math.max(2, skillGuardRepeatInputThreshold);
        this.skillGuardCooldownSeconds = Math.max(0L, skillGuardCooldownSeconds);
        this.habitExplainHintEnabled = habitExplainHintEnabled;
        this.preferenceReuseEnabled = preferenceReuseEnabled;
    }

    Optional<SkillResult> maybeBlockByCapability(String skillName) {
        if (skillName == null || skillName.isBlank() || skillCapabilityPolicy == null || skillCapabilityPolicy.isAllowed(skillName)) {
            return Optional.empty();
        }
        String message = "安全策略已阻止 skill 执行: " + skillName
                + "，缺少能力权限: " + skillCapabilityPolicy.missingCapabilities(skillName);
        return Optional.of(SkillResult.success("security.guard", message));
    }

    boolean isSkillLoopGuardBlocked(String userId, String skillName, String userInput) {
        if (dispatcherMemoryFacade == null || skillName == null || skillName.isBlank()) {
            return false;
        }
        if (normalize(skillName).contains("search")) {
            return false;
        }
        List<ProceduralMemoryEntry> history = dispatcherMemoryFacade.getSkillUsageHistory(userId);
        if (isConsecutiveSkillLoop(history, skillName)) {
            return true;
        }
        return isRepeatedInputLoop(history, skillName, userInput);
    }

    SkillResult decorateMemoryHabitResult(SkillResult result, String routedSkill, Map<String, Object> profileContext) {
        if (!habitExplainHintEnabled || result == null || result.output() == null || result.output().isBlank()) {
            return result;
        }
        StringBuilder hint = new StringBuilder("[自动调度] 已按历史习惯调用 skill: ").append(routedSkill);
        if (preferenceReuseEnabled && profileContext != null && !profileContext.isEmpty()) {
            hint.append("，并复用用户偏好");
        }
        return new SkillResult(result.skillName(), hint + "\n" + result.output(), result.success());
    }

    private boolean isConsecutiveSkillLoop(List<ProceduralMemoryEntry> history, String skillName) {
        int consecutive = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            ProceduralMemoryEntry entry = history.get(i);
            if (!entry.success() || !skillName.equals(entry.skillName())) {
                break;
            }
            consecutive++;
            if (consecutive > skillGuardMaxConsecutive) {
                return true;
            }
        }
        return false;
    }

    private boolean isRepeatedInputLoop(List<ProceduralMemoryEntry> history, String skillName, String userInput) {
        if (skillGuardCooldownSeconds <= 0L) {
            return false;
        }
        String fingerprint = loopGuardFingerprint(userInput);
        if (fingerprint.isBlank()) {
            return false;
        }
        Instant now = Instant.now();
        int scanned = 0;
        int repeatedWithinCooldown = 0;
        for (int i = history.size() - 1; i >= 0 && scanned < skillGuardRecentWindowSize; i--) {
            ProceduralMemoryEntry entry = history.get(i);
            scanned++;
            if (!entry.success() || !skillName.equals(entry.skillName())) {
                continue;
            }
            if (entry.createdAt() != null) {
                long ageSeconds = Math.max(0L, Duration.between(entry.createdAt(), now).getSeconds());
                if (ageSeconds > skillGuardCooldownSeconds) {
                    continue;
                }
            }
            if (fingerprint.equals(loopGuardFingerprint(entry.input()))) {
                repeatedWithinCooldown++;
                if (repeatedWithinCooldown >= skillGuardRepeatInputThreshold) {
                    return true;
                }
            }
        }
        return false;
    }

    private String loopGuardFingerprint(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String sanitized = behaviorRoutingSupport == null
                ? value
                : behaviorRoutingSupport.sanitizeContinuationPrefix(value);
        return normalize(sanitized);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
