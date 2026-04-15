package com.zhongbo.mindos.assistant.skill.examples;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.Skill;
import com.zhongbo.mindos.assistant.skill.SkillCatalogFacade;
import com.zhongbo.mindos.assistant.skill.SkillDescriptor;
import com.zhongbo.mindos.assistant.skill.SkillDescriptorProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
public class SkillsHelpSkill implements Skill, SkillDescriptorProvider {

    private final ObjectProvider<SkillCatalogFacade> skillCatalogProvider;

    public SkillsHelpSkill(ObjectProvider<SkillCatalogFacade> skillCatalogProvider) {
        this.skillCatalogProvider = skillCatalogProvider;
    }

    @Override
    public String name() {
        return "skills.help";
    }

    @Override
    public String description() {
        return "Returns deterministic help text about available or learnable skills.";
    }

    @Override
    public SkillDescriptor skillDescriptor() {
        return new SkillDescriptor(
                name(),
                description(),
                List.of("available skills", "learnable skills")
        );
    }

    @Override
    public SkillResult run(SkillContext context) {
        String mode = asText(context == null || context.attributes() == null ? null : context.attributes().get("mode"));
        if ("learnable".equalsIgnoreCase(mode)) {
            return SkillResult.success(name(), buildLearnableSkillsReply());
        }
        return SkillResult.success(name(), buildAvailableSkillsReply());
    }

    private String buildAvailableSkillsReply() {
        SkillCatalogFacade skillCatalog = skillCatalogProvider.getIfAvailable();
        List<String> skills = skillCatalog == null ? List.of() : skillCatalog.listAvailableSkillSummaries();
        if (skills.isEmpty()) {
            return "我现在还没有注册任何技能。你可以稍后让我重载自定义技能，或者接入 MCP / 外部 JAR 来扩展能力。";
        }
        StringBuilder reply = new StringBuilder("我目前可用的技能有：");
        boolean hasTimeSkill = false;
        boolean hasTeachingPlanSkill = false;
        for (String skill : skills) {
            String normalized = normalize(summaryName(skill));
            if (normalized.isBlank()
                    || normalized.startsWith("im.")
                    || normalized.startsWith("internal.")
                    || normalized.startsWith("skills.")) {
                continue;
            }
            reply.append("\n- ").append(skill);
            if (normalized.equals("time") || normalized.startsWith("time ")) {
                hasTimeSkill = true;
            }
            if (normalized.equals("teaching.plan") || normalized.startsWith("teaching.plan ")) {
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

    private String summaryName(String summary) {
        if (summary == null || summary.isBlank()) {
            return "";
        }
        int separator = summary.indexOf(" - ");
        return separator >= 0 ? summary.substring(0, separator).trim() : summary.trim();
    }

    private String asText(Object value) {
        if (value == null) {
            return "";
        }
        String normalized = String.valueOf(value).trim();
        return normalized.isBlank() ? "" : normalized;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
