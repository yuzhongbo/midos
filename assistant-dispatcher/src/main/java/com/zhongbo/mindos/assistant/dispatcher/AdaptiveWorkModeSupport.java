package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.LegacyRoleSupport;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class AdaptiveWorkModeSupport {

    private static final int MAX_WORKING_MODES = 2;
    private static final double MIN_WORKING_MODE_CONFIDENCE = 0.30d;
    private static final double MIN_INDUSTRY_CONFIDENCE = 0.30d;
    private static final double DEVELOPER_AUTO_PACK_THRESHOLD = 0.60d;

    private static final List<ModeRule> MODE_RULES = List.of(
            new ModeRule("developer", List.of(
                    "code", "bug", "api", "sql", "java", "spring", "controller", "service",
                    "repository", "dto", "class", "method", "function", "workspace", "repo",
                    "git", "maven", "gradle", "stack trace", "stacktrace", "log", "test",
                    "代码", "报错", "异常", "接口", "函数", "方法", "类", "工作区",
                    "仓库", "日志", "编译", "单测", "测试"
            )),
            new ModeRule("planner", List.of(
                    "plan", "roadmap", "milestone", "timeline", "strategy",
                    "计划", "方案", "路线图", "排期", "拆解", "推进策略", "规划"
            )),
            new ModeRule("analyst", List.of(
                    "analysis", "analyze", "metric", "kpi", "funnel", "root cause", "tradeoff",
                    "分析", "指标", "归因", "漏斗", "根因", "复盘", "权衡", "对比", "诊断"
            )),
            new ModeRule("researcher", List.of(
                    "research", "investigate", "search", "trend", "benchmark", "latest",
                    "调研", "研究", "查资料", "趋势", "对标", "最新", "网页", "联网", "搜索"
            )),
            new ModeRule("teacher", List.of(
                    "study", "teach", "course", "lesson", "review plan",
                    "学习", "教学", "课程", "复习", "备考", "训练营", "讲解"
            )),
            new ModeRule("coach", List.of(
                    "reply", "communication", "apology", "conflict", "reassure",
                    "回复", "沟通", "话术", "道歉", "冲突", "安慰", "表达"
            )),
            new ModeRule("operator", List.of(
                    "operate", "ops", "workflow", "sop", "process", "delivery",
                    "运营", "推进", "流程", "执行", "交付", "协同", "项目管理"
            )),
            new ModeRule("writer", List.of(
                    "write", "copywriting", "draft", "rewrite", "summary",
                    "写作", "文案", "改写", "润色", "总结", "纪要"
            ))
    );

    private static final List<IndustryRule> INDUSTRY_RULES = List.of(
            new IndustryRule("finance", List.of(
                    "finance", "bank", "banking", "stock", "fund", "investment", "securities", "audit",
                    "金融", "银行", "证券", "基金", "股票", "投资", "财务", "审计", "风控"
            )),
            new IndustryRule("healthcare", List.of(
                    "healthcare", "medical", "hospital", "clinical", "pharma", "patient",
                    "医疗", "医院", "临床", "医药", "患者", "健康", "药品"
            )),
            new IndustryRule("education", List.of(
                    "education", "school", "student", "curriculum", "training",
                    "教育", "学校", "学生", "课程", "培训", "教学"
            )),
            new IndustryRule("legal", List.of(
                    "legal", "law", "contract", "compliance", "regulation",
                    "法务", "法律", "合同", "合规", "监管", "条款"
            )),
            new IndustryRule("retail", List.of(
                    "retail", "ecommerce", "e-commerce", "store", "merchandise", "order",
                    "零售", "电商", "门店", "商品", "订单", "供应链"
            )),
            new IndustryRule("manufacturing", List.of(
                    "manufacturing", "factory", "production", "equipment", "quality",
                    "制造", "工厂", "生产", "设备", "质检", "产线"
            )),
            new IndustryRule("government", List.of(
                    "government", "public sector", "policy", "administration",
                    "政务", "政府", "政策", "行政", "公文"
            )),
            new IndustryRule("hr", List.of(
                    "hr", "recruiting", "recruitment", "interview", "performance", "organization",
                    "人力", "招聘", "面试", "绩效", "组织"
            )),
            new IndustryRule("sales", List.of(
                    "sales", "customer", "lead", "crm", "pipeline",
                    "销售", "客户", "商机", "线索", "成交"
            )),
            new IndustryRule("marketing", List.of(
                    "marketing", "brand", "campaign", "growth", "conversion",
                    "营销", "市场", "品牌", "增长", "转化", "投放"
            )),
            new IndustryRule("product", List.of(
                    "product", "saas", "app", "platform", "user story",
                    "产品", "平台", "应用", "用户故事", "需求"
            ))
    );

    private static final List<String> DEVELOPER_SUBJECT_KEYWORDS = List.of(
            "code", "bug", "api", "sql", "java", "spring", "controller", "service", "repository",
            "dto", "class", "method", "workspace", "repo", "git", "maven", "gradle",
            "stack trace", "stacktrace", "log", "test", "代码", "报错", "异常", "接口", "函数",
            "方法", "类", "工作区", "仓库", "日志", "编译", "测试", "单测"
    );

    private static final List<String> DEVELOPER_ACTION_KEYWORDS = List.of(
            "fix", "debug", "generate", "implement", "write", "refactor", "review", "optimize", "search", "find",
            "修复", "排查", "定位", "生成", "实现", "写", "重构", "检查", "优化", "搜索", "查找", "继续", "接着", "按之前方式"
    );

    private static final Set<String> DEVELOPER_ROLE_TOKENS = Set.of(
            "developer", "engineer", "programmer", "coder", "architect", "devops", "sre", "qa",
            "开发", "程序员", "工程师", "架构师", "研发", "运维", "测试"
    );

    private AdaptiveWorkModeSupport() {
    }

    static Map<String, Object> enrichProfileContext(Map<String, Object> profileContext, String userInput) {
        return enrichProfileContext(profileContext, userInput, "", List.of());
    }

    static Map<String, Object> enrichProfileContext(Map<String, Object> profileContext,
                                                    String userInput,
                                                    String activeTaskHint,
                                                    List<String> memoryHints) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (profileContext != null && !profileContext.isEmpty()) {
            merged.putAll(profileContext);
        }
        String normalizedInput = normalize(userInput);
        String normalizedBackground = normalize(joinText(activeTaskHint, memoryHints));
        LinkedHashMap<String, Double> modeScores = new LinkedHashMap<>();
        mergeExistingWorkingModes(modeScores, merged.get("workingModes"), merged.get("workingModeConfidence"));
        addModeFromRole(modeScores, merged.get("role"));
        inferWorkingModes(normalizedInput, normalizedBackground).forEach((mode, confidence) ->
                modeScores.merge(mode, confidence, Math::max));
        List<ScoredMode> topWorkingModes = topWorkingModes(modeScores);
        if (!topWorkingModes.isEmpty()) {
            merged.put("workingModes", topWorkingModes.stream().map(ScoredMode::mode).toList());
            merged.put("workingModeConfidence", toConfidenceMap(topWorkingModes));
            merged.put("primaryWorkMode", topWorkingModes.get(0).mode());
        }
        merged.putIfAbsent("assistantMode", "adaptive-multi-domain");
        merged.putIfAbsent("roleStrategy", "single-persona-dynamic-modes");
        if (!merged.containsKey("industryFocus")) {
            IndustryMatch industryFocus = inferIndustryFocus(normalizedInput, normalizedBackground);
            if (industryFocus.matched()) {
                merged.put("industryFocus", industryFocus.industry());
                merged.put("industryFocusConfidence", roundConfidence(industryFocus.confidence()));
            }
        }
        if (shouldAutoEnableDeveloperPack(merged, normalizedInput, normalizedBackground, topWorkingModes)) {
            merged.put("capabilityPacks", mergeCapabilityPacks(merged.get("capabilityPacks"), "developer"));
        }
        return merged.isEmpty() ? Map.of() : Map.copyOf(merged);
    }

    private static Map<String, Double> inferWorkingModes(String normalizedInput, String normalizedBackground) {
        if ((normalizedInput == null || normalizedInput.isBlank())
                && (normalizedBackground == null || normalizedBackground.isBlank())) {
            return Map.of();
        }
        LinkedHashMap<String, Double> scores = new LinkedHashMap<>();
        for (ModeRule rule : MODE_RULES) {
            double confidence = rule.confidence(normalizedInput, normalizedBackground);
            if (confidence >= MIN_WORKING_MODE_CONFIDENCE) {
                scores.put(rule.mode(), roundConfidence(confidence));
            }
        }
        return scores.isEmpty() ? Map.of() : Map.copyOf(scores);
    }

    private static IndustryMatch inferIndustryFocus(String normalizedInput, String normalizedBackground) {
        if ((normalizedInput == null || normalizedInput.isBlank())
                && (normalizedBackground == null || normalizedBackground.isBlank())) {
            return IndustryMatch.none();
        }
        IndustryRule best = null;
        double bestScore = 0.0d;
        for (IndustryRule rule : INDUSTRY_RULES) {
            double score = rule.confidence(normalizedInput, normalizedBackground);
            if (score > bestScore) {
                best = rule;
                bestScore = score;
            }
        }
        if (best == null || bestScore < MIN_INDUSTRY_CONFIDENCE) {
            return IndustryMatch.none();
        }
        return new IndustryMatch(best.industry(), roundConfidence(bestScore));
    }

    private static boolean shouldAutoEnableDeveloperPack(Map<String, Object> merged,
                                                         String normalizedInput,
                                                         String normalizedBackground,
                                                         Collection<ScoredMode> workingModes) {
        if (hasDeveloperPack(merged.get("capabilityPack"))
                || hasDeveloperPack(merged.get("capabilityPacks"))
                || Boolean.TRUE.equals(merged.get("developerMode"))) {
            return true;
        }
        double developerConfidence = modeConfidence(workingModes, "developer");
        if (developerConfidence < DEVELOPER_AUTO_PACK_THRESHOLD) {
            return false;
        }
        boolean developerAction = containsAny(normalizedInput, DEVELOPER_ACTION_KEYWORDS);
        boolean developerSubject = containsAny(normalizedInput, DEVELOPER_SUBJECT_KEYWORDS)
                || containsAny(normalizedBackground, DEVELOPER_SUBJECT_KEYWORDS);
        return developerAction && developerSubject;
    }

    private static void addModeFromRole(Map<String, Double> modeScores, Object rawRole) {
        String normalizedRole = normalize(LegacyRoleSupport.explicitRole(rawRole));
        if (normalizedRole.isBlank()) {
            return;
        }
        for (String token : DEVELOPER_ROLE_TOKENS) {
            if (normalizedRole.contains(token)) {
                modeScores.merge("developer", 0.95d, Math::max);
                break;
            }
        }
    }

    private static void mergeExistingWorkingModes(Map<String, Double> modeScores,
                                                  Object rawModes,
                                                  Object rawConfidence) {
        List<String> modes = extractStringValues(rawModes);
        Map<String, Double> confidenceMap = extractConfidenceMap(rawConfidence);
        for (String mode : modes) {
            double baseline = confidenceMap.getOrDefault(mode, 0.66d);
            modeScores.merge(mode, roundConfidence(baseline), Math::max);
        }
    }

    private static List<String> mergeCapabilityPacks(Object existing, String extraPack) {
        LinkedHashSet<String> packs = new LinkedHashSet<>(extractStringValues(existing));
        if (extraPack != null && !extraPack.isBlank()) {
            packs.add(extraPack.trim().toLowerCase(Locale.ROOT));
        }
        return packs.isEmpty() ? List.of() : List.copyOf(packs);
    }

    private static boolean hasDeveloperPack(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Collection<?> values) {
            for (Object item : values) {
                if (hasDeveloperPack(item)) {
                    return true;
                }
            }
            return false;
        }
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            return false;
        }
        for (String token : normalized.split("[,\\s]+")) {
            if (token.contains("developer") || token.contains("engineer") || token.contains("programmer")
                    || token.contains("coder") || token.contains("开发") || token.contains("程序") || token.contains("工程")) {
                return true;
            }
        }
        return false;
    }

    private static List<ScoredMode> topWorkingModes(Map<String, Double> modeScores) {
        if (modeScores == null || modeScores.isEmpty()) {
            return List.of();
        }
        return modeScores.entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue() >= MIN_WORKING_MODE_CONFIDENCE)
                .map(entry -> new ScoredMode(entry.getKey(), roundConfidence(entry.getValue()), modeIndex(entry.getKey())))
                .sorted(Comparator.comparingDouble(ScoredMode::confidence).reversed()
                        .thenComparingInt(ScoredMode::ruleIndex)
                        .thenComparing(ScoredMode::mode))
                .limit(MAX_WORKING_MODES)
                .toList();
    }

    private static int modeIndex(String mode) {
        if (mode == null || mode.isBlank()) {
            return Integer.MAX_VALUE;
        }
        for (int index = 0; index < MODE_RULES.size(); index++) {
            if (mode.equals(MODE_RULES.get(index).mode())) {
                return index;
            }
        }
        return Integer.MAX_VALUE;
    }

    private static Map<String, Double> toConfidenceMap(List<ScoredMode> scoredModes) {
        if (scoredModes == null || scoredModes.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Double> confidence = new LinkedHashMap<>();
        for (ScoredMode mode : scoredModes) {
            confidence.put(mode.mode(), roundConfidence(mode.confidence()));
        }
        return Collections.unmodifiableMap(confidence);
    }

    private static Map<String, Double> extractConfidenceMap(Object rawConfidence) {
        if (!(rawConfidence instanceof Map<?, ?> confidenceMap) || confidenceMap.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Double> parsed = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : confidenceMap.entrySet()) {
            String mode = normalize(entry.getKey());
            if (mode.isBlank()) {
                continue;
            }
            double confidence = toDouble(entry.getValue());
            if (confidence > 0.0d) {
                parsed.put(mode, roundConfidence(confidence));
            }
        }
        return parsed.isEmpty() ? Map.of() : Collections.unmodifiableMap(parsed);
    }

    private static double modeConfidence(Collection<ScoredMode> workingModes, String mode) {
        if (workingModes == null || workingModes.isEmpty() || mode == null || mode.isBlank()) {
            return 0.0d;
        }
        return workingModes.stream()
                .filter(candidate -> mode.equals(candidate.mode()))
                .mapToDouble(ScoredMode::confidence)
                .max()
                .orElse(0.0d);
    }

    private static List<String> extractStringValues(Object value) {
        if (value == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                String normalized = normalize(item);
                if (!normalized.isBlank()) {
                    values.add(normalized);
                }
            }
            return values.isEmpty() ? List.of() : List.copyOf(values);
        }
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            return List.of();
        }
        for (String token : normalized.split("[,\\s]+")) {
            if (!token.isBlank()) {
                values.add(token);
            }
        }
        return values.isEmpty() ? List.of() : List.copyOf(values);
    }

    private static double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            return 0.0d;
        }
        try {
            return Double.parseDouble(normalized);
        } catch (NumberFormatException ignored) {
            return 0.0d;
        }
    }

    private static double roundConfidence(double value) {
        return Math.round(Math.max(0.0d, Math.min(0.99d, value)) * 100.0d) / 100.0d;
    }

    private static boolean containsAny(String haystack, Collection<String> needles) {
        if (haystack == null || haystack.isBlank() || needles == null || needles.isEmpty()) {
            return false;
        }
        String normalizedHaystack = normalize(haystack);
        for (String needle : needles) {
            String normalizedNeedle = normalize(needle);
            if (!normalizedNeedle.isBlank() && normalizedHaystack.contains(normalizedNeedle)) {
                return true;
            }
        }
        return false;
    }

    private static String joinText(String first, List<String> more) {
        List<String> parts = new ArrayList<>();
        if (first != null && !first.isBlank()) {
            parts.add(first.trim());
        }
        if (more != null) {
            more.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .forEach(parts::add);
        }
        return String.join("\n", parts).trim();
    }

    private static String joinText(String first, String second) {
        if ((first == null || first.isBlank()) && (second == null || second.isBlank())) {
            return "";
        }
        if (first == null || first.isBlank()) {
            return second.trim();
        }
        if (second == null || second.isBlank()) {
            return first.trim();
        }
        return first.trim() + "\n" + second.trim();
    }

    private static String normalize(Object value) {
        return value == null ? "" : String.valueOf(value).trim().toLowerCase(Locale.ROOT);
    }

    private record ModeRule(String mode, List<String> keywords) {
        double confidence(String normalizedInput, String normalizedBackground) {
            int inputHits = countMatches(normalizedInput, keywords);
            int backgroundHits = countMatches(normalizedBackground, keywords);
            if (inputHits <= 0 && backgroundHits <= 0) {
                return 0.0d;
            }
            double score = Math.min(3, inputHits) * 0.32d
                    + Math.min(3, backgroundHits) * 0.24d
                    + (inputHits > 0 && backgroundHits > 0 ? 0.06d : 0.0d);
            return roundConfidence(Math.min(0.98d, score));
        }
    }

    private record ScoredMode(String mode, double confidence, int ruleIndex) {
    }

    private record IndustryMatch(String industry, double confidence) {
        static IndustryMatch none() {
            return new IndustryMatch("", 0.0d);
        }

        boolean matched() {
            return industry != null && !industry.isBlank() && confidence >= MIN_INDUSTRY_CONFIDENCE;
        }
    }

    private record IndustryRule(String industry, List<String> keywords) {
        double confidence(String normalizedInput, String normalizedBackground) {
            int inputHits = countMatches(normalizedInput, keywords);
            int backgroundHits = countMatches(normalizedBackground, keywords);
            if (inputHits <= 0 && backgroundHits <= 0) {
                return 0.0d;
            }
            double score = Math.min(2, inputHits) * 0.32d
                    + Math.min(2, backgroundHits) * 0.18d;
            return roundConfidence(Math.min(0.95d, score));
        }
    }

    private static int countMatches(String haystack, Collection<String> needles) {
        if (haystack == null || haystack.isBlank() || needles == null || needles.isEmpty()) {
            return 0;
        }
        int matches = 0;
        for (String needle : needles) {
            String normalizedNeedle = normalize(needle);
            if (!normalizedNeedle.isBlank() && haystack.contains(normalizedNeedle)) {
                matches++;
            }
        }
        return matches;
    }
}
