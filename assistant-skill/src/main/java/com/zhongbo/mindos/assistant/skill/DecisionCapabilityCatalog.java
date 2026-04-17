package com.zhongbo.mindos.assistant.skill;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public final class DecisionCapabilityCatalog {

    public static final String WEB_LOOKUP_DECISION_TARGET = "web.lookup";

    private static final List<CapabilityDefinition> DEFINITIONS = List.of(
            new CapabilityDefinition(
                    "task.manage",
                    "todo.create",
                    "Manage tasks, reminders, deadlines, and follow-up actions when the user explicitly asks to create or update a task.",
                    List.of("创建待办", "设置提醒", "新增任务", "安排任务", "截止提醒", "任务管理"),
                    CapabilityAudience.CORE
            ),
            new CapabilityDefinition(
                    "code.assist",
                    "code.generate",
                    "Fix, generate, or refactor code when the user explicitly asks for implementation help.",
                    List.of("修复代码", "生成代码", "写代码", "接口 bug", "代码实现", "重构代码"),
                    CapabilityAudience.DEVELOPER
            ),
            new CapabilityDefinition(
                    "conversation.coach",
                    "eq.coach",
                    "Coach difficult communication such as apology, refusal, reassurance, and conflict handling.",
                    List.of("沟通建议", "怎么回复", "怎么说", "道歉话术", "安慰话术", "冲突处理"),
                    CapabilityAudience.CORE
            ),
            new CapabilityDefinition(
                    "learning.plan",
                    "teaching.plan",
                    "Plan study, teaching, review, and learning schedules around an explicit learning goal.",
                    List.of("学习计划", "教学规划", "复习计划", "学习路线", "课程安排", "备考规划"),
                    CapabilityAudience.CORE
            ),
            new CapabilityDefinition(
                    "workspace.search",
                    "file.search",
                    "Search files, folders, paths, and keywords inside the current workspace.",
                    List.of("找文件", "查文件", "搜索目录", "搜索路径", "workspace search", "grep 文件"),
                    CapabilityAudience.DEVELOPER
            ),
            new CapabilityDefinition(
                    "skill.studio",
                    "skill.factory",
                    "Create, import, adapt, and stage new skills from natural-language requests without auto-publishing them live.",
                    List.of("开发技能", "创建技能", "做个skill", "skill studio", "skill factory", "导入技能", "学习开源技能", "扩展能力"),
                    CapabilityAudience.CORE
            ),
            new CapabilityDefinition(
                    "news.lookup",
                    "news_search",
                    "Look up the latest news, headlines, and hot topics when the user explicitly asks for current news.",
                    List.of("今天新闻", "今日新闻", "最新新闻", "国际新闻", "最新消息", "最新动态", "查看新闻", "看新闻", "新闻搜索", "news"),
                    CapabilityAudience.CORE
            ),
            new CapabilityDefinition(
                    "docs.lookup",
                    "mcp.docs.searchDocs",
                    "Search official documentation, guides, SDK manuals, and product docs.",
                    List.of("search docs", "docs", "documentation", "official docs", "查文档", "搜索文档", "官方文档", "文档查询"),
                    CapabilityAudience.CORE
            ),
            new CapabilityDefinition(
                    "time.lookup",
                    "time",
                    "Look up the current time when the user explicitly asks for the time.",
                    List.of("time", "现在几点", "当前时间", "几点了", "现在时间", "what time", "current time"),
                    CapabilityAudience.CORE
            )
    );

    private static final CapabilityDefinition WEB_LOOKUP_CAPABILITY = new CapabilityDefinition(
            WEB_LOOKUP_DECISION_TARGET,
            WEB_LOOKUP_DECISION_TARGET,
            "Search current web information such as weather, traffic, market, travel, realtime news, and other web lookup needs.",
            List.of(
                    "查天气", "天气", "weather",
                    "查路况", "路况", "traffic",
                    "查航班", "航班", "travel", "出行",
                    "查股价", "股价", "行情", "market",
                    "实时搜索", "实时", "最新情况", "最新",
                    "web search", "search web",
                    "今天新闻", "最新新闻", "新闻", "news", "头条", "热点",
                    "bravesearch", "qwensearch", "serper", "serpapi"
            ),
            CapabilityAudience.CORE
    );

    private static final Map<String, CapabilityDefinition> BY_DECISION_TARGET = DEFINITIONS.stream()
            .collect(Collectors.collectingAndThen(
                    Collectors.toMap(
                            definition -> normalize(definition.decisionTarget()),
                            definition -> definition,
                            (left, right) -> left,
                            LinkedHashMap::new
                    ),
                    Map::copyOf
            ));

    private static final Map<String, CapabilityDefinition> BY_EXECUTION_SKILL = DEFINITIONS.stream()
            .collect(Collectors.collectingAndThen(
                    Collectors.toMap(
                            definition -> normalize(definition.executionSkill()),
                            definition -> definition,
                            (left, right) -> left,
                            LinkedHashMap::new
                    ),
                    Map::copyOf
            ));

    private DecisionCapabilityCatalog() {
    }

    public static boolean isDecisionCapability(String target) {
        return findByDecisionTarget(target).isPresent();
    }

    public static Optional<CapabilityDefinition> findByDecisionTarget(String target) {
        String normalized = normalize(target);
        if (normalized.isBlank()) {
            return Optional.empty();
        }
        if (WEB_LOOKUP_DECISION_TARGET.equals(normalized)) {
            return Optional.of(WEB_LOOKUP_CAPABILITY);
        }
        return Optional.ofNullable(BY_DECISION_TARGET.get(normalized));
    }

    public static Optional<CapabilityDefinition> findByExecutionSkill(String skillName) {
        String normalized = normalize(skillName);
        if (normalized.isBlank()) {
            return Optional.empty();
        }
        if (WEB_LOOKUP_DECISION_TARGET.equals(normalized) || isGenericWebSearchExecutionSkill(normalized)) {
            return Optional.of(WEB_LOOKUP_CAPABILITY);
        }
        return Optional.ofNullable(BY_EXECUTION_SKILL.get(normalized));
    }

    public static String decisionTarget(String skillName) {
        return findByExecutionSkill(skillName)
                .map(CapabilityDefinition::decisionTarget)
                .orElse(normalize(skillName));
    }

    public static String executionTarget(String target) {
        String normalized = normalize(target);
        if (WEB_LOOKUP_DECISION_TARGET.equals(normalized)) {
            return WEB_LOOKUP_DECISION_TARGET;
        }
        return findByDecisionTarget(normalized)
                .map(CapabilityDefinition::executionSkill)
                .orElse(normalized);
    }

    public static boolean hidesExecutionSkill(String skillName) {
        return findByExecutionSkill(skillName).isPresent();
    }

    public static List<CapabilityDefinition> availableCapabilities(Collection<String> availableSkillNames) {
        return availableCapabilities(availableSkillNames, Map.of());
    }

    public static List<CapabilityDefinition> availableCapabilities(Collection<String> availableSkillNames,
                                                                   Map<String, Object> profileContext) {
        Set<String> available = availableSkillNames == null ? Set.of() : availableSkillNames.stream()
                .map(DecisionCapabilityCatalog::normalize)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
        List<CapabilityDefinition> definitions = DEFINITIONS.stream()
                .filter(definition -> definition.enabledFor(profileContext))
                .filter(definition -> available.contains(normalize(definition.executionSkill())))
                .toList();
        if (!containsGenericWebSearchSkill(available) || !WEB_LOOKUP_CAPABILITY.enabledFor(profileContext)) {
            return definitions;
        }
        List<CapabilityDefinition> all = new java.util.ArrayList<>(definitions);
        all.add(WEB_LOOKUP_CAPABILITY);
        return List.copyOf(all);
    }

    public static boolean developerAudienceEnabled(Map<String, Object> profileContext) {
        if (profileContext == null || profileContext.isEmpty()) {
            return false;
        }
        String normalizedRole = normalize(stringValue(profileContext.get("role")));
        if (containsAny(normalizedRole,
                "developer", "engineer", "programmer", "coder", "architect", "devops", "sre", "qa")) {
            return true;
        }
        if (containsAny(normalizedRole, "开发", "程序员", "工程师", "架构师", "研发", "运维", "测试")) {
            return true;
        }
        if (isDeveloperPackValue(profileContext.get("capabilityPack"))
                || isDeveloperPackValue(profileContext.get("capabilityPacks"))
                || Boolean.TRUE.equals(profileContext.get("developerMode"))) {
            return true;
        }
        Object learnedPreferences = profileContext.get("learnedPreferences");
        if (learnedPreferences instanceof Map<?, ?> learnedMap) {
            return isDeveloperPackValue(learnedMap.get("capabilityPack"))
                    || isDeveloperPackValue(learnedMap.get("capabilityPacks"))
                    || Boolean.TRUE.equals(learnedMap.get("developerMode"));
        }
        return false;
    }

    public static boolean isGenericWebSearchExecutionSkill(String skillName) {
        String normalized = normalize(skillName);
        return normalized.startsWith("mcp.") && normalized.endsWith(".websearch");
    }

    private static boolean containsGenericWebSearchSkill(Collection<String> availableSkillNames) {
        if (availableSkillNames == null || availableSkillNames.isEmpty()) {
            return false;
        }
        for (String availableSkillName : availableSkillNames) {
            if (isGenericWebSearchExecutionSkill(availableSkillName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDeveloperPackValue(Object rawValue) {
        if (rawValue == null) {
            return false;
        }
        if (rawValue instanceof Collection<?> values) {
            for (Object value : values) {
                if (isDeveloperPackValue(value)) {
                    return true;
                }
            }
            return false;
        }
        String normalized = normalize(String.valueOf(rawValue));
        if (normalized.isBlank()) {
            return false;
        }
        for (String token : normalized.split("[,\\s]+")) {
            if (containsAny(token, "developer", "dev", "engineering", "engineer", "programmer", "coder")) {
                return true;
            }
            if (containsAny(token, "开发", "工程", "程序")) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(String value, String... needles) {
        String normalized = normalize(value);
        if (normalized.isBlank() || needles == null || needles.length == 0) {
            return false;
        }
        for (String needle : needles) {
            String normalizedNeedle = normalize(needle);
            if (!normalizedNeedle.isBlank() && normalized.contains(normalizedNeedle)) {
                return true;
            }
        }
        return false;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public record CapabilityDefinition(String decisionTarget,
                                       String executionSkill,
                                       String description,
                                       List<String> routingKeywords,
                                       CapabilityAudience audience) {
        public CapabilityDefinition(String decisionTarget,
                                    String executionSkill,
                                    String description,
                                    List<String> routingKeywords) {
            this(decisionTarget, executionSkill, description, routingKeywords, CapabilityAudience.CORE);
        }

        public CapabilityDefinition {
            decisionTarget = normalizedText(decisionTarget);
            executionSkill = normalizedText(executionSkill);
            description = description == null ? "" : description.trim();
            routingKeywords = routingKeywords == null ? List.of() : routingKeywords.stream()
                    .map(CapabilityDefinition::normalizedText)
                    .filter(value -> !value.isBlank())
                    .distinct()
                    .toList();
            audience = audience == null ? CapabilityAudience.CORE : audience;
        }

        public SkillDescriptor asDescriptor(String fallbackDescription) {
            String effectiveDescription = description == null || description.isBlank()
                    ? (fallbackDescription == null ? "" : fallbackDescription.trim())
                    : description;
            return new SkillDescriptor(decisionTarget, effectiveDescription, routingKeywords);
        }

        public boolean enabledFor(Map<String, Object> profileContext) {
            return audience != CapabilityAudience.DEVELOPER || developerAudienceEnabled(profileContext);
        }

        private static String normalizedText(String value) {
            return value == null ? "" : value.trim();
        }
    }

    public enum CapabilityAudience {
        CORE,
        DEVELOPER
    }
}
