package com.zhongbo.mindos.assistant.dispatcher.system;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.DecisionCapabilityCatalog;
import com.zhongbo.mindos.assistant.skill.Skill;
import com.zhongbo.mindos.assistant.skill.SkillCatalogFacade;
import com.zhongbo.mindos.assistant.skill.SkillDescriptor;
import com.zhongbo.mindos.assistant.skill.SkillDescriptorProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
        List<VisibleSkill> visibleSkills = visibleSkills(skillCatalog);
        if (visibleSkills.isEmpty()) {
            return "我现在还没有注册任何技能。你可以稍后让我重载自定义技能，或者接入 MCP / 外部 JAR 来扩展能力。";
        }
        StringBuilder reply = new StringBuilder("我目前按能力视角可直接帮你处理：");
        boolean hasTimeCapability = false;
        boolean hasLearningPlanCapability = false;
        for (VisibleSkill skill : visibleSkills) {
            reply.append("\n- ").append(skill.name());
            if (!skill.description().isBlank()) {
                reply.append("：").append(skill.description());
            }
            hasTimeCapability = hasTimeCapability || "time.lookup".equals(skill.name());
            hasLearningPlanCapability = hasLearningPlanCapability || "learning.plan".equals(skill.name());
        }
        reply.append("\n\n默认展示的是能力面，不展开底层 MCP / 诊断工具名；如果你要指定具体工具或继续扩展能力，也可以直接告诉我。");
        if (hasTimeCapability || hasLearningPlanCapability) {
            reply.append("\n\n你也可以直接这样用：");
            if (hasTimeCapability) {
                reply.append("\n- 问我“现在几点了”，我会直接返回当前时间。");
            }
            if (hasLearningPlanCapability) {
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

    private List<VisibleSkill> visibleSkills(SkillCatalogFacade skillCatalog) {
        if (skillCatalog == null) {
            return List.of();
        }
        Map<String, SkillDescriptor> descriptorsByName = new LinkedHashMap<>();
        for (SkillDescriptor descriptor : skillCatalog.listSkillDescriptors()) {
            if (descriptor == null || descriptor.name() == null || descriptor.name().isBlank()) {
                continue;
            }
            descriptorsByName.put(normalize(descriptor.name()), descriptor);
        }
        Set<String> availableNames = new LinkedHashSet<>(descriptorsByName.keySet());
        for (String summary : skillCatalog.listAvailableSkillSummaries()) {
            String name = normalize(summaryName(summary));
            if (!name.isBlank()) {
                availableNames.add(name);
            }
        }

        Map<String, VisibleSkill> visible = new LinkedHashMap<>();
        for (DecisionCapabilityCatalog.CapabilityDefinition capability : DecisionCapabilityCatalog.availableCapabilities(availableNames)) {
            SkillDescriptor rawDescriptor = descriptorsByName.get(normalize(capability.executionSkill()));
            visible.put(capability.decisionTarget(), new VisibleSkill(
                    capability.decisionTarget(),
                    humanizedDescription(capability.decisionTarget(), rawDescriptor == null ? "" : rawDescriptor.description())
            ));
        }
        descriptorsByName.values().stream()
                .sorted(java.util.Comparator.comparing(SkillDescriptor::name))
                .forEach(descriptor -> addDirectVisibleSkill(visible, descriptor.name(), descriptor.description()));
        for (String summary : skillCatalog.listAvailableSkillSummaries()) {
            addDirectVisibleSkill(visible, summaryName(summary), summaryDescription(summary));
        }
        return List.copyOf(visible.values());
    }

    private void addDirectVisibleSkill(Map<String, VisibleSkill> visible, String name, String description) {
        String normalized = normalize(name);
        if (!isDirectlyVisible(normalized)) {
            return;
        }
        visible.putIfAbsent(normalized, new VisibleSkill(normalized, humanizedDescription(normalized, description)));
    }

    private boolean isDirectlyVisible(String normalizedName) {
        return !normalizedName.isBlank()
                && !normalizedName.startsWith("im.")
                && !normalizedName.startsWith("internal.")
                && !normalizedName.startsWith("skills.")
                && !normalizedName.startsWith("mcp.")
                && !"semantic.analyze".equals(normalizedName)
                && !"llm.orchestrate".equals(normalizedName)
                && !DecisionCapabilityCatalog.hidesExecutionSkill(normalizedName);
    }

    private String summaryDescription(String summary) {
        if (summary == null || summary.isBlank()) {
            return "";
        }
        int separator = summary.indexOf(" - ");
        return separator >= 0 ? summary.substring(separator + 3).trim() : "";
    }

    private String humanizedDescription(String skillName, String fallbackDescription) {
        return switch (normalize(skillName)) {
            case "task.manage" -> "创建待办、提醒和截止事项";
            case "code.assist" -> "修复、生成和重构代码";
            case "conversation.coach" -> "给沟通回复、安慰、道歉和冲突处理建议";
            case "learning.plan" -> "生成学习、教学和复习计划";
            case "workspace.search" -> "在当前工作区搜索文件、目录和内容";
            case "news.lookup" -> "查看最新新闻、热点和头条";
            case "web.lookup" -> "联网查询天气、路况、航班、行情等实时信息";
            case "docs.lookup" -> "搜索官方文档、指南和开发手册";
            case "time.lookup" -> "查询当前时间";
            case "echo" -> "回显输入，适合调试连通性";
            default -> fallbackDescription == null ? "" : fallbackDescription.trim();
        };
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

    private record VisibleSkill(String name, String description) {
    }
}
