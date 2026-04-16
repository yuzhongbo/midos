package com.zhongbo.mindos.assistant.skill.semantic;

import com.zhongbo.mindos.assistant.skill.DecisionCapabilityCatalog;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record SemanticAnalysisResult(String source,
                                     String intent,
                                     String rewrittenInput,
                                     String suggestedSkill,
                                     Map<String, Object> payload,
                                     List<String> keywords,
                                     String summary,
                                     double confidence,
                                     List<CandidateIntent> candidateIntents) {

    public static final String ATTR_ANALYSIS_SOURCE = "semanticAnalysisSource";
    public static final String ATTR_INTENT = "semanticIntent";
    public static final String ATTR_REWRITTEN_INPUT = "semanticRewrittenInput";
    public static final String ATTR_SUGGESTED_SKILL = "semanticSuggestedSkill";
    public static final String ATTR_CONFIDENCE = "semanticConfidence";
    public static final String ATTR_SUMMARY = "semanticSummary";
    public static final String ATTR_PAYLOAD = "semanticPayload";
    public static final String ATTR_KEYWORDS = "semanticKeywords";
    public static final String ATTR_CANDIDATE_INTENTS = "semanticCandidateIntents";
    public static final String ATTR_INTENT_TYPE = "semanticIntentType";
    public static final String ATTR_CONTEXT_SCOPE = "semanticContextScope";
    public static final String ATTR_TOOL_REQUIRED = "semanticToolRequired";
    public static final String ATTR_MEMORY_OPERATION = "semanticMemoryOperation";
    public static final String ATTR_INTENT_STATE = "semanticIntentState";
    public static final String ATTR_TASK_FOCUS = "semanticTaskFocus";
    public static final String ATTR_FOLLOW_UP_MODE = "semanticFollowUpMode";
    public static final String ATTR_INTENT_PHASE = "semanticIntentPhase";
    public static final String ATTR_THREAD_RELATION = "semanticThreadRelation";

    private static final SemanticAnalysisResult EMPTY =
            new SemanticAnalysisResult("disabled", "", "", "", Map.of(), List.of(), "", 0.0, List.of());
    private static final double AMBIGUOUS_CHOICE_MAX_TOP_CONFIDENCE = 0.82d;
    private static final double AMBIGUOUS_CHOICE_MIN_SECOND_CONFIDENCE = 0.55d;
    private static final double AMBIGUOUS_CHOICE_MAX_GAP = 0.08d;

    public SemanticAnalysisResult(String source,
                                  String intent,
                                  String rewrittenInput,
                                  String suggestedSkill,
                                  Map<String, Object> payload,
                                  List<String> keywords,
                                  double confidence) {
        this(source, intent, rewrittenInput, suggestedSkill, payload, keywords, "", confidence, List.of());
    }

    public SemanticAnalysisResult(String source,
                                  String intent,
                                  String rewrittenInput,
                                  String suggestedSkill,
                                  Map<String, Object> payload,
                                  List<String> keywords,
                                  String summary,
                                  double confidence) {
        this(source, intent, rewrittenInput, suggestedSkill, payload, keywords, summary, confidence, List.of());
    }

    public SemanticAnalysisResult {
        payload = toImmutablePayload(payload);
        keywords = toImmutableKeywords(keywords);
        summary = normalizedText(summary);
        confidence = normalizedConfidence(confidence);
        candidateIntents = toImmutableCandidateIntents(candidateIntents);
    }

    public static SemanticAnalysisResult empty() {
        return EMPTY;
    }

    public boolean hasSuggestedSkill() {
        return hasText(suggestedSkill);
    }

    public boolean hasRewrittenInput() {
        return hasText(rewrittenInput);
    }

    public String routingInput(String originalInput) {
        return hasRewrittenInput() ? rewrittenInput : (originalInput == null ? "" : originalInput);
    }

    public boolean isConfident(double threshold) {
        return effectiveConfidence() >= threshold;
    }

    public double effectiveConfidence() {
        double candidateScore = confidenceForSkill(suggestedSkill);
        return Math.max(confidence, candidateScore);
    }

    public double confidenceForSkill(String skillName) {
        if (skillName == null || skillName.isBlank() || candidateIntents.isEmpty()) {
            return 0.0;
        }
        String normalized = skillName.trim();
        return candidateIntents.stream()
                .filter(candidate -> normalized.equals(candidate.intent()))
                .mapToDouble(CandidateIntent::confidence)
                .max()
                .orElse(0.0);
    }

    public boolean hasAmbiguousSkillChoice() {
        return ambiguousCandidateIntents().size() >= 2;
    }

    public List<CandidateIntent> ambiguousCandidateIntents() {
        List<CandidateIntent> ranked = rankedDistinctCandidateIntents();
        if (ranked.size() < 2) {
            return List.of();
        }
        CandidateIntent first = ranked.get(0);
        CandidateIntent second = ranked.get(1);
        if (first.confidence() > AMBIGUOUS_CHOICE_MAX_TOP_CONFIDENCE
                || second.confidence() < AMBIGUOUS_CHOICE_MIN_SECOND_CONFIDENCE
                || first.confidence() - second.confidence() > AMBIGUOUS_CHOICE_MAX_GAP) {
            return List.of();
        }
        return ranked.subList(0, Math.min(3, ranked.size()));
    }

    public Map<String, Object> asAttributes() {
        Map<String, Object> attributes = new LinkedHashMap<>(16);
        attributes.put(ATTR_ANALYSIS_SOURCE, emptyIfNull(source));
        attributes.put(ATTR_INTENT, emptyIfNull(intent));
        attributes.put(ATTR_REWRITTEN_INPUT, emptyIfNull(rewrittenInput));
        attributes.put(ATTR_SUGGESTED_SKILL, emptyIfNull(suggestedSkill));
        attributes.put(ATTR_CONFIDENCE, effectiveConfidence());
        attributes.put(ATTR_INTENT_TYPE, intentType());
        attributes.put(ATTR_CONTEXT_SCOPE, contextScope());
        attributes.put(ATTR_TOOL_REQUIRED, toolRequired());
        attributes.put(ATTR_MEMORY_OPERATION, memoryOperation());
        attributes.put(ATTR_INTENT_STATE, intentState());
        attributes.put(ATTR_FOLLOW_UP_MODE, followUpMode());
        attributes.put(ATTR_INTENT_PHASE, intentPhase());
        attributes.put(ATTR_THREAD_RELATION, threadRelation());
        if (hasText(summary)) {
            attributes.put(ATTR_SUMMARY, summary);
        }
        if (hasText(taskFocus())) {
            attributes.put(ATTR_TASK_FOCUS, taskFocus());
        }
        if (!payload.isEmpty()) {
            attributes.put(ATTR_PAYLOAD, payload);
        }
        if (!keywords.isEmpty()) {
            attributes.put(ATTR_KEYWORDS, keywords);
        }
        if (!candidateIntents.isEmpty()) {
            attributes.put(ATTR_CANDIDATE_INTENTS, candidateIntents);
        }
        return Collections.unmodifiableMap(attributes);
    }

    public String toPromptSummary() {
        StringBuilder prompt = new StringBuilder(256);
        if (hasText(intent)) {
            prompt.append("- intent: ").append(intent).append('\n');
        }
        if (hasText(intentType())) {
            prompt.append("- intentType: ").append(intentType()).append('\n');
        }
        if (hasText(suggestedSkill)) {
            prompt.append("- suggestedSkill: ").append(suggestedSkill).append('\n');
        }
        prompt.append("- toolRequired: ").append(toolRequired()).append('\n');
        if (hasText(contextScope())) {
            prompt.append("- contextScope: ").append(contextScope()).append('\n');
        }
        if (hasText(memoryOperation())) {
            prompt.append("- memoryOperation: ").append(memoryOperation()).append('\n');
        }
        if (hasText(intentState())) {
            prompt.append("- intentState: ").append(intentState()).append('\n');
        }
        if (hasText(intentPhase())) {
            prompt.append("- intentPhase: ").append(intentPhase()).append('\n');
        }
        if (hasText(threadRelation())) {
            prompt.append("- threadRelation: ").append(threadRelation()).append('\n');
        }
        if (hasText(followUpMode())) {
            prompt.append("- followUpMode: ").append(followUpMode()).append('\n');
        }
        if (hasText(taskFocus())) {
            prompt.append("- taskFocus: ").append(taskFocus()).append('\n');
        }
        if (hasText(rewrittenInput)) {
            prompt.append("- rewrittenInput: ").append(rewrittenInput).append('\n');
        }
        if (!keywords.isEmpty()) {
            prompt.append("- keywords: ").append(String.join(", ", keywords)).append('\n');
        }
        if (hasText(this.summary)) {
            prompt.append("- summary: ").append(this.summary).append('\n');
        }
        if (!payload.isEmpty()) {
            prompt.append("- payload: ").append(toStablePayloadString(payload)).append('\n');
        }
        if (!candidateIntents.isEmpty()) {
            prompt.append("- candidateIntents: ").append(summarizeCandidateIntents(3)).append('\n');
        }
        if (hasAmbiguousSkillChoice()) {
            prompt.append("- ambiguousSkillChoice: true\n");
        }
        if (prompt.length() == 0) {
            return "";
        }
        prompt.append("- source: ").append(source == null ? "unknown" : source).append('\n');
        prompt.append("- confidence: ")
                .append(String.format(java.util.Locale.ROOT, "%.2f", effectiveConfidence()))
                .append('\n');
        return prompt.toString();
    }

    public String intentType() {
        if (isPrivacyModeIntent()) {
            return "privacy-control";
        }
        if (isMemoryRecallIntent()) {
            return "memory-recall";
        }
        if (isMemoryWriteIntent()) {
            return "memory-write";
        }
        if (toolRequired()) {
            return "tool-call";
        }
        return "chat";
    }

    public String contextScope() {
        String corpus = semanticCorpus();
        if (containsAny(corpus,
                "继续", "按之前", "按上次", "沿用", "再来一次", "同样方式", "继续按",
                "按刚才", "按这个", "按上面", "开始吧", "开始执行", "执行吧", "帮我推进",
                "推进一下", "照这个", "那就这样", "就这样",
                "当前事项遇到阻塞：", "为当前事项制定下一步方案：", "更新当前事项进展：", "当前事项：")) {
            return "continuation";
        }
        if (containsAny(corpus, "domain=news", "domain=weather", "domain=market", "domain=travel",
                "新闻", "资讯", "快讯", "天气", "实时", "最新", "今日", "今天", "现在")) {
            return "realtime";
        }
        if (!"none".equals(memoryOperation())) {
            return "memory";
        }
        return "standalone";
    }

    public String taskFocus() {
        String payloadFocus = firstNonBlankPayloadValue("task", "title", "goal", "project", "topic", "query", "taskFocus");
        if (hasText(payloadFocus)) {
            return payloadFocus;
        }
        if (toolRequired() && hasText(summary)) {
            return summary;
        }
        if ("continuation".equals(contextScope()) && hasText(rewrittenInput)) {
            return rewrittenInput;
        }
        return "";
    }

    public String followUpMode() {
        String corpus = semanticCorpus();
        if ("continuation".equals(contextScope())) {
            if (isSelectionSignal(corpus)) {
                return "selection";
            }
            if (isRevisionSignal(corpus)) {
                return "revision";
            }
            if (isConfirmationSignal(corpus)) {
                return "confirmation";
            }
            return "continuation";
        }
        return "standalone";
    }

    public String intentState() {
        String corpus = semanticCorpus();
        if (containsAny(corpus, "完成了", "完成啦", "搞定了", "结束了", "处理完了", "已经发了", "done")) {
            return "complete";
        }
        if (containsAny(corpus, "暂停", "先这样", "先别", "晚点", "搁置", "暂时不做", "先放一放")) {
            return "pause";
        }
        if (containsAny(corpus, "提醒我", "提醒一下", "记得", "明天提醒", "稍后提醒", "follow up", "follow-up")) {
            return "remind";
        }
        if (isBlockingSignal(corpus)) {
            return "blocked";
        }
        if (isRevisionSignal(corpus) || isSelectionSignal(corpus)) {
            return "update";
        }
        if ("continuation".equals(contextScope())
                || "continuation".equals(followUpMode())
                || "confirmation".equals(followUpMode())
                || "resume".equals(threadRelation())) {
            return "continue";
        }
        if (toolRequired()) {
            return "start";
        }
        return "none";
    }

    public String intentPhase() {
        String corpus = semanticCorpus();
        if (!"none".equals(memoryOperation())) {
            return "memory";
        }
        if (isBlockingSignal(corpus)) {
            return "blocking";
        }
        if (isPlanningSignal(corpus)) {
            return "planning";
        }
        if (isProgressReportSignal(corpus) || "complete".equals(intentState())) {
            return "reporting";
        }
        if (isDecisionSignal(corpus) || Set.of("pause", "update").contains(intentState())) {
            return "decision";
        }
        if (toolRequired() || Set.of("start", "continue", "remind", "blocked").contains(intentState())) {
            return "execution";
        }
        return "chat";
    }

    public String threadRelation() {
        String corpus = semanticCorpus();
        if (!"none".equals(memoryOperation())) {
            return "memory";
        }
        if (isResumeSignal(corpus)) {
            return "resume";
        }
        if (isSwitchSignal(corpus)) {
            return "switch";
        }
        if ("continuation".equals(contextScope()) || isContinuationLikeFollowUpMode(followUpMode())) {
            return "continue";
        }
        if (toolRequired() && hasText(taskFocus())) {
            return "new";
        }
        return "standalone";
    }

    public boolean toolRequired() {
        return hasSuggestedSkill();
    }

    public String memoryOperation() {
        if (isPrivacyModeIntent()) {
            return containsAny(semanticCorpus(), "恢复记忆", "开启记忆", "恢复记录") ? "resume" : "suppress";
        }
        if (isMemoryRecallIntent()) {
            return "recall";
        }
        if (isMemoryWriteIntent()) {
            return "write";
        }
        return "none";
    }

    private boolean isMemoryRecallIntent() {
        return containsAny(semanticCorpus(), "根据记忆", "查看记忆", "读取记忆", "回顾之前", "复述之前", "你还记得", "memory.direct");
    }

    private boolean isMemoryWriteIntent() {
        return containsAny(semanticCorpus(), "记住", "请记住", "帮我记住", "remember", "write memory");
    }

    private boolean isPrivacyModeIntent() {
        return containsAny(semanticCorpus(), "不要记忆", "关闭记忆", "暂停记忆", "隐私模式", "恢复记忆", "开启记忆");
    }

    private boolean isBlockingSignal(String corpus) {
        return containsAny(corpus,
                "卡住", "受阻", "阻塞", "报错", "失败了", "失败啦", "不通",
                "没权限", "无权限", "无法", "做不了", "遇到问题", "出问题", "异常",
                "blocked", "error", "failed");
    }

    private boolean isPlanningSignal(String corpus) {
        return containsAny(corpus,
                "先给方案", "给个方案", "方案呢", "下一步方案",
                "步骤", "拆解", "拆一下", "怎么做",
                "下一步怎么", "给我个思路", "先出个提纲", "排期", "框架", "计划一下");
    }

    private boolean isProgressReportSignal(String corpus) {
        return containsAny(corpus,
                "进展", "状态", "目前", "处理到", "做到", "同步一下", "汇报",
                "已经发", "已经提交", "已经同步", "刚发", "刚提交", "刚同步",
                "更新一下进展", "现在到");
    }

    private boolean isDecisionSignal(String corpus) {
        return isRevisionSignal(corpus)
                || isSelectionSignal(corpus)
                || containsAny(corpus, "就按这个", "就这样", "先这样");
    }

    private boolean isSwitchSignal(String corpus) {
        return containsAny(corpus,
                "另外一个", "另一个", "换个", "换一个", "另一件",
                "别的事", "新任务", "新事项", "先看别的", "先处理别的", "换到");
    }

    private boolean isResumeSignal(String corpus) {
        return containsAny(corpus,
                "回到", "回头继续", "还是说回", "刚才那个", "上一个", "之前那个", "再说回");
    }

    private String semanticCorpus() {
        StringBuilder builder = new StringBuilder();
        appendCorpus(builder, intent);
        appendCorpus(builder, rewrittenInput);
        appendCorpus(builder, suggestedSkill);
        appendCorpus(builder, summary);
        if (keywords != null && !keywords.isEmpty()) {
            for (String keyword : keywords) {
                appendCorpus(builder, keyword);
            }
        }
        if (payload != null && !payload.isEmpty()) {
            appendCorpus(builder, toStablePayloadString(payload));
        }
        return builder.toString().toLowerCase(java.util.Locale.ROOT);
    }

    private boolean isContinuationLikeFollowUpMode(String followUpMode) {
        return Set.of("continuation", "selection", "revision", "confirmation").contains(followUpMode);
    }

    private boolean isRevisionSignal(String corpus) {
        return containsAny(corpus,
                "改成", "更新", "补充", "调整", "修改", "换成", "加上", "补一下",
                "目标是", "截止", "优先级", "截止改到", "改到", "调整为");
    }

    private boolean isSelectionSignal(String corpus) {
        return containsAny(corpus,
                "第一个", "第二个", "第三个",
                "第一种", "第二种", "第三种",
                "第一版", "第二版", "第三版",
                "第一套", "第二套", "第三套",
                "前一个", "后一个", "前一种", "后一种",
                "前面那个", "后面那个", "上一个方案", "下一个方案");
    }

    private boolean isConfirmationSignal(String corpus) {
        return containsAny(corpus,
                "开始吧", "开始执行", "执行吧", "就按这个", "按刚才", "按这个",
                "照这个", "帮我推进", "推进一下", "可以", "好的", "没问题", "开工");
    }

    private void appendCorpus(StringBuilder builder, String value) {
        if (!hasText(value)) {
            return;
        }
        if (builder.length() > 0) {
            builder.append(' ');
        }
        builder.append(value.trim());
    }

    private boolean containsAny(String text, String... terms) {
        if (text == null || text.isBlank() || terms == null) {
            return false;
        }
        for (String term : terms) {
            if (term != null && !term.isBlank() && text.contains(term.toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Object> toImmutablePayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    private static List<String> toImmutableKeywords(List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return List.of();
        }
        return List.copyOf(keywords);
    }

    private static List<CandidateIntent> toImmutableCandidateIntents(List<CandidateIntent> candidateIntents) {
        if (candidateIntents == null || candidateIntents.isEmpty()) {
            return List.of();
        }
        List<CandidateIntent> normalized = new java.util.ArrayList<>();
        for (CandidateIntent candidate : candidateIntents) {
            if (candidate == null || !hasText(candidate.intent())) {
                continue;
            }
            normalized.add(new CandidateIntent(candidate.intent().trim(), normalizedConfidence(candidate.confidence())));
        }
        return normalized.isEmpty() ? List.of() : List.copyOf(normalized);
    }

    private static String toStablePayloadString(Map<String, Object> payload) {
        StringBuilder builder = new StringBuilder();
        builder.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> entry : payload.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .toList()) {
            if (!first) {
                builder.append(", ");
            }
            builder.append(entry.getKey()).append('=').append(entry.getValue());
            first = false;
        }
        builder.append('}');
        return builder.toString();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String emptyIfNull(String value) {
        return value == null ? "" : value;
    }

    private List<CandidateIntent> rankedDistinctCandidateIntents() {
        Map<String, CandidateIntent> distinct = new LinkedHashMap<>();
        if (hasText(suggestedSkill)) {
            double suggestedConfidence = Math.max(confidence, confidenceForSkill(suggestedSkill));
            distinct.put(ambiguityDedupKey(suggestedSkill), new CandidateIntent(suggestedSkill.trim(), suggestedConfidence));
        }
        for (CandidateIntent candidate : candidateIntents.stream()
                .sorted(Comparator.comparingDouble(CandidateIntent::confidence).reversed())
                .toList()) {
            String key = ambiguityDedupKey(candidate.intent());
            if (!distinct.containsKey(key)) {
                distinct.put(key, candidate);
            }
        }
        return distinct.values().stream()
                .sorted(Comparator.comparingDouble(CandidateIntent::confidence).reversed())
                .toList();
    }

    private String ambiguityDedupKey(String intentName) {
        String normalized = stringValue(intentName);
        if (normalized.isBlank()) {
            return "";
        }
        return DecisionCapabilityCatalog.executionTarget(normalized);
    }

    private String summarizeCandidateIntents(int maxCount) {
        return rankedDistinctCandidateIntents().stream()
                .limit(Math.max(1, maxCount))
                .map(candidate -> candidate.intent() + "="
                        + String.format(java.util.Locale.ROOT, "%.2f", candidate.confidence()))
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    private String firstNonBlankPayloadValue(String... keys) {
        if (payload == null || payload.isEmpty() || keys == null) {
            return "";
        }
        for (String key : keys) {
            if (key == null || key.isBlank()) {
                continue;
            }
            String value = stringValue(payload.get(key));
            if (hasText(value)) {
                return value;
            }
        }
        return "";
    }

    private static String normalizedText(String value) {
        return value == null ? "" : value.trim();
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static double normalizedConfidence(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    public record CandidateIntent(String intent, double confidence) {
    }
}
