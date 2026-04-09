package com.zhongbo.mindos.assistant.skill.semantic;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.zhongbo.mindos.assistant.common.LlmClient;
import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.Skill;
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
public class SemanticAnalysisService {

    private static final Logger LOGGER = Logger.getLogger(SemanticAnalysisService.class.getName());
    private static final Pattern JSON_BLOCK_PATTERN = Pattern.compile("\\{.*}", Pattern.DOTALL);
    private static final Pattern DUE_DATE_PATTERN = Pattern.compile("(?:截止|due|到期|deadline)\\s*(?:时间|日期|date)?\\s*[:：]?\\s*([^，。；;\\n]+)",
            Pattern.CASE_INSENSITIVE);
    private static final String SEMANTIC_LOCAL_PROVIDER = "local";
    private static final List<String> INTERNAL_SEMANTIC_ROUTE_SKILLS = List.of("semantic.analyze");
    private static final List<String> SEMANTIC_META_HINTS = List.of("语义", "分析", "路由", "结构化", "意图", "候选", "semantic", "解析");
    private static final int DEFAULT_LLM_COMPLEXITY_MIN_INPUT_CHARS = 10;
    private static final String DEFAULT_LLM_COMPLEXITY_TRIGGER_TERMS = "新闻,搜索,实时,分析,规划,计划,代码,排查,debug,search,latest,news,plan,report";

    private final LlmClient llmClient;
    private final SkillRegistry skillRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();
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

    public SemanticAnalysisResult analyze(String userId,
                                          String userInput,
                                          String memoryContext,
                                          Map<String, Object> profileContext,
                                          List<String> availableSkillSummaries) {
        if (!enabled || userInput == null || userInput.isBlank()) {
            return SemanticAnalysisResult.empty();
        }
        SemanticAnalysisResult best = heuristicAnalysis(userInput);

        Optional<SemanticAnalysisResult> delegated = analyzeWithDelegateSkill(userId, userInput, memoryContext, profileContext, availableSkillSummaries);
        if (delegated.isPresent() && delegated.get().confidence() >= best.confidence()) {
            best = delegated.get();
        }

        Optional<SemanticAnalysisResult> llm = analyzeWithLlm(userId, userInput, memoryContext, profileContext, availableSkillSummaries, best);
        if (llm.isPresent() && llm.get().confidence() >= best.confidence()) {
            best = llm.get();
        }
        return sanitize(best, userInput);
    }

    private Optional<SemanticAnalysisResult> analyzeWithDelegateSkill(String userId,
                                                                      String userInput,
                                                                      String memoryContext,
                                                                      Map<String, Object> profileContext,
                                                                      List<String> availableSkillSummaries) {
        if (delegateSkillName.isBlank() || "semantic.analyze".equals(delegateSkillName)) {
            return Optional.empty();
        }
        Optional<Skill> delegateSkill = skillRegistry.getSkill(delegateSkillName);
        if (delegateSkill.isEmpty()) {
            return Optional.empty();
        }
        try {
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("input", userInput);
            attributes.put("memoryContext", memoryContext == null ? "" : memoryContext);
            attributes.put("availableSkills", availableSkillSummaries == null ? List.of() : availableSkillSummaries);
            attributes.put("responseFormat", "json");
            if (profileContext != null && !profileContext.isEmpty()) {
                attributes.put("profile", profileContext);
            }
            SkillResult result = delegateSkill.get().run(new SkillContext(userId, userInput, attributes));
            if (result == null || !result.success()) {
                return Optional.empty();
            }
            return parseResult(result.output(), "skill:" + delegateSkillName);
        } catch (RuntimeException ex) {
            LOGGER.log(Level.WARNING, "Semantic delegate skill failed, fallback to local analysis", ex);
            return Optional.empty();
        }
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
        String prompt = "You are MindOS semantic dispatch analyzer. Return strict JSON only. "
                + "Required keys: intent, suggestedSkill, summary, confidence, payload (params is allowed as alias), keywords. "
                + "If no local skill applies, leave suggestedSkill empty and payload empty.\n"
                + "Available skills: " + summarizeAvailableSkills(availableSkillSummaries) + "\n"
                + "Memory context:\n" + capText(memoryContext, 1000) + "\n"
                + "User input:\n" + capText(userInput, 400);

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
            if (cloudResult.isPresent() && (localResult.isEmpty() || cloudResult.get().confidence() >= localResult.get().confidence())) {
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

        // Strings: intent, rewrittenInput, suggestedSkill, summary
        cleaned.put("intent", stringValue(raw.get("intent")));
        cleaned.put("rewrittenInput", stringValue(raw.get("rewrittenInput")));
        cleaned.put("suggestedSkill", stringValue(raw.get("suggestedSkill")));
        cleaned.put("summary", stringValue(raw.get("summary")));

        // Payload / params alias: prefer payload, fallback to params; coerce only if map-like
        Object payloadObj = raw.get("payload");
        if (!(payloadObj instanceof Map) && raw.get("params") instanceof Map) {
            payloadObj = raw.get("params");
        }
        if (payloadObj instanceof Map<?, ?> mapVal) {
            cleaned.put("payload", toStringObjectMap(mapVal));
        } else {
            // not a map -> treat as empty payload to avoid execution surprises
            cleaned.put("payload", Map.of());
        }
        // debug trace for malformed payloads to aid tests/diagnosis
        try {
            if (!(raw.get("payload") instanceof Map)) {
                LOGGER.fine("semantic.analysis.validate.cleaned payload was non-map; cleaned=" + cleaned);
            }
        } catch (RuntimeException ex) {
            // ignore logging issues
        }

        // Keywords: accept list of values or comma/space-separated string
        Object keywordsObj = raw.get("keywords");
        List<String> keywords = List.of();
        if (keywordsObj instanceof List<?>) {
            keywords = toStringList(keywordsObj);
        } else if (keywordsObj instanceof String s) {
            List<String> parts = new ArrayList<>();
            for (String p : s.split("[,;\\s]+")) {
                String norm = stringValue(p);
                if (!norm.isBlank()) parts.add(norm);
            }
            keywords = List.copyOf(parts);
        }
        cleaned.put("keywords", keywords);

        // Candidate intents: list of {intent, confidence}
        cleaned.put("candidate_intents", parseCandidateIntents(raw.get("candidate_intents")));

        // Confidence: coerce numeric or parseable string, clamp 0..1
        double conf = numberValue(raw.get("confidence"), 0.0);
        if (Double.isNaN(conf) || conf < 0.0) conf = 0.0;
        if (conf > 1.0) conf = 1.0;
        cleaned.put("confidence", conf);

        return cleaned;
    }

    private SemanticAnalysisResult fromMap(Map<String, Object> raw, String source) {
        if (raw == null || raw.isEmpty()) {
            return SemanticAnalysisResult.empty();
        }
        String intent = stringValue(raw.get("intent"));
        String rewrittenInput = stringValue(raw.get("rewrittenInput"));
        String suggestedSkill = normalizeSkillName(stringValue(raw.get("suggestedSkill")));
        double confidence = numberValue(raw.get("confidence"), 0.0);
        String summary = stringValue(raw.get("summary"));
        Map<String, Object> payload = raw.get("payload") instanceof Map<?, ?> nested
                ? toStringObjectMap(nested)
                : Map.of();
        if (payload.isEmpty() && raw.get("params") instanceof Map<?, ?> params) {
            payload = toStringObjectMap(params);
        }
        if (intent.isBlank()) {
            intent = summary;
        }
        if (summary.isBlank()) {
            summary = !rewrittenInput.isBlank() ? rewrittenInput : intent;
        }
        // Log when key semantic fields are missing/backfilled to aid debugging and tests
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
            // swallow logging errors to avoid affecting analysis flow
            LOGGER.log(Level.FINE, "Failed to log semantic.parse missing fields", ex);
        }
        List<String> keywords = toStringList(raw.get("keywords"));
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
                            Math.max(0.0, Math.min(1.0, candidate.confidence()))));
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
            double confidence = numberValue(map.get("confidence"), 0.0);
            confidence = Math.max(0.0, Math.min(1.0, confidence));
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
        if (matchesSkill(userInput, normalized, "teaching.plan", "学习计划", "教学规划", "复习计划", "课程规划", "study plan", "teaching plan")) {
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
        if (matchesSkill(userInput, normalized, "eq.coach", "情商", "沟通", "高情商", "心理分析", "怎么说", "安慰", "道歉", "冲突")) {
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
        if (matchesSkill(userInput, normalized, "todo.create", "待办", "todo", "提醒", "记得", "安排任务", "创建任务", "截止")) {
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
        if (matchesSkill(userInput, normalized, "code.generate", "代码", "接口", "api", "dto", "controller", "bug", "修复", "生成代码", "sql")) {
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
        if (matchesSkill(userInput, normalized, "file.search", "找文件", "查文件", "搜索文件", "search file", "grep", "目录", "路径")) {
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

    private SemanticAnalysisResult heuristicRealtimeAnalysis(String userInput, String normalized) {
        if (normalized == null || normalized.length() < 6) {
            return null;
        }
        if (containsAny(normalized, SEMANTIC_META_HINTS.toArray(String[]::new))) {
            return null;
        }
        if (matchesSkill(userInput, normalized, "news_search", "新闻", "资讯", "快讯", "头条", "热搜", "最新新闻", "今日新闻", "国际新闻", "news")) {
            return buildRealtimeSemanticAnalysis(
                    userInput,
                    "获取最新新闻资讯",
                    "news_search",
                    "用户请求获取实时新闻资讯",
                    0.89,
                    Map.of("query", userInput.trim(), "domain", "news"),
                    routingKeywordHints(userInput, "news_search", "新闻", "资讯", "头条", "快讯", "热搜"),
                    List.of(new SemanticAnalysisResult.CandidateIntent("news_search", 0.94), new SemanticAnalysisResult.CandidateIntent("mcp.bravesearch.webSearch", 0.80))
            );
        }
        if (matchesSkill(userInput, normalized, "mcp.bravesearch.webSearch", "天气", "气温", "空气质量", "pm2.5", "天气预报", "weather", "forecast")) {
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
        if (matchesSkill(userInput, normalized, "mcp.bravesearch.webSearch", "航班", "列车", "高铁", "火车", "机票", "车票", "延误", "出发", "到达", "路况", "交通", "出行", "旅行", "行程", "flight", "train", "traffic", "travel")) {
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
        if (matchesSkill(userInput, normalized, "mcp.bravesearch.webSearch", "股价", "股票", "基金", "汇率", "行情", "指数", "大盘", "市场", "stock", "market", "exchange")) {
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
        String suggestedSkill = normalizeSkillName(result.suggestedSkill());
        if (isInternalSemanticRouteSkill(suggestedSkill)) {
            suggestedSkill = "";
        }
        if (!suggestedSkill.isBlank() && skillRegistry.getSkill(suggestedSkill).isEmpty()) {
            suggestedSkill = "";
        }
        String rewrittenInput = result.hasRewrittenInput() ? result.rewrittenInput().trim() : originalInput == null ? "" : originalInput.trim();
        String summary = stringValue(result.summary());
        if (summary.isBlank()) {
            summary = capText(rewrittenInput.isBlank() ? stringValue(originalInput) : rewrittenInput, 90);
        }
        String intent = stringValue(result.intent());
        if (intent.isBlank()) {
            intent = summary;
        }
        List<SemanticAnalysisResult.CandidateIntent> candidateIntents = result.candidateIntents().stream()
                .filter(candidate -> !isInternalSemanticRouteSkill(candidate.intent()))
                .toList();
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

    private String summarizeAvailableSkills(List<String> availableSkillSummaries) {
        List<String> skills = availableSkillSummaries == null ? List.of() : availableSkillSummaries;
        if (skills.isEmpty()) {
            return "";
        }
        int limit = Math.min(8, skills.size());
        List<String> summarized = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            summarized.add(capText(skills.get(i), 72));
        }
        String joined = String.join(", ", summarized);
        if (skills.size() > limit) {
            joined += " …(+" + (skills.size() - limit) + ")";
        }
        return capText(joined, 320);
    }

    private String normalizeSkillName(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        return normalized.isBlank() ? "" : normalized;
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
        if (skillRegistry.routingScore(skillName, originalInput) > 0) {
            return true;
        }
        return containsAny(normalizedInput, fallbackTerms);
    }

    private List<String> routingKeywordHints(String userInput, String skillName, String... fallbackTerms) {
        List<String> matched = new ArrayList<>();
        String normalized = normalize(userInput);
        for (String keyword : skillRegistry.resolvedRoutingKeywords(skillName)) {
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

    private String extractByPattern(String input, Pattern pattern) {
        if (input == null || pattern == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(input);
        return matcher.find() ? matcher.group(1).trim() : null;
    }
}
