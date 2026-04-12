package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillDsl;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.common.dto.ExecutionTraceDto;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.dispatcher.decision.DecisionParser;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryFacade;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.DecisionOrchestrator;
import com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

final class DispatcherRoutingCompatibilitySupport {

    private final DispatcherMemoryFacade dispatcherMemoryFacade;
    private final DispatchHeuristicsSupport dispatchHeuristicsSupport;
    private final SkillRoutingSupport skillRoutingSupport;
    private final DispatchLlmSupport dispatchLlmSupport;
    private final DecisionParser decisionParser;
    private final DecisionOrchestrator decisionOrchestrator;
    private final BehaviorRoutingSupport behaviorRoutingSupport;
    private final DispatchRuleCatalog dispatchRuleCatalog;
    private final SkillCapabilityPolicy skillCapabilityPolicy;
    private final boolean llmRoutingConversationalBypassEnabled;
    private final boolean preExecuteHeavySkillLoopGuardEnabled;
    private final Set<String> preExecuteHeavySkillLoopGuardSkills;
    private final int skillGuardMaxConsecutive;
    private final int skillGuardRecentWindowSize;
    private final int skillGuardRepeatInputThreshold;
    private final long skillGuardCooldownSeconds;
    private final boolean habitExplainHintEnabled;
    private final boolean preferenceReuseEnabled;
    private final int skillPreAnalyzeConfidenceThreshold;
    private final String skillPreAnalyzeMode;
    private final int promptMaxChars;
    private final int llmDslMemoryContextMaxChars;

    DispatcherRoutingCompatibilitySupport(DispatcherMemoryFacade dispatcherMemoryFacade,
                                          DispatchHeuristicsSupport dispatchHeuristicsSupport,
                                          SkillRoutingSupport skillRoutingSupport,
                                          DispatchLlmSupport dispatchLlmSupport,
                                          DecisionParser decisionParser,
                                          DecisionOrchestrator decisionOrchestrator,
                                          BehaviorRoutingSupport behaviorRoutingSupport,
                                          DispatchRuleCatalog dispatchRuleCatalog,
                                          SkillCapabilityPolicy skillCapabilityPolicy,
                                          boolean llmRoutingConversationalBypassEnabled,
                                          boolean preExecuteHeavySkillLoopGuardEnabled,
                                          Set<String> preExecuteHeavySkillLoopGuardSkills,
                                          int skillGuardMaxConsecutive,
                                          int skillGuardRecentWindowSize,
                                          int skillGuardRepeatInputThreshold,
                                          long skillGuardCooldownSeconds,
                                          boolean habitExplainHintEnabled,
                                          boolean preferenceReuseEnabled,
                                          int skillPreAnalyzeConfidenceThreshold,
                                          String skillPreAnalyzeMode,
                                          int promptMaxChars,
                                          int llmDslMemoryContextMaxChars) {
        this.dispatcherMemoryFacade = Objects.requireNonNull(dispatcherMemoryFacade, "dispatcherMemoryFacade");
        this.dispatchHeuristicsSupport = Objects.requireNonNull(dispatchHeuristicsSupport, "dispatchHeuristicsSupport");
        this.skillRoutingSupport = Objects.requireNonNull(skillRoutingSupport, "skillRoutingSupport");
        this.dispatchLlmSupport = Objects.requireNonNull(dispatchLlmSupport, "dispatchLlmSupport");
        this.decisionParser = Objects.requireNonNull(decisionParser, "decisionParser");
        this.decisionOrchestrator = Objects.requireNonNull(decisionOrchestrator, "decisionOrchestrator");
        this.behaviorRoutingSupport = Objects.requireNonNull(behaviorRoutingSupport, "behaviorRoutingSupport");
        this.dispatchRuleCatalog = Objects.requireNonNull(dispatchRuleCatalog, "dispatchRuleCatalog");
        this.skillCapabilityPolicy = Objects.requireNonNull(skillCapabilityPolicy, "skillCapabilityPolicy");
        this.llmRoutingConversationalBypassEnabled = llmRoutingConversationalBypassEnabled;
        this.preExecuteHeavySkillLoopGuardEnabled = preExecuteHeavySkillLoopGuardEnabled;
        this.preExecuteHeavySkillLoopGuardSkills = preExecuteHeavySkillLoopGuardSkills == null ? Set.of() : Set.copyOf(preExecuteHeavySkillLoopGuardSkills);
        this.skillGuardMaxConsecutive = Math.max(1, skillGuardMaxConsecutive);
        this.skillGuardRecentWindowSize = Math.max(2, skillGuardRecentWindowSize);
        this.skillGuardRepeatInputThreshold = Math.max(2, skillGuardRepeatInputThreshold);
        this.skillGuardCooldownSeconds = Math.max(0L, skillGuardCooldownSeconds);
        this.habitExplainHintEnabled = habitExplainHintEnabled;
        this.preferenceReuseEnabled = preferenceReuseEnabled;
        this.skillPreAnalyzeConfidenceThreshold = Math.max(0, skillPreAnalyzeConfidenceThreshold);
        this.skillPreAnalyzeMode = skillPreAnalyzeMode == null ? "auto" : skillPreAnalyzeMode;
        this.promptMaxChars = Math.max(600, promptMaxChars);
        this.llmDslMemoryContextMaxChars = Math.max(160, llmDslMemoryContextMaxChars);
    }

    Optional<SkillResult> maybeBlockByCapability(String skillName) {
        if (skillName == null || skillName.isBlank() || skillCapabilityPolicy.isAllowed(skillName)) {
            return Optional.empty();
        }
        String message = "安全策略已阻止 skill 执行: " + skillName
                + "，缺少能力权限: " + skillCapabilityPolicy.missingCapabilities(skillName);
        return Optional.of(SkillResult.success("security.guard", message));
    }

    boolean isSkillPreExecuteGuardBlocked(String userId, String skillName, String userInput) {
        if (!preExecuteHeavySkillLoopGuardEnabled || !isPreExecuteHeavySkill(skillName)) {
            return false;
        }
        return isSkillLoopGuardBlocked(userId, skillName, userInput);
    }

    boolean isSkillLoopGuardBlocked(String userId, String skillName, String userInput) {
        if (skillName == null || skillName.isBlank()) {
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

    boolean isSemanticRouteLoopGuardBlocked(String userId, String skillName, String userInput) {
        if (skillName == null || skillName.isBlank()) {
            return false;
        }
        if (normalize(skillName).contains("search")) {
            return false;
        }
        List<ProceduralMemoryEntry> history = dispatcherMemoryFacade.getSkillUsageHistory(userId);
        return isRepeatedInputLoop(history, skillName, userInput);
    }

    boolean shouldRunSkillPreAnalyze(String userId, String userInput) {
        if ("never".equals(skillPreAnalyzeMode)) {
            return false;
        }
        if ("always".equals(skillPreAnalyzeMode)) {
            return true;
        }
        int confidence = skillRoutingSupport.bestSkillRoutingScore(userId, userInput);
        return confidence >= skillPreAnalyzeConfidenceThreshold;
    }

    LlmDetectionResult detectSkillWithLlm(String userId,
                                          String userInput,
                                          String memoryContext,
                                          SkillContext skillContext,
                                          Map<String, Object> profileContext) {
        String normalizedInput = normalize(userInput);
        if (llmRoutingConversationalBypassEnabled && dispatchHeuristicsSupport.isConversationalBypassInput(normalizedInput)) {
            return LlmDetectionResult.empty();
        }
        String knownSkills = skillRoutingSupport.describeSkillRoutingCandidates(userId, userInput);
        if (knownSkills.isBlank()) {
            return LlmDetectionResult.empty();
        }
        String prompt = "You are a dispatcher. Decide whether a skill is needed. "
                + "Return ONLY JSON with schema {\"intent\":\"name\",\"target\":\"skill-or-tool\",\"params\":{},\"confidence\":0.0,\"requireClarify\":false} or NONE.\n"
                + "Only choose from these candidate skills: " + capText(knownSkills, 800) + ".\n"
                + "Context:\n" + capText(dispatchLlmSupport.buildLlmDslMemoryContext(memoryContext, profileContext), llmDslMemoryContextMaxChars) + "\n"
                + "User input:\n" + capText(userInput, 400);
        prompt = capText(prompt, promptMaxChars);

        Map<String, Object> llmContext = new LinkedHashMap<>();
        llmContext.put("userId", userId);
        llmContext.put("memoryContext", dispatchLlmSupport.buildLlmDslMemoryContext(memoryContext, profileContext));
        llmContext.put("input", userInput);
        llmContext.put("routeStage", "llm-dsl");
        dispatchLlmSupport.applyStageLlmRoute("llm-dsl", profileContext, llmContext);
        List<Map<String, Object>> chatHistory = dispatcherMemoryFacade.buildChatHistory(userId);
        if (!chatHistory.isEmpty()) {
            llmContext.put("chatHistory", chatHistory);
        }
        String llmReply = dispatchLlmSupport.callLlmWithLocalEscalation(prompt, Map.copyOf(llmContext));
        if (llmReply == null || llmReply.isBlank() || "NONE".equalsIgnoreCase(llmReply.trim())) {
            return LlmDetectionResult.empty();
        }
        Optional<Decision> decision = decisionParser.parse(llmReply);
        if (decision.isPresent()) {
            DecisionOrchestrator.OrchestrationOutcome outcome = decisionOrchestrator.orchestrate(
                    decision.get(),
                    new DecisionOrchestrator.OrchestrationRequest(userId, userInput, skillContext, profileContext)
            );
            if (outcome.hasClarification()) {
                return new LlmDetectionResult(Optional.empty(), Optional.empty(), Optional.of(outcome.clarification()), Optional.ofNullable(outcome.trace()), outcome.usedFallback());
            }
            if (outcome.hasResult()) {
                if (!outcome.result().success()) {
                    return new LlmDetectionResult(Optional.empty(), Optional.empty(), Optional.empty(), Optional.ofNullable(outcome.trace()), outcome.usedFallback());
                }
                if (shouldRejectCodeGenerate(userInput, outcome.result().skillName())) {
                    return new LlmDetectionResult(Optional.empty(), Optional.empty(), Optional.empty(), Optional.ofNullable(outcome.trace()), outcome.usedFallback());
                }
                return new LlmDetectionResult(Optional.of(outcome.result()), Optional.ofNullable(outcome.skillDsl()), Optional.empty(), Optional.ofNullable(outcome.trace()), outcome.usedFallback());
            }
            if (outcome.hasSkillDsl()) {
                if (shouldRejectCodeGenerate(userInput, outcome.skillDsl().skill())) {
                    return new LlmDetectionResult(Optional.empty(), Optional.empty(), Optional.empty(), Optional.ofNullable(outcome.trace()), outcome.usedFallback());
                }
                return new LlmDetectionResult(Optional.empty(), Optional.of(outcome.skillDsl()), Optional.empty(), Optional.ofNullable(outcome.trace()), outcome.usedFallback());
            }
        }
        if (!llmReply.trim().startsWith("{")) {
            return LlmDetectionResult.empty();
        }
        return LlmDetectionResult.empty();
    }

    SkillResult enrichMemoryHabitResult(SkillResult result, String routedSkill, Map<String, Object> profileContext) {
        if (!habitExplainHintEnabled) {
            return result;
        }
        if (result == null || result.output() == null || result.output().isBlank()) {
            return result;
        }
        StringBuilder hint = new StringBuilder("[自动调度] 已按历史习惯调用 skill: ")
                .append(routedSkill);
        if (preferenceReuseEnabled && profileContext != null && !profileContext.isEmpty()) {
            hint.append("，并复用用户偏好");
        }
        String output = hint + "\n" + result.output();
        return new SkillResult(result.skillName(), output, result.success());
    }

    private boolean isPreExecuteHeavySkill(String skillName) {
        if (preExecuteHeavySkillLoopGuardSkills.isEmpty()) {
            return false;
        }
        return matchesConfiguredSkill(skillName, preExecuteHeavySkillLoopGuardSkills);
    }

    private boolean matchesConfiguredSkill(String skillName, Set<String> configuredSkills) {
        if (skillName == null || skillName.isBlank()) {
            return false;
        }
        if (configuredSkills == null || configuredSkills.isEmpty()) {
            return true;
        }
        String normalized = skillName.trim().toLowerCase(Locale.ROOT);
        for (String configured : configuredSkills) {
            if (configured == null || configured.isBlank()) {
                continue;
            }
            String candidate = configured.trim().toLowerCase(Locale.ROOT);
            if (candidate.endsWith(".*")) {
                String prefix = candidate.substring(0, candidate.length() - 1);
                if (!prefix.isBlank() && normalized.startsWith(prefix)) {
                    return true;
                }
                continue;
            }
            if (normalized.equals(candidate)) {
                return true;
            }
        }
        return false;
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
        return normalize(behaviorRoutingSupport.sanitizeContinuationPrefix(value));
    }

    private boolean shouldRejectCodeGenerate(String userInput, String skillName) {
        return "code.generate".equals(skillName)
                && !dispatchRuleCatalog.isCodeGenerationIntent(userInput)
                && !behaviorRoutingSupport.isContinuationOnlyInput(userInput);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
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
}
