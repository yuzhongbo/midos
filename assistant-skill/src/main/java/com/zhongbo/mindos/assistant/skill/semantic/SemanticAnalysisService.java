package com.zhongbo.mindos.assistant.skill.semantic;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.zhongbo.mindos.assistant.common.LlmClient;
import com.zhongbo.mindos.assistant.skill.DecisionCapabilityCatalog;
import com.zhongbo.mindos.assistant.skill.DefaultSkillCatalog;
import com.zhongbo.mindos.assistant.skill.SkillRegistry;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SemanticAnalysisService implements SemanticAnalyzer {

    private static final Logger LOGGER = Logger.getLogger(SemanticAnalysisService.class.getName());
    private static final Pattern JSON_BLOCK_PATTERN = Pattern.compile("\\{.*}", Pattern.DOTALL);
    private static final Pattern DUE_DATE_PATTERN = Pattern.compile("(?:截止|due|到期|deadline)\\s*(?:时间|日期|date)?\\s*[:：]?\\s*([^，。；;\\n]+)",
            Pattern.CASE_INSENSITIVE);
    private static final String SEMANTIC_LOCAL_PROVIDER = "local";
    private static final List<String> INTERNAL_SEMANTIC_ROUTE_SKILLS = List.of("semantic.analyze");
    private static final List<String> SEMANTIC_META_HINTS = List.of("语义", "分析", "路由", "结构化", "意图", "候选", "semantic", "解析");
    private static final int DEFAULT_LLM_COMPLEXITY_MIN_INPUT_CHARS = 10;
    private static final String DEFAULT_LLM_COMPLEXITY_TRIGGER_TERMS = "新闻,搜索,实时,分析,规划,计划,代码,排查,文档,手册,指南,debug,search,latest,news,plan,report,docs,documentation,manual,guide,api";

    private final LlmClient llmClient;
    private final SkillRegistry skillRegistry;
    private final DefaultSkillCatalog skillCatalog;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SemanticDecisionPromptBuilder decisionPromptBuilder = new SemanticDecisionPromptBuilder();
    private final boolean enabled;
    private final boolean llmEnabled;
    private final boolean forceLocalProvider;
    private final String delegateSkillName;
    private final String llmProvider;
    private final String llmPreset;
    private final String llmModel;
    private final int llmMaxTokens;
    private final boolean semanticLocalEscalationEnabled;
    private final String semanticCloudProvider;
    private final String semanticCloudPreset;
    private final String semanticCloudModel;
    private final double semanticLocalEscalationMinConfidence;
    private final int llmComplexityMinInputChars;
    private final List<String> llmComplexityTriggerTerms;

    @Autowired
    public SemanticAnalysisService(LlmClient llmClient,
                                   @Lazy SkillRegistry skillRegistry,
                                   @Lazy DefaultSkillCatalog skillCatalog,
                                   @Value("${mindos.dispatcher.semantic-analysis.enabled:true}") boolean enabled,
                                   @Value("${mindos.dispatcher.semantic-analysis.llm-enabled:false}") boolean llmEnabled,
                                   @Value("${mindos.dispatcher.semantic-analysis.force-local:true}") boolean forceLocalProvider,
                                   @Value("${mindos.dispatcher.semantic-analysis.delegate-skill:}") String delegateSkillName,
                                   @Value("${mindos.dispatcher.semantic-analysis.llm-provider:local}") String llmProvider,
                                   @Value("${mindos.dispatcher.semantic-analysis.llm-preset:cost}") String llmPreset,
                                   @Value("${mindos.dispatcher.semantic-analysis.llm-model:}") String llmModel,
                                   @Value("${mindos.dispatcher.semantic-analysis.max-tokens:120}") int llmMaxTokens,
                                   @Value("${mindos.dispatcher.semantic-analysis.local-escalation.enabled:false}") boolean semanticLocalEscalationEnabled,
                                   @Value("${mindos.dispatcher.semantic-analysis.local-escalation.cloud-provider:qwen}") String semanticCloudProvider,
                                   @Value("${mindos.dispatcher.semantic-analysis.local-escalation.cloud-preset:quality}") String semanticCloudPreset,
                                   @Value("${mindos.dispatcher.semantic-analysis.local-escalation.cloud-model:}") String semanticCloudModel,
                                   @Value("${mindos.dispatcher.semantic-analysis.local-escalation.min-confidence:0.78}") double semanticLocalEscalationMinConfidence,
                                   @Value("${mindos.dispatcher.semantic-analysis.llm-complexity.min-input-chars:10}") int llmComplexityMinInputChars,
                                   @Value("${mindos.dispatcher.semantic-analysis.llm-complexity.trigger-terms:新闻,搜索,实时,分析,规划,计划,代码,排查,debug,search,latest,news,plan,report}") String llmComplexityTriggerTerms) {
        this.llmClient = llmClient;
        this.skillRegistry = skillRegistry;
        this.skillCatalog = skillCatalog;
        this.enabled = enabled;
        this.llmEnabled = llmEnabled;
        this.forceLocalProvider = forceLocalProvider;
        this.delegateSkillName = delegateSkillName == null ? "" : delegateSkillName.trim();
        this.llmProvider = llmProvider == null ? "" : llmProvider.trim();
        this.llmPreset = llmPreset == null ? "" : llmPreset.trim();
        this.llmModel = llmModel == null ? "" : llmModel.trim();
        this.llmMaxTokens = Math.max(0, llmMaxTokens);
        this.semanticLocalEscalationEnabled = semanticLocalEscalationEnabled;
        this.semanticCloudProvider = semanticCloudProvider == null ? "" : semanticCloudProvider.trim();
        this.semanticCloudPreset = semanticCloudPreset == null ? "" : semanticCloudPreset.trim();
        this.semanticCloudModel = semanticCloudModel == null ? "" : semanticCloudModel.trim();
        this.semanticLocalEscalationMinConfidence = Math.max(0.0, Math.min(1.0, semanticLocalEscalationMinConfidence));
        this.llmComplexityMinInputChars = Math.max(0, llmComplexityMinInputChars);
        this.llmComplexityTriggerTerms = parseTerms(llmComplexityTriggerTerms);
    }

    public SemanticAnalysisService(LlmClient llmClient,
                                    SkillRegistry skillRegistry,
                                    boolean enabled,
                                   boolean llmEnabled,
                                   boolean forceLocalProvider,
                                   String delegateSkillName,
                                   String llmProvider,
                                   String llmPreset,
                                   int llmMaxTokens) {
        this(llmClient,
                skillRegistry,
                new DefaultSkillCatalog(skillRegistry, null, new com.zhongbo.mindos.assistant.skill.SkillRoutingProperties()),
                enabled,
                llmEnabled,
                forceLocalProvider,
                delegateSkillName,
                llmProvider,
                llmPreset,
                llmMaxTokens);
    }

    public SemanticAnalysisService(LlmClient llmClient,
                                    SkillRegistry skillRegistry,
                                    DefaultSkillCatalog skillCatalog,
                                    boolean enabled,
                                   boolean llmEnabled,
                                   boolean forceLocalProvider,
                                   String delegateSkillName,
                                   String llmProvider,
                                   String llmPreset,
                                   int llmMaxTokens) {
        this(llmClient,
                skillRegistry,
                skillCatalog,
                enabled,
                llmEnabled,
                forceLocalProvider,
                delegateSkillName,
                llmProvider,
                llmPreset,
                "",
                llmMaxTokens,
                false,
                "qwen",
                "quality",
                "",
                0.78,
                DEFAULT_LLM_COMPLEXITY_MIN_INPUT_CHARS,
                DEFAULT_LLM_COMPLEXITY_TRIGGER_TERMS);
    }

    public SemanticAnalysisService(LlmClient llmClient,
                                    SkillRegistry skillRegistry,
                                    boolean enabled,
                                   boolean llmEnabled,
                                   boolean forceLocalProvider,
                                   String delegateSkillName,
                                   String llmProvider,
                                   String llmPreset,
                                   int llmMaxTokens,
                                   boolean semanticLocalEscalationEnabled,
                                   String semanticCloudProvider,
                                   String semanticCloudPreset,
                                   double semanticLocalEscalationMinConfidence) {
        this(llmClient,
                skillRegistry,
                new DefaultSkillCatalog(skillRegistry, null, new com.zhongbo.mindos.assistant.skill.SkillRoutingProperties()),
                enabled,
                llmEnabled,
                forceLocalProvider,
                delegateSkillName,
                llmProvider,
                llmPreset,
                llmMaxTokens,
                semanticLocalEscalationEnabled,
                semanticCloudProvider,
                semanticCloudPreset,
                semanticLocalEscalationMinConfidence);
    }

    public SemanticAnalysisService(LlmClient llmClient,
                                    SkillRegistry skillRegistry,
                                    DefaultSkillCatalog skillCatalog,
                                    boolean enabled,
                                   boolean llmEnabled,
                                   boolean forceLocalProvider,
                                   String delegateSkillName,
                                   String llmProvider,
                                   String llmPreset,
                                   int llmMaxTokens,
                                   boolean semanticLocalEscalationEnabled,
                                   String semanticCloudProvider,
                                   String semanticCloudPreset,
                                   double semanticLocalEscalationMinConfidence) {
        this(llmClient,
                skillRegistry,
                skillCatalog,
                enabled,
                llmEnabled,
                forceLocalProvider,
                delegateSkillName,
                llmProvider,
                llmPreset,
                "",
                llmMaxTokens,
                semanticLocalEscalationEnabled,
                semanticCloudProvider,
                semanticCloudPreset,
                "",
                semanticLocalEscalationMinConfidence,
                DEFAULT_LLM_COMPLEXITY_MIN_INPUT_CHARS,
                DEFAULT_LLM_COMPLEXITY_TRIGGER_TERMS);
    }

    @Override
    public SemanticAnalysisResult analyze(String userId,
                                          String userInput,
                                          String memoryContext,
                                          Map<String, Object> profileContext,
                                          List<String> availableSkillSummaries) {
        if (!enabled || userInput == null || userInput.isBlank()) {
            return SemanticAnalysisResult.empty();
        }
        SemanticAnalysisResult best = heuristicAnalysis(userInput);
        best = preferHigherConfidence(best, heuristicContinuationAnalysis(userInput, memoryContext));
        best = preferHigherConfidence(best, analyzeWithLlm(userId, userInput, memoryContext, profileContext, availableSkillSummaries, best));
        return sanitize(best, userInput);
    }

    public SemanticAnalysisResult analyzeHeuristically(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return SemanticAnalysisResult.empty();
        }
        return sanitize(heuristicAnalysis(userInput), userInput);
    }

    private Optional<SemanticAnalysisResult> analyzeWithLlm(String userId,
                                                            String userInput,
                                                            String memoryContext,
                                                            Map<String, Object> profileContext,
                                                            List<String> availableSkillSummaries,
                                                            SemanticAnalysisResult baseline) {
        if (!llmEnabled || llmClient == null) {
            return Optional.empty();
        }
        if (baseline != null && baseline.confidence() >= 0.86) {
            return Optional.empty();
        }
        if (shouldSkipLlmByComplexity(userInput)) {
            return Optional.empty();
        }
        String prompt = decisionPromptBuilder.buildPrompt(
                userInput,
                memoryContext,
                profileContext,
                availableSkillSummaries,
                baseline
        );

        if (semanticLocalEscalationEnabled) {
            Optional<SemanticAnalysisResult> localResult = callSemanticLlm(prompt, userId, userInput, profileContext, SEMANTIC_LOCAL_PROVIDER, llmPreset, resolveSemanticLocalModel());
            LOGGER.info("semantic.analysis.local.result confidence="
                    + localResult.map(SemanticAnalysisResult::confidence).orElse(0.0)
                    + ", threshold=" + semanticLocalEscalationMinConfidence
                    + ", hasResult=" + localResult.isPresent());
            if (localResult.isPresent() && localResult.get().confidence() >= semanticLocalEscalationMinConfidence) {
                return localResult;
            }
            String cloudProvider = resolveSemanticCloudProvider();
            if (cloudProvider.isBlank() || SEMANTIC_LOCAL_PROVIDER.equals(cloudProvider)) {
                return localResult;
            }
            LOGGER.info("semantic.analysis.local.escalate provider=" + cloudProvider
                    + ", reason=" + (localResult.isEmpty() ? "local_empty" : "low_confidence"));
            Optional<SemanticAnalysisResult> cloudResult = callSemanticLlm(
                    prompt,
                    userId,
                    userInput,
                    profileContext,
                    cloudProvider,
                    semanticCloudPreset.isBlank() ? llmPreset : semanticCloudPreset,
                    semanticCloudModel
            );
            if (isPreferred(cloudResult, localResult.orElse(null))) {
                LOGGER.info("semantic.analysis.local.escalate.accepted cloudConfidence=" + cloudResult.get().confidence());
                return cloudResult;
            }
            LOGGER.info("semantic.analysis.local.escalate.skipped-cloud-result");
            return localResult;
        }

        return callSemanticLlm(prompt, userId, userInput, profileContext, resolveSemanticLlmProvider(), llmPreset, llmModel);
    }

    private boolean shouldSkipLlmByComplexity(String userInput) {
        String normalizedInput = normalize(userInput);
        if (normalizedInput.isBlank()) {
            return true;
        }
        if (normalizedInput.length() >= llmComplexityMinInputChars) {
            return false;
        }
        for (String term : llmComplexityTriggerTerms) {
            if (!term.isBlank() && normalizedInput.contains(term)) {
                return false;
            }
        }
        return true;
    }

    private List<String> parseTerms(String rawTerms) {
        if (rawTerms == null || rawTerms.isBlank()) {
            return List.of();
        }
        List<String> terms = new ArrayList<>();
        for (String candidate : rawTerms.split(",")) {
            String normalized = normalize(candidate);
            if (!normalized.isBlank()) {
                terms.add(normalized);
            }
        }
        return List.copyOf(terms);
    }

    private Optional<SemanticAnalysisResult> callSemanticLlm(String prompt,
                                                             String userId,
                                                             String userInput,
                                                             Map<String, Object> profileContext,
                                                             String provider,
                                                             String preset,
                                                             String model) {
        try {
            Map<String, Object> context = buildSemanticLlmContext(userId, userInput, profileContext, provider, preset, model);
            return parseResult(llmClient.generateResponse(prompt, Map.copyOf(context)), "llm");
        } catch (RuntimeException ex) {
            LOGGER.log(Level.WARNING, "Semantic analysis LLM call failed, fallback to local analysis", ex);
            return Optional.empty();
        }
    }

    private Map<String, Object> buildSemanticLlmContext(String userId,
                                                         String userInput,
                                                         Map<String, Object> profileContext,
                                                         String provider,
                                                         String preset,
                                                         String model) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("userId", userId == null ? "" : userId);
        context.put("routeStage", "semantic-analysis");
        context.put("input", userInput);
        context.put("llmProvider", provider == null || provider.isBlank() ? resolveSemanticLlmProvider() : provider);
        if (preset != null && !preset.isBlank()) {
            context.put("llmPreset", preset);
        }
        String profileModel = profileContext == null ? "" : stringValue(profileContext.get("llmModel"));
        String effectiveModel = profileModel.isBlank() ? model : profileModel;
        if (effectiveModel != null && !effectiveModel.isBlank()) {
            context.put("model", effectiveModel);
        }
        if (llmMaxTokens > 0) {
            context.put("maxTokens", llmMaxTokens);
        }
        if (profileContext != null && !profileContext.isEmpty()) {
            context.put("profile", profileContext);
        }
        return context;
    }

    private SemanticAnalysisResult preferHigherConfidence(SemanticAnalysisResult baseline,
                                                          Optional<SemanticAnalysisResult> candidate) {
        if (!isPreferred(candidate, baseline)) {
            return baseline;
        }
        return candidate.orElse(baseline);
    }

    private boolean isPreferred(Optional<SemanticAnalysisResult> candidate, SemanticAnalysisResult baseline) {
        return candidate.isPresent() && (baseline == null || candidate.get().confidence() >= baseline.confidence());
    }

    private Optional<SemanticAnalysisResult> parseResult(String raw, String source) {
        String jsonBody = extractJsonBody(raw);
        if (jsonBody == null || jsonBody.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(jsonBody);
            if (root == null || !root.isObject()) {
                LOGGER.fine("Semantic analysis JSON parse ignored non-object root from " + source);
                return Optional.empty();
            }
            Map<String, Object> payload = objectMapper.convertValue(root, new TypeReference<>() {
            });
            Map<String, Object> cleaned = validateAndCleanSemanticMap(payload);
            return Optional.of(fromMap(cleaned, source));
        } catch (Exception ex) {
            LOGGER.log(Level.FINE, "Semantic analysis JSON parse failed", ex);
            return Optional.empty();
        }
    }

    /**
     * Lightweight validation and cleaning for semantic analysis result maps.
     * Accepts only the allowed keys and normalizes types where reasonable.
     */
    private Map<String, Object> validateAndCleanSemanticMap(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> cleaned = new LinkedHashMap<>();

        cleaned.put("intent", firstNonBlankString(raw.get("intent"), raw.get("intentLabel"), raw.get("intent_label")));
        cleaned.put("rewrittenInput", firstNonBlankString(raw.get("rewrittenInput"), raw.get("rewritten_input"), raw.get("query")));
        cleaned.put("suggestedSkill", firstNonBlankString(
                raw.get("suggestedSkill"),
                raw.get("suggested_skill"),
                raw.get("target"),
                raw.get("selectedSkill"),
                raw.get("skill")
        ));
        cleaned.put("summary", firstNonBlankString(raw.get("summary"), raw.get("intentSummary"), raw.get("intent_summary")));
        cleaned.put("payload", resolvePayloadMap(raw));
        logMalformedPayloadIfNeeded(raw, cleaned);
        cleaned.put("keywords", parseKeywords(raw.get("keywords")));
        cleaned.put("candidate_intents", parseCandidateIntents(raw.containsKey("candidate_intents")
                ? raw.get("candidate_intents")
                : raw.get("candidateIntents")));
        cleaned.put("confidence", clampConfidence(numberValue(raw.get("confidence"), 0.0)));
        return cleaned;
    }

    private SemanticAnalysisResult fromMap(Map<String, Object> raw, String source) {
        if (raw == null || raw.isEmpty()) {
            return SemanticAnalysisResult.empty();
        }
        String intent = resolvedIntent(raw);
        String rewrittenInput = stringValue(raw.get("rewrittenInput"));
        String suggestedSkill = normalizeSkillName(stringValue(raw.get("suggestedSkill")));
        double confidence = clampConfidence(numberValue(raw.get("confidence"), 0.0));
        String summary = resolvedSummary(raw, rewrittenInput, intent);
        Map<String, Object> payload = resolvePayloadMap(raw);
        logMissingSemanticFields(source, raw, intent, summary, suggestedSkill, payload);
        List<String> keywords = parseKeywords(raw.get("keywords"));
        List<SemanticAnalysisResult.CandidateIntent> candidateIntents = parseCandidateIntents(raw.get("candidate_intents"));
        return new SemanticAnalysisResult(source, intent, rewrittenInput, suggestedSkill, payload, keywords, summary, confidence, candidateIntents);
    }

    private List<SemanticAnalysisResult.CandidateIntent> parseCandidateIntents(Object raw) {
        if (!(raw instanceof List<?> values) || values.isEmpty()) {
            return List.of();
        }
        List<SemanticAnalysisResult.CandidateIntent> parsed = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof SemanticAnalysisResult.CandidateIntent candidate) {
                String intent = stringValue(candidate.intent());
                if (!intent.isBlank()) {
                    parsed.add(new SemanticAnalysisResult.CandidateIntent(intent,
                            clampConfidence(candidate.confidence())));
                }
                continue;
            }
            if (!(value instanceof Map<?, ?> map)) {
                continue;
            }
            String intent = stringValue(map.get("intent"));
            if (intent.isBlank()) {
                continue;
            }
            double confidence = clampConfidence(numberValue(map.get("confidence"), 0.0));
            parsed.add(new SemanticAnalysisResult.CandidateIntent(intent, confidence));
        }
        return parsed.isEmpty() ? List.of() : List.copyOf(parsed);
    }

    private SemanticAnalysisResult heuristicAnalysis(String userInput) {
        String normalized = normalize(userInput);
        if (normalized.isBlank()) {
            return SemanticAnalysisResult.empty();
        }
        if (matchesSkill(userInput, normalized, "semantic.analyze", "semantic", "semantic.analyze", "语义分析", "分析我的语义")) {
            return new SemanticAnalysisResult(
                    "heuristic",
                    "分析用户语义并给出结构化意图建议",
                    userInput.trim(),
                    "",
                    Map.of(),
                    routingKeywordHints(userInput, "semantic.analyze", "语义分析", "semantic"),
                    capText(userInput, 60),
                    0.92
            );
        }
        SemanticAnalysisResult realtimeAnalysis = heuristicRealtimeAnalysis(userInput, normalized);
        if (realtimeAnalysis != null) {
            return realtimeAnalysis;
        }
        if ((isExplicitTeachingPlanRequest(normalized)
                || hasRequestedSpecificRoutingKeyword(userInput, normalized, "teaching.plan"))
                && matchesSkill(userInput, normalized, "teaching.plan", "学习计划", "教学规划", "复习计划", "课程规划", "study plan", "teaching plan")) {
            return new SemanticAnalysisResult(
                    "heuristic",
                    "为用户生成学习/教学规划",
                    userInput.trim(),
                    "teaching.plan",
                    Map.of(),
                    routingKeywordHints(userInput, "teaching.plan", "学习计划", "教学规划", "复习计划"),
                    "用户希望生成学习/教学规划",
                    0.88
            );
        }
        if ((isExplicitCoachRequest(normalized)
                || hasRequestedSpecificRoutingKeyword(userInput, normalized, "eq.coach"))
                && matchesSkill(userInput, normalized, "eq.coach", "情商", "沟通", "高情商", "心理分析", "怎么说", "安慰", "道歉", "冲突")) {
            return new SemanticAnalysisResult(
                    "heuristic",
                    "分析沟通场景并生成情商沟通建议",
                    userInput.trim(),
                    "eq.coach",
                    Map.of("query", userInput.trim()),
                    routingKeywordHints(userInput, "eq.coach", "沟通", "情商", "心理分析", "冲突"),
                    "用户需要情绪或沟通场景建议",
                    0.84
            );
        }
        if ((isExplicitTodoRequest(normalized)
                || hasRequestedSpecificRoutingKeyword(userInput, normalized, "todo.create"))
                && matchesSkill(userInput, normalized, "todo.create", "待办", "todo", "提醒", "记得", "安排任务", "创建任务", "截止")) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("task", userInput.trim());
            String dueDate = extractByPattern(userInput, DUE_DATE_PATTERN);
            if (dueDate != null && !dueDate.isBlank()) {
                payload.put("dueDate", dueDate);
            }
            return new SemanticAnalysisResult(
                    "heuristic",
                    "创建待办或提醒事项",
                    userInput.trim(),
                    "todo.create",
                    payload,
                    routingKeywordHints(userInput, "todo.create", "待办", "提醒", "截止"),
                    "用户要创建待办事项",
                    0.80
            );
        }
        if ((isExplicitCodeRequest(normalized)
                || hasRequestedSpecificRoutingKeyword(userInput, normalized, "code.generate"))
                && matchesSkill(userInput, normalized, "code.generate", "代码", "接口", "api", "dto", "controller", "bug", "修复", "生成代码", "sql")) {
            return new SemanticAnalysisResult(
                    "heuristic",
                    "生成或整理代码实现方案",
                    userInput.trim(),
                    "code.generate",
                    Map.of("task", userInput.trim()),
                    routingKeywordHints(userInput, "code.generate", "代码", "接口", "API", "DTO", "Controller"),
                    "用户请求代码相关实现或修复",
                    0.78
            );
        }
        if ((isExplicitFileSearchRequest(normalized)
                || hasRequestedSpecificRoutingKeyword(userInput, normalized, "file.search"))
                && matchesSkill(userInput, normalized, "file.search", "找文件", "查文件", "搜索文件", "search file", "grep", "目录", "路径")) {
            return new SemanticAnalysisResult(
                    "heuristic",
                    "搜索文件或目录中的目标内容",
                    userInput.trim(),
                    "file.search",
                    Map.of("path", "./", "keyword", userInput.trim()),
                    routingKeywordHints(userInput, "file.search", "文件", "目录", "路径", "搜索"),
                    "用户希望搜索文件或路径内容",
                    0.74
            );
        }
        return new SemanticAnalysisResult(
                "heuristic",
                "整理用户原始诉求并保留给后续模型理解",
                userInput.trim(),
                "",
                Map.of(),
                extractKeywords(userInput),
                capText(userInput, 80),
                0.35
        );
    }

    private boolean isExplicitTeachingPlanRequest(String normalized) {
        return containsAny(normalized,
                "teaching.plan", "study plan", "teaching plan",
                "学习计划", "教学规划", "教学计划", "复习计划", "课程规划", "学习路线", "提分计划")
                || (containsAny(normalized, "学习", "教学", "复习", "课程", "备考", "提分")
                && containsAny(normalized, "计划", "规划", "路线", "安排", "提纲", "排期", "节奏", "方案"));
    }

    private boolean isExplicitCoachRequest(String normalized) {
        return containsAny(normalized,
                "eq.coach", "高情商",
                "怎么说", "怎么回", "怎么回复", "如何说", "如何回", "如何回复",
                "帮我回复", "给我建议", "沟通建议", "道歉话术", "安慰话术", "拒绝话术", "回什么", "话术")
                || (containsAny(normalized, "沟通", "冲突", "误会", "尴尬", "关系", "情绪", "争执", "道歉", "安慰", "拒绝")
                && containsAny(normalized, "怎么", "如何", "帮我", "建议", "回复", "表达", "处理"));
    }

    private boolean isExplicitTodoRequest(String normalized) {
        if (containsAny(normalized,
                "todo.create", "创建待办", "创建任务", "新增待办", "加个待办",
                "提醒我", "提醒一下", "稍后提醒", "明天提醒", "设置提醒", "设个提醒", "加个提醒", "记个待办")) {
            return true;
        }
        return containsAny(normalized, "待办", "todo", "提醒", "任务", "截止", "到期", "deadline", "due")
                && containsAny(normalized, "帮我", "请", "安排", "创建", "新增", "添加", "记下", "记一下", "记录", "提醒", "设", "加个");
    }

    private boolean isExplicitCodeRequest(String normalized) {
        if (containsAny(normalized, "code.generate", "generate code", "生成代码", "写代码", "代码草稿")) {
            return true;
        }
        boolean strongCodeDomain = containsAny(normalized,
                "代码", "api", "dto", "controller", "sql", "脚本", "方法", "类", "bug", "报错", "异常");
        boolean interfaceDomain = containsAny(normalized, "接口");
        boolean strongAction = containsAny(normalized,
                "修复", "fix", "排查", "定位", "debug", "分析", "实现", "生成", "编写", "开发", "重构", "review", "优化", "调整", "补");
        boolean requestCue = containsAny(normalized, "帮我", "请", "需要", "想要", "如何", "怎么", "麻烦");
        return (strongCodeDomain && (strongAction || requestCue))
                || (interfaceDomain && strongAction);
    }

    private boolean isExplicitFileSearchRequest(String normalized) {
        if (containsAny(normalized,
                "file.search", "search file", "search files", "grep",
                "找文件", "查文件", "搜索文件", "搜文件", "搜目录", "搜路径")) {
            return true;
        }
        return containsAny(normalized, "文件", "目录", "路径", "path", "folder")
                && containsAny(normalized, "找", "查", "搜索", "搜", "定位", "在哪个文件", "哪个文件", "检索", "grep");
    }

    private boolean hasRequestedSpecificRoutingKeyword(String userInput, String normalizedInput, String skillName) {
        if (skillCatalog == null || skillName == null || skillName.isBlank()) {
            return false;
        }
        String normalized = normalizedInput == null ? normalize(userInput) : normalizedInput;
        if (!looksLikeDirectRequest(normalized)) {
            return false;
        }
        for (String keyword : skillCatalog.resolvedRoutingKeywords(skillName)) {
            String candidate = normalize(keyword);
            if (candidate.isBlank() || isGenericRoutingKeyword(candidate)) {
                continue;
            }
            if (normalized.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private boolean looksLikeDirectRequest(String normalized) {
        return containsAny(normalized,
                "帮我", "请", "麻烦", "给我", "来个", "做一个", "做个", "安排",
                "生成", "写", "创建", "查", "找", "搜索", "提醒", "修复", "分析",
                "规划", "计划", "怎么", "如何", "能不能", "可以帮");
    }

    private boolean isGenericRoutingKeyword(String keyword) {
        return keyword == null || keyword.isBlank() || keyword.codePointCount(0, keyword.length()) <= 2;
    }

    private Optional<SemanticAnalysisResult> heuristicContinuationAnalysis(String userInput, String memoryContext) {
        String normalized = normalize(userInput);
        if (!looksLikeContinuationFollowUp(userInput, normalized) || memoryContext == null || memoryContext.isBlank()) {
            return Optional.empty();
        }
        ContinuationContext continuation = resolveContinuationContext(memoryContext);
        if (continuation.isEmpty()) {
            return Optional.empty();
        }
        SemanticAnalysisResult inherited = continuation.focus().isBlank()
                ? SemanticAnalysisResult.empty()
                : heuristicAnalysis(continuation.focus());
        String inheritedSkill = firstNonBlankString(continuation.skillName(), inherited.suggestedSkill());
        String suggestedSkill = shouldCarryExecutionSkill(normalized) ? inheritedSkill : "";
        Map<String, Object> payload = buildContinuationPayload(suggestedSkill, continuation.payload(), inherited, continuation.focus());
        String focus = firstNonBlankString(continuation.focus(), resolveContinuationFocus(payload), inherited.taskFocus());
        if (focus.isBlank()) {
            return Optional.empty();
        }
        String rewrittenInput = buildContinuationRewrittenInput(userInput, focus);
        String intent = buildContinuationIntent(normalized, suggestedSkill);
        String summary = buildContinuationSummary(normalized, focus);
        double confidence = resolveContinuationConfidence(normalized, suggestedSkill);
        List<String> keywords = resolveContinuationKeywords(userInput, rewrittenInput, normalized, suggestedSkill);
        List<SemanticAnalysisResult.CandidateIntent> candidateIntents = suggestedSkill.isBlank()
                ? List.of()
                : List.of(new SemanticAnalysisResult.CandidateIntent(suggestedSkill, 0.88));
        return Optional.of(new SemanticAnalysisResult(
                "heuristic",
                intent,
                rewrittenInput,
                suggestedSkill,
                payload,
                keywords,
                summary,
                confidence,
                candidateIntents
        ));
    }

    private SemanticAnalysisResult heuristicRealtimeAnalysis(String userInput, String normalized) {
        if (normalized == null || normalized.length() < 6) {
            return null;
        }
        if (containsAny(normalized, SEMANTIC_META_HINTS.toArray(String[]::new))) {
            return null;
        }
        boolean explicitNewsIntent = containsAny(normalized,
                "新闻", "资讯", "快讯", "头条", "热搜", "最新新闻", "今日新闻", "国际新闻", "news");
        if (explicitNewsIntent
                && matchesSkill(userInput, normalized, "news.lookup", "新闻", "资讯", "快讯", "头条", "热搜", "最新新闻", "今日新闻", "国际新闻", "news")) {
            return buildRealtimeSemanticAnalysis(
                    userInput,
                    "获取最新新闻资讯",
                    "news.lookup",
                    "用户请求获取实时新闻资讯",
                    0.89,
                    Map.of("query", userInput.trim(), "domain", "news"),
                    routingKeywordHints(userInput, "news.lookup", "新闻", "资讯", "头条", "快讯", "热搜"),
                    List.of(new SemanticAnalysisResult.CandidateIntent("news.lookup", 0.94), new SemanticAnalysisResult.CandidateIntent("mcp.bravesearch.webSearch", 0.80))
            );
        }
        boolean explicitWeatherIntent = containsAny(normalized,
                "天气", "气温", "空气质量", "pm2.5", "天气预报", "weather", "forecast");
        if (explicitWeatherIntent
                && matchesSkill(userInput, normalized, "mcp.bravesearch.webSearch", "天气", "气温", "空气质量", "pm2.5", "天气预报", "weather", "forecast")) {
            return buildRealtimeSemanticAnalysis(
                    userInput,
                    "查询实时天气信息",
                    "mcp.bravesearch.webSearch",
                    "用户请求查询最新天气或空气质量信息",
                    0.88,
                    Map.of("query", userInput.trim(), "domain", "weather"),
                    routingKeywordHints(userInput, "mcp.bravesearch.webSearch", "天气", "气温", "空气质量", "天气预报"),
                    List.of(new SemanticAnalysisResult.CandidateIntent("mcp.bravesearch.webSearch", 0.93))
            );
        }
        boolean explicitTravelIntent = containsAny(normalized,
                "航班", "列车", "高铁", "火车", "机票", "车票", "延误", "出发", "到达", "路况", "交通", "出行", "旅行", "行程", "flight", "train", "traffic", "travel");
        if (explicitTravelIntent
                && matchesSkill(userInput, normalized, "mcp.bravesearch.webSearch", "航班", "列车", "高铁", "火车", "机票", "车票", "延误", "出发", "到达", "路况", "交通", "出行", "旅行", "行程", "flight", "train", "traffic", "travel")) {
            return buildRealtimeSemanticAnalysis(
                    userInput,
                    "查询实时出行信息",
                    "mcp.bravesearch.webSearch",
                    "用户请求查询实时出行或路况信息",
                    0.86,
                    Map.of("query", userInput.trim(), "domain", "travel"),
                    routingKeywordHints(userInput, "mcp.bravesearch.webSearch", "航班", "列车", "高铁", "火车", "路况", "交通", "出行"),
                    List.of(new SemanticAnalysisResult.CandidateIntent("mcp.bravesearch.webSearch", 0.91))
            );
        }
        boolean explicitMarketIntent = containsAny(normalized,
                "股价", "股票", "基金", "汇率", "行情", "指数", "大盘", "市场", "stock", "market", "exchange");
        if (explicitMarketIntent
                && matchesSkill(userInput, normalized, "mcp.bravesearch.webSearch", "股价", "股票", "基金", "汇率", "行情", "指数", "大盘", "市场", "stock", "market", "exchange")) {
            return buildRealtimeSemanticAnalysis(
                    userInput,
                    "查询实时行情信息",
                    "mcp.bravesearch.webSearch",
                    "用户请求查询最新行情或汇率信息",
                    0.87,
                    Map.of("query", userInput.trim(), "domain", "market"),
                    routingKeywordHints(userInput, "mcp.bravesearch.webSearch", "股价", "股票", "基金", "汇率", "行情", "指数"),
                    List.of(new SemanticAnalysisResult.CandidateIntent("mcp.bravesearch.webSearch", 0.92))
            );
        }
        return null;
    }

    private SemanticAnalysisResult buildRealtimeSemanticAnalysis(String userInput,
                                                                 String intent,
                                                                 String suggestedSkill,
                                                                 String summary,
                                                                 double confidence,
                                                                 Map<String, Object> payload,
                                                                 List<String> keywords,
                                                                 List<SemanticAnalysisResult.CandidateIntent> candidateIntents) {
        return new SemanticAnalysisResult(
                "heuristic",
                intent,
                userInput == null ? "" : userInput.trim(),
                suggestedSkill,
                payload == null ? Map.of() : payload,
                keywords == null ? List.of() : keywords,
                summary,
                confidence,
                candidateIntents == null ? List.of() : candidateIntents
        );
    }

    private SemanticAnalysisResult sanitize(SemanticAnalysisResult result, String originalInput) {
        if (result == null) {
            return SemanticAnalysisResult.empty();
        }
        String suggestedSkill = sanitizeSuggestedSkill(result.suggestedSkill());
        String rewrittenInput = result.hasRewrittenInput() ? result.rewrittenInput().trim() : originalInput == null ? "" : originalInput.trim();
        String summary = normalizeSanitizedSummary(result.summary(), rewrittenInput, originalInput);
        String intent = normalizeSanitizedIntent(result.intent(), summary);
        List<SemanticAnalysisResult.CandidateIntent> candidateIntents = sanitizeCandidateIntents(result.candidateIntents());
        return new SemanticAnalysisResult(
                result.source(),
                intent,
                rewrittenInput,
                suggestedSkill,
                result.payload(),
                result.keywords(),
                summary,
                result.confidence(),
                candidateIntents
        );
    }

    private Map<String, Object> resolvePayloadMap(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Object payloadObj = raw.get("payload");
        if (!(payloadObj instanceof Map) && raw.get("params") instanceof Map) {
            payloadObj = raw.get("params");
        }
        if (payloadObj instanceof Map<?, ?> mapVal) {
            return toStringObjectMap(mapVal);
        }
        return Map.of();
    }

    private void logMalformedPayloadIfNeeded(Map<String, Object> raw, Map<String, Object> cleaned) {
        try {
            if (raw != null && !(raw.get("payload") instanceof Map)) {
                LOGGER.fine("semantic.analysis.validate.cleaned payload was non-map; cleaned=" + cleaned);
            }
        } catch (RuntimeException ex) {
            LOGGER.log(Level.FINE, "Failed to log semantic cleaned payload", ex);
        }
    }

    private List<String> parseKeywords(Object rawKeywords) {
        if (rawKeywords instanceof List<?>) {
            return toStringList(rawKeywords);
        }
        if (rawKeywords instanceof String rawText) {
            List<String> parts = new ArrayList<>();
            for (String part : rawText.split("[,;\\s]+")) {
                String normalized = stringValue(part);
                if (!normalized.isBlank()) {
                    parts.add(normalized);
                }
            }
            return parts.isEmpty() ? List.of() : List.copyOf(parts);
        }
        return List.of();
    }

    private double clampConfidence(double confidence) {
        if (Double.isNaN(confidence) || confidence < 0.0) {
            return 0.0;
        }
        if (confidence > 1.0) {
            return 1.0;
        }
        return confidence;
    }

    private String resolvedIntent(Map<String, Object> raw) {
        String intent = stringValue(raw.get("intent"));
        if (!intent.isBlank()) {
            return intent;
        }
        return stringValue(raw.get("summary"));
    }

    private String resolvedSummary(Map<String, Object> raw, String rewrittenInput, String intent) {
        String summary = stringValue(raw.get("summary"));
        if (!summary.isBlank()) {
            return summary;
        }
        return !rewrittenInput.isBlank() ? rewrittenInput : intent;
    }

    private void logMissingSemanticFields(String source,
                                          Map<String, Object> raw,
                                          String intent,
                                          String summary,
                                          String suggestedSkill,
                                          Map<String, Object> payload) {
        try {
            List<String> missing = new ArrayList<>();
            if (intent.isBlank()) missing.add("intent");
            if (summary.isBlank()) missing.add("summary");
            if (suggestedSkill.isBlank()) missing.add("suggestedSkill");
            if (payload.isEmpty()) missing.add("payload");
            if (!missing.isEmpty()) {
                LOGGER.info("semantic.analysis.parse.missing-fields source=" + source + " missing=" + missing + " rawKeys=" + raw.keySet());
            }
        } catch (RuntimeException ex) {
            LOGGER.log(Level.FINE, "Failed to log semantic.parse missing fields", ex);
        }
    }

    private String sanitizeSuggestedSkill(String rawSuggestedSkill) {
        String suggestedSkill = normalizeSkillName(rawSuggestedSkill);
        if (isInternalSemanticRouteSkill(suggestedSkill)) {
            return "";
        }
        if (suggestedSkill.startsWith("im.") || suggestedSkill.startsWith("internal.")) {
            return "";
        }
        if (DecisionCapabilityCatalog.isDecisionCapability(suggestedSkill)) {
            return suggestedSkill;
        }
        if (!suggestedSkill.isBlank() && skillRegistry.getSkill(suggestedSkill).isEmpty()) {
            return "";
        }
        return suggestedSkill;
    }

    private String normalizeSanitizedSummary(String rawSummary, String rewrittenInput, String originalInput) {
        String summary = stringValue(rawSummary);
        if (!summary.isBlank()) {
            return summary;
        }
        return capText(rewrittenInput.isBlank() ? stringValue(originalInput) : rewrittenInput, 90);
    }

    private String normalizeSanitizedIntent(String rawIntent, String summary) {
        String intent = stringValue(rawIntent);
        return intent.isBlank() ? summary : intent;
    }

    private List<SemanticAnalysisResult.CandidateIntent> sanitizeCandidateIntents(List<SemanticAnalysisResult.CandidateIntent> candidateIntents) {
        if (candidateIntents == null || candidateIntents.isEmpty()) {
            return List.of();
        }
        return candidateIntents.stream()
                .filter(candidate -> !isInternalSemanticRouteSkill(candidate.intent()))
                .filter(candidate -> candidate.intent() != null
                        && !candidate.intent().startsWith("im.")
                        && !candidate.intent().startsWith("internal."))
                .toList();
    }

    private boolean isInternalSemanticRouteSkill(String skillName) {
        if (skillName == null || skillName.isBlank()) {
            return false;
        }
        String normalized = skillName.trim();
        return INTERNAL_SEMANTIC_ROUTE_SKILLS.stream().anyMatch(normalized::equals);
    }

    private String capText(String value, int maxLength) {
        String normalized = stringValue(value);
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    private String normalizeSkillName(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        return normalized.isBlank() ? "" : normalized;
    }

    private String firstNonBlankString(Object... values) {
        if (values == null) {
            return "";
        }
        for (Object value : values) {
            String normalized = stringValue(value);
            if (!normalized.isBlank()) {
                return normalized;
            }
        }
        return "";
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private double numberValue(Object value, double defaultValue) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private List<String> toStringList(Object raw) {
        if (!(raw instanceof List<?> values)) {
            return List.of();
        }
        List<String> results = new ArrayList<>();
        for (Object value : values) {
            String normalized = stringValue(value);
            if (!normalized.isBlank()) {
                results.add(normalized);
            }
        }
        return List.copyOf(results);
    }

    private Map<String, Object> toStringObjectMap(Map<?, ?> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        raw.forEach((key, value) -> normalized.put(String.valueOf(key), value));
        return normalized;
    }

    private String extractJsonBody(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Matcher matcher = JSON_BLOCK_PATTERN.matcher(raw);
        return matcher.find() ? matcher.group() : null;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private String resolveSemanticLlmProvider() {
        if (forceLocalProvider || llmProvider == null || llmProvider.isBlank()) {
            return SEMANTIC_LOCAL_PROVIDER;
        }
        String normalized = llmProvider.trim().toLowerCase(Locale.ROOT);
        if ("local".equals(normalized) || "ollama".equals(normalized) || "gemma".equals(normalized)) {
            return SEMANTIC_LOCAL_PROVIDER;
        }
        return normalized;
    }

    private String resolveSemanticLocalModel() {
        return llmModel;
    }

    private String resolveSemanticCloudProvider() {
        if (semanticCloudProvider == null || semanticCloudProvider.isBlank()) {
            return "";
        }
        String normalized = semanticCloudProvider.trim().toLowerCase(Locale.ROOT);
        if ("ollama".equals(normalized) || "gemma".equals(normalized)) {
            return SEMANTIC_LOCAL_PROVIDER;
        }
        return normalized;
    }

    private boolean containsAny(String normalized, String... terms) {
        for (String term : terms) {
            if (normalized.contains(term.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesSkill(String originalInput, String normalizedInput, String skillName, String... fallbackTerms) {
        if (skillCatalog.routingScore(skillName, originalInput) > 0) {
            return true;
        }
        return containsAny(normalizedInput, fallbackTerms);
    }

    private List<String> routingKeywordHints(String userInput, String skillName, String... fallbackTerms) {
        List<String> matched = new ArrayList<>();
        String normalized = normalize(userInput);
        for (String keyword : skillCatalog.resolvedRoutingKeywords(skillName)) {
            String candidate = normalize(keyword);
            if (!candidate.isBlank() && normalized.contains(candidate)) {
                matched.add(keyword);
            }
            if (matched.size() >= 6) {
                break;
            }
        }
        return matched.isEmpty() ? extractKeywords(userInput, fallbackTerms) : List.copyOf(new LinkedHashSet<>(matched));
    }

    private List<String> extractKeywords(String userInput, String... priorityTerms) {
        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        if (priorityTerms != null) {
            for (String term : priorityTerms) {
                if (term == null || term.isBlank()) {
                    continue;
                }
                if (userInput != null && userInput.toLowerCase(Locale.ROOT).contains(term.toLowerCase(Locale.ROOT))) {
                    keywords.add(term);
                }
            }
        }
        String normalized = normalize(userInput);
        String[] parts = normalized.split("[^\\p{L}\\p{N}.#_-]+");
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (part.length() == 1 && part.codePoints().noneMatch(code -> Character.UnicodeScript.of(code) == Character.UnicodeScript.HAN)) {
                continue;
            }
            keywords.add(part);
            if (keywords.size() >= 6) {
                break;
            }
        }
        return keywords.isEmpty() ? List.of() : List.copyOf(keywords);
    }

    private boolean looksLikeContinuationFollowUp(String userInput, String normalized) {
        if (normalized.isBlank()) {
            return false;
        }
        if (looksLikeBlockingFollowUp(normalized)
                || looksLikePlanningFollowUp(normalized)
                || looksLikeProgressReportFollowUp(normalized)
                || looksLikeChoiceSelectionFollowUp(normalized)
                || looksLikeDecisionFollowUp(normalized)) {
            return true;
        }
        if (containsAny(normalized,
                "继续", "接着", "按刚才", "按这个", "按上面", "就按这个", "就这个",
                "开始吧", "开始执行", "执行吧", "帮我推进", "推进一下", "往下做",
                "照这个", "那就这样", "就这样", "按之前", "按上次",
                "暂停", "先这样", "先别", "晚点", "搁置",
                "完成了", "搞定了", "结束了", "处理完了",
                "提醒我", "提醒一下", "记得", "明天提醒", "稍后提醒")) {
            return true;
        }
        String trimmed = stringValue(userInput);
        return trimmed.length() <= 4 && containsAny(normalized, "可以", "好的", "行", "好", "嗯");
    }

    private boolean looksLikeBlockingFollowUp(String normalized) {
        return containsAny(normalized,
                "卡住", "受阻", "阻塞", "报错", "失败了", "不通",
                "没权限", "无权限", "无法", "做不了", "遇到问题", "出问题", "异常");
    }

    private boolean looksLikePlanningFollowUp(String normalized) {
        return containsAny(normalized,
                "方案", "计划", "步骤", "拆解", "拆一下", "怎么做",
                "下一步怎么", "给我个思路", "先给方案", "先出个提纲", "排期", "框架");
    }

    private boolean looksLikeProgressReportFollowUp(String normalized) {
        return containsAny(normalized,
                "进展", "状态", "目前", "处理到", "做到", "同步一下", "汇报",
                "已经发", "已经提交", "已经同步", "刚发", "刚提交", "刚同步",
                "更新一下进展", "现在到");
    }

    private boolean looksLikeDecisionFollowUp(String normalized) {
        return containsAny(normalized,
                "改成", "换成", "加上", "补一下", "优先", "目标是",
                "截止改到", "改到", "调整为");
    }

    private boolean looksLikeChoiceSelectionFollowUp(String normalized) {
        return containsAny(normalized,
                "第一个", "第二个", "第三个",
                "第一种", "第二种", "第三种",
                "第一版", "第二版", "第三版",
                "第一套", "第二套", "第三套",
                "前一个", "后一个", "前一种", "后一种",
                "前面那个", "后面那个", "上一个方案", "下一个方案");
    }

    private boolean shouldCarryExecutionSkill(String normalized) {
        return containsAny(normalized,
                "开始吧", "开始执行", "执行吧", "就按这个", "按刚才", "按这个",
                "按之前", "按之前方式", "继续按之前", "继续按之前方式", "按上次", "照之前",
                "帮我推进", "推进一下", "照这个", "那就这样", "就这样", "开工", "开始做");
    }

    private ContinuationContext resolveContinuationContext(String memoryContext) {
        if (memoryContext == null || memoryContext.isBlank()) {
            return ContinuationContext.empty();
        }
        String focus = "";
        String skillName = "";
        Map<String, Object> payload = new LinkedHashMap<>();
        for (String rawLine : memoryContext.split("\\R")) {
            String line = normalizeContextLine(rawLine);
            if (line.isBlank()
                    || "none".equalsIgnoreCase(line)
                    || line.endsWith(":")
                    || line.startsWith("Recent conversation")
                    || line.startsWith("Relevant knowledge")
                    || line.startsWith("User skill habits")) {
                continue;
            }
            skillName = firstNonBlankString(skillName,
                    extractValueAfterAny(line, "可用执行方式：", "执行方式："));
            extractContinuationPayload(line, payload);
            focus = firstNonBlankString(
                    focus,
                    normalizeContinuationFocus(extractValueAfterAny(line,
                            "当前事项：", "任务：", "事项：", "事项标题：", "目标：",
                            "用户当前想要：", "用户刚才在处理：", "主题："))
            );
        }
        focus = firstNonBlankString(focus, resolveContinuationFocus(payload));
        if (focus.isBlank() && skillName.isBlank()) {
            return ContinuationContext.empty();
        }
        return new ContinuationContext(focus, skillName, payload.isEmpty() ? Map.of() : Map.copyOf(payload));
    }

    private String normalizeContextLine(String rawLine) {
        String line = stringValue(rawLine);
        if (line.startsWith("- ")) {
            line = line.substring(2).trim();
        }
        if (line.startsWith("persisted rollup:")) {
            line = line.substring("persisted rollup:".length()).trim();
        }
        if (line.startsWith("earlier summary:")) {
            line = line.substring("earlier summary:".length()).trim();
        }
        return line;
    }

    private void extractContinuationPayload(String line, Map<String, Object> payload) {
        putContinuationValue(payload, "task", line, "当前事项：", "任务：", "事项：");
        putContinuationValue(payload, "title", line, "事项标题：");
        putContinuationValue(payload, "goal", line, "目标：");
        putContinuationValue(payload, "project", line, "项目：");
        putContinuationValue(payload, "topic", line, "主题：");
        putContinuationValue(payload, "dueDate", line, "截止时间：", "截止：", "到期时间：", "到期：");
        putContinuationValue(payload, "owner", line, "负责人：");
        putContinuationValue(payload, "location", line, "地点：", "位置：");
        putContinuationValue(payload, "query", line, "查询：", "关键词：");

        String kvSource = firstNonBlankString(
                extractValueAfterAny(line, "已确认信息：", "关键信息："),
                line.contains("=") ? line : ""
        );
        if (!kvSource.isBlank()) {
            parseKeyValueFragments(kvSource, payload);
        }
    }

    private void putContinuationValue(Map<String, Object> payload,
                                      String key,
                                      String line,
                                      String... prefixes) {
        if (payload.containsKey(key)) {
            return;
        }
        String value = extractValueAfterAny(line, prefixes);
        if (!value.isBlank()) {
            payload.put(key, value);
        }
    }

    private void parseKeyValueFragments(String text, Map<String, Object> payload) {
        if (text == null || text.isBlank()) {
            return;
        }
        for (String fragment : text.split("[,，;；|]")) {
            String candidate = stringValue(fragment);
            if (candidate.isBlank() || !candidate.contains("=")) {
                continue;
            }
            String[] pair = candidate.split("=", 2);
            if (pair.length != 2) {
                continue;
            }
            String key = stringValue(pair[0]);
            String value = stringValue(pair[1]);
            if (!key.isBlank() && !value.isBlank() && !payload.containsKey(key)) {
                payload.put(key, value);
            }
        }
    }

    private String extractValueAfterAny(String line, String... prefixes) {
        if (line == null || line.isBlank() || prefixes == null) {
            return "";
        }
        for (String prefix : prefixes) {
            if (prefix == null || prefix.isBlank()) {
                continue;
            }
            int index = line.indexOf(prefix);
            if (index < 0) {
                continue;
            }
            String candidate = line.substring(index + prefix.length()).trim();
            for (String separator : List.of("；", ";", "|")) {
                int separatorIndex = candidate.indexOf(separator);
                if (separatorIndex >= 0) {
                    candidate = candidate.substring(0, separatorIndex).trim();
                }
            }
            if (!candidate.isBlank()) {
                return candidate;
            }
        }
        return "";
    }

    private String normalizeContinuationFocus(String focus) {
        String candidate = stringValue(focus);
        if (candidate.isBlank()) {
            return "";
        }
        String normalized = normalize(candidate);
        if (containsAny(normalized,
                "用户希望", "用户请求", "用户要",
                "生成学习/教学规划", "创建待办或提醒事项", "生成或整理代码实现方案",
                "搜索文件或目录中的目标内容", "分析沟通场景并生成情商沟通建议",
                "获取最新新闻资讯", "查询实时天气信息", "查询实时出行信息", "查询实时行情信息")) {
            return "";
        }
        return candidate;
    }

    private Map<String, Object> buildContinuationPayload(String suggestedSkill,
                                                         Map<String, Object> inheritedPayload,
                                                         SemanticAnalysisResult inherited,
                                                         String focus) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (inherited != null && inherited.payload() != null && !inherited.payload().isEmpty()) {
            payload.putAll(inherited.payload());
        }
        if (inheritedPayload != null && !inheritedPayload.isEmpty()) {
            payload.putAll(inheritedPayload);
        }
        if (focus != null && !focus.isBlank()) {
            payload.putIfAbsent("taskFocus", focus);
        }
        payload.putIfAbsent("continuation", true);
        if (suggestedSkill == null || suggestedSkill.isBlank() || focus == null || focus.isBlank()) {
            return payload.isEmpty() ? Map.of() : Map.copyOf(payload);
        }
        String executionTarget = DecisionCapabilityCatalog.executionTarget(suggestedSkill);
        switch (executionTarget) {
            case "todo.create", "code.generate" -> payload.putIfAbsent("task", focus);
            case "file.search" -> {
                payload.putIfAbsent("keyword", focus);
                payload.putIfAbsent("path", "./");
            }
            default -> {
                if (suggestedSkill.contains("search") || suggestedSkill.contains("Search")) {
                    payload.putIfAbsent("query", focus);
                }
            }
        }
        return payload.isEmpty() ? Map.of() : Map.copyOf(payload);
    }

    private String resolveContinuationFocus(Map<String, Object> payload) {
        return firstNonBlankString(
                payload == null ? null : payload.get("task"),
                payload == null ? null : payload.get("title"),
                payload == null ? null : payload.get("goal"),
                payload == null ? null : payload.get("project"),
                payload == null ? null : payload.get("topic"),
                payload == null ? null : payload.get("query"),
                payload == null ? null : payload.get("taskFocus")
        );
    }

    private String buildContinuationRewrittenInput(String userInput, String focus) {
        String trimmed = stringValue(userInput);
        String normalized = normalize(trimmed);
        if (focus == null || focus.isBlank()) {
            return trimmed;
        }
        if (containsAny(normalized, "暂停", "先这样", "先别", "晚点", "搁置")) {
            return "暂停当前事项：" + focus;
        }
        if (containsAny(normalized, "完成了", "搞定了", "结束了", "处理完了")) {
            return "将当前事项标记为完成：" + focus;
        }
        if (containsAny(normalized, "提醒我", "提醒一下", "记得", "明天提醒", "稍后提醒")) {
            return "为当前事项设置提醒：" + focus;
        }
        if (looksLikeBlockingFollowUp(normalized)) {
            return "当前事项遇到阻塞：" + focus;
        }
        if (looksLikePlanningFollowUp(normalized)) {
            return "为当前事项制定下一步方案：" + focus;
        }
        if (looksLikeProgressReportFollowUp(normalized)) {
            return "更新当前事项进展：" + focus;
        }
        if (looksLikeChoiceSelectionFollowUp(normalized) && !trimmed.contains(focus)) {
            return trimmed + "，当前事项：" + focus;
        }
        if (looksLikeDecisionFollowUp(normalized) && !trimmed.contains(focus)) {
            return trimmed + "，当前事项：" + focus;
        }
        if (trimmed.length() <= 6 || looksLikeContinuationFollowUp(trimmed, normalized)) {
            return "继续推进：" + focus;
        }
        if (trimmed.contains(focus)) {
            return trimmed;
        }
        return trimmed + "，继续围绕：" + focus;
    }

    private String buildContinuationIntent(String normalized, String suggestedSkill) {
        if (looksLikePlanningFollowUp(normalized)) {
            return "围绕当前任务整理方案或步骤";
        }
        if (looksLikeBlockingFollowUp(normalized)) {
            return "围绕当前任务说明阻塞并寻求推进";
        }
        if (looksLikeProgressReportFollowUp(normalized)) {
            return "同步当前任务进展或状态";
        }
        if (looksLikeChoiceSelectionFollowUp(normalized)) {
            return "在当前事项中选择候选方案或执行方向";
        }
        if (looksLikeDecisionFollowUp(normalized)) {
            return "调整当前任务约束或执行方向";
        }
        return suggestedSkill.isBlank()
                ? "延续当前上下文并继续推进"
                : "延续当前任务并按已有方案执行";
    }

    private String buildContinuationSummary(String normalized, String focus) {
        String clippedFocus = capText(focus, 60);
        if (looksLikePlanningFollowUp(normalized)) {
            return "用户想先明确当前事项的方案或步骤：" + clippedFocus;
        }
        if (looksLikeBlockingFollowUp(normalized)) {
            return "用户表示当前事项遇到阻塞：" + clippedFocus;
        }
        if (looksLikeProgressReportFollowUp(normalized)) {
            return "用户在同步当前事项进展：" + clippedFocus;
        }
        if (looksLikeChoiceSelectionFollowUp(normalized)) {
            return "用户在为当前事项选择执行方案：" + clippedFocus;
        }
        if (looksLikeDecisionFollowUp(normalized)) {
            return "用户在调整当前事项要求：" + clippedFocus;
        }
        return "用户希望继续推进当前事项：" + clippedFocus;
    }

    private double resolveContinuationConfidence(String normalized, String suggestedSkill) {
        if (looksLikeBlockingFollowUp(normalized)
                || looksLikePlanningFollowUp(normalized)
                || looksLikeProgressReportFollowUp(normalized)
                || looksLikeChoiceSelectionFollowUp(normalized)
                || looksLikeDecisionFollowUp(normalized)) {
            return suggestedSkill.isBlank() ? 0.82 : 0.87;
        }
        return suggestedSkill.isBlank() ? 0.76 : 0.84;
    }

    private List<String> resolveContinuationKeywords(String userInput,
                                                     String rewrittenInput,
                                                     String normalized,
                                                     String suggestedSkill) {
        if (suggestedSkill != null && !suggestedSkill.isBlank()) {
            return routingKeywordHints(rewrittenInput, suggestedSkill, "继续", "推进", "刚才", "按这个");
        }
        if (looksLikeChoiceSelectionFollowUp(normalized)) {
            return extractKeywords(userInput, "第一种", "第二种", "第三种", "第一个", "第二个", "第三个");
        }
        if (looksLikeDecisionFollowUp(normalized)) {
            return extractKeywords(userInput, "改成", "换成", "调整", "目标", "优先");
        }
        if (looksLikePlanningFollowUp(normalized)) {
            return extractKeywords(userInput, "方案", "步骤", "计划");
        }
        if (looksLikeBlockingFollowUp(normalized)) {
            return extractKeywords(userInput, "卡住", "报错", "阻塞");
        }
        if (looksLikeProgressReportFollowUp(normalized)) {
            return extractKeywords(userInput, "进展", "状态", "同步");
        }
        return extractKeywords(rewrittenInput, "继续", "推进", "刚才", "按这个");
    }

    private String extractByPattern(String input, Pattern pattern) {
        if (input == null || pattern == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(input);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private record ContinuationContext(String focus, String skillName, Map<String, Object> payload) {
        private static ContinuationContext empty() {
            return new ContinuationContext("", "", Map.of());
        }

        private boolean isEmpty() {
            return (focus == null || focus.isBlank())
                    && (skillName == null || skillName.isBlank())
                    && (payload == null || payload.isEmpty());
        }
    }
}
