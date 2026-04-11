package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillDsl;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryFacade;
import com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SkillUsageStats;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class BehaviorRoutingSupport {

    private static final Logger LOGGER = Logger.getLogger(BehaviorRoutingSupport.class.getName());
    private static final List<String> HABIT_CONTINUATION_CUES = List.of(
            "继续",
            "按之前",
            "按上次",
            "沿用",
            "还是那个",
            "同样方式",
            "按照我的习惯",
            "根据我的习惯"
    );
    private static final Pattern TOPIC_BEFORE_PLAN_PATTERN = Pattern.compile("([\\p{L}A-Za-z0-9+#._-]{2,32})\\s*(?:教学规划|学习计划|复习计划|课程规划)");
    private static final Pattern TOPIC_AFTER_VERB_PATTERN = Pattern.compile("(?:学|学习|复习|备考|课程)\\s*([\\p{L}A-Za-z0-9+#._-]{2,32})");
    private static final Pattern GOAL_PATTERN = Pattern.compile("(?:目标(?:是|为)?|想要|希望)\\s*([^，。；;\\n]+)");
    private static final Pattern DURATION_PATTERN = Pattern.compile("([0-9零一二两三四五六七八九十百千万]+)\\s*(?:周|weeks?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern WEEKLY_HOURS_PATTERN = Pattern.compile("(?:每周|一周)\\s*([0-9零一二两三四五六七八九十百千万]+)\\s*(?:小时|h|hours?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEVEL_PATTERN = Pattern.compile("(?:年级|阶段|level|级别)\\s*[:：]?\\s*([A-Za-z0-9一二三四五六七八九十高初大研Gg-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern STUDENT_ID_PATTERN = Pattern.compile("(?:学生|student)\\s*(?:id|ID)?\\s*[:：]?\\s*([A-Za-z0-9._-]+)");
    private static final Pattern WEAK_TOPICS_PATTERN = Pattern.compile("(?:薄弱点|薄弱科目|弱项)\\s*[:：]?\\s*([^，。；;\\n]+)");
    private static final Pattern STRONG_TOPICS_PATTERN = Pattern.compile("(?:优势项|擅长|强项)\\s*[:：]?\\s*([^，。；;\\n]+)");
    private static final Pattern LEARNING_STYLE_PATTERN = Pattern.compile("(?:学习风格|学习方式)\\s*[:：]?\\s*([^，。；;\\n]+)");
    private static final Pattern CONSTRAINTS_PATTERN = Pattern.compile("(?:约束|限制|不可用时段)\\s*[:：]?\\s*([^，。；;\\n]+)");
    private static final Pattern RESOURCE_PATTERN = Pattern.compile("(?:资源偏好|资源|教材偏好)\\s*[:：]?\\s*([^，。；;\\n]+)");
    private static final Pattern FILE_PATH_PATTERN = Pattern.compile("(?:路径|path|目录)\\s*[:：]?\\s*([^，。；;\\n]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TODO_DUE_DATE_PATTERN = Pattern.compile("(?:截止|due|到期|deadline)\\s*(?:时间|日期|date)?\\s*[:：]?\\s*([^，。；;\\n]+)", Pattern.CASE_INSENSITIVE);

    private final SkillDslParser skillDslParser;
    private final DispatcherMemoryFacade dispatcherMemoryFacade;
    private final boolean preferenceReuseEnabled;
    private final boolean habitRoutingEnabled;
    private final int habitRoutingMinTotalCount;
    private final double habitRoutingMinSuccessRate;
    private final int habitContinuationInputMaxLength;
    private final int habitRoutingRecentWindowSize;
    private final int habitRoutingRecentMinSuccessCount;
    private final double habitRoutingRecentMaxAgeHours;
    private final boolean behaviorLearningEnabled;
    private final int behaviorLearningWindowSize;
    private final double behaviorLearningDefaultParamThreshold;

    BehaviorRoutingSupport(SkillDslParser skillDslParser,
                           DispatcherMemoryFacade dispatcherMemoryFacade,
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
        this.skillDslParser = skillDslParser;
        this.dispatcherMemoryFacade = dispatcherMemoryFacade;
        this.preferenceReuseEnabled = preferenceReuseEnabled;
        this.habitRoutingEnabled = habitRoutingEnabled;
        this.habitRoutingMinTotalCount = habitRoutingMinTotalCount;
        this.habitRoutingMinSuccessRate = habitRoutingMinSuccessRate;
        this.habitContinuationInputMaxLength = habitContinuationInputMaxLength;
        this.habitRoutingRecentWindowSize = habitRoutingRecentWindowSize;
        this.habitRoutingRecentMinSuccessCount = habitRoutingRecentMinSuccessCount;
        this.habitRoutingRecentMaxAgeHours = habitRoutingRecentMaxAgeHours;
        this.behaviorLearningEnabled = behaviorLearningEnabled;
        this.behaviorLearningWindowSize = behaviorLearningWindowSize;
        this.behaviorLearningDefaultParamThreshold = behaviorLearningDefaultParamThreshold;
    }

    Optional<SkillDsl> detectSkillWithMemoryHabits(String userId,
                                                   String userInput,
                                                   Map<String, Object> profileContext,
                                                   Predicate<String> loopGuardBlocked) {
        if (!habitRoutingEnabled) {
            return Optional.empty();
        }
        if (userInput == null || userInput.isBlank()) {
            return Optional.empty();
        }
        if (!isContinuationIntent(normalize(userInput))) {
            return Optional.empty();
        }
        List<ProceduralMemoryEntry> history = dispatcherMemoryFacade.getSkillUsageHistory(userId);
        Optional<String> preferredSkill = preferredSkillFromHistory(history)
                .or(() -> preferredSkillFromStats(userId));
        if (preferredSkill.isEmpty()) {
            return Optional.empty();
        }
        if (!passesHabitConfidenceGate(userId, preferredSkill.get(), history)) {
            return Optional.empty();
        }
        if (loopGuardBlocked != null && loopGuardBlocked.test(preferredSkill.get())) {
            return Optional.empty();
        }
        return toSkillDslByHabit(userId, preferredSkill.get(), userInput, profileContext == null ? Map.of() : profileContext);
    }

    Optional<String> preferredSkillFromHistory(List<ProceduralMemoryEntry> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            ProceduralMemoryEntry entry = history.get(i);
            if (entry != null && entry.success() && isHabitEligibleSkill(entry.skillName())) {
                return Optional.of(entry.skillName());
            }
        }
        return Optional.empty();
    }

    Optional<String> preferredSkillFromStats(String userId) {
        return dispatcherMemoryFacade.getSkillUsageStats(userId).stream()
                .filter(stats -> isHabitEligibleSkill(stats.skillName()))
                .filter(stats -> stats.totalCount() >= habitRoutingMinTotalCount)
                .filter(stats -> stats.successCount() * 1.0 / Math.max(1, stats.totalCount()) >= habitRoutingMinSuccessRate)
                .max(Comparator.comparingLong(SkillUsageStats::successCount))
                .map(SkillUsageStats::skillName);
    }

    Map<String, Object> extractTeachingPlanPayload(String userInput) {
        Map<String, Object> payload = new LinkedHashMap<>();
        putIfPresent(payload, "studentId", extractByPattern(userInput, STUDENT_ID_PATTERN));
        putIfPresent(payload, "topic", extractTopic(userInput));
        putIfPresent(payload, "goal", extractGoal(userInput));

        Integer durationWeeks = extractFlexibleNumber(userInput, DURATION_PATTERN);
        if (durationWeeks != null && durationWeeks > 0) {
            payload.put("durationWeeks", durationWeeks);
        }

        Integer weeklyHours = extractFlexibleNumber(userInput, WEEKLY_HOURS_PATTERN);
        if (weeklyHours != null && weeklyHours > 0) {
            payload.put("weeklyHours", weeklyHours);
        }

        putIfPresent(payload, "gradeOrLevel", extractLevel(userInput));
        putListIfPresent(payload, "weakTopics", extractDelimitedValues(userInput, WEAK_TOPICS_PATTERN));
        putListIfPresent(payload, "strongTopics", extractDelimitedValues(userInput, STRONG_TOPICS_PATTERN));
        putListIfPresent(payload, "learningStyle", extractDelimitedValues(userInput, LEARNING_STYLE_PATTERN));
        putListIfPresent(payload, "constraints", extractDelimitedValues(userInput, CONSTRAINTS_PATTERN));
        putListIfPresent(payload, "resourcePreference", extractDelimitedValues(userInput, RESOURCE_PATTERN));
        return payload;
    }

    void applyBehaviorLearnedDefaults(String userId, String skillName, Map<String, Object> payload) {
        if (!behaviorLearningEnabled || userId == null || userId.isBlank() || skillName == null || payload == null) {
            return;
        }
        Map<String, String> defaults = inferDefaultParamsFromHistory(userId, skillName);
        if (defaults.isEmpty()) {
            return;
        }
        List<String> applied = new ArrayList<>();
        defaults.forEach((key, value) -> {
            if (isBlankValue(payload.get(key))) {
                payload.put(key, value);
                applied.add(key + "=" + value);
            }
        });
        if (!applied.isEmpty()) {
            LOGGER.info(() -> "behavior-learning.apply userId=" + userId + ", skill=" + skillName + ", appliedDefaults=" + applied);
        }
    }

    void maybeStoreBehaviorProfile(String userId, SkillResult result) {
        if (!behaviorLearningEnabled || result == null || !result.success()) {
            return;
        }
        String channel = normalize(result.skillName());
        if (!isHabitEligibleSkill(channel)) {
            return;
        }
        String profile = buildBehaviorProfileSummary(userId);
        if (profile.isBlank()) {
            return;
        }
        String bucket = inferMemoryBucketBySkill(channel);
        List<SemanticMemoryEntry> recent = dispatcherMemoryFacade.searchKnowledge(userId, "behavior-profile", 1, bucket);
        if (!recent.isEmpty() && profile.equals(recent.get(0).text())) {
            return;
        }
        LOGGER.info(() -> "behavior-learning.store userId=" + userId + ", bucket=" + bucket + ", profileSummary=" + capText(profile, 200));
        List<Double> embedding = List.of((double) profile.length(), Math.abs(profile.hashCode() % 1000) / 1000.0);
        dispatcherMemoryFacade.writeSemantic(userId, profile, embedding, bucket);
    }

    boolean isHabitEligibleSkill(String skillName) {
        String normalized = normalize(skillName);
        if (normalized.isBlank()) {
            return false;
        }
        if ("llm".equals(normalized) || "security.guard".equals(normalized) || "reflection".equals(normalized)) {
            return false;
        }
        return !normalized.startsWith("memory.")
                && !normalized.startsWith("semantic.")
                && !normalized.startsWith("policy.")
                && !normalized.startsWith("planner.")
                && !normalized.startsWith("reflection.")
                && !normalized.startsWith("strategy.")
                && !normalized.startsWith("autonomous.");
    }

    private boolean passesHabitConfidenceGate(String userId,
                                              String preferredSkill,
                                              List<ProceduralMemoryEntry> history) {
        if (preferredSkill == null || preferredSkill.isBlank() || history == null || history.isEmpty()) {
            return false;
        }
        if (!passesStatsThreshold(userId, preferredSkill)) {
            return false;
        }

        int scanned = 0;
        int successCount = 0;
        Instant lastSuccessAt = null;
        for (int i = history.size() - 1; i >= 0 && scanned < habitRoutingRecentWindowSize; i--) {
            ProceduralMemoryEntry entry = history.get(i);
            scanned++;
            if (!entry.success() || !preferredSkill.equals(entry.skillName())) {
                continue;
            }
            successCount++;
            if (lastSuccessAt == null || (entry.createdAt() != null && entry.createdAt().isAfter(lastSuccessAt))) {
                lastSuccessAt = entry.createdAt();
            }
        }
        if (successCount < habitRoutingRecentMinSuccessCount || lastSuccessAt == null) {
            return false;
        }

        double ageHours = Math.max(0.0, Duration.between(lastSuccessAt, Instant.now()).toMillis() / 3_600_000d);
        return ageHours <= habitRoutingRecentMaxAgeHours;
    }

    private boolean passesStatsThreshold(String userId, String skillName) {
        return dispatcherMemoryFacade.getSkillUsageStats(userId).stream()
                .filter(stats -> isHabitEligibleSkill(stats.skillName()))
                .filter(stats -> skillName.equals(stats.skillName()))
                .anyMatch(stats -> stats.totalCount() >= habitRoutingMinTotalCount
                        && stats.successCount() * 1.0 / Math.max(1, stats.totalCount()) >= habitRoutingMinSuccessRate);
    }

    private Optional<SkillDsl> toSkillDslByHabit(String userId,
                                                 String skillName,
                                                 String userInput,
                                                 Map<String, Object> profileContext) {
        if ("teaching.plan".equals(skillName)) {
            Map<String, Object> payload = extractTeachingPlanPayload(userInput);
            Optional<String> lastInput = findLastSuccessfulSkillInput(userId, skillName);
            lastInput.ifPresent(value -> mergeTeachingPlanFromHistory(payload, value));
            mergeTeachingPlanFromProfile(payload, profileContext);
            return Optional.of(new SkillDsl(skillName, payload));
        }
        if ("code.generate".equals(skillName)) {
            Map<String, Object> payload = new LinkedHashMap<>();
            String task = userInput;
            if (isContinuationOnlyInput(userInput)) {
                task = findLastSuccessfulSkillInput(userId, skillName)
                        .map(lastInput -> resolveHistoricalTask(skillName, lastInput, "task"))
                        .orElse(userInput);
            }
            task = sanitizeContinuationPrefix(task);
            payload.put("task", task);
            mergeCodeGenerateFromProfile(payload, profileContext);
            return Optional.of(new SkillDsl(skillName, payload));
        }
        if ("todo.create".equals(skillName)) {
            Map<String, Object> payload = new LinkedHashMap<>();
            String task = userInput;
            String historicalInput = null;
            if (isContinuationOnlyInput(userInput)) {
                historicalInput = findLastSuccessfulSkillInput(userId, skillName).orElse(null);
                task = historicalInput == null ? userInput : resolveHistoricalTask(skillName, historicalInput, "task");
            }
            payload.put("task", sanitizeContinuationPrefix(task));
            String dueDate = extractByPattern(userInput, TODO_DUE_DATE_PATTERN);
            if ((dueDate == null || dueDate.isBlank()) && isContinuationOnlyInput(userInput)) {
                dueDate = extractByPattern(task, TODO_DUE_DATE_PATTERN);
                if ((dueDate == null || dueDate.isBlank()) && historicalInput != null) {
                    dueDate = resolveHistoricalTask(skillName, historicalInput, "dueDate");
                }
            }
            if (dueDate != null && !dueDate.isBlank()) {
                payload.put("dueDate", dueDate);
            }
            mergeTodoCreateFromProfile(payload, profileContext);
            return Optional.of(new SkillDsl(skillName, payload));
        }
        if ("file.search".equals(skillName)) {
            Map<String, Object> payload = new LinkedHashMap<>();
            String path = extractByPattern(userInput, FILE_PATH_PATTERN);
            payload.put("path", path == null || path.isBlank() ? "./" : path.trim());
            payload.put("keyword", userInput);
            return Optional.of(new SkillDsl(skillName, payload));
        }
        if ("echo".equals(skillName) || "time".equals(skillName)) {
            return Optional.of(SkillDsl.of(skillName));
        }
        return Optional.empty();
    }

    private Optional<String> findLastSuccessfulSkillInput(String userId, String skillName) {
        List<ProceduralMemoryEntry> history = dispatcherMemoryFacade.getSkillUsageHistory(userId);
        for (int i = history.size() - 1; i >= 0; i--) {
            ProceduralMemoryEntry entry = history.get(i);
            if (entry.success()
                    && skillName.equals(entry.skillName())
                    && entry.input() != null
                    && !entry.input().isBlank()) {
                return Optional.of(entry.input());
            }
        }
        return Optional.empty();
    }

    private void mergeTeachingPlanFromHistory(Map<String, Object> payload, String historyInput) {
        if (payload.get("topic") == null) {
            putIfPresent(payload, "topic", extractTopic(historyInput));
        }
        if (payload.get("goal") == null) {
            putIfPresent(payload, "goal", extractGoal(historyInput));
        }
        if (payload.get("studentId") == null) {
            putIfPresent(payload, "studentId", extractByPattern(historyInput, STUDENT_ID_PATTERN));
        }
        if (payload.get("durationWeeks") == null) {
            Integer durationWeeks = extractFlexibleNumber(historyInput, DURATION_PATTERN);
            if (durationWeeks != null && durationWeeks > 0) {
                payload.put("durationWeeks", durationWeeks);
            }
        }
        if (payload.get("weeklyHours") == null) {
            Integer weeklyHours = extractFlexibleNumber(historyInput, WEEKLY_HOURS_PATTERN);
            if (weeklyHours != null && weeklyHours > 0) {
                payload.put("weeklyHours", weeklyHours);
            }
        }
    }

    private void mergeTeachingPlanFromProfile(Map<String, Object> payload, Map<String, Object> profileContext) {
        if (!preferenceReuseEnabled || profileContext == null || profileContext.isEmpty()) {
            return;
        }
        String role = asString(profileContext.get("role"));
        if ((payload.get("gradeOrLevel") == null || String.valueOf(payload.get("gradeOrLevel")).isBlank())
                && role != null && !role.isBlank()) {
            payload.put("gradeOrLevel", role);
        }

        String style = asString(profileContext.get("style"));
        if (!payload.containsKey("learningStyle") && style != null && !style.isBlank()) {
            payload.put("learningStyle", List.of(style));
        }

        String timezone = asString(profileContext.get("timezone"));
        if (!payload.containsKey("constraints") && timezone != null && !timezone.isBlank()) {
            payload.put("constraints", List.of("时区:" + timezone));
        }

        String language = asString(profileContext.get("language"));
        if (!payload.containsKey("resourcePreference") && language != null && !language.isBlank()) {
            payload.put("resourcePreference", List.of("语言:" + language));
        }
    }

    private void mergeCodeGenerateFromProfile(Map<String, Object> payload, Map<String, Object> profileContext) {
        if (!preferenceReuseEnabled || profileContext == null || profileContext.isEmpty()) {
            return;
        }
        String style = asString(profileContext.get("style"));
        if (style != null && !style.isBlank() && !payload.containsKey("style")) {
            payload.put("style", style);
        }
        String language = asString(profileContext.get("language"));
        if (language != null && !language.isBlank() && !payload.containsKey("language")) {
            payload.put("language", language);
        }
    }

    private void mergeTodoCreateFromProfile(Map<String, Object> payload, Map<String, Object> profileContext) {
        if (!preferenceReuseEnabled || profileContext == null || profileContext.isEmpty()) {
            return;
        }
        String timezone = asString(profileContext.get("timezone"));
        if (timezone != null && !timezone.isBlank() && !payload.containsKey("timezone")) {
            payload.put("timezone", timezone);
        }
        String style = asString(profileContext.get("style"));
        if (style != null && !style.isBlank() && !payload.containsKey("style")) {
            payload.put("style", style);
        }
    }

    String sanitizeContinuationPrefix(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceFirst("^(继续|按之前|按上次|沿用|同样方式|还是那个)[，,、 ]*", "").trim();
    }

    private String resolveHistoricalTask(String skillName, String historicalInput, String fieldName) {
        if (historicalInput == null || historicalInput.isBlank()) {
            return "";
        }
        try {
            Optional<SkillDsl> parsed = skillDslParser.parse(historicalInput);
            if (parsed.isPresent() && skillName.equals(parsed.get().skill())) {
                Object value = parsed.get().input().get(fieldName);
                if (value != null) {
                    String normalized = String.valueOf(value).trim();
                    if (!normalized.isBlank()) {
                        return normalized;
                    }
                }
            }
        } catch (SkillDslValidationException ignored) {
            // Historical inputs are often plain natural language instead of explicit SkillDSL JSON.
        }
        return historicalInput;
    }

    boolean isContinuationOnlyInput(String userInput) {
        String normalized = normalize(userInput);
        return isContinuationIntent(normalized)
                && normalized.length() <= habitContinuationInputMaxLength;
    }

    boolean isContinuationIntent(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return false;
        }
        for (String cue : HABIT_CONTINUATION_CUES) {
            int index = normalized.indexOf(cue);
            if (index >= 0 && index <= 2) {
                return true;
            }
        }
        return false;
    }

    private Map<String, String> inferDefaultParamsFromHistory(String userId, String skillName) {
        List<ProceduralMemoryEntry> history = dispatcherMemoryFacade.getSkillUsageHistory(userId);
        if (history.isEmpty()) {
            return Map.of();
        }
        String normalizedSkill = normalize(skillName);
        int scanned = 0;
        Map<String, Map<String, Integer>> keyValueCounts = new LinkedHashMap<>();
        for (int i = history.size() - 1; i >= 0 && scanned < behaviorLearningWindowSize; i--) {
            ProceduralMemoryEntry entry = history.get(i);
            if (entry == null || !entry.success() || !normalizedSkill.equals(normalize(entry.skillName()))) {
                continue;
            }
            scanned++;
            extractBehaviorParams(normalizedSkill, entry.input()).forEach((key, value) -> {
                if (key == null || key.isBlank() || value == null || value.isBlank()) {
                    return;
                }
                keyValueCounts.computeIfAbsent(key, ignored -> new LinkedHashMap<>())
                        .merge(value, 1, Integer::sum);
            });
        }
        if (scanned < 2 || keyValueCounts.isEmpty()) {
            return Map.of();
        }
        Map<String, String> defaults = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Integer>> entry : keyValueCounts.entrySet()) {
            Map.Entry<String, Integer> top = entry.getValue().entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .orElse(null);
            if (top == null) {
                continue;
            }
            double ratio = top.getValue() * 1.0 / scanned;
            if (top.getValue() >= 2 && ratio >= behaviorLearningDefaultParamThreshold) {
                defaults.put(entry.getKey(), top.getKey());
            }
        }
        if (!defaults.isEmpty()) {
            int scannedWindow = scanned;
            LOGGER.info(() -> "behavior-learning.infer userId=" + userId + ", skill=" + skillName + ", defaults=" + defaults + ", scannedWindow=" + scannedWindow);
        }
        return defaults.isEmpty() ? Map.of() : Map.copyOf(defaults);
    }

    private Map<String, String> extractBehaviorParams(String skillName, String input) {
        if (input == null || input.isBlank()) {
            return Map.of();
        }
        Map<String, String> extracted = new LinkedHashMap<>();
        Optional<SkillDsl> parsed = skillDslParser.parse(input);
        if (parsed.isPresent() && normalize(skillName).equals(normalize(parsed.get().skill()))) {
            parsed.get().input().forEach((key, value) -> {
                if (key == null || key.isBlank() || value == null) {
                    return;
                }
                String normalized = String.valueOf(value).trim();
                if (!normalized.isBlank()) {
                    extracted.put(key, capText(normalized, 48));
                }
            });
        }
        if ("todo.create".equals(normalize(skillName)) && !extracted.containsKey("dueDate")) {
            String dueDate = extractByPattern(input, TODO_DUE_DATE_PATTERN);
            if (dueDate != null && !dueDate.isBlank()) {
                extracted.put("dueDate", capText(dueDate.trim(), 40));
            }
        }
        return extracted.isEmpty() ? Map.of() : Map.copyOf(extracted);
    }

    private String buildBehaviorProfileSummary(String userId) {
        List<ProceduralMemoryEntry> history = dispatcherMemoryFacade.getSkillUsageHistory(userId);
        if (history.isEmpty()) {
            return "";
        }
        List<ProceduralMemoryEntry> window = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0 && window.size() < behaviorLearningWindowSize; i--) {
            ProceduralMemoryEntry entry = history.get(i);
            if (entry != null && entry.success() && isHabitEligibleSkill(entry.skillName())) {
                window.add(0, entry);
            }
        }
        if (window.size() < 2) {
            return "";
        }
        Map<String, Integer> intentCounts = new LinkedHashMap<>();
        for (ProceduralMemoryEntry entry : window) {
            intentCounts.merge(normalize(entry.skillName()), 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> topIntents = intentCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(3)
                .toList();
        List<String> intentParts = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : topIntents) {
            intentParts.add(entry.getKey() + "(" + entry.getValue() + ")");
        }

        Map<String, String> defaults = inferDefaultParamsFromHistory(
                userId,
                topIntents.isEmpty() ? "" : topIntents.get(0).getKey()
        );
        List<String> defaultParts = defaults.entrySet().stream()
                .limit(3)
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .toList();

        Map<String, Integer> sequenceCounts = new LinkedHashMap<>();
        for (int i = 1; i < window.size(); i++) {
            String pair = normalize(window.get(i - 1).skillName()) + "->" + normalize(window.get(i).skillName());
            sequenceCounts.merge(pair, 1, Integer::sum);
        }
        Map.Entry<String, Integer> topSequence = sequenceCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .orElse(null);

        StringBuilder summary = new StringBuilder("behavior-profile intents=");
        summary.append(String.join(";", intentParts));
        if (!defaultParts.isEmpty()) {
            summary.append(", defaults=").append(String.join(";", defaultParts));
        }
        if (topSequence != null && topSequence.getValue() > 1) {
            summary.append(", sequence=").append(topSequence.getKey()).append("(").append(topSequence.getValue()).append(")");
        }
        return capText(summary.toString(), 360);
    }

    private String inferMemoryBucketBySkill(String skillName) {
        String normalized = normalize(skillName);
        if (normalized.startsWith("todo") || normalized.contains("task")) {
            return "task";
        }
        if (normalized.contains("teach") || normalized.contains("plan")) {
            return "learning";
        }
        if (normalized.contains("eq") || normalized.contains("coach")) {
            return "eq";
        }
        if (normalized.contains("code") || normalized.contains("file")) {
            return "coding";
        }
        return "general";
    }

    private String extractTopic(String userInput) {
        Matcher beforePlanMatcher = TOPIC_BEFORE_PLAN_PATTERN.matcher(userInput);
        if (beforePlanMatcher.find()) {
            return sanitizeTopic(beforePlanMatcher.group(1));
        }

        Matcher afterVerbMatcher = TOPIC_AFTER_VERB_PATTERN.matcher(userInput);
        if (afterVerbMatcher.find()) {
            return sanitizeTopic(afterVerbMatcher.group(1));
        }
        return null;
    }

    private String sanitizeTopic(String rawTopic) {
        if (rawTopic == null || rawTopic.isBlank()) {
            return null;
        }
        return rawTopic.trim()
                .replaceFirst("^(给我一个|给我一份|给我|帮我做|帮我|请帮我|请|做个|做一份)", "")
                .trim();
    }

    private String extractGoal(String userInput) {
        Matcher matcher = GOAL_PATTERN.matcher(userInput);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private String extractByPattern(String input, Pattern pattern) {
        Matcher matcher = pattern.matcher(input);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private String extractLevel(String userInput) {
        Matcher matcher = LEVEL_PATTERN.matcher(userInput);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private Integer extractFlexibleNumber(String input, Pattern pattern) {
        Matcher matcher = pattern.matcher(input);
        if (!matcher.find()) {
            return null;
        }
        return parseFlexibleNumber(matcher.group(1));
    }

    private Integer parseFlexibleNumber(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.matches("\\d+")) {
            return Integer.parseInt(normalized);
        }
        return parseSimpleChineseNumber(normalized);
    }

    private Integer parseSimpleChineseNumber(String value) {
        String normalized = value.trim().replace('两', '二');
        while (normalized.startsWith("零")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isBlank()) {
            return 0;
        }

        Integer withWan = parseChineseUnitNumber(normalized, '万', 10_000);
        if (withWan != null) {
            return withWan;
        }
        Integer withThousands = parseChineseUnitNumber(normalized, '千', 1_000);
        if (withThousands != null) {
            return withThousands;
        }
        Integer withHundreds = parseChineseUnitNumber(normalized, '百', 100);
        if (withHundreds != null) {
            return withHundreds;
        }
        if ("十".equals(normalized)) {
            return 10;
        }
        if (normalized.endsWith("十") && normalized.length() == 2) {
            Integer tens = chineseDigit(normalized.charAt(0));
            return tens == null ? null : tens * 10;
        }
        if (normalized.startsWith("十") && normalized.length() == 2) {
            Integer ones = chineseDigit(normalized.charAt(1));
            return ones == null ? null : 10 + ones;
        }
        if (normalized.contains("十") && normalized.length() == 3) {
            Integer tens = chineseDigit(normalized.charAt(0));
            Integer ones = chineseDigit(normalized.charAt(2));
            return (tens == null || ones == null) ? null : tens * 10 + ones;
        }
        if (normalized.length() == 1) {
            return chineseDigit(normalized.charAt(0));
        }
        return null;
    }

    private Integer parseChineseUnitNumber(String normalized, char unitChar, int unitValue) {
        int unitIndex = normalized.indexOf(unitChar);
        if (unitIndex < 0) {
            return null;
        }
        String headPart = normalized.substring(0, unitIndex);
        String tailPart = normalized.substring(unitIndex + 1);

        Integer head = headPart.isBlank() ? 1 : parseSimpleChineseNumber(headPart);
        if (head == null) {
            return null;
        }
        if (tailPart.isBlank()) {
            return head * unitValue;
        }
        Integer tail = parseSimpleChineseNumber(tailPart);
        return tail == null ? null : head * unitValue + tail;
    }

    private Integer chineseDigit(char c) {
        return switch (c) {
            case '零' -> 0;
            case '一' -> 1;
            case '二' -> 2;
            case '三' -> 3;
            case '四' -> 4;
            case '五' -> 5;
            case '六' -> 6;
            case '七' -> 7;
            case '八' -> 8;
            case '九' -> 9;
            default -> null;
        };
    }

    private void putIfPresent(Map<String, Object> payload, String key, String value) {
        if (value != null && !value.isBlank()) {
            payload.put(key, value);
        }
    }

    private void putListIfPresent(Map<String, Object> payload, String key, List<String> values) {
        if (values != null && !values.isEmpty()) {
            payload.put(key, values);
        }
    }

    private List<String> extractDelimitedValues(String input, Pattern pattern) {
        String raw = extractByPattern(input, pattern);
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String[] parts = raw.split("[,，;；/、]");
        List<String> values = new ArrayList<>();
        for (String part : parts) {
            String normalized = part.trim();
            if (!normalized.isBlank()) {
                values.add(normalized);
            }
        }
        return List.copyOf(values);
    }

    private String asString(Object value) {
        if (value == null) {
            return null;
        }
        String normalized = String.valueOf(value).trim();
        return normalized.isBlank() ? null : normalized;
    }

    private boolean isBlankValue(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String stringValue) {
            return stringValue.isBlank();
        }
        if (value instanceof List<?> listValue) {
            return listValue.isEmpty();
        }
        return false;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase().replaceAll("\\s+", " ");
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
