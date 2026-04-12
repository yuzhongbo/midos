package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.ImDegradedReplyMarker;
import com.zhongbo.mindos.assistant.common.LlmClient;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.common.dto.PromptMemoryContextDto;
import com.zhongbo.mindos.assistant.common.dto.RetrievedMemoryItemDto;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Logger;

final class DispatchLlmSupport {

    private static final Logger LOGGER = Logger.getLogger(DispatcherService.class.getName());
    private static final Set<String> NEWS_DOMAIN_WHITELIST_TERMS = Set.of(
            "新闻", "热点", "快讯", "发布", "政策", "监管", "部委", "国务院", "央行",
            "科技", "ai", "芯片", "大模型", "算力", "机器人", "云计算",
            "财经", "金融", "经济", "市场", "产业", "融资", "并购", "上市", "财报", "投资", "a股", "港股", "美股"
    );
    private static final Set<String> WEATHER_DOMAIN_PENALTY_TERMS = Set.of(
            "天气", "天气预报", "气温", "降雨", "湿度", "空气质量", "风力", "台风",
            "accuweather", "weather.com", "weathernews", "中国气象局", "全国天气网"
    );

    private final LlmClient llmClient;
    private final PromptBuilder promptBuilder;
    private final LLMDecisionEngine llmDecisionEngine;
    private final DispatchHeuristicsSupport heuristicsSupport;
    private final PromptConfig promptConfig;
    private final StageRouteConfig stageRouteConfig;
    private final SkillFinalizeConfig skillFinalizeConfig;
    private final EscalationConfig escalationConfig;
    private final Metrics metrics;

    DispatchLlmSupport(LlmClient llmClient,
                       PromptBuilder promptBuilder,
                       LLMDecisionEngine llmDecisionEngine,
                       DispatchHeuristicsSupport heuristicsSupport,
                       PromptConfig promptConfig,
                       StageRouteConfig stageRouteConfig,
                       SkillFinalizeConfig skillFinalizeConfig,
                       EscalationConfig escalationConfig,
                       Metrics metrics) {
        this.llmClient = llmClient;
        this.promptBuilder = promptBuilder;
        this.llmDecisionEngine = llmDecisionEngine;
        this.heuristicsSupport = heuristicsSupport;
        this.promptConfig = promptConfig;
        this.stageRouteConfig = stageRouteConfig;
        this.skillFinalizeConfig = skillFinalizeConfig;
        this.escalationConfig = escalationConfig;
        this.metrics = metrics;
    }

    SkillResult buildFallbackResult(String memoryContext,
                                    PromptMemoryContextDto promptMemoryContext,
                                    String userInput,
                                    Map<String, Object> llmContext,
                                    boolean realtimeIntentInput) {
        QueryContext queryContext = buildQueryContext(llmContext, userInput, promptMemoryContext);
        boolean realtimeLookup = realtimeIntentInput || heuristicsSupport.isRealtimeLikeInput(userInput);
        try {
            LOGGER.info(() -> "dispatcher.llm.debug userQuery=" + clip(userInput)
                    + ", explicit=" + queryContext.explicitLlmRequest()
                    + ", complex=" + queryContext.complexReasoningRequired()
                    + ", realtimeLookup=" + realtimeLookup
                    + ", memoryDebug=" + promptMemoryDebugSummary(promptMemoryContext)
            );
        } catch (Exception e) {
            // best-effort debug logging
        }
        if (!realtimeLookup && !llmDecisionEngine.shouldCallLLM(queryContext)) {
            return buildMemoryDirectResult(promptMemoryContext, userInput);
        }
        return SkillResult.success("llm", callLlmWithLocalEscalation(
                buildFallbackPrompt(memoryContext, promptMemoryContext, userInput, realtimeIntentInput),
                llmContext
        ));
    }

    SkillResult buildLlmFallbackStreamResult(String memoryContext,
                                             PromptMemoryContextDto promptMemoryContext,
                                             String userInput,
                                             Map<String, Object> llmContext,
                                             boolean realtimeIntentInput,
                                             Consumer<String> deltaConsumer) {
        QueryContext queryContext = buildQueryContext(llmContext, userInput, promptMemoryContext);
        boolean realtimeLookup = realtimeIntentInput || heuristicsSupport.isRealtimeLikeInput(userInput);
        try {
            LOGGER.info(() -> "dispatcher.llm.stream.debug userQuery=" + clip(userInput)
                    + ", explicit=" + queryContext.explicitLlmRequest()
                    + ", complex=" + queryContext.complexReasoningRequired()
                    + ", realtimeLookup=" + realtimeLookup
                    + ", memoryDebug=" + promptMemoryDebugSummary(promptMemoryContext)
            );
        } catch (Exception e) {
            // best-effort debug logging
        }
        if (!realtimeLookup && !llmDecisionEngine.shouldCallLLM(queryContext)) {
            SkillResult result = buildMemoryDirectResult(promptMemoryContext, userInput);
            if (deltaConsumer != null) {
                deltaConsumer.accept(result.output());
            }
            return result;
        }
        String prompt = buildFallbackPrompt(memoryContext, promptMemoryContext, userInput, realtimeIntentInput);
        StringBuilder aggregated = new StringBuilder();
        llmClient.streamResponse(prompt, llmContext, chunk -> {
            if (chunk == null || chunk.isBlank()) {
                return;
            }
            aggregated.append(chunk);
            if (deltaConsumer != null) {
                deltaConsumer.accept(chunk);
            }
        });
        String output = aggregated.toString().trim();
        if (output.isBlank()) {
            output = callLlmWithLocalEscalation(prompt, llmContext);
        }
        return SkillResult.success("llm", output);
    }

    SkillFinalizeOutcome maybeFinalizeSkillResultWithLlm(String userInput,
                                                         SkillResult result,
                                                         Map<String, Object> llmContext) {
        if (!skillFinalizeConfig.enabled() || result == null || !result.success()) {
            return SkillFinalizeOutcome.notApplied(result);
        }
        String channel = result.skillName();
        if (channel == null || channel.isBlank() || "llm".equals(channel) || "security.guard".equals(channel)) {
            return SkillFinalizeOutcome.notApplied(result);
        }
        if (!matchesConfiguredSkill(channel, skillFinalizeConfig.skills())) {
            return SkillFinalizeOutcome.notApplied(result);
        }
        String rawOutput = result.output() == null ? "" : result.output();
        if (rawOutput.isBlank()) {
            return SkillFinalizeOutcome.notApplied(result);
        }

        String prompt = buildSkillFinalizePrompt(userInput, channel, rawOutput);
        Map<String, Object> finalizeContext = new LinkedHashMap<>(llmContext == null ? Map.of() : llmContext);
        finalizeContext.put("routeStage", "skill-postprocess");
        finalizeContext.put("skillChannel", channel);
        logMcpPostprocessTrace(channel, rawOutput);
        if (stageRouteConfig.skillFinalizeWithLlmProvider() != null) {
            finalizeContext.put("llmProvider", stageRouteConfig.skillFinalizeWithLlmProvider());
        }
        if (stageRouteConfig.skillFinalizeWithLlmPreset() != null) {
            finalizeContext.put("llmPreset", stageRouteConfig.skillFinalizeWithLlmPreset());
        }
        applyStageLlmRoute("skill-postprocess", null, finalizeContext);
        String optimized = callLlmWithLocalEscalation(prompt, finalizeContext);
        if (optimized == null || optimized.isBlank()) {
            return SkillFinalizeOutcome.notApplied(result);
        }
        if (optimized.startsWith("[LLM ")) {
            return SkillFinalizeOutcome.notApplied(result);
        }
        String finalizedOutput = optimized.trim();
        if (isNewsSearchFinalizeChannel(channel)) {
            finalizedOutput = ensureNewsBriefShape(finalizedOutput, rawOutput);
        }
        return SkillFinalizeOutcome.applied(SkillResult.success(channel, capText(finalizedOutput, skillFinalizeConfig.maxOutputChars())));
    }

    String buildLlmDslMemoryContext(String memoryContext, Map<String, Object> profileContext) {
        StringBuilder builder = new StringBuilder();
        String semanticIntent = asString(profileContext == null ? null : profileContext.get("semanticIntent"));
        String semanticRewritten = asString(profileContext == null ? null : profileContext.get("semanticRewrittenInput"));
        if (semanticIntent != null || semanticRewritten != null) {
            builder.append("Semantic hint:\n");
            if (semanticIntent != null) {
                builder.append("- intent: ").append(semanticIntent).append('\n');
            }
            if (semanticRewritten != null) {
                builder.append("- rewrittenInput: ").append(semanticRewritten).append('\n');
            }
        }
        List<Map<String, Object>> history = extractRecentChatHistory(profileContext, 2);
        if (!history.isEmpty()) {
            builder.append("Recent chat turns:\n");
            for (Map<String, Object> turn : history) {
                String role = asString(turn.get("role"));
                String content = asString(turn.get("content"));
                if (content == null) {
                    continue;
                }
                builder.append("- ").append(role == null ? "assistant" : role).append(": ")
                        .append(capText(content, 140)).append('\n');
            }
        }
        if (builder.length() == 0) {
            return capText(memoryContext == null ? "" : memoryContext, promptConfig.llmDslMemoryContextMaxChars());
        }
        if (memoryContext != null && !memoryContext.isBlank()) {
            builder.append("Memory summary:\n")
                    .append(capText(memoryContext, Math.max(120, promptConfig.llmDslMemoryContextMaxChars() / 2)));
        }
        return capText(builder.toString(), promptConfig.llmDslMemoryContextMaxChars());
    }

    void applyStageLlmRoute(String stage,
                            Map<String, Object> profileContext,
                            Map<String, Object> llmContext) {
        if (llmContext == null) {
            return;
        }
        String profileProvider = profileContext == null ? null : asString(profileContext.get("llmProvider"));
        String profilePreset = profileContext == null ? null : asString(profileContext.get("llmPreset"));
        String profileModel = profileContext == null ? null : asString(profileContext.get("llmModel"));

        String provider = profileProvider;
        String preset = profilePreset;
        String model = profileModel;
        if ("llm-dsl".equals(stage)) {
            if (provider == null) {
                provider = stageRouteConfig.llmDslProvider();
            }
            if (preset == null) {
                preset = stageRouteConfig.llmDslPreset();
            }
            if (model == null) {
                model = stageRouteConfig.llmDslModel();
            }
        } else if ("llm-fallback".equals(stage)) {
            if (provider == null) {
                provider = stageRouteConfig.llmFallbackProvider();
            }
            if (preset == null) {
                preset = stageRouteConfig.llmFallbackPreset();
            }
            if (model == null) {
                model = stageRouteConfig.llmFallbackModel();
            }
        } else if ("skill-postprocess".equals(stage) && model == null) {
            model = stageRouteConfig.skillFinalizeWithLlmModel();
        }
        if (provider != null) {
            llmContext.put("llmProvider", provider);
        }
        if (preset != null) {
            llmContext.put("llmPreset", preset);
        }
        if (model != null) {
            llmContext.put("model", model);
        }
        if ("llm-dsl".equals(stage) && stageRouteConfig.llmDslMaxTokens() > 0) {
            llmContext.put("maxTokens", stageRouteConfig.llmDslMaxTokens());
        }
        if ("llm-fallback".equals(stage) && stageRouteConfig.llmFallbackMaxTokens() > 0) {
            llmContext.put("maxTokens", stageRouteConfig.llmFallbackMaxTokens());
        }
        if ("skill-postprocess".equals(stage) && stageRouteConfig.skillFinalizeMaxTokens() > 0) {
            llmContext.put("maxTokens", stageRouteConfig.skillFinalizeMaxTokens());
        }
    }

    String callLlmWithLocalEscalation(String prompt, Map<String, Object> llmContext) {
        boolean localPrimary = isLocalProviderContext(llmContext);
        if (localPrimary) {
            metrics.localEscalationAttemptCount().incrementAndGet();
        }
        String resourceGuardReason = detectResourceGuardEscalationReason(llmContext);
        if (resourceGuardReason != null) {
            metrics.fallbackChainAttemptCount().incrementAndGet();
            incrementEscalationReason(resourceGuardReason);
            Map<String, Object> escalatedContext = new LinkedHashMap<>(llmContext == null ? Map.of() : llmContext);
            String cloudProvider = resolveEscalationProvider(escalatedContext);
            if (cloudProvider == null) {
                return llmClient.generateResponse(prompt, llmContext);
            }
            escalatedContext.put("llmProvider", cloudProvider);
            if (escalationConfig.cloudPreset() != null) {
                escalatedContext.put("llmPreset", escalationConfig.cloudPreset());
            }
            if (escalationConfig.cloudModel() != null) {
                escalatedContext.put("model", escalationConfig.cloudModel());
            }
            escalatedContext.put("localEscalationReason", resourceGuardReason);
            LOGGER.info("Dispatcher route=llm-local-escalation, from=local, to=" + cloudProvider
                    + ", stage=" + asString(escalatedContext.get("routeStage"))
                    + ", reason=" + resourceGuardReason);
            String escalatedReply = llmClient.generateResponse(prompt, escalatedContext);
            if (isSuccessfulLlmReply(escalatedReply)) {
                metrics.fallbackChainHitCount().incrementAndGet();
            }
            return escalatedReply;
        }
        String primaryReply = llmClient.generateResponse(prompt, llmContext);
        String reason = detectEscalationReason(primaryReply, llmContext);
        if (reason == null) {
            if (localPrimary && isSuccessfulLlmReply(primaryReply)) {
                metrics.localEscalationHitCount().incrementAndGet();
            }
            return primaryReply;
        }
        metrics.fallbackChainAttemptCount().incrementAndGet();
        incrementEscalationReason(reason);
        Map<String, Object> escalatedContext = new LinkedHashMap<>(llmContext == null ? Map.of() : llmContext);
        String cloudProvider = resolveEscalationProvider(escalatedContext);
        if (cloudProvider == null) {
            return primaryReply;
        }
        escalatedContext.put("llmProvider", cloudProvider);
        if (escalationConfig.cloudPreset() != null) {
            escalatedContext.put("llmPreset", escalationConfig.cloudPreset());
        }
        if (escalationConfig.cloudModel() != null) {
            escalatedContext.put("model", escalationConfig.cloudModel());
        }
        escalatedContext.put("localEscalationReason", reason);
        LOGGER.info("Dispatcher route=llm-local-escalation, from=local, to=" + cloudProvider
                + ", stage=" + asString(escalatedContext.get("routeStage"))
                + ", reason=" + reason);
        String escalatedReply = llmClient.generateResponse(prompt, escalatedContext);
        if (isSuccessfulLlmReply(escalatedReply)) {
            metrics.fallbackChainHitCount().incrementAndGet();
        }
        return escalatedReply;
    }

    String capLlmReply(String output) {
        return capText(output, promptConfig.llmReplyMaxChars());
    }

    String classifyMcpSearchSource(String channel) {
        String normalized = normalize(channel);
        if (!normalized.startsWith("mcp.")) {
            return "";
        }
        int providerStart = "mcp.".length();
        int providerEnd = normalized.indexOf('.', providerStart);
        if (providerEnd <= providerStart) {
            return "";
        }
        String provider = normalized.substring(providerStart, providerEnd);
        if (provider.endsWith("search") && provider.length() > "search".length()) {
            provider = provider.substring(0, provider.length() - "search".length());
        }
        return provider;
    }

    private String buildFallbackPrompt(String memoryContext,
                                       PromptMemoryContextDto promptMemoryContext,
                                       String userInput,
                                       boolean realtimeIntentInput) {
        if (shouldApplyRealtimeMemoryShrink(realtimeIntentInput)) {
            return buildRealtimeFallbackPrompt(promptMemoryContext, userInput);
        }
        return capText(promptBuilder.build(promptMemoryContext, userInput), promptConfig.promptMaxChars());
    }

    private SkillResult buildMemoryDirectResult(PromptMemoryContextDto promptMemoryContext, String userInput) {
        List<String> items = promptMemoryContext == null || promptMemoryContext.debugTopItems() == null
                ? List.of()
                : promptMemoryContext.debugTopItems().stream()
                .filter(item -> item != null && item.type() != null && !"episodic".equalsIgnoreCase(item.type()))
                .sorted(Comparator.comparingDouble(RetrievedMemoryItemDto::finalScore).reversed())
                .limit(3)
                .map(item -> item.text() == null ? "" : item.text().replace('\n', ' ').trim())
                .filter(text -> !text.isBlank())
                .toList();
        if (items.isEmpty()) {
            items = List.of("未找到可直接复用的高相关记忆，请补充更多背景。");
        }
        StringBuilder reply = new StringBuilder("根据已有记忆，我先直接回答：");
        for (int i = 0; i < items.size(); i++) {
            reply.append("\n").append(i + 1).append(". ").append(capText(items.get(i), 160));
        }
        if (userInput != null && !userInput.isBlank()) {
            reply.append("\n如需更深入分析，请明确说明你希望我详细推理的部分。");
        }
        return SkillResult.success("memory.direct", capText(reply.toString(), promptConfig.llmReplyMaxChars()));
    }

    private String promptMemoryDebugSummary(PromptMemoryContextDto ctx) {
        if (ctx == null || ctx.debugTopItems() == null) {
            return "promptMemoryDebug=empty";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("promptMemory.items=").append(ctx.debugTopItems().size());
        int i = 0;
        for (RetrievedMemoryItemDto item : ctx.debugTopItems()) {
            if (item == null) {
                continue;
            }
            i++;
            sb.append(" |").append(i).append(":type=").append(item.type() == null ? "" : item.type())
                    .append(",final=").append(String.format(Locale.ROOT, "%.3f", item.finalScore()))
                    .append(",recency=").append(String.format(Locale.ROOT, "%.3f", item.recencyScore()))
                    .append(",text=")
                    .append(item.text() == null ? "" : item.text().replace('\n', ' ').trim());
            if (i >= 5) {
                break;
            }
        }
        return sb.toString();
    }

    private QueryContext buildQueryContext(Map<String, Object> llmContext,
                                           String userInput,
                                           PromptMemoryContextDto promptMemoryContext) {
        String userId = llmContext == null ? "" : Objects.toString(llmContext.get("userId"), "");
        return new QueryContext(
                userId,
                userInput,
                promptMemoryContext,
                isExplicitLlmRequest(userInput),
                requiresComplexReasoning(userInput)
        );
    }

    private boolean isExplicitLlmRequest(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return false;
        }
        String normalized = userInput.toLowerCase(Locale.ROOT);
        return normalized.contains("调用llm")
                || normalized.contains("调用大模型")
                || normalized.contains("step by step")
                || normalized.contains("请详细分析")
                || normalized.contains("请深入分析");
    }

    private boolean requiresComplexReasoning(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return false;
        }
        String normalized = userInput.toLowerCase(Locale.ROOT);
        return normalized.contains("为什么")
                || normalized.contains("比较")
                || normalized.contains("权衡")
                || normalized.contains("tradeoff")
                || normalized.contains("设计方案")
                || normalized.contains("根因")
                || normalized.contains("如何设计");
    }

    private boolean shouldApplyRealtimeMemoryShrink(boolean realtimeIntentInput) {
        return realtimeIntentInput && promptConfig.realtimeIntentMemoryShrinkEnabled();
    }

    private String buildRealtimeFallbackPrompt(PromptMemoryContextDto promptMemoryContext, String userInput) {
        StringBuilder prompt = new StringBuilder("请使用中文简要回答，优先使用最新事实并避免陈旧假设。\n");
        if (promptConfig.realtimeIntentMemoryShrinkIncludePersona() && promptMemoryContext != null
                && promptMemoryContext.personaSnapshot() != null
                && !promptMemoryContext.personaSnapshot().isEmpty()) {
            prompt.append("Persona:\n")
                    .append(capText(promptMemoryContext.personaSnapshot().toString(), promptConfig.realtimeIntentMemoryShrinkMaxChars() / 2))
                    .append('\n');
        }
        prompt.append("User input: ")
                .append(capText(userInput, 400));
        return capText(prompt.toString(), Math.min(promptConfig.promptMaxChars(), promptConfig.realtimeIntentMemoryShrinkMaxChars() + 220));
    }

    private String buildSkillFinalizePrompt(String userInput, String channel, String rawOutput) {
        StringBuilder summary = new StringBuilder();
        summary.append("skill=").append(channel).append('\n');
        summary.append("input=").append(capText(userInput == null ? "" : userInput, 220)).append('\n');
        summary.append("raw_output=\n").append(capText(rawOutput, 1200));

        if (isNewsSearchFinalizeChannel(channel)) {
            String prompt = "你是新闻整理助手。给你一份搜索得到的新闻/资讯结果，请整理成适合直接发给用户的新闻简报。"
                    + "要求：\n"
                    + "1. 保持新闻特点，严格按下面结构输出，不要改标题名：\n"
                    + "今日新闻标题：\n"
                    + "1. ...\n"
                    + "2. ...\n"
                    + "3. ...\n"
                    + "总结：...\n"
                    + "2. 直接输出结果，不要写任何开场白、寒暄、致歉、确认、等待或自我说明；不要出现“好的”“请稍等”“我正在搜索”“已收到”“稍后给你”等话术；\n"
                    + "3. ‘今日新闻标题：’下面列出 3-6 条新闻标题或核心要点，每条单独一行，优先保留原始标题信息；\n"
                    + "4. 标题要尽量贴近原始结果，不要凭空编造；\n"
                    + "5. 最后必须单独输出“总结：”，概括今天的整体动态、趋势或值得关注点；\n"
                    + "6. 如果原始结果不足，就按实际数量输出，不要凑数；\n"
                    + "7. 语言自然、信息清晰，不要泄露内部字段名；控制在 8-12 行中文。\n"
                    + summary;
            return capText(prompt, promptConfig.promptMaxChars());
        }

        String prompt = "你是回复优化助手。给你一个技能结构化执行结果，请输出面向用户的最终答复。"
                + "要求：自然、简洁、可执行，避免模板化列表；不要泄露内部字段名；不要写任何开场白、寒暄、致歉、确认、等待或“我正在…”类句子；控制在 6-10 行中文。\n"
                + summary;
        return capText(prompt, promptConfig.promptMaxChars());
    }

    private boolean isNewsSearchFinalizeChannel(String channel) {
        String normalized = normalize(channel);
        return normalized.startsWith("mcp.") && normalized.endsWith(".websearch");
    }

    private String ensureNewsBriefShape(String optimizedOutput, String rawOutput) {
        String normalizedOutput = normalizeMultilineText(stripImDegradedMarkers(optimizedOutput));
        String normalizedRawOutput = normalizeMultilineText(stripImDegradedMarkers(rawOutput));
        if (normalizedOutput.isBlank()) {
            return optimizedOutput == null ? "" : optimizedOutput.trim();
        }

        List<String> headlines = extractNewsHeadlines(normalizedOutput);
        if (headlines.size() < 2) {
            headlines = mergeHeadlines(headlines, extractNewsHeadlines(normalizedRawOutput));
        }
        String summary = extractNewsSummary(normalizedOutput);
        if (summary.isBlank()) {
            summary = synthesizeNewsSummary(headlines, normalizedOutput);
        }

        if (headlines.isEmpty()) {
            return normalizedOutput.contains("总结：") ? normalizedOutput : normalizedOutput + "\n总结：" + summary;
        }

        StringBuilder builder = new StringBuilder("今日新闻标题：\n");
        int index = 1;
        for (String headline : headlines) {
            builder.append(index).append(". ").append(headline).append('\n');
            index++;
            if (index > 6) {
                break;
            }
        }
        builder.append("总结：").append(summary);
        return builder.toString().trim();
    }

    private String normalizeMultilineText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim();
    }

    private List<String> extractNewsHeadlines(String text) {
        String normalized = normalizeMultilineText(text);
        if (normalized.isBlank()) {
            return List.of();
        }
        List<RankedHeadlineCandidate> rankedCandidates = new ArrayList<>();
        int sourceOrder = 0;
        for (String line : normalized.split("\n+")) {
            String originalLine = normalizeMultilineText(line);
            String candidate = sanitizeNewsLine(line);
            if (candidate.isBlank()) {
                continue;
            }
            boolean structuredLine = originalLine.matches("^(?:[-*•]+|[0-9]+[.)、]).*");
            if (candidate.startsWith("今日新闻标题")) {
                continue;
            }
            if (candidate.startsWith("总结：") || candidate.startsWith("总结:")) {
                continue;
            }
            if (ImDegradedReplyMarker.parse(candidate).isPresent()) {
                continue;
            }
            if (candidate.startsWith("http://") || candidate.startsWith("https://")) {
                continue;
            }
            if (candidate.startsWith("Brave 搜索（") || candidate.startsWith("Qwen MCP") || candidate.startsWith("搜索结果")) {
                continue;
            }
            if (isLikelyWeatherNoiseLine(candidate)) {
                continue;
            }
            if (candidate.length() < 4) {
                continue;
            }
            if (!structuredLine && candidate.length() > 24 && (candidate.contains("，") || candidate.contains("。"))) {
                continue;
            }
            int separatorIndex = candidate.indexOf(" - ");
            if (separatorIndex > 0) {
                candidate = candidate.substring(0, separatorIndex).trim();
            }
            int domainScore = scoreNewsDomainRelevance(candidate);
            if (domainScore <= 0) {
                continue;
            }
            rankedCandidates.add(new RankedHeadlineCandidate(candidate, domainScore, sourceOrder));
            sourceOrder++;
        }
        if (rankedCandidates.isEmpty()) {
            return List.of();
        }
        rankedCandidates.sort(Comparator
                .comparingInt(RankedHeadlineCandidate::score).reversed()
                .thenComparingInt(RankedHeadlineCandidate::sourceOrder));
        LinkedHashSet<String> deduped = new LinkedHashSet<>();
        for (RankedHeadlineCandidate candidate : rankedCandidates) {
            deduped.add(candidate.text());
            if (deduped.size() >= 6) {
                break;
            }
        }
        return deduped.isEmpty() ? List.of() : List.copyOf(deduped);
    }

    private List<String> mergeHeadlines(List<String> first, List<String> second) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (first != null) {
            merged.addAll(first);
        }
        if (second != null) {
            merged.addAll(second);
        }
        return merged.isEmpty() ? List.of() : List.copyOf(merged);
    }

    private String sanitizeNewsLine(String line) {
        String candidate = normalizeMultilineText(line);
        candidate = candidate.replaceFirst("^(?:[-*•]+|[0-9]+[.)、])\\s*", "");
        candidate = candidate.replaceFirst("^(?:标题|要点)[:：]\\s*", "");
        return candidate.trim();
    }

    private String extractNewsSummary(String text) {
        String normalized = normalizeMultilineText(stripImDegradedMarkers(text));
        if (normalized.isBlank()) {
            return "";
        }
        for (String line : normalized.split("\n+")) {
            String candidate = normalizeMultilineText(line);
            if (candidate.startsWith("总结：") || candidate.startsWith("总结:")) {
                String extracted = candidate.substring(candidate.indexOf('：') >= 0 ? candidate.indexOf('：') + 1 : candidate.indexOf(':') + 1).trim();
                String sanitized = normalizeMultilineText(stripImDegradedMarkers(extracted));
                if (!sanitized.isBlank()) {
                    return sanitized;
                }
            }
        }
        return "";
    }

    private String synthesizeNewsSummary(List<String> headlines, String fallbackText) {
        if (headlines != null && !headlines.isEmpty()) {
            if (headlines.size() == 1) {
                return "今天的重点主要围绕“" + headlines.get(0) + "”展开，值得继续关注后续进展。";
            }
            return "今天的新闻重点主要集中在“" + headlines.get(0) + "”以及“" + headlines.get(1) + "”等方向，整体仍以持续推进和阶段性进展为主。";
        }
        String normalized = normalizeMultilineText(stripImDegradedMarkers(fallbackText));
        if (normalized.isBlank()) {
            return "今天的新闻动态以阶段性进展为主，建议结合后续更新持续关注。";
        }
        return normalized.length() <= 90 ? normalized : normalized.substring(0, 90).trim() + "…";
    }

    private String stripImDegradedMarkers(String text) {
        String normalized = normalizeMultilineText(text);
        if (normalized.isBlank()) {
            return "";
        }
        List<String> keptLines = new ArrayList<>();
        for (String line : normalized.split("\n+")) {
            String candidate = normalizeMultilineText(line);
            if (candidate.isBlank()) {
                continue;
            }
            var parsedMarker = ImDegradedReplyMarker.parse(candidate).orElse(null);
            if (parsedMarker != null) {
                if (!parsedMarker.remainder().isBlank()) {
                    keptLines.add(parsedMarker.remainder());
                }
                continue;
            }
            keptLines.add(candidate);
        }
        return keptLines.isEmpty() ? "" : String.join("\n", keptLines);
    }

    private boolean isLikelyWeatherNoiseLine(String line) {
        String normalized = normalize(line);
        if (normalized.isBlank()) {
            return false;
        }
        return normalized.contains("天气预报")
                || normalized.contains("7天天气")
                || normalized.contains("10天天气")
                || normalized.contains("15天天气")
                || normalized.contains("accuweather")
                || normalized.contains("中国气象局")
                || normalized.contains("weather.com")
                || normalized.contains("weathernews")
                || normalized.contains("全国天气网");
    }

    private void logMcpPostprocessTrace(String channel, String rawOutput) {
        String source = classifyMcpSearchSource(channel);
        if (source.isBlank()) {
            return;
        }
        LOGGER.info(() -> "{\"event\":\"dispatcher.skill-postprocess.trace\",\"source\":\""
                + source
                + "\",\"channel\":\""
                + (channel == null ? "" : channel)
                + "\",\"sent\":true,\"outputChars\":"
                + (rawOutput == null ? 0 : rawOutput.length())
                + "}");
    }

    private String detectEscalationReason(String reply, Map<String, Object> llmContext) {
        if (!escalationConfig.enabled() || llmContext == null || llmContext.isEmpty()) {
            return null;
        }
        String provider = resolveEscalationSourceProvider(llmContext);
        if (!"local".equals(provider) && !"ollama".equals(provider) && !"gemma".equals(provider)) {
            return null;
        }
        if (isManualEscalationRequested(llmContext)) {
            return "manual";
        }
        String normalizedReply = normalize(reply);
        if (normalizedReply.isBlank()) {
            return "empty_response";
        }
        if (!normalizedReply.startsWith("[llm local]")) {
            return shouldEscalateForQuality(reply, llmContext) ? "quality" : null;
        }
        if (normalizedReply.contains("reason=timeout") || normalizedReply.contains(" timed out") || normalizedReply.contains(" timeout")) {
            return "timeout";
        }
        if (normalizedReply.contains("reason=upstream_5xx")
                || normalizedReply.contains("http_500")
                || normalizedReply.contains("http_502")
                || normalizedReply.contains("http_503")
                || normalizedReply.contains("http_504")) {
            return "upstream_5xx";
        }
        if (normalizedReply.contains("reason=empty_response")
                || normalizedReply.contains("empty_response_content")
                || normalizedReply.contains("empty response")) {
            return "empty_response";
        }
        return null;
    }

    private String detectResourceGuardEscalationReason(Map<String, Object> llmContext) {
        if (!escalationConfig.enabled() || !escalationConfig.resourceGuardEnabled() || llmContext == null || llmContext.isEmpty()) {
            return null;
        }
        String provider = resolveEscalationSourceProvider(llmContext);
        if (!"local".equals(provider) && !"ollama".equals(provider) && !"gemma".equals(provider)) {
            return null;
        }
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        long freeMemory = Math.max(0L, maxMemory - usedMemory);
        long freeMemoryMb = freeMemory / (1024 * 1024);
        double freeMemoryRatio = maxMemory <= 0 ? 1.0 : (double) freeMemory / (double) maxMemory;
        int availableProcessors = runtime.availableProcessors();
        if (freeMemoryMb >= escalationConfig.resourceGuardMinFreeMemoryMb()
                && freeMemoryRatio >= escalationConfig.resourceGuardMinFreeMemoryRatio()
                && availableProcessors >= escalationConfig.resourceGuardMinAvailableProcessors()) {
            return null;
        }
        LOGGER.info("Dispatcher local resource guard triggered: freeMemoryMb=" + freeMemoryMb
                + ", freeMemoryRatio=" + String.format(Locale.ROOT, "%.4f", freeMemoryRatio)
                + ", availableProcessors=" + availableProcessors
                + ", minFreeMemoryMb=" + escalationConfig.resourceGuardMinFreeMemoryMb()
                + ", minFreeMemoryRatio=" + String.format(Locale.ROOT, "%.4f", escalationConfig.resourceGuardMinFreeMemoryRatio())
                + ", minAvailableProcessors=" + escalationConfig.resourceGuardMinAvailableProcessors());
        return "resource_guard";
    }

    private boolean isManualEscalationRequested(Map<String, Object> llmContext) {
        String directReason = normalize(asString(llmContext.get("localEscalationReason")));
        if ("manual".equals(directReason)) {
            return true;
        }
        if (isTrue(llmContext.get("forceCloudRetry"))) {
            return true;
        }
        Map<String, Object> profile = asObjectMap(llmContext.get("profile"));
        String profileReason = normalize(asString(profile.get("localEscalationReason")));
        if ("manual".equals(profileReason)) {
            return true;
        }
        return isTrue(profile.get("forceCloudRetry"));
    }

    private boolean shouldEscalateForQuality(String reply, Map<String, Object> llmContext) {
        if (!escalationConfig.qualityEnabled()) {
            return false;
        }
        if (!isSuccessfulLlmReply(reply)) {
            return false;
        }
        String input = asString(llmContext.get("originalInput"));
        if (input == null) {
            input = asString(llmContext.get("input"));
        }
        if (input == null) {
            return false;
        }
        String normalizedInput = normalize(input);
        if (!matchesAnyTerm(normalizedInput, escalationConfig.qualityInputTerms())) {
            return false;
        }
        String normalizedReply = normalize(reply);
        if (normalizedReply.length() > escalationConfig.qualityMaxReplyChars()) {
            return false;
        }
        return matchesAnyTerm(normalizedReply, escalationConfig.qualityReplyTerms());
    }

    private boolean matchesAnyTerm(String normalizedText, Set<String> terms) {
        if (normalizedText == null || normalizedText.isBlank() || terms == null || terms.isEmpty()) {
            return false;
        }
        for (String term : terms) {
            if (term != null && !term.isBlank() && normalizedText.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private boolean isTrue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        if (value instanceof String text) {
            String normalized = normalize(text);
            return "true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized) || "on".equals(normalized);
        }
        return false;
    }

    private Map<String, Object> asObjectMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> mapped = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            mapped.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return mapped.isEmpty() ? Map.of() : Map.copyOf(mapped);
    }

    private boolean isLocalProviderContext(Map<String, Object> llmContext) {
        if (llmContext == null || llmContext.isEmpty()) {
            return false;
        }
        String provider = resolveEscalationSourceProvider(llmContext);
        return "local".equals(provider) || "ollama".equals(provider) || "gemma".equals(provider);
    }

    private String resolveEscalationSourceProvider(Map<String, Object> llmContext) {
        String provider = normalize(asString(llmContext.get("llmProvider")));
        if (!provider.isBlank()) {
            return provider;
        }
        Map<String, Object> profile = asObjectMap(llmContext.get("profile"));
        String profileProvider = normalize(asString(profile.get("llmProvider")));
        if (!profileProvider.isBlank() && !"auto".equals(profileProvider)) {
            return profileProvider;
        }
        String routeStage = normalize(asString(llmContext.get("routeStage")));
        if ("llm-fallback".equals(routeStage)) {
            return normalize(stageRouteConfig.llmFallbackProvider());
        }
        if ("llm-dsl".equals(routeStage)) {
            return normalize(stageRouteConfig.llmDslProvider());
        }
        return provider;
    }

    private boolean isSuccessfulLlmReply(String reply) {
        if (reply == null || reply.isBlank()) {
            return false;
        }
        return !normalize(reply).startsWith("[llm ");
    }

    private void incrementEscalationReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return;
        }
        metrics.escalationReasonCounters().computeIfAbsent(reason, ignored -> new AtomicLong()).incrementAndGet();
    }

    private List<Map<String, Object>> extractRecentChatHistory(Map<String, Object> profileContext, int keepLast) {
        if (profileContext == null || keepLast <= 0) {
            return List.of();
        }
        Object raw = profileContext.get("chatHistory");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        int from = Math.max(0, list.size() - keepLast);
        List<Map<String, Object>> turns = new ArrayList<>();
        for (int i = from; i < list.size(); i++) {
            Object item = list.get(i);
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> normalized = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    normalized.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                turns.add(Map.copyOf(normalized));
            }
        }
        return turns.isEmpty() ? List.of() : List.copyOf(turns);
    }

    private String resolveEscalationProvider(Map<String, Object> llmContext) {
        String configured = escalationConfig.cloudProvider();
        if (configured == null) {
            configured = stageRouteConfig.llmFallbackProvider();
        }
        String normalized = normalize(configured);
        if (normalized.isBlank()
                || "local".equals(normalized)
                || "ollama".equals(normalized)
                || "gemma".equals(normalized)) {
            return null;
        }
        String currentProvider = normalize(asString(llmContext.get("llmProvider")));
        if (normalized.equals(currentProvider)) {
            return null;
        }
        return configured.trim();
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

    private int scoreNewsDomainRelevance(String line) {
        String normalized = normalize(line);
        if (normalized.isBlank()) {
            return 0;
        }
        int score = 0;
        for (String keyword : NEWS_DOMAIN_WHITELIST_TERMS) {
            if (normalized.contains(keyword)) {
                score += 2;
            }
        }
        for (String keyword : WEATHER_DOMAIN_PENALTY_TERMS) {
            if (normalized.contains(keyword)) {
                score -= 3;
            }
        }
        return score;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private String asString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
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

    private String clip(String value) {
        if (value == null) {
            return "null";
        }
        int max = 240;
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    record PromptConfig(int promptMaxChars,
                        int llmReplyMaxChars,
                        int llmDslMemoryContextMaxChars,
                        boolean realtimeIntentMemoryShrinkEnabled,
                        int realtimeIntentMemoryShrinkMaxChars,
                        boolean realtimeIntentMemoryShrinkIncludePersona) {
    }

    record StageRouteConfig(String llmDslProvider,
                            String llmDslPreset,
                            String llmDslModel,
                            int llmDslMaxTokens,
                            String llmFallbackProvider,
                            String llmFallbackPreset,
                            String llmFallbackModel,
                            int llmFallbackMaxTokens,
                            String skillFinalizeWithLlmProvider,
                            String skillFinalizeWithLlmPreset,
                            String skillFinalizeWithLlmModel,
                            int skillFinalizeMaxTokens) {
    }

    record SkillFinalizeConfig(boolean enabled, Set<String> skills, int maxOutputChars) {
    }

    record EscalationConfig(boolean enabled,
                            String cloudProvider,
                            String cloudPreset,
                            String cloudModel,
                            boolean qualityEnabled,
                            int qualityMaxReplyChars,
                            Set<String> qualityInputTerms,
                            Set<String> qualityReplyTerms,
                            boolean resourceGuardEnabled,
                            int resourceGuardMinFreeMemoryMb,
                            double resourceGuardMinFreeMemoryRatio,
                            int resourceGuardMinAvailableProcessors) {
    }

    record Metrics(AtomicLong localEscalationAttemptCount,
                   AtomicLong localEscalationHitCount,
                   AtomicLong fallbackChainAttemptCount,
                   AtomicLong fallbackChainHitCount,
                   Map<String, AtomicLong> escalationReasonCounters) {
    }

    private record RankedHeadlineCandidate(String text, int score, int sourceOrder) {
    }
}
