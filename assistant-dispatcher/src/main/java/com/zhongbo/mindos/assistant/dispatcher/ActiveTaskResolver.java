package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.dto.PromptMemoryContextDto;
import com.zhongbo.mindos.assistant.common.dto.RetrievedMemoryItemDto;
import com.zhongbo.mindos.assistant.common.dto.TaskThreadSnapshotDto;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryFacade;
import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ActiveTaskResolver {

    private static final String TASK_FACT_MARKER = "[任务事实]";
    private static final String TASK_STATE_MARKER = "[任务状态]";
    private static final String LEARNING_SIGNAL_MARKER = "[学习信号]";
    private static final List<String> LABEL_PREFIXES = List.of(
            "[fact]", "[working]", "[buffer]", "[summary]", "[assistant-context]"
    );

    private final DispatcherMemoryFacade dispatcherMemoryFacade;

    ActiveTaskResolver(DispatcherMemoryFacade dispatcherMemoryFacade) {
        this.dispatcherMemoryFacade = dispatcherMemoryFacade;
    }

    ResolvedTaskThread resolve(String userId, String userInput, PromptMemoryContextDto promptMemoryContext) {
        if (promptMemoryContext != null
                && promptMemoryContext.taskThreadSnapshot() != null
                && !promptMemoryContext.taskThreadSnapshot().isEmpty()) {
            return ResolvedTaskThread.fromSnapshot(promptMemoryContext.taskThreadSnapshot());
        }
        Builder builder = new Builder();
        harvestPromptMemory(promptMemoryContext, builder);
        if (builder.isEmpty() && dispatcherMemoryFacade != null && userId != null && !userId.isBlank()) {
            for (SemanticMemoryEntry entry : dispatcherMemoryFacade.searchKnowledge(userId, userInput, 4, "task")) {
                if (entry != null && entry.text() != null) {
                    ingest(entry.text(), builder);
                }
            }
        }
        if (builder.focus.isBlank()) {
            return ResolvedTaskThread.empty();
        }
        return new ResolvedTaskThread(
                builder.focus,
                builder.state,
                builder.nextAction,
                builder.project,
                builder.topic,
                builder.dueDate,
                builder.deliverable,
                builder.blocker,
                builder.doneDefinition,
                builder.preferenceHint,
                builder.summary()
        );
    }

    String enrichMemoryContext(String memoryContext, ResolvedTaskThread taskThread, int maxChars) {
        String base = memoryContext == null ? "" : memoryContext.trim();
        if (taskThread == null || taskThread.isEmpty()) {
            return base;
        }
        String section = taskThread.toMemoryContextSection();
        if (section.isBlank()) {
            return base;
        }
        String merged = base.isBlank() ? section : base + "\n" + section;
        if (merged.length() <= maxChars || maxChars <= 0) {
            return merged;
        }
        return merged.substring(0, Math.max(0, maxChars - 3)) + "...";
    }

    private void harvestPromptMemory(PromptMemoryContextDto promptMemoryContext, Builder builder) {
        if (promptMemoryContext == null) {
            return;
        }
        ingest(promptMemoryContext.semanticContext(), builder);
        if (promptMemoryContext.debugTopItems() == null || promptMemoryContext.debugTopItems().isEmpty()) {
            return;
        }
        for (RetrievedMemoryItemDto item : promptMemoryContext.debugTopItems()) {
            if (item == null || item.text() == null) {
                continue;
            }
            ingest(item.text(), builder);
        }
    }

    private void ingest(String rawText, Builder builder) {
        String normalized = normalize(rawText);
        if (normalized.isBlank()) {
            return;
        }
        for (String rawLine : normalized.split("\\R")) {
            String line = stripLinePrefix(rawLine);
            if (line.isBlank() || line.endsWith(":") || "none".equalsIgnoreCase(line)) {
                continue;
            }
            parseFragments(line, builder);
        }
    }

    private void parseFragments(String line, Builder builder) {
        for (String fragment : line.split("[；;]")) {
            String candidate = stripMarkers(fragment);
            if (candidate.isBlank()) {
                continue;
            }
            assign(builder, "当前事项", candidate);
            assign(builder, "任务", candidate);
            assign(builder, "事项", candidate);
            assign(builder, "状态", candidate);
            assign(builder, "下一步", candidate);
            assign(builder, "项目", candidate);
            assign(builder, "主题", candidate);
            assign(builder, "截止时间", candidate);
            assign(builder, "交付物", candidate);
            assign(builder, "产出物", candidate);
            assign(builder, "阻塞点", candidate);
            assign(builder, "卡点", candidate);
            assign(builder, "阻塞", candidate);
            assign(builder, "完成标准", candidate);
            assign(builder, "验收标准", candidate);
            assign(builder, "完成定义", candidate);
            assign(builder, "偏好", candidate);
        }
    }

    private void assign(Builder builder, String label, String fragment) {
        if (fragment == null || fragment.isBlank()) {
            return;
        }
        String prefix = label + "：";
        int index = fragment.indexOf(prefix);
        if (index < 0) {
            return;
        }
        String value = fragment.substring(index + prefix.length()).trim();
        if (value.isBlank()) {
            return;
        }
        switch (label) {
            case "当前事项", "任务", "事项" -> {
                if (builder.focus.isBlank()) {
                    builder.focus = value;
                }
            }
            case "状态" -> {
                if (builder.state.isBlank()) {
                    builder.state = value;
                }
            }
            case "下一步" -> {
                if (builder.nextAction.isBlank()) {
                    builder.nextAction = value;
                }
            }
            case "项目" -> {
                if (builder.project.isBlank()) {
                    builder.project = value;
                }
            }
            case "主题" -> {
                if (builder.topic.isBlank()) {
                    builder.topic = value;
                }
            }
            case "截止时间" -> {
                if (builder.dueDate.isBlank()) {
                    builder.dueDate = value;
                }
            }
            case "交付物", "产出物" -> {
                if (builder.deliverable.isBlank()) {
                    builder.deliverable = value;
                }
            }
            case "阻塞点", "卡点", "阻塞" -> {
                if (builder.blocker.isBlank()) {
                    builder.blocker = value;
                }
            }
            case "完成标准", "验收标准", "完成定义" -> {
                if (builder.doneDefinition.isBlank()) {
                    builder.doneDefinition = value;
                }
            }
            case "偏好" -> {
                if (builder.preferenceHint.isBlank()) {
                    builder.preferenceHint = value;
                }
            }
            default -> {
            }
        }
    }

    private String stripLinePrefix(String rawLine) {
        String line = rawLine == null ? "" : rawLine.trim();
        if (line.startsWith("- ")) {
            line = line.substring(2).trim();
        }
        for (String prefix : LABEL_PREFIXES) {
            if (line.startsWith(prefix)) {
                line = line.substring(prefix.length()).trim();
                break;
            }
        }
        return line;
    }

    private String stripMarkers(String fragment) {
        String value = fragment == null ? "" : fragment.trim();
        return value.replace(TASK_FACT_MARKER, "")
                .replace(TASK_STATE_MARKER, "")
                .replace(LEARNING_SIGNAL_MARKER, "")
                .replaceAll("\\s+", " ")
                .replaceAll("^[：:;；,，\\-\\s]+", "")
                .trim();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    record ResolvedTaskThread(String focus,
                              String state,
                              String nextAction,
                              String project,
                              String topic,
                              String dueDate,
                              String deliverable,
                              String blocker,
                              String doneDefinition,
                              String preferenceHint,
                              String summary) {

        static ResolvedTaskThread empty() {
            return new ResolvedTaskThread("", "", "", "", "", "", "", "", "", "", "");
        }

        static ResolvedTaskThread fromSnapshot(TaskThreadSnapshotDto snapshot) {
            if (snapshot == null || snapshot.isEmpty()) {
                return empty();
            }
            return new ResolvedTaskThread(
                    snapshot.focus(),
                    snapshot.state(),
                    snapshot.nextAction(),
                    snapshot.project(),
                    snapshot.topic(),
                    snapshot.dueDate(),
                    snapshot.deliverable(),
                    snapshot.blocker(),
                    snapshot.doneDefinition(),
                    snapshot.preferenceHint(),
                    snapshot.summary()
            );
        }

        boolean isEmpty() {
            return focus == null || focus.isBlank();
        }

        String toMemoryContextSection() {
            return toSnapshot().toMemoryContextSection();
        }

        Map<String, Object> asAttributes() {
            return toSnapshot().asAttributes();
        }

        TaskThreadSnapshotDto toSnapshot() {
            return new TaskThreadSnapshotDto(
                    focus,
                    state,
                    nextAction,
                    project,
                    topic,
                    dueDate,
                    deliverable,
                    blocker,
                    doneDefinition,
                    preferenceHint,
                    summary
            );
        }
    }

    private static final class Builder {
        private String focus = "";
        private String state = "";
        private String nextAction = "";
        private String project = "";
        private String topic = "";
        private String dueDate = "";
        private String deliverable = "";
        private String blocker = "";
        private String doneDefinition = "";
        private String preferenceHint = "";

        private boolean isEmpty() {
            return focus.isBlank()
                    && state.isBlank()
                    && nextAction.isBlank()
                    && project.isBlank()
                    && topic.isBlank()
                    && dueDate.isBlank()
                    && deliverable.isBlank()
                    && blocker.isBlank()
                    && doneDefinition.isBlank()
                    && preferenceHint.isBlank();
        }

        private String summary() {
            StringBuilder builder = new StringBuilder();
            if (!focus.isBlank()) {
                builder.append("当前事项 ").append(focus);
            }
            if (!state.isBlank()) {
                if (builder.length() > 0) {
                    builder.append("；");
                }
                builder.append("状态 ").append(state);
            }
            if (!nextAction.isBlank()) {
                if (builder.length() > 0) {
                    builder.append("；");
                }
                builder.append("下一步 ").append(nextAction);
            }
            if (!topic.isBlank()) {
                if (builder.length() > 0) {
                    builder.append("；");
                }
                builder.append("主题 ").append(topic);
            }
            if (!deliverable.isBlank()) {
                if (builder.length() > 0) {
                    builder.append("；");
                }
                builder.append("交付物 ").append(deliverable);
            }
            if (!blocker.isBlank()) {
                if (builder.length() > 0) {
                    builder.append("；");
                }
                builder.append("阻塞点 ").append(blocker);
            }
            if (!doneDefinition.isBlank()) {
                if (builder.length() > 0) {
                    builder.append("；");
                }
                builder.append("完成标准 ").append(doneDefinition);
            }
            return builder.toString();
        }
    }
}
