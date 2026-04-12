package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillDsl;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.SkillEngineFacade;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

final class DispatchRuleCatalog {

    private static final String SKILL_HELP_CHANNEL = "skills.help";

    private final SkillEngineFacade skillEngine;
    private final BehaviorRoutingSupport behaviorRoutingSupport;

    DispatchRuleCatalog(SkillEngineFacade skillEngine, BehaviorRoutingSupport behaviorRoutingSupport) {
        this.skillEngine = skillEngine;
        this.behaviorRoutingSupport = behaviorRoutingSupport;
    }

    Optional<SkillDsl> detectSkillWithRules(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return Optional.empty();
        }
        String normalized = normalize(userInput);
        if (normalized.startsWith("echo ")) {
            return Optional.of(new SkillDsl("echo", Map.of("text", userInput.substring("echo ".length()))));
        }
        if (containsAny(normalized, "time", "clock", "几点", "时间", "what time")) {
            return Optional.of(SkillDsl.of("time"));
        }
        if ((normalized.startsWith("code ") || normalized.contains("generate code")) && isCodeGenerationIntent(userInput)) {
            return Optional.of(new SkillDsl("code.generate", Map.of("task", userInput)));
        }
        if (isTeachingPlanIntent(normalized)) {
            return Optional.of(new SkillDsl("teaching.plan", behaviorRoutingSupport.extractTeachingPlanPayload(userInput)));
        }
        return Optional.empty();
    }

    boolean isCodeGenerationIntent(String input) {
        if (input == null || input.isBlank()) {
            return false;
        }
        String normalized = normalize(input);
        boolean hasCodeCue = containsAny(normalized,
                "generate code",
                "code ",
                "写代码",
                "生成代码",
                "代码实现",
                "代码示例",
                "写个函数",
                "实现一个",
                "java代码",
                "python代码",
                "sql",
                "接口",
                "api",
                "bug",
                "debug",
                "修复");
        if (!hasCodeCue) {
            return false;
        }
        boolean looksLikeGeneralQuestion = containsAny(normalized,
                "是什么",
                "原理",
                "解释",
                "怎么理解",
                "什么意思",
                "why",
                "what is",
                "explain")
                && !containsAny(normalized, "代码", "函数", "class", "method", "api", "bug", "修复");
        return !looksLikeGeneralQuestion;
    }

    Optional<SkillResult> answerMetaQuestion(String userInput) {
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

    String inferMemoryBucket(String input) {
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
        StringBuilder reply = new StringBuilder("我目前可用的技能有：");
        boolean hasTimeSkill = false;
        boolean hasTeachingPlanSkill = false;
        for (String skill : skills) {
            reply.append("\n- ").append(skill);
            String normalizedSkill = normalize(skill);
            if (normalizedSkill.startsWith("time ") || normalizedSkill.startsWith("time-") || normalizedSkill.startsWith("time")) {
                hasTimeSkill = true;
            }
            if (normalizedSkill.startsWith("teaching.plan ") || normalizedSkill.startsWith("teaching.plan-") || normalizedSkill.startsWith("teaching.plan")) {
                hasTeachingPlanSkill = true;
            }
        }
        if (hasTimeSkill || hasTeachingPlanSkill) {
            reply.append("\n\n你也可以直接这样用：");
            if (hasTimeSkill) {
                reply.append("\n- 问我“现在几点了”，我会直接返回当前时间。");
            }
            if (hasTeachingPlanSkill) {
                reply.append("\n- 让我生成“六周数学学习计划”，我会给出分周学习安排。");
            }
        }
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
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
