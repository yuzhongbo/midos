package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.LlmClient;
import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillDsl;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.memory.MemoryManager;
import com.zhongbo.mindos.assistant.memory.model.ConversationTurn;
import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;
import com.zhongbo.mindos.assistant.skill.SkillEngine;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

@Service
public class DispatcherService {

    private static final Logger LOGGER = Logger.getLogger(DispatcherService.class.getName());

    private static final int CONTEXT_HISTORY_LIMIT = 6;
    private static final int CONTEXT_KNOWLEDGE_LIMIT = 3;
    private static final String SKILL_HELP_CHANNEL = "skills.help";

    private final SkillEngine skillEngine;
    private final SkillDslParser skillDslParser;
    private final MemoryManager memoryManager;
    private final LlmClient llmClient;

    public DispatcherService(SkillEngine skillEngine,
                             SkillDslParser skillDslParser,
                             MemoryManager memoryManager,
                             LlmClient llmClient) {
        this.skillEngine = skillEngine;
        this.skillDslParser = skillDslParser;
        this.memoryManager = memoryManager;
        this.llmClient = llmClient;
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

        return routeToSkillAsync(userId, userInput, context, memoryContext)
                .thenApply(optionalResult -> optionalResult
                        .orElseGet(() -> SkillResult.success("llm", llmClient.generateResponse(
                                buildFallbackPrompt(memoryContext, userInput),
                                llmContext
                        ))))
                .thenApply(result -> {
                    memoryManager.storeAssistantConversation(userId, result.output());
                    return new DispatchResult(result.output(), result.skillName());
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
        return Optional.empty();
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
        List<ConversationTurn> recentConversation = memoryManager.getRecentConversation(userId, CONTEXT_HISTORY_LIMIT);
        List<SemanticMemoryEntry> knowledge = memoryManager.searchKnowledge(userId, userInput, CONTEXT_KNOWLEDGE_LIMIT);

        StringBuilder builder = new StringBuilder();
        builder.append("Recent conversation:\n");
        for (ConversationTurn turn : recentConversation) {
            builder.append("- ").append(turn.role()).append(": ").append(turn.content()).append('\n');
        }
        builder.append("Relevant knowledge:\n");
        for (SemanticMemoryEntry entry : knowledge) {
            builder.append("- ").append(entry.text()).append('\n');
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
        Map<String, Object> embeddingSeed = new LinkedHashMap<>();
        embeddingSeed.put("length", knowledge.length());
        embeddingSeed.put("hash", Math.abs(knowledge.hashCode() % 1000));

        List<Double> embedding = List.of(
                (double) ((Integer) embeddingSeed.get("length")),
                ((Integer) embeddingSeed.get("hash")) / 1000.0
        );
        memoryManager.storeKnowledge(userId, knowledge, embedding);
    }

    private String clip(String value) {
        if (value == null) {
            return "null";
        }
        int max = 240;
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }
}

