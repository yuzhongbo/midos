package com.zhongbo.mindos.assistant.skill.semantic;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private final LlmClient llmClient;
    private final SkillRegistry skillRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final boolean enabled;
    private final boolean llmEnabled;
    private final boolean forceLocalProvider;
    private final String delegateSkillName;
    private final String llmProvider;
    private final String llmPreset;
    private final int llmMaxTokens;
    private final boolean semanticLocalEscalationEnabled;
    private final String semanticCloudProvider;
    private final String semanticCloudPreset;
    private final double semanticLocalEscalationMinConfidence;

    @Autowired
    public SemanticAnalysisService(LlmClient llmClient,
                                   @Lazy SkillRegistry skillRegistry,
                                   @Value("${mindos.dispatcher.semantic-analysis.enabled:true}") boolean enabled,
                                   @Value("${mindos.dispatcher.semantic-analysis.llm-enabled:false}") boolean llmEnabled,
                                   @Value("${mindos.dispatcher.semantic-analysis.force-local:true}") boolean forceLocalProvider,
                                   @Value("${mindos.dispatcher.semantic-analysis.delegate-skill:}") String delegateSkillName,
                                   @Value("${mindos.dispatcher.semantic-analysis.llm-provider:local}") String llmProvider,
                                   @Value("${mindos.dispatcher.semantic-analysis.llm-preset:cost}") String llmPreset,
                                   @Value("${mindos.dispatcher.semantic-analysis.max-tokens:120}") int llmMaxTokens,
                                   @Value("${mindos.dispatcher.semantic-analysis.local-escalation.enabled:false}") boolean semanticLocalEscalationEnabled,
                                   @Value("${mindos.dispatcher.semantic-analysis.local-escalation.cloud-provider:qwen}") String semanticCloudProvider,
                                   @Value("${mindos.dispatcher.semantic-analysis.local-escalation.cloud-preset:quality}") String semanticCloudPreset,
                                   @Value("${mindos.dispatcher.semantic-analysis.local-escalation.min-confidence:0.78}") double semanticLocalEscalationMinConfidence) {
        this.llmClient = llmClient;
        this.skillRegistry = skillRegistry;
        this.enabled = enabled;
        this.llmEnabled = llmEnabled;
        this.forceLocalProvider = forceLocalProvider;
        this.delegateSkillName = delegateSkillName == null ? "" : delegateSkillName.trim();
        this.llmProvider = llmProvider == null ? "" : llmProvider.trim();
        this.llmPreset = llmPreset == null ? "" : llmPreset.trim();
        this.llmMaxTokens = Math.max(0, llmMaxTokens);
        this.semanticLocalEscalationEnabled = semanticLocalEscalationEnabled;
        this.semanticCloudProvider = semanticCloudProvider == null ? "" : semanticCloudProvider.trim();
        this.semanticCloudPreset = semanticCloudPreset == null ? "" : semanticCloudPreset.trim();
        this.semanticLocalEscalationMinConfidence = Math.max(0.0, Math.min(1.0, semanticLocalEscalationMinConfidence));
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
                llmMaxTokens,
                false,
                "qwen",
                "quality",
                0.78);
    }

    public SemanticAnalysisResult analyze(String userId,
                                          String userInput,
                                          String memoryContext,
                                          Map<String, Object> profileContext,
                                          List<String> availableSkillSummaries) {
        if (!enabled || userInput == null || userInput.isBlank()) {
            return SemanticAnalysisResult.empty();
        }
        SemanticAnalysisResult heuristic = heuristicAnalysis(userInput);
        SemanticAnalysisResult best = heuristic;

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
        String prompt = "You are a semantic intent analyzer for an AI assistant. "
                + "Return ONLY JSON with schema "
                + "{\"intent\":\"...\",\"rewrittenInput\":\"...\",\"suggestedSkill\":\"...\",\"payload\":{},"
                + "\"keywords\":[\"...\"],\"confidence\":0.0}. "
                + "If no local skill should be suggested, use an empty suggestedSkill.\n"
                + "Available skills: " + String.join(", ", availableSkillSummaries == null ? List.of() : availableSkillSummaries) + "\n"
                + "Memory context:\n" + (memoryContext == null ? "" : memoryContext) + "\n"
                + "User input:\n" + userInput;

        Optional<SemanticAnalysisResult> localResult = Optional.empty();
        if (semanticLocalEscalationEnabled) {
            localResult = callSemanticLlm(prompt, userId, userInput, profileContext, SEMANTIC_LOCAL_PROVIDER, llmPreset);
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
                    semanticCloudPreset.isBlank() ? llmPreset : semanticCloudPreset
            );
            if (cloudResult.isPresent() && (localResult.isEmpty() || cloudResult.get().confidence() >= localResult.get().confidence())) {
                LOGGER.info("semantic.analysis.local.escalate.accepted cloudConfidence=" + cloudResult.get().confidence());
                return cloudResult;
            }
            LOGGER.info("semantic.analysis.local.escalate.skipped-cloud-result");
            return localResult;
        }

        return callSemanticLlm(prompt, userId, userInput, profileContext, resolveSemanticLlmProvider(), llmPreset);
    }

    private Optional<SemanticAnalysisResult> callSemanticLlm(String prompt,
                                                             String userId,
                                                             String userInput,
                                                             Map<String, Object> profileContext,
                                                             String provider,
                                                             String preset) {
        try {
            Map<String, Object> context = buildSemanticLlmContext(userId, userInput, profileContext, provider, preset);
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
                                                         String preset) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("userId", userId == null ? "" : userId);
        context.put("routeStage", "semantic-analysis");
        context.put("input", userInput);
        context.put("llmProvider", provider == null || provider.isBlank() ? resolveSemanticLlmProvider() : provider);
        if (preset != null && !preset.isBlank()) {
            context.put("llmPreset", preset);
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
            Map<String, Object> payload = objectMapper.readValue(jsonBody, new TypeReference<>() {
            });
            return Optional.of(fromMap(payload, source));
        } catch (Exception ex) {
            LOGGER.log(Level.FINE, "Semantic analysis JSON parse failed", ex);
            return Optional.empty();
        }
    }

    private SemanticAnalysisResult fromMap(Map<String, Object> raw, String source) {
        if (raw == null || raw.isEmpty()) {
            return SemanticAnalysisResult.empty();
        }
        String intent = stringValue(raw.get("intent"));
        String rewrittenInput = stringValue(raw.get("rewrittenInput"));
        String suggestedSkill = normalizeSkillName(stringValue(raw.get("suggestedSkill")));
        double confidence = numberValue(raw.get("confidence"), 0.0);
        Map<String, Object> payload = raw.get("payload") instanceof Map<?, ?> nested
                ? toStringObjectMap(nested)
                : Map.of();
        List<String> keywords = toStringList(raw.get("keywords"));
        return new SemanticAnalysisResult(source, intent, rewrittenInput, suggestedSkill, payload, keywords, confidence);
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
                    0.92
            );
        }
        if (matchesSkill(userInput, normalized, "teaching.plan", "学习计划", "教学规划", "复习计划", "课程规划", "study plan", "teaching plan")) {
            return new SemanticAnalysisResult(
                    "heuristic",
                    "为用户生成学习/教学规划",
                    userInput.trim(),
                    "teaching.plan",
                    Map.of(),
                    routingKeywordHints(userInput, "teaching.plan", "学习计划", "教学规划", "复习计划"),
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
                0.35
        );
    }

    private SemanticAnalysisResult sanitize(SemanticAnalysisResult result, String originalInput) {
        if (result == null) {
            return SemanticAnalysisResult.empty();
        }
        String suggestedSkill = normalizeSkillName(result.suggestedSkill());
        if (!suggestedSkill.isBlank() && skillRegistry.getSkill(suggestedSkill).isEmpty()) {
            suggestedSkill = "";
        }
        String rewrittenInput = result.hasRewrittenInput() ? result.rewrittenInput().trim() : originalInput == null ? "" : originalInput.trim();
        return new SemanticAnalysisResult(
                result.source(),
                stringValue(result.intent()),
                rewrittenInput,
                suggestedSkill,
                result.payload(),
                result.keywords(),
                result.confidence()
        );
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
