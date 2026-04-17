package com.zhongbo.mindos.assistant.common.dto;

import java.util.LinkedHashMap;
import java.util.Map;

public record ActiveGoalSnapshotDto(
        String goalId,
        String title,
        String objective,
        String status,
        String successCriteria,
        String dueDate,
        String nextReviewAt,
        int progressPercent,
        String summary
) {

    public ActiveGoalSnapshotDto {
        goalId = safeText(goalId);
        title = safeText(title);
        objective = safeText(objective);
        status = safeText(status);
        successCriteria = safeText(successCriteria);
        dueDate = safeText(dueDate);
        nextReviewAt = safeText(nextReviewAt);
        progressPercent = Math.max(0, Math.min(100, progressPercent));
        summary = safeText(summary);
    }

    public static ActiveGoalSnapshotDto empty() {
        return new ActiveGoalSnapshotDto("", "", "", "", "", "", "", 0, "");
    }

    public boolean isEmpty() {
        return title.isBlank()
                && objective.isBlank()
                && status.isBlank()
                && successCriteria.isBlank()
                && dueDate.isBlank()
                && nextReviewAt.isBlank()
                && progressPercent <= 0
                && summary.isBlank();
    }

    public Map<String, Object> asAttributes() {
        if (isEmpty()) {
            return Map.of();
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        putIfHasText(attributes, "activeGoalId", goalId);
        putIfHasText(attributes, "activeGoal", title);
        putIfHasText(attributes, "activeGoalObjective", objective);
        putIfHasText(attributes, "activeGoalStatus", status);
        putIfHasText(attributes, "activeGoalSuccessCriteria", successCriteria);
        putIfHasText(attributes, "activeGoalDueDate", dueDate);
        putIfHasText(attributes, "activeGoalNextReviewAt", nextReviewAt);
        attributes.put("activeGoalProgressPercent", progressPercent);
        putIfHasText(attributes, "activeGoalSummary", summary);
        return Map.copyOf(attributes);
    }

    public String toMemoryContextSection() {
        if (isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder("Active long-term goal:\n");
        appendLine(builder, "长期目标", title);
        appendLine(builder, "目标说明", objective);
        appendLine(builder, "状态", status);
        if (progressPercent > 0) {
            appendLine(builder, "进度", progressPercent + "%");
        }
        appendLine(builder, "完成标准", successCriteria);
        appendLine(builder, "目标截止", dueDate);
        appendLine(builder, "下次复盘", nextReviewAt);
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
