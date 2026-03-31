package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.ContextCompressionMetricsReader;
import com.zhongbo.mindos.assistant.common.LlmClient;
import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillDsl;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.common.dto.ContextCompressionMetricsDto;
import com.zhongbo.mindos.assistant.common.dto.ExecutionTraceDto;
import com.zhongbo.mindos.assistant.common.dto.RoutingDecisionDto;
import com.zhongbo.mindos.assistant.memory.MemoryManager;
import com.zhongbo.mindos.assistant.memory.model.MemoryCompressionPlan;
import com.zhongbo.mindos.assistant.memory.model.MemoryStyleProfile;
import com.zhongbo.mindos.assistant.memory.model.ConversationTurn;
import com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SkillUsageStats;
import com.zhongbo.mindos.assistant.skill.SkillEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class DispatcherService implements ContextCompressionMetricsReader {

    private static final Logger LOGGER = Logger.getLogger(DispatcherService.class.getName());

    private static final int CONTEXT_HISTORY_LIMIT = 6;
    private static final int CONTEXT_KNOWLEDGE_LIMIT = 3;
    private static final int HABIT_SKILL_STATS_LIMIT = 3;
    private static final String SKILL_HELP_CHANNEL = "skills.help";
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
    private static final Pattern ROUTING_TOKEN_SPLIT_PATTERN = Pattern.compile("[^\\p{L}\\p{N}.#_-]+");
    private static final Pattern EXPLICIT_MEMORY_STORE_PATTERN = Pattern.compile(
            "^(?:remember\\s*[:：]?|please remember\\s*[:：]?|请记住\\s*[:：]?|帮我记住\\s*[:：]?|记住\\s*[:：]?|记一下\\s*[:：]?)(.+)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EXPLICIT_MEMORY_BUCKET_PATTERN = Pattern.compile(
            "^(task|learning|eq|coding|general|任务|学习|情商|沟通|代码|编程|通用)\\s*[:：]\\s*(.+)$",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> SMALL_TALK_INPUTS = Set.of(
            "hi", "hello", "hey", "thanks", "thank you", "ok", "okay", "got it", "roger",
            "你好", "您好", "嗨", "谢谢", "多谢", "收到", "好的", "好", "嗯", "嗯嗯", "晚安", "早上好"
    );

    private final SkillEngine skillEngine;
    private final SkillDslParser skillDslParser;
    private final MetaOrchestratorService metaOrchestratorService;
    private final SkillCapabilityPolicy skillCapabilityPolicy;
    private final PersonaCoreService personaCoreService;
    private final MemoryManager memoryManager;
    private final LlmClient llmClient;
    private final boolean preferenceReuseEnabled;
    private final boolean habitRoutingEnabled;
    private final int habitRoutingMinTotalCount;
    private final double habitRoutingMinSuccessRate;
    private final boolean habitExplainHintEnabled;
    private final int habitContinuationInputMaxLength;
    private final int habitRoutingRecentWindowSize;
    private final int habitRoutingRecentMinSuccessCount;
    private final double habitRoutingRecentMaxAgeHours;
    private final int promptMaxChars;
    private final int memoryContextMaxChars;
    private final int llmReplyMaxChars;
    private final int skillGuardMaxConsecutive;
    private final int skillGuardRecentWindowSize;
    private final int skillGuardRepeatInputThreshold;
    private final long skillGuardCooldownSeconds;
    private final boolean promptInjectionGuardEnabled;
    private final List<String> promptInjectionRiskTerms;
    private final String promptInjectionSafeReply;
    private final int llmRoutingShortlistMaxSkills;
    private final boolean llmRoutingConversationalBypassEnabled;
    private final int memoryContextKeepRecentTurns;
    private final int memoryContextHistorySummaryMinTurns;
    private final AtomicLong contextCompressionRequestCount = new AtomicLong();
    private final AtomicLong contextCompressionAppliedCount = new AtomicLong();
    private final AtomicLong contextCompressionInputChars = new AtomicLong();
    private final AtomicLong contextCompressionOutputChars = new AtomicLong();
    private final AtomicLong contextCompressionSummarizedTurns = new AtomicLong();

    public DispatcherService(SkillEngine skillEngine,
                             SkillDslParser skillDslParser,
                             MetaOrchestratorService metaOrchestratorService,
                             SkillCapabilityPolicy skillCapabilityPolicy,
                             PersonaCoreService personaCoreService,
                             MemoryManager memoryManager,
                             LlmClient llmClient,
                             @Value("${mindos.dispatcher.preference-reuse.enabled:false}") boolean preferenceReuseEnabled,
                             @Value("${mindos.dispatcher.habit-routing.enabled:true}") boolean habitRoutingEnabled,
                             @Value("${mindos.dispatcher.habit-routing.min-total-count:2}") int habitRoutingMinTotalCount,
                             @Value("${mindos.dispatcher.habit-routing.min-success-rate:0.6}") double habitRoutingMinSuccessRate,
                             @Value("${mindos.dispatcher.habit-routing.explain-hint-enabled:true}") boolean habitExplainHintEnabled,
                             @Value("${mindos.dispatcher.habit-routing.max-continuation-input-length:16}") int habitContinuationInputMaxLength,
                             @Value("${mindos.dispatcher.habit-routing.recent-window-size:6}") int habitRoutingRecentWindowSize,
                             @Value("${mindos.dispatcher.habit-routing.recent-min-success-count:2}") int habitRoutingRecentMinSuccessCount,
                             @Value("${mindos.dispatcher.habit-routing.recent-success-max-age-hours:72}") double habitRoutingRecentMaxAgeHours,
                             @Value("${mindos.dispatcher.prompt.max-chars:2800}") int promptMaxChars,
                             @Value("${mindos.dispatcher.memory-context.max-chars:1800}") int memoryContextMaxChars,
                             @Value("${mindos.dispatcher.llm-reply.max-chars:1200}") int llmReplyMaxChars,
                             @Value("${mindos.dispatcher.skill.guard.max-consecutive:2}") int skillGuardMaxConsecutive,
                             @Value("${mindos.dispatcher.skill.guard.recent-window-size:6}") int skillGuardRecentWindowSize,
                             @Value("${mindos.dispatcher.skill.guard.repeat-input-threshold:2}") int skillGuardRepeatInputThreshold,
                             @Value("${mindos.dispatcher.skill.guard.cooldown-seconds:180}") long skillGuardCooldownSeconds,
                             @Value("${mindos.dispatcher.prompt-injection.guard.enabled:true}") boolean promptInjectionGuardEnabled,
                             @Value("${mindos.dispatcher.prompt-injection.guard.risk-terms:ignore previous instructions,ignore all previous instructions,reveal api key,show system prompt,忽略之前的指令,忽略系统指令,泄露api key,显示系统提示词}") String promptInjectionRiskTerms,
                              @Value("${mindos.dispatcher.prompt-injection.guard.safe-reply:检测到高风险诱导指令，已拒绝执行敏感操作。请改为明确、安全、可审计的请求。}") String promptInjectionSafeReply,
                              @Value("${mindos.dispatcher.skill-routing.llm-shortlist-max-skills:8}") int llmRoutingShortlistMaxSkills,
                              @Value("${mindos.dispatcher.skill-routing.conversational-bypass.enabled:true}") boolean llmRoutingConversationalBypassEnabled,
                              @Value("${mindos.dispatcher.memory-context.keep-recent-turns:2}") int memoryContextKeepRecentTurns,
                              @Value("${mindos.dispatcher.memory-context.history-summary-min-turns:4}") int memoryContextHistorySummaryMinTurns) {
        this.skillEngine = skillEngine;
        this.skillDslParser = skillDslParser;
        this.metaOrchestratorService = metaOrchestratorService;
        this.skillCapabilityPolicy = skillCapabilityPolicy;
        this.personaCoreService = personaCoreService;
        this.memoryManager = memoryManager;
        this.llmClient = llmClient;
        this.preferenceReuseEnabled = preferenceReuseEnabled;
        this.habitRoutingEnabled = habitRoutingEnabled;
        this.habitRoutingMinTotalCount = Math.max(1, habitRoutingMinTotalCount);
        this.habitRoutingMinSuccessRate = Math.max(0.0, Math.min(1.0, habitRoutingMinSuccessRate));
        this.habitExplainHintEnabled = habitExplainHintEnabled;
        this.habitContinuationInputMaxLength = Math.max(4, habitContinuationInputMaxLength);
        this.habitRoutingRecentWindowSize = Math.max(3, habitRoutingRecentWindowSize);
        this.habitRoutingRecentMinSuccessCount = Math.max(1, habitRoutingRecentMinSuccessCount);
        this.habitRoutingRecentMaxAgeHours = Math.max(1.0, habitRoutingRecentMaxAgeHours);
        this.promptMaxChars = Math.max(600, promptMaxChars);
        this.memoryContextMaxChars = Math.max(400, memoryContextMaxChars);
        this.llmReplyMaxChars = Math.max(200, llmReplyMaxChars);
        this.skillGuardMaxConsecutive = Math.max(1, skillGuardMaxConsecutive);
        this.skillGuardRecentWindowSize = Math.max(2, skillGuardRecentWindowSize);
        this.skillGuardRepeatInputThreshold = Math.max(2, skillGuardRepeatInputThreshold);
        this.skillGuardCooldownSeconds = Math.max(0L, skillGuardCooldownSeconds);
        this.promptInjectionGuardEnabled = promptInjectionGuardEnabled;
        this.promptInjectionRiskTerms = parseRiskTerms(promptInjectionRiskTerms);
        this.promptInjectionSafeReply = promptInjectionSafeReply == null || promptInjectionSafeReply.isBlank()
                ? "检测到高风险诱导指令，已拒绝执行敏感操作。请改为明确、安全、可审计的请求。"
                : promptInjectionSafeReply;
        this.llmRoutingShortlistMaxSkills = Math.max(1, llmRoutingShortlistMaxSkills);
        this.llmRoutingConversationalBypassEnabled = llmRoutingConversationalBypassEnabled;
        this.memoryContextKeepRecentTurns = Math.max(1, memoryContextKeepRecentTurns);
        this.memoryContextHistorySummaryMinTurns = Math.max(2, memoryContextHistorySummaryMinTurns);
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
        java.util.concurrent.atomic.AtomicReference<RoutingDecisionDto> routingDecisionRef = new java.util.concurrent.atomic.AtomicReference<>();

        memoryManager.storeUserConversation(userId, userInput);
        maybeStoreSemanticMemory(userId, userInput);

        if (isPromptInjectionAttempt(userInput)) {
            LOGGER.warning("Dispatcher guard=prompt-injection, userId=" + userId + ", input=" + clip(userInput));
            memoryManager.storeAssistantConversation(userId, promptInjectionSafeReply);
            routingDecisionRef.set(new RoutingDecisionDto(
                    "security.guard",
                    "security.guard",
                    1.0,
                    List.of("prompt injection guard matched configured risky terms"),
                    List.of()
            ));
            return CompletableFuture.completedFuture(new DispatchResult(
                    promptInjectionSafeReply,
                    "security.guard",
                    new ExecutionTraceDto("single-pass", 0, null, List.of(), routingDecisionRef.get())
            ));
        }

        Map<String, Object> resolvedProfileContext = personaCoreService.resolveProfileContext(
                userId,
                profileContext == null ? Map.of() : profileContext
        );
        String memoryContext = buildMemoryContext(userId, userInput);
        List<Map<String, Object>> chatHistory = buildChatHistory(userId);
        SkillContext context = new SkillContext(userId, userInput, resolvedProfileContext);
        Map<String, Object> llmContext = new LinkedHashMap<>();
        llmContext.put("userId", userId);
        llmContext.put("memoryContext", memoryContext);
        llmContext.put("input", userInput);
        llmContext.put("routeStage", "llm-fallback");
        llmContext.put("profile", resolvedProfileContext);
        if (!chatHistory.isEmpty()) {
            llmContext.put("chatHistory", chatHistory);
        }
        String llmProvider = asString(resolvedProfileContext.get("llmProvider"));
        if (llmProvider != null) {
            llmContext.put("llmProvider", llmProvider);
        }
        String llmPreset = asString(resolvedProfileContext.get("llmPreset"));
        if (llmPreset != null) {
            llmContext.put("llmPreset", llmPreset);
        }

        return metaOrchestratorService.orchestrate(
                        () -> executeSinglePass(userId, userInput, context, memoryContext, llmContext, routingDecisionRef),
                        () -> CompletableFuture.completedFuture(buildLlmFallbackResult(memoryContext, userInput, llmContext))
                )
                .thenApply(orchestration -> {
                    SkillResult result = orchestration.result();
                    if ("llm".equals(result.skillName())) {
                        result = SkillResult.success("llm", capText(result.output(), llmReplyMaxChars));
                    }
                    ExecutionTraceDto trace = enrichTraceWithRouting(orchestration.trace(), routingDecisionRef.get());
                    memoryManager.storeAssistantConversation(userId, result.output());
                    personaCoreService.learnFromTurn(userId, resolvedProfileContext, result);
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
                                                             Map<String, Object> llmContext,
                                                             java.util.concurrent.atomic.AtomicReference<RoutingDecisionDto> routingDecisionRef) {
        return routeToSkillAsync(userId, userInput, context, memoryContext)
                .thenApply(routingOutcome -> {
                    routingDecisionRef.set(routingOutcome.routingDecision());
                    return routingOutcome.result().orElseGet(() ->
                        buildLlmFallbackResult(memoryContext, userInput, llmContext));
                });
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

    private CompletableFuture<RoutingOutcome> routeToSkillAsync(String userId,
                                                                String userInput,
                                                                SkillContext context,
                                                                String memoryContext) {
        List<String> rejectedReasons = new java.util.ArrayList<>();
        Optional<SkillDsl> explicitDsl = skillDslParser.parse(userInput);
        if (explicitDsl.isPresent()) {
            Optional<SkillResult> blocked = maybeBlockByCapability(explicitDsl.get().skill());
            if (blocked.isPresent()) {
                return CompletableFuture.completedFuture(new RoutingOutcome(blocked, new RoutingDecisionDto(
                        "security.guard",
                        explicitDsl.get().skill(),
                        1.0,
                        List.of("explicit skill DSL requested but capability guard blocked execution"),
                        List.copyOf(rejectedReasons)
                )));
            }
            LOGGER.info("Dispatcher route=explicit-dsl, userId=" + userId + ", skill=" + explicitDsl.get().skill());
            return explicitDsl.map(dsl -> skillEngine.executeDslAsync(dsl, context)
                            .thenApply(result -> new RoutingOutcome(Optional.of(result), new RoutingDecisionDto(
                                    "explicit-dsl",
                                    dsl.skill(),
                                    1.0,
                                    List.of("input parsed as explicit SkillDSL"),
                                    List.copyOf(rejectedReasons)
                            ))))
                    .orElseGet(() -> CompletableFuture.completedFuture(new RoutingOutcome(Optional.empty(), fallbackRoutingDecision(rejectedReasons))));
        }
        rejectedReasons.add("no explicit SkillDSL detected");

        Optional<SkillDsl> ruleDsl = detectSkillWithRules(userInput);
        if (ruleDsl.isPresent()) {
            Optional<SkillResult> blocked = maybeBlockByCapability(ruleDsl.get().skill());
            if (blocked.isPresent()) {
                return CompletableFuture.completedFuture(new RoutingOutcome(blocked, new RoutingDecisionDto(
                        "security.guard",
                        ruleDsl.get().skill(),
                        0.99,
                        List.of("rule-based route matched but capability guard blocked execution"),
                        List.copyOf(rejectedReasons)
                )));
            }
            LOGGER.info("Dispatcher route=rule, userId=" + userId + ", skill=" + ruleDsl.get().skill());
            return ruleDsl.map(dsl -> skillEngine.executeDslAsync(dsl, context)
                            .thenApply(result -> new RoutingOutcome(Optional.of(result), new RoutingDecisionDto(
                                    "rule",
                                    dsl.skill(),
                                    0.98,
                                    List.of("matched deterministic built-in routing rule"),
                                    List.copyOf(rejectedReasons)
                            ))))
                    .orElseGet(() -> CompletableFuture.completedFuture(new RoutingOutcome(Optional.empty(), fallbackRoutingDecision(rejectedReasons))));
        }
        rejectedReasons.add("no deterministic rule matched");

        Optional<SkillResult> metaReply = answerMetaQuestion(userInput);
        if (metaReply.isPresent()) {
            LOGGER.info("Dispatcher route=meta-help, userId=" + userId + ", channel=" + SKILL_HELP_CHANNEL);
            return CompletableFuture.completedFuture(new RoutingOutcome(metaReply, new RoutingDecisionDto(
                    "meta-help",
                    SKILL_HELP_CHANNEL,
                    0.97,
                    List.of("input matched a built-in meta help question"),
                    List.copyOf(rejectedReasons)
            )));
        }
        rejectedReasons.add("input is not a meta help question");

        return skillEngine.executeDetectedSkillAsync(context)
                .thenCompose(detectedSkill -> {
                    if (detectedSkill.isPresent()) {
                        Optional<SkillResult> blocked = maybeBlockByCapability(detectedSkill.get().skillName());
                        if (blocked.isPresent()) {
                            return CompletableFuture.completedFuture(new RoutingOutcome(blocked, new RoutingDecisionDto(
                                    "security.guard",
                                    detectedSkill.get().skillName(),
                                    0.95,
                                    List.of("auto-detected skill matched but capability guard blocked execution"),
                                    List.copyOf(rejectedReasons)
                            )));
                        }
                        if (isSkillLoopGuardBlocked(userId, detectedSkill.get().skillName(), userInput)) {
                            LOGGER.info("Dispatcher guard=loop-skip, userId=" + userId + ", skill=" + detectedSkill.get().skillName());
                            rejectedReasons.add("detected skill blocked by loop guard");
                            return CompletableFuture.completedFuture(new RoutingOutcome(Optional.empty(), fallbackRoutingDecision(rejectedReasons)));
                        }
                        LOGGER.info("Dispatcher route=detected-skill, userId=" + userId + ", skill=" + detectedSkill.get().skillName());
                        return CompletableFuture.completedFuture(new RoutingOutcome(detectedSkill, new RoutingDecisionDto(
                                "detected-skill",
                                detectedSkill.get().skillName(),
                                0.92,
                                List.of("registered skill.supports matched the input"),
                                List.copyOf(rejectedReasons)
                        )));
                    }
                    rejectedReasons.add("no registered skill.supports match");

                    Optional<SkillDsl> habitDsl = detectSkillWithMemoryHabits(userId, userInput, context.attributes());
                    if (habitDsl.isPresent()) {
                        Optional<SkillResult> blocked = maybeBlockByCapability(habitDsl.get().skill());
                        if (blocked.isPresent()) {
                            return CompletableFuture.completedFuture(new RoutingOutcome(blocked, new RoutingDecisionDto(
                                    "security.guard",
                                    habitDsl.get().skill(),
                                    0.90,
                                    List.of("habit route selected but capability guard blocked execution"),
                                    List.copyOf(rejectedReasons)
                            )));
                        }
                        LOGGER.info("Dispatcher route=memory-habit, userId=" + userId + ", skill=" + habitDsl.get().skill());
                        return habitDsl.map(dsl -> skillEngine.executeDslAsync(dsl, context)
                                        .thenApply(result -> new RoutingOutcome(
                                                Optional.of(enrichMemoryHabitResult(result, dsl.skill(), context.attributes())),
                                                new RoutingDecisionDto(
                                                        "memory-habit",
                                                        dsl.skill(),
                                                        0.88,
                                                        List.of("recent successful skill history matched continuation intent"),
                                                        List.copyOf(rejectedReasons)
                                                ))))
                                .orElseGet(() -> CompletableFuture.completedFuture(new RoutingOutcome(Optional.empty(), fallbackRoutingDecision(rejectedReasons))));
                    }
                    rejectedReasons.add("habit route confidence gate not satisfied");

                    Optional<SkillDsl> llmDsl = detectSkillWithLlm(userId, userInput, memoryContext, context.attributes());
                    if (llmDsl.isPresent()) {
                        Optional<SkillResult> blocked = maybeBlockByCapability(llmDsl.get().skill());
                        if (blocked.isPresent()) {
                            return CompletableFuture.completedFuture(new RoutingOutcome(blocked, new RoutingDecisionDto(
                                    "security.guard",
                                    llmDsl.get().skill(),
                                    0.80,
                                    List.of("LLM routing selected a skill but capability guard blocked execution"),
                                    List.copyOf(rejectedReasons)
                            )));
                        }
                        if (isSkillLoopGuardBlocked(userId, llmDsl.get().skill(), userInput)) {
                            LOGGER.info("Dispatcher guard=loop-skip, userId=" + userId + ", skill=" + llmDsl.get().skill());
                            rejectedReasons.add("LLM-routed skill blocked by loop guard");
                            return CompletableFuture.completedFuture(new RoutingOutcome(Optional.empty(), fallbackRoutingDecision(rejectedReasons)));
                        }
                        LOGGER.info("Dispatcher route=llm-dsl, userId=" + userId + ", skill=" + llmDsl.get().skill());
                        return llmDsl.map(dsl -> skillEngine.executeDslAsync(dsl, context)
                                        .thenApply(result -> new RoutingOutcome(Optional.of(result), new RoutingDecisionDto(
                                                "llm-dsl",
                                                dsl.skill(),
                                                0.76,
                                                List.of("LLM router selected one of the shortlisted candidate skills"),
                                                List.copyOf(rejectedReasons)
                                        ))))
                                .orElseGet(() -> CompletableFuture.completedFuture(new RoutingOutcome(Optional.empty(), fallbackRoutingDecision(rejectedReasons))));
                    }

                    LOGGER.info("Dispatcher route=llm-fallback, userId=" + userId);
                    rejectedReasons.add("LLM router returned NONE or no shortlist candidate was selected");
                    return CompletableFuture.completedFuture(new RoutingOutcome(Optional.empty(), fallbackRoutingDecision(rejectedReasons)));
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
        if (!isContinuationIntent(normalized)) {
            return Optional.empty();
        }

        List<ProceduralMemoryEntry> history = memoryManager.getSkillUsageHistory(userId);
        Optional<String> preferredSkill = preferredSkillFromHistory(history)
                .or(() -> preferredSkillFromStats(userId));
        if (preferredSkill.isEmpty()) {
            return Optional.empty();
        }
        if (!passesHabitConfidenceGate(userId, preferredSkill.get(), history)) {
            return Optional.empty();
        }
        if (isSkillLoopGuardBlocked(userId, preferredSkill.get(), userInput)) {
            return Optional.empty();
        }

        return toSkillDslByHabit(userId, preferredSkill.get(), userInput, profileContext == null ? Map.of() : profileContext);
    }

    private Optional<String> preferredSkillFromHistory(List<ProceduralMemoryEntry> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            ProceduralMemoryEntry entry = history.get(i);
            if (entry.success() && entry.skillName() != null && !entry.skillName().isBlank()) {
                return Optional.of(entry.skillName());
            }
        }
        return Optional.empty();
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
        if (successCount < habitRoutingRecentMinSuccessCount) {
            return false;
        }
        if (lastSuccessAt == null) {
            return false;
        }

        double ageHours = Math.max(0.0, Duration.between(lastSuccessAt, Instant.now()).toMillis() / 3_600_000d);
        return ageHours <= habitRoutingRecentMaxAgeHours;
    }

    private boolean passesStatsThreshold(String userId, String skillName) {
        return memoryManager.getSkillUsageStats(userId).stream()
                .filter(stats -> skillName.equals(stats.skillName()))
                .anyMatch(stats -> stats.totalCount() >= habitRoutingMinTotalCount
                        && stats.successCount() * 1.0 / Math.max(1, stats.totalCount()) >= habitRoutingMinSuccessRate);
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
        return isContinuationIntent(normalized)
                && normalized.length() <= habitContinuationInputMaxLength;
    }

    private boolean isContinuationIntent(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return false;
        }
        for (String cue : HABIT_CONTINUATION_CUES) {
            int index = normalized.indexOf(cue);
            if (index < 0) {
                continue;
            }
            if (index <= 2) {
                return true;
            }
        }
        return false;
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

    private Optional<SkillDsl> detectSkillWithLlm(String userId,
                                                  String userInput,
                                                  String memoryContext,
                                                  Map<String, Object> profileContext) {
        String normalizedInput = normalize(userInput);
        if (llmRoutingConversationalBypassEnabled && isConversationalBypassInput(normalizedInput)) {
            return Optional.empty();
        }
        String knownSkills = describeSkillRoutingCandidates(userId, userInput);
        if (knownSkills.isBlank()) {
            return Optional.empty();
        }
        String prompt = "You are a dispatcher. Decide whether a skill is needed. "
                + "Return ONLY JSON with schema {\"skill\":\"name\",\"input\":{...}} or NONE.\n"
                + "Only choose from these candidate skills: " + capText(knownSkills, 800) + ".\n"
                + "Context:\n" + capText(memoryContext, memoryContextMaxChars) + "\n"
                + "User input:\n" + capText(userInput, 400);
        prompt = capText(prompt, promptMaxChars);

        Map<String, Object> llmContext = new LinkedHashMap<>();
        llmContext.put("userId", userId);
        llmContext.put("memoryContext", memoryContext);
        llmContext.put("input", userInput);
        llmContext.put("routeStage", "llm-dsl");
        List<Map<String, Object>> chatHistory = buildChatHistory(userId);
        if (!chatHistory.isEmpty()) {
            llmContext.put("chatHistory", chatHistory);
        }
        String llmProvider = profileContext == null ? null : asString(profileContext.get("llmProvider"));
        if (llmProvider != null) {
            llmContext.put("llmProvider", llmProvider);
        }
        String llmPreset = profileContext == null ? null : asString(profileContext.get("llmPreset"));
        if (llmPreset != null) {
            llmContext.put("llmPreset", llmPreset);
        }
        String llmReply = llmClient.generateResponse(prompt, Map.copyOf(llmContext));
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
        List<SemanticMemoryEntry> conversationRollups = memoryManager.searchKnowledge(
                userId,
                userInput,
                1,
                "conversation-rollup"
        );
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

        int historyBudget = Math.max(160, (int) (memoryContextMaxChars * 0.5));
        int knowledgeBudget = Math.max(120, (int) (memoryContextMaxChars * 0.3));
        int habitsBudget = Math.max(80, memoryContextMaxChars - historyBudget - knowledgeBudget);

        String rawConversationContext = buildRawConversationContext(recentConversation);
        String compressedConversationContext = buildConversationContext(userId, recentConversation, conversationRollups);
        String rawKnowledgeContext = buildKnowledgeContext(knowledge);
        String rawHabitContext = buildHabitContext(usageStats);

        StringBuilder builder = new StringBuilder();
        appendContextSection(builder, "Recent conversation", compressedConversationContext, historyBudget);
        appendContextSection(builder, "Relevant knowledge", rawKnowledgeContext, knowledgeBudget);
        appendContextSection(builder, "User skill habits", rawHabitContext, habitsBudget);
        String finalContext = capText(builder.toString(), memoryContextMaxChars);
        recordContextCompressionMetrics(
                rawConversationContext.length(),
                compressedConversationContext.length(),
                compressedConversationContext.length() < rawConversationContext.length() || !conversationRollups.isEmpty(),
                Math.max(0, recentConversation.size() - memoryContextKeepRecentTurns)
        );
        return finalContext;
    }

    private List<Map<String, Object>> buildChatHistory(String userId) {
        List<ConversationTurn> recentConversation = memoryManager.getRecentConversation(userId, CONTEXT_HISTORY_LIMIT);
        if (recentConversation == null || recentConversation.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> history = new java.util.ArrayList<>();
        for (ConversationTurn turn : recentConversation) {
            if (turn == null || turn.content() == null || turn.content().isBlank()) {
                continue;
            }
            String role = turn.role() == null || turn.role().isBlank() ? "assistant" : turn.role();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", role);
            item.put("content", turn.content());
            if (turn.createdAt() != null) {
                item.put("createdAt", turn.createdAt().toString());
            }
            history.add(item);
        }
        return List.copyOf(history);
    }

    private String buildFallbackPrompt(String memoryContext, String userInput) {
        String prompt = "Answer naturally using the context when helpful.\n"
                + capText(memoryContext, memoryContextMaxChars) + "\n"
                + "User input: " + capText(userInput, 400);
        return capText(prompt, promptMaxChars);
    }

    private boolean isSkillLoopGuardBlocked(String userId, String skillName, String userInput) {
        if (skillName == null || skillName.isBlank()) {
            return false;
        }
        List<ProceduralMemoryEntry> history = memoryManager.getSkillUsageHistory(userId);
        if (isConsecutiveSkillLoop(history, skillName)) {
            return true;
        }
        return isRepeatedInputLoop(history, skillName, userInput);
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
        return normalize(sanitizeContinuationPrefix(value));
    }

    private Optional<SkillResult> maybeBlockByCapability(String skillName) {
        if (skillName == null || skillName.isBlank() || skillCapabilityPolicy.isAllowed(skillName)) {
            return Optional.empty();
        }
        String message = "安全策略已阻止 skill 执行: " + skillName
                + "，缺少能力权限: " + skillCapabilityPolicy.missingCapabilities(skillName);
        LOGGER.warning("Dispatcher guard=capability-deny, skill=" + skillName
                + ", missing=" + skillCapabilityPolicy.missingCapabilities(skillName));
        return Optional.of(SkillResult.success("security.guard", message));
    }

    private boolean isPromptInjectionAttempt(String userInput) {
        if (!promptInjectionGuardEnabled || userInput == null || userInput.isBlank()) {
            return false;
        }
        String normalized = normalize(userInput).toLowerCase(Locale.ROOT);
        for (String term : promptInjectionRiskTerms) {
            if (normalized.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private List<String> parseRiskTerms(String rawTerms) {
        if (rawTerms == null || rawTerms.isBlank()) {
            return List.of();
        }
        return Arrays.stream(rawTerms.split(","))
                .map(value -> value == null ? "" : value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isBlank())
                .toList();
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

    private void maybeStoreSemanticMemory(String userId, String input) {
        String knowledge = extractRememberedKnowledge(input);
        if (knowledge == null || knowledge.isBlank()) {
            return;
        }

        String explicitBucket = extractExplicitMemoryBucket(knowledge);
        if (explicitBucket != null) {
            knowledge = stripExplicitMemoryBucket(knowledge);
        }
        if (knowledge.isBlank()) {
            return;
        }
        String memoryBucket = explicitBucket == null ? inferMemoryBucket(knowledge) : explicitBucket;
        Map<String, Object> embeddingSeed = new LinkedHashMap<>();
        embeddingSeed.put("length", knowledge.length());
        embeddingSeed.put("hash", Math.abs(knowledge.hashCode() % 1000));

        List<Double> embedding = List.of(
                (double) ((Integer) embeddingSeed.get("length")),
                ((Integer) embeddingSeed.get("hash")) / 1000.0
        );
        memoryManager.storeKnowledge(userId, knowledge, embedding, memoryBucket);
    }

    private String buildConversationContext(String userId,
                                            List<ConversationTurn> recentConversation,
                                            List<SemanticMemoryEntry> conversationRollups) {
        if (recentConversation.isEmpty()) {
            return buildConversationRollupPrefix(conversationRollups) + "- none\n";
        }
        int keepRecent = Math.min(memoryContextKeepRecentTurns, recentConversation.size());
        int splitIndex = Math.max(0, recentConversation.size() - keepRecent);
        List<ConversationTurn> olderTurns = recentConversation.size() >= memoryContextHistorySummaryMinTurns
                ? recentConversation.subList(0, splitIndex)
                : List.of();
        List<ConversationTurn> preservedTurns = recentConversation.subList(splitIndex, recentConversation.size());

        StringBuilder builder = new StringBuilder(buildConversationRollupPrefix(conversationRollups));
        String olderSummary = summarizeOlderConversation(userId, olderTurns);
        if (!olderSummary.isBlank()) {
            builder.append("- earlier summary: ").append(olderSummary).append('\n');
        }
        for (ConversationTurn turn : preservedTurns) {
            builder.append("- ").append(turn.role()).append(": ").append(turn.content()).append('\n');
        }
        return builder.toString();
    }

    private String buildConversationRollupPrefix(List<SemanticMemoryEntry> conversationRollups) {
        if (conversationRollups == null || conversationRollups.isEmpty()) {
            return "";
        }
        return conversationRollups.stream()
                .map(SemanticMemoryEntry::text)
                .filter(text -> text != null && !text.isBlank())
                .findFirst()
                .map(text -> "- persisted rollup: " + text + '\n')
                .orElse("");
    }

    private String buildRawConversationContext(List<ConversationTurn> recentConversation) {
        if (recentConversation.isEmpty()) {
            return "- none\n";
        }
        StringBuilder builder = new StringBuilder();
        for (ConversationTurn turn : recentConversation) {
            builder.append("- ").append(turn.role()).append(": ").append(turn.content()).append('\n');
        }
        return builder.toString();
    }

    private String summarizeOlderConversation(String userId, List<ConversationTurn> olderTurns) {
        if (olderTurns == null || olderTurns.isEmpty()) {
            return "";
        }
        String source = olderTurns.stream()
                .map(turn -> turn.role() + ": " + turn.content())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        if (source.isBlank()) {
            return "";
        }
        MemoryCompressionPlan plan = memoryManager.buildMemoryCompressionPlan(
                userId,
                source,
                new MemoryStyleProfile("concise", "direct", "bullet"),
                "review"
        );
        return plan.steps().stream()
                .filter(step -> "BRIEF".equals(step.stage()))
                .map(step -> step.content().replace('\n', ' '))
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElse("");
    }

    private String buildKnowledgeContext(List<SemanticMemoryEntry> knowledge) {
        if (knowledge.isEmpty()) {
            return "- none\n";
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (SemanticMemoryEntry entry : knowledge) {
            if (entry != null && entry.text() != null && !entry.text().isBlank()) {
                unique.add(entry.text());
            }
        }
        if (unique.isEmpty()) {
            return "- none\n";
        }
        StringBuilder builder = new StringBuilder();
        for (String text : unique) {
            builder.append("- ").append(text).append('\n');
        }
        return builder.toString();
    }

    private String buildHabitContext(List<SkillUsageStats> usageStats) {
        if (usageStats.isEmpty()) {
            return "- none\n";
        }
        StringBuilder builder = new StringBuilder();
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
        return builder.toString();
    }

    private void appendContextSection(StringBuilder builder, String title, String content, int budget) {
        builder.append(title).append(":\n");
        builder.append(capText(content == null || content.isBlank() ? "- none\n" : content, budget));
        if (builder.length() == 0 || builder.charAt(builder.length() - 1) != '\n') {
            builder.append('\n');
        }
    }

    private void recordContextCompressionMetrics(int rawChars,
                                                 int finalChars,
                                                 boolean compressed,
                                                 int summarizedTurns) {
        long input = Math.max(0, rawChars);
        long output = Math.max(0, finalChars);
        contextCompressionRequestCount.incrementAndGet();
        contextCompressionInputChars.addAndGet(input);
        contextCompressionOutputChars.addAndGet(output);
        if (compressed) {
            contextCompressionAppliedCount.incrementAndGet();
        }
        contextCompressionSummarizedTurns.addAndGet(Math.max(0, summarizedTurns));
    }

    @Override
    public ContextCompressionMetricsDto snapshotContextCompressionMetrics() {
        long requests = contextCompressionRequestCount.get();
        long inputChars = contextCompressionInputChars.get();
        long outputChars = contextCompressionOutputChars.get();
        double ratio = inputChars <= 0 ? 0.0 : (double) outputChars / inputChars;
        return new ContextCompressionMetricsDto(
                requests,
                contextCompressionAppliedCount.get(),
                inputChars,
                outputChars,
                ratio,
                contextCompressionSummarizedTurns.get()
        );
    }

    private String describeSkillRoutingCandidates(String userId, String userInput) {
        List<String> summaries = skillEngine.listAvailableSkillSummaries();
        if (summaries.isEmpty()) {
            return "";
        }
        Set<String> inputTokens = routingTokens(userInput);
        String memoryBucket = inferMemoryBucket(userInput);
        Optional<String> preferredFromStats = preferredSkillFromStats(userId);
        Optional<String> preferredFromHistory = preferredSkillFromHistory(memoryManager.getSkillUsageHistory(userId));

        List<SkillRoutingCandidate> rankedCandidates = summaries.stream()
                .map(summary -> new SkillRoutingCandidate(summary, skillRoutingScore(
                        summary,
                        normalize(userInput),
                        inputTokens,
                        memoryBucket,
                        preferredFromStats,
                        preferredFromHistory)))
                .sorted(Comparator.comparingInt(SkillRoutingCandidate::score).reversed()
                        .thenComparing(SkillRoutingCandidate::summary))
                .toList();

        List<String> shortlisted = rankedCandidates.stream()
                .filter(candidate -> candidate.score() > 0)
                .limit(llmRoutingShortlistMaxSkills)
                .map(SkillRoutingCandidate::summary)
                .toList();
        if (shortlisted.isEmpty()) {
            shortlisted = rankedCandidates.stream()
                    .limit(llmRoutingShortlistMaxSkills)
                    .map(SkillRoutingCandidate::summary)
                    .toList();
        }

        return shortlisted.stream()
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    private int skillRoutingScore(String summary,
                                  String normalizedInput,
                                  Set<String> inputTokens,
                                  String memoryBucket,
                                  Optional<String> preferredFromStats,
                                  Optional<String> preferredFromHistory) {
        String skillName = summary;
        String description = "";
        int separator = summary.indexOf(" - ");
        if (separator >= 0) {
            skillName = summary.substring(0, separator).trim();
            description = summary.substring(separator + 3).trim();
        }
        String normalizedSkillName = normalize(skillName);
        int score = 0;
        if (!normalizedSkillName.isBlank() && normalizedInput.contains(normalizedSkillName)) {
            score += 80;
        }
        Set<String> skillTokens = routingTokens(skillName + " " + description);
        for (String token : inputTokens) {
            if (skillTokens.contains(token)) {
                score += 12;
            }
        }
        if (preferredFromStats.filter(normalizedSkillName::equals).isPresent()) {
            score += 30;
        }
        if (preferredFromHistory.filter(normalizedSkillName::equals).isPresent()) {
            score += 20;
        }
        score += bucketRoutingBoost(memoryBucket, normalizedSkillName);
        return score;
    }

    private int bucketRoutingBoost(String memoryBucket, String skillName) {
        return switch (memoryBucket) {
            case "learning" -> "teaching.plan".equals(skillName) ? 80 : 0;
            case "eq" -> "eq.coach".equals(skillName) ? 80 : 0;
            case "task" -> "todo.create".equals(skillName) ? 60 : 0;
            case "coding" -> {
                if ("code.generate".equals(skillName)) {
                    yield 80;
                }
                yield "file.search".equals(skillName) ? 40 : 0;
            }
            default -> 0;
        };
    }

    private Set<String> routingTokens(String text) {
        String normalized = normalize(text);
        if (normalized.isBlank()) {
            return Set.of();
        }
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        String[] parts = ROUTING_TOKEN_SPLIT_PATTERN.split(normalized, -1);
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (part.length() == 1 && !containsHan(part)) {
                continue;
            }
            tokens.add(part);
        }
        return tokens.isEmpty() ? Set.of() : Set.copyOf(tokens);
    }

    private boolean isConversationalBypassInput(String normalizedInput) {
        if (normalizedInput == null || normalizedInput.isBlank()) {
            return true;
        }
        if (SMALL_TALK_INPUTS.contains(normalizedInput)) {
            return true;
        }
        return normalizedInput.length() <= 12
                && (normalizedInput.startsWith("谢谢")
                || normalizedInput.startsWith("收到")
                || normalizedInput.startsWith("好的")
                || normalizedInput.startsWith("hello")
                || normalizedInput.startsWith("thanks"));
    }

    private String extractRememberedKnowledge(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        Matcher matcher = EXPLICIT_MEMORY_STORE_PATTERN.matcher(input.trim());
        if (!matcher.matches()) {
            return null;
        }
        String knowledge = matcher.group(1);
        return knowledge == null ? null : knowledge.trim();
    }

    private String extractExplicitMemoryBucket(String knowledge) {
        if (knowledge == null || knowledge.isBlank()) {
            return null;
        }
        Matcher matcher = EXPLICIT_MEMORY_BUCKET_PATTERN.matcher(knowledge.trim());
        if (!matcher.matches()) {
            return null;
        }
        return normalizeExplicitMemoryBucket(matcher.group(1));
    }

    private String stripExplicitMemoryBucket(String knowledge) {
        Matcher matcher = EXPLICIT_MEMORY_BUCKET_PATTERN.matcher(knowledge.trim());
        if (!matcher.matches()) {
            return knowledge.trim();
        }
        return matcher.group(2) == null ? "" : matcher.group(2).trim();
    }

    private String normalizeExplicitMemoryBucket(String bucket) {
        if (bucket == null || bucket.isBlank()) {
            return null;
        }
        return switch (bucket.trim().toLowerCase(Locale.ROOT)) {
            case "task", "任务" -> "task";
            case "learning", "学习" -> "learning";
            case "eq", "情商", "沟通" -> "eq";
            case "coding", "代码", "编程" -> "coding";
            default -> "general";
        };
    }

    private boolean containsHan(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return value.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }

    private RoutingDecisionDto fallbackRoutingDecision(List<String> rejectedReasons) {
        return new RoutingDecisionDto(
                "llm-fallback",
                "llm",
                0.0,
                List.of("no safe skill route satisfied the current request"),
                rejectedReasons == null ? List.of() : List.copyOf(rejectedReasons)
        );
    }

    private ExecutionTraceDto enrichTraceWithRouting(ExecutionTraceDto trace, RoutingDecisionDto routingDecision) {
        if (trace == null) {
            return new ExecutionTraceDto("single-pass", 0, null, List.of(), routingDecision);
        }
        return new ExecutionTraceDto(
                trace.strategy(),
                trace.replanCount(),
                trace.critique(),
                trace.steps(),
                routingDecision
        );
    }

    private record SkillRoutingCandidate(String summary, int score) {
    }

    private record RoutingOutcome(Optional<SkillResult> result, RoutingDecisionDto routingDecision) {
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
