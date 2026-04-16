package com.zhongbo.mindos.assistant.common.dto;

import java.util.LinkedHashMap;
import java.util.Map;

public record TaskThreadSnapshotDto(
        String focus,
        String state,
        String nextAction,
        String project,
        String topic,
        String dueDate,
        String preferenceHint,
        String summary
) {

    public TaskThreadSnapshotDto {
        focus = safeText(focus);
        state = safeText(state);
        nextAction = safeText(nextAction);
        project = safeText(project);
        topic = safeText(topic);
        dueDate = safeText(dueDate);
        preferenceHint = safeText(preferenceHint);
        summary = safeText(summary);
    }

    public static TaskThreadSnapshotDto empty() {
        return new TaskThreadSnapshotDto("", "", "", "", "", "", "", "");
    }

    public boolean isEmpty() {
        return focus.isBlank()
                && state.isBlank()
                && nextAction.isBlank()
                && project.isBlank()
                && topic.isBlank()
                && dueDate.isBlank()
                && preferenceHint.isBlank()
                && summary.isBlank();
    }

    public Map<String, Object> asAttributes() {
        if (isEmpty()) {
            return Map.of();
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        putIfHasText(attributes, "activeTask", focus);
        putIfHasText(attributes, "activeTaskState", state);
        putIfHasText(attributes, "activeTaskNextAction", nextAction);
        putIfHasText(attributes, "activeTaskProject", project);
        putIfHasText(attributes, "activeTaskTopic", topic);
        putIfHasText(attributes, "activeTaskDueDate", dueDate);
        putIfHasText(attributes, "activeTaskPreferenceHint", preferenceHint);
        putIfHasText(attributes, "activeTaskSummary", summary);
        return Map.copyOf(attributes);
    }

    public String toMemoryContextSection() {
        if (isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder("Active task thread:\n");
        appendLine(builder, "当前事项", focus);
        appendLine(builder, "状态", state);
        appendLine(builder, "下一步", nextAction);
        appendLine(builder, "项目", project);
        appendLine(builder, "主题", topic);
        appendLine(builder, "截止时间", dueDate);
        appendLine(builder, "偏好", preferenceHint);
        return builder.toString().trim();
    }

    private static void appendLine(StringBuilder builder, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        builder.append("- ").append(label).append("：").append(value.trim()).append('\n');
    }

    private static void putIfHasText(Map<String, Object> target, String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        target.put(key, value.trim());
    }

    private static String safeText(String value) {
        return value == null ? "" : value.trim();
    }
}
