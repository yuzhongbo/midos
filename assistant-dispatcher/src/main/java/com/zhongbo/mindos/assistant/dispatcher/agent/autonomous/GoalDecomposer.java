package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class GoalDecomposer {

    private static final Pattern CLAUSE_SPLIT = Pattern.compile("\\s*(?:->|=>|\\R+|然后|并且|接着|同时|after that|and then|then|;|；)\\s*");

    public List<GoalTask> decompose(Goal goal) {
        Goal safeGoal = goal == null ? Goal.of("", 0.0) : goal;
        List<String> clauses = splitClauses(safeGoal.description());
        if (clauses.isEmpty()) {
            return List.of();
        }
        List<GoalTask> tasks = new ArrayList<>();
        String previousTaskId = "";
        int index = 1;
        for (String clause : clauses) {
            String taskId = "goal-task-" + index;
            Map<String, Object> params = new LinkedHashMap<>(safeGoal.metadata());
            params.put("goalDescription", safeGoal.description());
            params.put("goalTaskDescription", clause);
            tasks.add(new GoalTask(
                    taskId,
                    clause,
                    previousTaskId.isBlank() ? List.of() : List.of(previousTaskId),
                    params,
                    inferTargetHint(clause),
                    false,
                    2
            ));
            previousTaskId = taskId;
            index++;
        }
        return List.copyOf(tasks);
    }

    private List<String> splitClauses(String description) {
        if (description == null || description.isBlank()) {
            return List.of();
        }
        String normalized = description.trim()
                .replace("1.", "\n")
                .replace("2.", "\n")
                .replace("3.", "\n")
                .replace("4.", "\n");
        String[] rawParts = CLAUSE_SPLIT.split(normalized);
        LinkedHashSet<String> clauses = new LinkedHashSet<>();
        for (String rawPart : rawParts) {
            String clause = rawPart == null ? "" : rawPart.trim();
            if (!clause.isBlank()) {
                clauses.add(clause);
            }
        }
        if (clauses.isEmpty()) {
            clauses.add(normalized);
        }
        return List.copyOf(clauses);
    }

    private String inferTargetHint(String clause) {
        String normalized = clause == null ? "" : clause.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return "";
        }
        if (containsAny(normalized, "代码", "实现", "修复", "debug", "generate code", "code")) {
            return "code.generate";
        }
        if (containsAny(normalized, "待办", "todo", "任务", "清单")) {
            return "todo.create";
        }
        if (containsAny(normalized, "文件", "代码库", "搜索文件", "grep", "search file")) {
            return "file.search";
        }
        if (containsAny(normalized, "新闻", "实时", "最新", "热点", "头条", "today news", "latest news")) {
            return "web.lookup";
        }
        if (containsAny(normalized, "情绪", "安抚", "沟通", "焦虑", "关系")) {
            return "eq.coach";
        }
        return "";
    }

    private boolean containsAny(String text, String... values) {
        if (text == null || text.isBlank() || values == null) {
            return false;
        }
        for (String value : values) {
            if (value != null && !value.isBlank() && text.contains(value.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
