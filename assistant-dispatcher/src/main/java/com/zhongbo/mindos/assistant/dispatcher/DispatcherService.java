package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.LlmClient;
import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillDsl;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.common.dto.ExecutionTraceDto;
import com.zhongbo.mindos.assistant.memory.MemoryManager;
import com.zhongbo.mindos.assistant.memory.model.ConversationTurn;
import com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SkillUsageStats;
import com.zhongbo.mindos.assistant.skill.SkillEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DispatcherService {

    private static final Logger LOGGER = Logger.getLogger(DispatcherService.class.getName());

    private static final int CONTEXT_HISTORY_LIMIT = 6;
    private static final int CONTEXT_KNOWLEDGE_LIMIT = 3;
    private static final int HABIT_SKILL_STATS_LIMIT = 3;
    private static final String SKILL_HELP_CHANNEL = "skills.help";
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

    private final SkillEngine skillEngine;
    private final SkillDslParser skillDslParser;
    private final MetaOrchestratorService metaOrchestratorService;
    private final MemoryManager memoryManager;
    private final LlmClient llmClient;
    private final boolean preferenceReuseEnabled;
    private final boolean habitRoutingEnabled;
    private final int habitRoutingMinTotalCount;
    private final double habitRoutingMinSuccessRate;
    private final boolean habitExplainHintEnabled;
    private final int habitContinuationInputMaxLength;

    public DispatcherService(SkillEngine skillEngine,
                             SkillDslParser skillDslParser,
                             MetaOrchestratorService metaOrchestratorService,
                             MemoryManager memoryManager,
                             LlmClient llmClient,
                             @Value("${mindos.dispatcher.preference-reuse.enabled:false}") boolean preferenceReuseEnabled,
                             @Value("${mindos.dispatcher.habit-routing.enabled:true}") boolean habitRoutingEnabled,
                             @Value("${mindos.dispatcher.habit-routing.min-total-count:2}") int habitRoutingMinTotalCount,
                             @Value("${mindos.dispatcher.habit-routing.min-success-rate:0.6}") double habitRoutingMinSuccessRate,
                             @Value("${mindos.dispatcher.habit-routing.explain-hint-enabled:true}") boolean habitExplainHintEnabled,
                             @Value("${mindos.dispatcher.habit-routing.max-continuation-input-length:16}") int habitContinuationInputMaxLength) {
        this.skillEngine = skillEngine;
        this.skillDslParser = skillDslParser;
        this.metaOrchestratorService = metaOrchestratorService;
        this.memoryManager = memoryManager;
        this.llmClient = llmClient;
        this.preferenceReuseEnabled = preferenceReuseEnabled;
        this.habitRoutingEnabled = habitRoutingEnabled;
        this.habitRoutingMinTotalCount = Math.max(1, habitRoutingMinTotalCount);
        this.habitRoutingMinSuccessRate = Math.max(0.0, Math.min(1.0, habitRoutingMinSuccessRate));
        this.habitExplainHintEnabled = habitExplainHintEnabled;
        this.habitContinuationInputMaxLength = Math.max(4, habitContinuationInputMaxLength);
    }

    public DispatchResult dispatch(String userId, String userInput) {
        return dispatch(userId, userInput, Map.of());
    }

    public DispatchResult dispatch(String userId, String userInput, Map<String, Object> profileContext) {
        return dispatchAsync(userId, userInput, profileContext).join();
    }

    public CompletableFuture<DispatchResult> dispatchAsync(String userId, String userInput) {
        return dispatchAsync(userId, userInput, Map.of());
    }

    public CompletableFuture<DispatchResult> dispatchAsync(String userId, String userInput, Map<String, Object> profileContext) {
        Instant startTime = Instant.now();
        LOGGER.info("Dispatcher input: userId=" + userId + ", input=" + clip(userInput));

        memoryManager.storeUserConversation(userId, userInput);
        maybeStoreSemanticMemory(userId, userInput);

        String memoryContext = buildMemoryContext(userId, userInput);
        SkillContext context = new SkillContext(userId, userInput, profileContext == null ? Map.of() : profileContext);
        Map<String, Object> llmContext = Map.of(
                "userId", userId,
                "memoryContext", memoryContext,
                "input", userInput,
                "profile", profileContext == null ? Map.of() : profileContext
        );

        return metaOrchestratorService.orchestrate(
                        () -> executeSinglePass(userId, userInput, context, memoryContext, llmContext),
                        () -> CompletableFuture.completedFuture(buildLlmFallbackResult(memoryContext, userInput, llmContext))
                )
                .thenApply(orchestration -> {
                    SkillResult result = orchestration.result();
                    ExecutionTraceDto trace = orchestration.trace();
                    memoryManager.storeAssistantConversation(userId, result.output());
                    maybeStoreExecutionTraceMemory(userId, trace);
                    return new DispatchResult(result.output(), result.skillName(), trace);
                })
                .whenComplete((result, error) -> {
                    long durationMs = Duration.between(startTime, Instant.now()).toMillis();
                    if (error != null) {
                        LOGGER.log(Level.SEVERE,
                                "Dispatcher error: userId=" + userId + ", durationMs=" + durationMs,
                                error);
                        return;
                    }
                    LOGGER.info("Dispatcher output: userId=" + userId
                            + ", channel=" + result.channel()
                            + ", output=" + clip(result.reply())
                            + ", durationMs=" + durationMs);
                });
    }

    private CompletableFuture<SkillResult> executeSinglePass(String userId,
                                                             String userInput,
                                                             SkillContext context,
                                                             String memoryContext,
                                                             Map<String, Object> llmContext) {
        return routeToSkillAsync(userId, userInput, context, memoryContext)
                .thenApply(optionalResult -> optionalResult.orElseGet(() ->
                        buildLlmFallbackResult(memoryContext, userInput, llmContext)));
    }

    private SkillResult buildLlmFallbackResult(String memoryContext,
                                               String userInput,
                                               Map<String, Object> llmContext) {
        return SkillResult.success("llm", llmClient.generateResponse(
                buildFallbackPrompt(memoryContext, userInput),
                llmContext
        ));
    }

    private void maybeStoreExecutionTraceMemory(String userId, ExecutionTraceDto trace) {
        if (trace == null || trace.replanCount() <= 0) {
            return;
        }
        String summary = "meta-trace strategy=" + trace.strategy()
                + ", replans=" + trace.replanCount()
                + ", critique=" + (trace.critique() == null ? "none" : trace.critique().action());
        List<Double> embedding = List.of(
                (double) summary.length(),
                Math.abs(summary.hashCode() % 1000) / 1000.0
        );
        memoryManager.storeKnowledge(userId, summary, embedding, "meta");
    }

    private CompletableFuture<Optional<SkillResult>> routeToSkillAsync(String userId,
                                                                       String userInput,
                                                                       SkillContext context,
                                                                       String memoryContext) {
        Optional<SkillDsl> explicitDsl = skillDslParser.parse(userInput);
        if (explicitDsl.isPresent()) {
            LOGGER.info("Dispatcher route=explicit-dsl, userId=" + userId + ", skill=" + explicitDsl.get().skill());
            return explicitDsl.map(dsl -> skillEngine.executeDslAsync(dsl, context).thenApply(Optional::of))
                    .orElseGet(() -> CompletableFuture.completedFuture(Optional.empty()));
        }

        Optional<SkillDsl> ruleDsl = detectSkillWithRules(userInput);
        if (ruleDsl.isPresent()) {
            LOGGER.info("Dispatcher route=rule, userId=" + userId + ", skill=" + ruleDsl.get().skill());
            return ruleDsl.map(dsl -> skillEngine.executeDslAsync(dsl, context).thenApply(Optional::of))
                    .orElseGet(() -> CompletableFuture.completedFuture(Optional.empty()));
        }

        Optional<SkillResult> metaReply = answerMetaQuestion(userInput);
        if (metaReply.isPresent()) {
            LOGGER.info("Dispatcher route=meta-help, userId=" + userId + ", channel=" + SKILL_HELP_CHANNEL);
            return CompletableFuture.completedFuture(metaReply);
        }

        return skillEngine.executeDetectedSkillAsync(context)
                .thenCompose(detectedSkill -> {
                    if (detectedSkill.isPresent()) {
                        LOGGER.info("Dispatcher route=detected-skill, userId=" + userId + ", skill=" + detectedSkill.get().skillName());
                        return CompletableFuture.completedFuture(detectedSkill);
                    }

                    Optional<SkillDsl> habitDsl = detectSkillWithMemoryHabits(userId, userInput, context.attributes());
                    if (habitDsl.isPresent()) {
                        LOGGER.info("Dispatcher route=memory-habit, userId=" + userId + ", skill=" + habitDsl.get().skill());
                        return habitDsl.map(dsl -> skillEngine.executeDslAsync(dsl, context)
                                        .thenApply(result -> Optional.of(enrichMemoryHabitResult(result, dsl.skill(), context.attributes()))))
                                .orElseGet(() -> CompletableFuture.completedFuture(Optional.empty()));
                    }

                    Optional<SkillDsl> llmDsl = detectSkillWithLlm(userId, userInput, memoryContext);
                    if (llmDsl.isPresent()) {
                        LOGGER.info("Dispatcher route=llm-dsl, userId=" + userId + ", skill=" + llmDsl.get().skill());
                        return llmDsl.map(dsl -> skillEngine.executeDslAsync(dsl, context).thenApply(Optional::of))
                                .orElseGet(() -> CompletableFuture.completedFuture(Optional.empty()));
                    }

                    LOGGER.info("Dispatcher route=llm-fallback, userId=" + userId);
                    return CompletableFuture.completedFuture(Optional.empty());
                });
    }

    private Optional<SkillDsl> detectSkillWithRules(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return Optional.empty();
        }

        String normalized = userInput.trim().toLowerCase();
        if (normalized.startsWith("echo ")) {
            Map<String, Object> payload = Map.of("text", userInput.substring("echo ".length()));
            return Optional.of(new SkillDsl("echo", payload));
        }
        if (normalized.contains("time") || normalized.contains("clock")) {
            return Optional.of(SkillDsl.of("time"));
        }
        if (normalized.startsWith("code ") || normalized.contains("generate code")) {
            Map<String, Object> payload = Map.of("task", userInput);
            return Optional.of(new SkillDsl("code.generate", payload));
        }
        if (isTeachingPlanIntent(normalized)) {
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
            return Optional.of(new SkillDsl("teaching.plan", payload));
        }
        return Optional.empty();
    }

    private Optional<SkillDsl> detectSkillWithMemoryHabits(String userId,
                                                           String userInput,
                                                           Map<String, Object> profileContext) {
        if (!habitRoutingEnabled) {
            return Optional.empty();
        }
        if (userInput == null || userInput.isBlank()) {
            return Optional.empty();
        }

        String normalized = normalize(userInput);
        boolean continuationIntent = containsAny(normalized,
                "继续",
                "按之前",
                "按上次",
                "沿用",
                "还是那个",
                "同样方式",
                "按照我的习惯",
                "根据我的习惯");

        if (!continuationIntent) {
            return Optional.empty();
        }

        Optional<String> preferredSkill = preferredSkillFromHistory(userId)
                .or(() -> preferredSkillFromStats(userId));
        if (preferredSkill.isEmpty()) {
            return Optional.empty();
        }
        return toSkillDslByHabit(userId, preferredSkill.get(), userInput, profileContext == null ? Map.of() : profileContext);
    }

    private Optional<String> preferredSkillFromHistory(String userId) {
        List<ProceduralMemoryEntry> history = memoryManager.getSkillUsageHistory(userId);
        for (int i = history.size() - 1; i >= 0; i--) {
            ProceduralMemoryEntry entry = history.get(i);
            if (entry.success() && entry.skillName() != null && !entry.skillName().isBlank()) {
                return Optional.of(entry.skillName());
            }
        }
        return Optional.empty();
    }

    private Optional<String> preferredSkillFromStats(String userId) {
        return memoryManager.getSkillUsageStats(userId).stream()
                .filter(stats -> stats.skillName() != null && !stats.skillName().isBlank())
                .filter(stats -> stats.totalCount() >= habitRoutingMinTotalCount)
                .filter(stats -> stats.successCount() * 1.0 / Math.max(1, stats.totalCount()) >= habitRoutingMinSuccessRate)
                .max(Comparator.comparingLong(SkillUsageStats::successCount))
                .map(SkillUsageStats::skillName);
    }

    private Optional<SkillDsl> toSkillDslByHabit(String userId,
                                                 String skillName,
                                                 String userInput,
                                                 Map<String, Object> profileContext) {
        if ("teaching.plan".equals(skillName)) {
            Map<String, Object> payload = extractTeachingPlanPayload(userInput);
            Optional<String> lastInput = findLastSuccessfulSkillInput(userId, skillName);
            if (lastInput.isPresent()) {
                mergeTeachingPlanFromHistory(payload, lastInput.get());
            }
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
        List<ProceduralMemoryEntry> history = memoryManager.getSkillUsageHistory(userId);
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

    private String sanitizeContinuationPrefix(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceFirst("^(继续|按之前|按上次|沿用|同样方式|还是那个)[，,、 ]*", "").trim();
    }

    private String resolveHistoricalTask(String skillName, String historicalInput, String fieldName) {
        if (historicalInput == null || historicalInput.isBlank()) {
            return "";
        }
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
        return historicalInput;
    }

    private SkillResult enrichMemoryHabitResult(SkillResult result,
                                                String routedSkill,
                                                Map<String, Object> profileContext) {
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

    private String asString(Object value) {
        if (value == null) {
            return null;
        }
        String normalized = String.valueOf(value).trim();
        return normalized.isBlank() ? null : normalized;
    }

    private boolean isContinuationOnlyInput(String userInput) {
        String normalized = normalize(userInput);
        return containsAny(normalized,
                "继续",
                "按之前",
                "按上次",
                "沿用",
                "还是那个",
                "同样方式",
                "按照我的习惯",
                "根据我的习惯")
                && normalized.length() <= habitContinuationInputMaxLength;
    }

    private Map<String, Object> extractTeachingPlanPayload(String userInput) {
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

    private boolean isTeachingPlanIntent(String normalized) {
        return containsAny(normalized,
                "教学规划",
                "学习计划",
                "复习计划",
                "课程规划",
                "学习路线",
                "study plan",
                "teaching plan");
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
        List<String> values = new java.util.ArrayList<>();
        for (String part : parts) {
            String normalized = part.trim();
            if (!normalized.isBlank()) {
                values.add(normalized);
            }
        }
        return List.copyOf(values);
    }

    private Optional<SkillDsl> detectSkillWithLlm(String userId, String userInput, String memoryContext) {
        String knownSkills = skillEngine.describeAvailableSkills();
        String prompt = "You are a dispatcher. Decide whether a skill is needed. "
                + "Return ONLY JSON with schema {\"skill\":\"name\",\"input\":{...}} or NONE.\n"
                + "Known skills: " + knownSkills + ".\n"
                + "Context:\n" + memoryContext + "\n"
                + "User input:\n" + userInput;

        String llmReply = llmClient.generateResponse(prompt, Map.of("userId", userId, "memoryContext", memoryContext));
        if (llmReply == null || llmReply.isBlank() || "NONE".equalsIgnoreCase(llmReply.trim())) {
            return Optional.empty();
        }
        return skillDslParser.parseSkillDslJson(llmReply);
    }

    private Optional<SkillResult> answerMetaQuestion(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return Optional.empty();
        }

        String normalized = normalize(userInput);
        if (isLearnableSkillsQuestion(normalized)) {
            return Optional.of(SkillResult.success(SKILL_HELP_CHANNEL, buildLearnableSkillsReply()));
        }
        if (isAvailableSkillsQuestion(normalized)) {
            return Optional.of(SkillResult.success(SKILL_HELP_CHANNEL, buildAvailableSkillsReply()));
        }
        return Optional.empty();
    }

    private boolean isAvailableSkillsQuestion(String normalized) {
        return containsAny(normalized,
                "你有哪些技能",
                "你有什么技能",
                "你会什么",
                "你能做什么",
                "你可以做什么",
                "你有什么能力",
                "支持哪些技能",
                "有哪些技能",
                "skill list",
                "list skills",
                "show skills",
                "available skills",
                "what skills do you have",
                "what can you do");
    }

    private boolean isLearnableSkillsQuestion(String normalized) {
        return containsAny(normalized,
                "可以学习哪些技能",
                "能学习哪些技能",
                "还能学习什么技能",
                "还可以学习哪些技能",
                "你能学什么",
                "你可以学什么",
                "怎么学习新技能",
                "怎么添加新技能",
                "怎么扩展技能",
                "what skills can you learn",
                "can you learn new skills",
                "how can you learn new skills",
                "add new skills",
                "learn new skills");
    }

    private boolean containsAny(String normalized, String... phrases) {
        for (String phrase : phrases) {
            if (normalized.contains(phrase)) {
                return true;
            }
        }
        return false;
    }

    private String buildAvailableSkillsReply() {
        List<String> skills = skillEngine.listAvailableSkillSummaries();
        if (skills.isEmpty()) {
            return "我现在还没有注册任何技能。你可以稍后让我重载自定义技能，或者接入 MCP / 外部 JAR 来扩展能力。";
        }

        StringBuilder reply = new StringBuilder("我当前可以直接使用这些技能：\n");
        for (String skill : skills) {
            reply.append("- ").append(skill).append('\n');
        }
        reply.append("你可以继续直接聊天，比如“现在几点了”、“echo 你好”，或者先问我“你还可以学习哪些技能？”。");
        return reply.toString();
    }

    private String buildLearnableSkillsReply() {
        return "我目前可以通过 3 种方式扩展/学习新技能：\n"
                + "1. 自定义 JSON 技能：把 .json 技能定义放到 mindos.skills.custom-dir，然后重载。\n"
                + "2. MCP 工具技能：配置 mindos.skills.mcp-servers，或运行时接入一个 MCP server。\n"
                + "3. 外部 JAR 技能：加载实现 Skill SPI 的外部 JAR。\n"
                + "如果你愿意，也可以先告诉我你想新增什么能力，我可以帮你判断更适合用哪一种方式。";
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    private String buildMemoryContext(String userId, String userInput) {
        String memoryBucket = inferMemoryBucket(userInput);
        List<ConversationTurn> recentConversation = memoryManager.getRecentConversation(userId, CONTEXT_HISTORY_LIMIT);
        List<SemanticMemoryEntry> knowledge = memoryManager.searchKnowledge(
                userId,
                userInput,
                CONTEXT_KNOWLEDGE_LIMIT,
                memoryBucket
        );
        List<SkillUsageStats> usageStats = memoryManager.getSkillUsageStats(userId).stream()
                .sorted(Comparator.comparingLong(SkillUsageStats::successCount).reversed())
                .limit(HABIT_SKILL_STATS_LIMIT)
                .toList();

        StringBuilder builder = new StringBuilder();
        builder.append("Recent conversation:\n");
        for (ConversationTurn turn : recentConversation) {
            builder.append("- ").append(turn.role()).append(": ").append(turn.content()).append('\n');
        }
        builder.append("Relevant knowledge:\n");
        for (SemanticMemoryEntry entry : knowledge) {
            builder.append("- ").append(entry.text()).append('\n');
        }
        builder.append("User skill habits:\n");
        if (usageStats.isEmpty()) {
            builder.append("- none\n");
        } else {
            for (SkillUsageStats stats : usageStats) {
                long total = Math.max(1L, stats.totalCount());
                long successRate = Math.round(stats.successCount() * 100.0 / total);
                builder.append("- ")
                        .append(stats.skillName())
                        .append(" (success=")
                        .append(stats.successCount())
                        .append("/")
                        .append(stats.totalCount())
                        .append(", rate=")
                        .append(successRate)
                        .append("%)\n");
            }
        }
        return builder.toString();
    }

    private String buildFallbackPrompt(String memoryContext, String userInput) {
        return "Answer naturally using the context when helpful.\n"
                + memoryContext + "\n"
                + "User input: " + userInput;
    }

    private void maybeStoreSemanticMemory(String userId, String input) {
        if (input == null || !input.toLowerCase().startsWith("remember ")) {
            return;
        }

        String knowledge = input.substring("remember ".length()).trim();
        String memoryBucket = inferMemoryBucket(knowledge);
        Map<String, Object> embeddingSeed = new LinkedHashMap<>();
        embeddingSeed.put("length", knowledge.length());
        embeddingSeed.put("hash", Math.abs(knowledge.hashCode() % 1000));

        List<Double> embedding = List.of(
                (double) ((Integer) embeddingSeed.get("length")),
                ((Integer) embeddingSeed.get("hash")) / 1000.0
        );
        memoryManager.storeKnowledge(userId, knowledge, embedding, memoryBucket);
    }

    private String inferMemoryBucket(String input) {
        String normalized = normalize(input);
        if (normalized.isBlank()) {
            return "general";
        }
        if (containsAny(normalized,
                "学习计划", "教学规划", "复习计划", "备考", "课程", "学科", "数学", "英语", "物理", "化学")) {
            return "learning";
        }
        if (containsAny(normalized,
                "情商", "沟通", "同事", "关系", "冲突", "安抚", "eq", "coach")) {
            return "eq";
        }
        if (containsAny(normalized,
                "待办", "todo", "截止", "任务", "清单", "优先级", "计划")) {
            return "task";
        }
        if (containsAny(normalized,
                "代码", "编译", "java", "spring", "bug", "接口", "mcp", "sdk")) {
            return "coding";
        }
        return "general";
    }

    private String clip(String value) {
        if (value == null) {
            return "null";
        }
        int max = 240;
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }
}

