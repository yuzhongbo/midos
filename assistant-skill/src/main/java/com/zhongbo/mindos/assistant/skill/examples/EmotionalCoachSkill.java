package com.zhongbo.mindos.assistant.skill.examples;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.Skill;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class EmotionalCoachSkill implements Skill {

    private static final String RISK_HIGH_TERMS_PROP = "mindos.eq.coach.risk.high-terms";
    private static final String RISK_MEDIUM_TERMS_PROP = "mindos.eq.coach.risk.medium-terms";
    private static final Set<String> DEFAULT_RISK_HIGH_TERMS = Set.of("离婚", "分手", "崩溃", "绝望", "威胁");
    private static final Set<String> DEFAULT_RISK_MEDIUM_TERMS = Set.of("冲突", "冷战", "争执", "焦虑", "拖延");

    private static final Map<String, String> STYLE_LABELS = Map.of(
            "gentle", "温和版",
            "direct", "直接版",
            "workplace", "职场版",
            "intimate", "亲密关系版"
    );

    @Override
    public String name() {
        return "eq.coach";
    }

    @Override
    public String description() {
        return "Provides daily emotional-intelligence coaching with practical phrasing and action steps.";
    }

    @Override
    public boolean supports(String input) {
        if (input == null || input.isBlank()) {
            return false;
        }
        String normalized = input.toLowerCase(Locale.ROOT);
        return normalized.contains("情商")
                || normalized.contains("沟通")
                || normalized.contains("怎么说")
                || normalized.contains("高情商")
                || normalized.contains("心理分析")
                || normalized.contains("事情分析")
                || normalized.contains("分析这件事")
                || normalized.contains("道歉")
                || normalized.contains("安慰")
                || normalized.contains("冲突")
                || normalized.contains("拒绝");
    }

    @Override
    public SkillResult run(SkillContext context) {
        String scenario = resolveScenario(context);
        if (scenario.isBlank()) {
            return SkillResult.failure(name(), "请告诉我一个具体场景，我会给你高情商沟通建议。");
        }

        String style = normalizeStyle(context.attributes().get("style"));
        String styleLabel = STYLE_LABELS.getOrDefault(style, "温和版");
        String mode = normalizeMode(context.attributes().get("mode"));
        String priorityFocus = normalizePriorityFocus(context.attributes().get("priorityFocus"));
        String riskLevel = assessRiskLevel(scenario);
        int confidence = estimateConfidence(scenario);

        StringBuilder output = new StringBuilder();
        output.append("[情商沟通指导 - ").append(styleLabel).append("]\n");
        output.append("模式: ").append(mode).append("\n");
        output.append("风险等级: ").append(riskLevel)
                .append(" | 置信度: ").append(confidence).append("%\n\n");
        if (!"analysis".equals(mode)) {
            output.append(buildReplyByStyle(style, scenario, styleLabel));
        }
        if (!"reply".equals(mode)) {
            output.append(buildAnalysisBlock(scenario));
        }
        output.append(buildPrioritySection(riskLevel, priorityFocus));
        output.append(build24HourChecklist(riskLevel, priorityFocus));

        return SkillResult.success(name(), output.toString());
    }

    private String buildReplyByStyle(String style, String scenario, String styleLabel) {
        return switch (style) {
            case "direct" -> buildDirectReply(scenario, styleLabel);
            case "workplace" -> buildWorkplaceReply(scenario, styleLabel);
            case "intimate" -> buildIntimateReply(scenario, styleLabel);
            default -> buildGentleReply(scenario, styleLabel);
        };
    }

    private String buildGentleReply(String scenario, String styleLabel) {
        return "[情商沟通指导 - " + styleLabel + "]\n"
                + "场景: " + scenario + "\n\n"
                + "1) 先共情，降低对方防御\n"
                + "- 建议话术: 我理解你现在的感受，这件事确实不容易。\n\n"
                + "2) 再表达立场，用" + '"' + "我" + '"' + "开头\n"
                + "- 建议话术: 我希望我们可以一起找到更稳妥的做法，我也愿意配合。\n\n"
                + "3) 最后给可执行下一步\n"
                + "- 建议话术: 我们先试一个小步骤，今天先确认A，明天我再跟进B。\n\n"
                + "沟通策略\n"
                + "- 少用绝对化表达（如\"你总是\"），多用事实 + 感受 + 请求\n"
                + "- 情绪上来时先暂停 10 秒，再回复\n"
                + "- 目标是解决问题，不是赢得争论\n\n"
                + "如果你愿意，我可以按这个场景继续给你\"温和版/直接版\"两套完整对话稿。";
    }

    private String buildDirectReply(String scenario, String styleLabel) {
        return "[情商沟通指导 - " + styleLabel + "]\n"
                + "场景: " + scenario + "\n\n"
                + "1) 先对齐目标\n"
                + "- 建议话术: 我想把这件事尽快解决，我们先对齐目标。\n\n"
                + "2) 直接表达边界与请求\n"
                + "- 建议话术: 这个点我不能接受，我需要你在今晚前给我明确回复。\n\n"
                + "3) 给出结果导向动作\n"
                + "- 建议话术: 现在先定方案A，若 24 小时内无反馈我会按备选方案推进。\n\n"
                + "沟通策略\n"
                + "- 句子短、结论先行、请求明确\n"
                + "- 用时间节点约束，而不是情绪施压\n"
                + "- 先讲事实再讲判断，避免人身归因\n\n";
    }

    private String buildWorkplaceReply(String scenario, String styleLabel) {
        return "[情商沟通指导 - " + styleLabel + "]\n"
                + "场景: " + scenario + "\n\n"
                + "1) 先对齐业务目标\n"
                + "- 建议话术: 我们都希望这个项目按时高质量交付。\n\n"
                + "2) 再描述风险与影响\n"
                + "- 建议话术: 如果这个环节继续延后，会影响下游排期和评审结果。\n\n"
                + "3) 最后提出协作方案\n"
                + "- 建议话术: 我建议今天先完成关键项A，我负责B，明早10点同步进度。\n\n"
                + "沟通策略\n"
                + "- 聚焦目标、进度、风险，不贴标签\n"
                + "- 对事不对人，用可量化节点推进\n"
                + "- 关键沟通留痕，减少重复拉扯\n\n";
    }

    private String buildIntimateReply(String scenario, String styleLabel) {
        return "[情商沟通指导 - " + styleLabel + "]\n"
                + "场景: " + scenario + "\n\n"
                + "1) 先确认关系，再谈分歧\n"
                + "- 建议话术: 我很在乎你，也很重视我们的关系。\n\n"
                + "2) 用感受表达替代指责\n"
                + "- 建议话术: 当这件事发生时，我会有点委屈，也有些不安。\n\n"
                + "3) 提出可被回应的小请求\n"
                + "- 建议话术: 这周我们能不能先约 30 分钟，好好把这件事聊清楚？\n\n"
                + "沟通策略\n"
                + "- 先连接情感，再处理问题\n"
                + "- 少翻旧账，多谈当下可改变的行动\n"
                + "- 发生情绪时先暂停，等都平稳后再继续\n\n";
    }

    private String buildAnalysisBlock(String scenario) {
        return "心理分析\n"
                + "- 可能情绪: 紧张、委屈或被忽视，核心是希望被理解并获得确定感。\n"
                + "- 触发因素: 预期落差、沟通节奏不一致、边界不清。\n"
                + "- 深层需要: 被尊重、被看见、关系稳定和结果可控。\n\n"
                + "事情分析\n"
                + "- 事实层: 先拆清楚已发生事实，避免把推测当结论。\n"
                + "- 风险层: 若继续当前沟通方式，可能出现误解升级、效率下降。\n"
                + "- 可控层: 你可先明确目标、给出具体请求、约定下一次同步节点。\n"
                + "- 建议动作: 用\"事实-感受-请求\"一句话模板先发一版沟通消息。\n\n"
                + "分析对象: " + scenario + "\n\n";
    }

    private String resolveScenario(SkillContext context) {
        Object query = context.attributes().get("query");
        if (query != null && !String.valueOf(query).isBlank()) {
            return String.valueOf(query).trim();
        }
        return context.input() == null ? "" : context.input().trim();
    }

    private String normalizeStyle(Object rawStyle) {
        if (rawStyle == null) {
            return "gentle";
        }
        String style = String.valueOf(rawStyle).trim().toLowerCase(Locale.ROOT);
        if (style.isBlank()) {
            return "gentle";
        }
        if (style.contains("direct") || style.contains("直接")) {
            return "direct";
        }
        if (style.contains("work") || style.contains("职场")) {
            return "workplace";
        }
        if (style.contains("intimate") || style.contains("亲密") || style.contains("关系")) {
            return "intimate";
        }
        return "gentle";
    }

    private String normalizeMode(Object rawMode) {
        if (rawMode == null) {
            return "both";
        }
        String mode = String.valueOf(rawMode).trim().toLowerCase(Locale.ROOT);
        if (mode.isBlank()) {
            return "both";
        }
        if (mode.contains("analysis") || mode.contains("分析")) {
            return "analysis";
        }
        if (mode.contains("reply") || mode.contains("回复") || mode.contains("话术")) {
            return "reply";
        }
        return "both";
    }

    private String assessRiskLevel(String scenario) {
        String normalized = scenario.toLowerCase(Locale.ROOT);
        if (containsAnyTerm(normalized, configuredTerms(RISK_HIGH_TERMS_PROP, DEFAULT_RISK_HIGH_TERMS))) {
            return "高";
        }
        if (containsAnyTerm(normalized, configuredTerms(RISK_MEDIUM_TERMS_PROP, DEFAULT_RISK_MEDIUM_TERMS))) {
            return "中";
        }
        return "低";
    }

    private Set<String> configuredTerms(String propertyName, Set<String> defaults) {
        String raw = System.getProperty(propertyName);
        if (raw == null || raw.isBlank()) {
            return defaults;
        }
        Set<String> terms = new LinkedHashSet<>();
        Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(term -> !term.isBlank())
                .forEach(terms::add);
        return terms.isEmpty() ? defaults : Set.copyOf(terms);
    }

    private boolean containsAnyTerm(String text, Set<String> terms) {
        for (String term : terms) {
            if (text.contains(term.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private int estimateConfidence(String scenario) {
        String normalized = scenario == null ? "" : scenario;
        int score = 58 + Math.min(24, normalized.length() / 6);
        if (normalized.contains("因为")) {
            score += 4;
        }
        if (normalized.contains("希望") || normalized.contains("想")) {
            score += 4;
        }
        return Math.min(score, 92);
    }

    private String normalizePriorityFocus(Object rawFocus) {
        if (rawFocus == null) {
            return null;
        }
        String focus = String.valueOf(rawFocus).trim().toLowerCase(Locale.ROOT);
        if (focus.isBlank()) {
            return null;
        }
        if (focus.contains("1") || focus.contains("p1") || focus.contains("一级")) {
            return "p1";
        }
        if (focus.contains("2") || focus.contains("p2") || focus.contains("二级")) {
            return "p2";
        }
        if (focus.contains("3") || focus.contains("p3") || focus.contains("三级")) {
            return "p3";
        }
        return null;
    }

    private String buildPrioritySection(String riskLevel, String priorityFocus) {
        String p1Focus = "高".equals(riskLevel) ? "先止损，避免冲突升级" : "先完成关键对齐";
        String p2Focus = "高".equals(riskLevel) ? "补全事实与边界" : "补充信息与反馈";
        String p3Focus = "高".equals(riskLevel) ? "复盘触发因素" : "优化后续节奏";
        if ("p1".equals(priorityFocus)) {
            return "建议优先级（已聚焦 P1）\n"
                    + "- P1: " + p1Focus + "\n\n";
        }
        if ("p2".equals(priorityFocus)) {
            return "建议优先级（已聚焦 P2）\n"
                    + "- P2: " + p2Focus + "\n\n";
        }
        if ("p3".equals(priorityFocus)) {
            return "建议优先级（已聚焦 P3）\n"
                    + "- P3: " + p3Focus + "\n\n";
        }
        return "建议优先级\n"
                + "- P1: " + p1Focus + "\n"
                + "- P2: " + p2Focus + "\n"
                + "- P3: " + p3Focus + "\n\n";
    }

    private String build24HourChecklist(String riskLevel, String priorityFocus) {
        String syncWindow = "高".equals(riskLevel) ? "2小时内" : "今天内";
        String focusedAction = switch (priorityFocus == null ? "" : priorityFocus) {
            case "p1" -> "优先完成 P1：先止损并明确最关键的一步沟通动作。";
            case "p2" -> "优先完成 P2：补齐事实与边界，避免误判。";
            case "p3" -> "优先完成 P3：做一轮复盘，优化后续节奏。";
            default -> "按 P1->P2->P3 顺序推进，避免同时开太多线程。";
        };
        return "24小时行动清单\n"
                + "1) 写下事实与目标（5分钟），只保留可验证信息。\n"
                + "2) 用“事实-感受-请求”发出第一条消息（10分钟）。\n"
                + "3) 在" + syncWindow + "约一个简短同步，确认下一步分工。\n"
                + "4) 同步后复盘一次：哪些表达有效，哪些需要调整。\n"
                + "5) " + focusedAction + "\n\n";
    }
}

