package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisResult;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class HermesProactiveAssistant {

    private static final Set<String> BLOCKED_CHANNELS = Set.of(
            "decision.invalid",
            "loop.guard",
            "memory.direct",
            "memory.mode",
            "security.guard",
            "semantic.clarify",
            "skills.help",
            "system.draining"
    );

    private static final Set<String> ELIGIBLE_INTENT_STATES = Set.of(
            "start",
            "continue",
            "update",
            "remind"
    );

    private static final int MAX_REPLY_CHARS = 320;
    private static final int MAX_REPLY_LINES = 6;

    Augmentation maybeAugment(String userInput, HermesDecisionContext context, SkillResult result) {
        if (context == null || result == null || !result.success()) {
            return Augmentation.unchanged(result);
        }
        String channel = normalize(result.skillName());
        if (BLOCKED_CHANNELS.contains(channel)) {
            return Augmentation.unchanged(result);
        }
        SemanticAnalysisResult semanticAnalysis = context.semanticAnalysis() == null
                ? SemanticAnalysisResult.empty()
                : context.semanticAnalysis();
        if (!ELIGIBLE_INTENT_STATES.contains(semanticAnalysis.intentState())) {
            return Augmentation.unchanged(result);
        }
        if ("realtime".equals(semanticAnalysis.contextScope()) || !"none".equals(semanticAnalysis.memoryOperation())) {
            return Augmentation.unchanged(result);
        }
        String output = safeText(result.output());
        if (output.isBlank() || !isEligibleReply(output) || alreadyContainsGuidance(output)) {
            return Augmentation.unchanged(result);
        }
        TaskThread taskThread = resolveTaskThread(context, semanticAnalysis);
        if (taskThread.task().isBlank()) {
            return Augmentation.unchanged(result);
        }
        Guidance guidance = buildGuidance(taskThread, semanticAnalysis.intentState());
        if (guidance.text().isBlank()) {
            return Augmentation.unchanged(result);
        }
        String suffix = "\n\n" + guidance.text();
        SkillResult augmented = SkillResult.success(result.skillName(), output + suffix);
        return new Augmentation(augmented, true, guidance.type(), suffix);
    }

    private boolean isEligibleReply(String output) {
        if (output.length() > MAX_REPLY_CHARS || output.contains("```")) {
            return false;
        }
        int lines = output.split("\\R").length;
        return lines <= MAX_REPLY_LINES;
    }

    private boolean alreadyContainsGuidance(String output) {
        String normalized = normalize(output);
        return normalized.contains("下一步")
                || normalized.contains("接下来")
                || normalized.contains("需要的话我可以")
                || normalized.contains("你要的话我可以")
                || normalized.contains("我可以直接继续");
    }

    private TaskThread resolveTaskThread(HermesDecisionContext context, SemanticAnalysisResult semanticAnalysis) {
        Map<String, Object> payload = semanticAnalysis.payload() == null ? Map.of() : semanticAnalysis.payload();
        Map<String, Object> attributes = safeAttributes(context.skillContext());
        String task = firstNonBlank(
                sanitizeTask(stringValue(attributes.get("activeTask"))),
                sanitizeTask(stringValue(payload.get("task"))),
                sanitizeTask(stringValue(payload.get("title"))),
                sanitizeTask(stringValue(payload.get("goal"))),
                continuationTask(semanticAnalysis)
        );
        String nextAction = firstNonBlank(
                sanitizeNextAction(stringValue(payload.get("nextAction"))),
                sanitizeNextAction(stringValue(payload.get("next_step"))),
                sanitizeNextAction(stringValue(attributes.get("activeTaskNextAction")))
        );
        String dueDate = firstNonBlank(
                stringValue(payload.get("dueDate")),
                stringValue(payload.get("deadline")),
                stringValue(payload.get("date")),
                stringValue(payload.get("time")),
                stringValue(payload.get("scheduleTime")),
                stringValue(attributes.get("activeTaskDueDate"))
        );
        String preferenceHint = stringValue(attributes.get("activeTaskPreferenceHint"));
        return new TaskThread(task, nextAction, dueDate, preferenceHint);
    }

    private String continuationTask(SemanticAnalysisResult semanticAnalysis) {
        String intentState = semanticAnalysis == null ? "" : semanticAnalysis.intentState();
        if (!Set.of("continue", "update", "remind").contains(intentState)) {
            return "";
        }
        return sanitizeTask(semanticAnalysis.taskFocus());
    }

    private Guidance buildGuidance(TaskThread taskThread, String intentState) {
        String task = taskThread.task();
        String nextAction = taskThread.nextAction();
        String dueDate = safeText(taskThread.dueDate());
        if ("remind".equals(intentState)) {
            if (!dueDate.isBlank()) {
                return new Guidance(
                        "reminder-detail",
                        "下一步建议：把提醒时间细到具体时点，这样「" + task + "」更不容易漏掉。需要的话我可以直接补上。"
                );
            }
            return new Guidance(
                    "reminder-detail",
                    "下一步建议：把提醒时间说到具体日期或时点。需要的话我可以直接帮你补全。"
            );
        }
        if (!nextAction.isBlank()) {
            return new Guidance(
                    "next-action",
                    "下一步建议：先" + ensureSentence(nextAction) + " 需要的话我可以直接继续做这一步。"
            );
        }
        if (!dueDate.isBlank()) {
            return new Guidance(
                    "deadline-focus",
                    "下一步建议：先把「" + task + "」的下一步动作定清楚，这样更容易卡住 " + dueDate + " 这个节点。需要的话我可以直接帮你补上。"
            );
        }
        if ("update".equals(intentState) && !safeText(taskThread.preferenceHint()).isBlank()) {
            return new Guidance(
                    "task-update",
                    "下一步建议：沿用当前任务线程继续吸收新约束，再落成一个明确下一步。需要的话我可以直接帮你整理出来。"
            );
        }
        return new Guidance(
                "task-continue",
                "下一步建议：先把「" + task + "」拆成一个明确下一步。需要的话我可以直接继续推进。"
        );
    }

    private String ensureSentence(String value) {
        String text = safeText(value);
        if (text.isBlank()) {
            return "";
        }
        return text.endsWith("。") || text.endsWith("！") || text.endsWith("？") ? text : text + "。";
    }

    private Map<String, Object> safeAttributes(SkillContext skillContext) {
        if (skillContext == null || skillContext.attributes() == null || skillContext.attributes().isEmpty()) {
            return Map.of();
        }
        return skillContext.attributes();
    }

    private String sanitizeTask(String value) {
        String text = safeText(value);
        if (text.isBlank()) {
            return "";
        }
        text = text.replace("继续推进：", "")
                .replace("暂停当前事项：", "")
                .replace("将当前事项标记为完成：", "")
                .replace("为当前事项设置提醒：", "")
                .replace("创建一个待办：", "")
                .replace("创建待办：", "")
                .replace("当前事项 ", "")
                .trim();
        int separator = text.indexOf('；');
        if (separator > 0) {
            text = text.substring(0, separator).trim();
        }
        return text;
    }

    private String sanitizeNextAction(String value) {
        String text = safeText(value);
        if (text.isBlank()) {
            return "";
        }
        if (text.startsWith("继续通过 ") || text.startsWith("等待提醒后继续推进")) {
            return "";
        }
        return text;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalize(String value) {
        return safeText(value).toLowerCase(Locale.ROOT);
    }

    record Augmentation(SkillResult result, boolean applied, String hintType, String appendedSuffix) {

        static Augmentation unchanged(SkillResult result) {
            return new Augmentation(result, false, "", "");
        }
    }

    private record TaskThread(String task, String nextAction, String dueDate, String preferenceHint) {
    }

    private record Guidance(String type, String text) {
    }
}
