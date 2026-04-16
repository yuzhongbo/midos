package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryCommandService;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryFacade;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.memory.MemoryWriteBatch;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.memory.MemoryWriteOperation;
import com.zhongbo.mindos.assistant.memory.model.PreferenceProfile;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisResult;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class HermesMemoryRecorder {

    private static final String ASSISTANT_CONTEXT_MARKER = "[助手上下文]";
    private static final String TASK_FACT_MARKER = "[任务事实]";
    private static final Set<String> NON_SKILL_CHANNELS = Set.of(
            "llm",
            "memory.direct",
            "semantic.clarify",
            "conversational-bypass",
            "decision.invalid",
            "loop.guard",
            "security.guard",
            "system.draining"
    );

    private final DispatcherMemoryFacade dispatcherMemoryFacade;
    private final DispatcherMemoryCommandService memoryCommandService;
    private final DispatchMemoryLifecycle dispatchMemoryLifecycle;
    private final SemanticRoutingSupport semanticRoutingSupport;

    HermesMemoryRecorder(DispatcherMemoryFacade dispatcherMemoryFacade,
                         DispatcherMemoryCommandService memoryCommandService,
                         DispatchMemoryLifecycle dispatchMemoryLifecycle,
                         SemanticRoutingSupport semanticRoutingSupport) {
        this.dispatcherMemoryFacade = dispatcherMemoryFacade;
        this.memoryCommandService = memoryCommandService;
        this.dispatchMemoryLifecycle = dispatchMemoryLifecycle;
        this.semanticRoutingSupport = semanticRoutingSupport;
    }

    void record(String userId,
                String userInput,
                SkillResult finalResult,
                Map<String, Object> profileContext,
                SemanticAnalysisResult semanticAnalysis,
                String attemptedSkill,
                Boolean attemptedSuccess,
                boolean memoryEnabled) {
        if (!memoryEnabled || userId == null || userId.isBlank() || dispatcherMemoryFacade == null) {
            return;
        }
        MemoryWriteBatch batch = dispatchMemoryLifecycle == null
                ? MemoryWriteBatch.empty()
                : dispatchMemoryLifecycle.recordUserInput(userId, userInput == null ? "" : userInput);
        if (finalResult != null) {
            if (dispatchMemoryLifecycle != null) {
                batch = batch.merge(dispatchMemoryLifecycle.recordSkillOutcome(userId, finalResult));
            } else if (finalResult.output() != null && !finalResult.output().isBlank()) {
                batch = batch.append(new MemoryWriteOperation.AppendAssistantConversation(finalResult.output()));
            }
        }
        if (attemptedSkill != null && !attemptedSkill.isBlank() && attemptedSuccess != null && shouldRecordSkillUsage(attemptedSkill)) {
            batch = batch.append(new MemoryWriteOperation.RecordSkillUsage(attemptedSkill, userInput, attemptedSuccess));
        }

        PreferenceProfile learnedProfile = buildLearnedProfile(profileContext);
        if (!PreferenceProfile.empty().equals(learnedProfile)) {
            PreferenceProfile merged = dispatcherMemoryFacade.getPreferenceProfile(userId).merge(learnedProfile);
            batch = batch.append(new MemoryWriteOperation.UpdatePreferenceProfile(merged));
        }

        if (semanticRoutingSupport != null
                && finalResult != null
                && shouldRecordSemanticSummary(finalResult.skillName())) {
            batch = batch.merge(semanticRoutingSupport.maybeStoreSemanticSummary(userId, userInput, semanticAnalysis));
        }
        batch = batch.merge(buildTaskFactBatch(semanticAnalysis, finalResult));

        String rollup = buildConversationRollup(userInput, semanticAnalysis, finalResult);
        if (!rollup.isBlank()) {
            batch = batch.append(new MemoryWriteOperation.WriteSemantic(rollup, List.of(), "conversation-rollup"));
        }
        applyBatch(userId, batch);
    }

    private void applyBatch(String userId, MemoryWriteBatch batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        for (MemoryWriteOperation operation : batch.operations()) {
            if (operation instanceof MemoryWriteOperation.AppendUserConversation value) {
                commandService().appendUserConversation(userId, value.userInput());
            } else if (operation instanceof MemoryWriteOperation.AppendAssistantConversation value) {
                commandService().appendAssistantConversation(userId, value.reply());
            } else if (operation instanceof MemoryWriteOperation.WriteSemantic value) {
                commandService().writeSemantic(userId, value.text(), value.embedding(), value.bucket());
            } else if (operation instanceof MemoryWriteOperation.UpdatePreferenceProfile value) {
                commandService().updatePreferenceProfile(userId, value.profile());
            } else if (operation instanceof MemoryWriteOperation.RecordSkillUsage value) {
                commandService().recordSkillUsage(userId, value.skillName(), value.input(), value.success());
            } else if (operation instanceof MemoryWriteOperation.WriteProcedural value) {
                commandService().writeProcedural(userId, value.entry());
            }
        }
    }

    private DispatcherMemoryCommandService commandService() {
        return memoryCommandService == null
                ? new DispatcherMemoryCommandService(dispatcherMemoryFacade, null)
                : memoryCommandService;
    }

    private boolean shouldRecordSkillUsage(String skillName) {
        String normalized = normalize(skillName);
        return !normalized.isBlank() && !NON_SKILL_CHANNELS.contains(normalized);
    }

    private boolean shouldRecordSemanticSummary(String skillName) {
        String normalized = normalize(skillName);
        return !normalized.isBlank()
                && !"llm".equals(normalized)
                && !"memory.direct".equals(normalized)
                && !"security.guard".equals(normalized)
                && !"system.draining".equals(normalized);
    }

    private PreferenceProfile buildLearnedProfile(Map<String, Object> profileContext) {
        Map<String, Object> safeContext = profileContext == null ? Map.of() : profileContext;
        return new PreferenceProfile(
                sanitizeProfileValue(safeContext.get("assistantName")),
                sanitizeProfileValue(safeContext.get("role")),
                sanitizeProfileValue(safeContext.get("style")),
                sanitizeProfileValue(safeContext.get("language")),
                sanitizeProfileValue(safeContext.get("timezone")),
                null
        );
    }

    private String buildConversationRollup(String userInput,
                                           SemanticAnalysisResult semanticAnalysis,
                                           SkillResult finalResult) {
        if (semanticAnalysis == null) {
            return "";
        }
        if (finalResult == null || !shouldRecordSemanticSummary(finalResult.skillName())) {
            return "";
        }
        String summary = firstNonBlank(semanticAnalysis.summary(), semanticAnalysis.intent());
        if (summary.isBlank()) {
            return "";
        }
        StringBuilder entry = new StringBuilder();
        entry.append(ASSISTANT_CONTEXT_MARKER).append(' ');
        entry.append("用户刚才在处理：").append(summary);
        if (finalResult != null && finalResult.skillName() != null && !finalResult.skillName().isBlank()) {
            entry.append("；执行方式：").append(finalResult.skillName());
        }
        entry.append("；结果：").append(finalResult.success() ? "已推进" : "遇到阻塞");
        String scope = humanizedContextScope(semanticAnalysis.contextScope());
        if (!scope.isBlank()) {
            entry.append("；上下文：").append(scope);
        }
        String paramsDigest = summarizePayload(semanticAnalysis.payload());
        if (!paramsDigest.isBlank()) {
            entry.append("；关键信息：").append(paramsDigest);
        }
        if (userInput != null && !userInput.isBlank()) {
            entry.append("；用户原话：").append(cap(userInput, 120));
        }
        return cap(entry.toString(), 320);
    }

    private MemoryWriteBatch buildTaskFactBatch(SemanticAnalysisResult semanticAnalysis, SkillResult finalResult) {
        if (semanticAnalysis == null || finalResult == null || !finalResult.success()) {
            return MemoryWriteBatch.empty();
        }
        if (!shouldRecordSemanticSummary(finalResult.skillName())
                || "realtime".equals(semanticAnalysis.contextScope())
                || !"none".equals(semanticAnalysis.memoryOperation())) {
            return MemoryWriteBatch.empty();
        }
        String entry = buildTaskFactEntry(semanticAnalysis);
        if (entry.isBlank()) {
            return MemoryWriteBatch.empty();
        }
        return MemoryWriteBatch.of(new MemoryWriteOperation.WriteSemantic(entry, List.of(), "task"));
    }

    private String buildTaskFactEntry(SemanticAnalysisResult semanticAnalysis) {
        Map<String, Object> payload = semanticAnalysis.payload();
        if (payload == null || payload.isEmpty()) {
            return "";
        }
        String task = firstNonBlank(
                stringValue(payload.get("task")),
                stringValue(payload.get("title")),
                stringValue(payload.get("goal")),
                semanticAnalysis.taskFocus()
        );
        String goal = stringValue(payload.get("goal"));
        String dueDate = firstNonBlank(
                stringValue(payload.get("dueDate")),
                stringValue(payload.get("deadline")),
                stringValue(payload.get("date")),
                stringValue(payload.get("time")),
                stringValue(payload.get("scheduleTime"))
        );
        String project = stringValue(payload.get("project"));
        String owner = firstNonBlank(stringValue(payload.get("owner")), stringValue(payload.get("assignee")));
        String location = firstNonBlank(stringValue(payload.get("location")), stringValue(payload.get("place")));
        String topic = stringValue(payload.get("topic"));
        if (task.isBlank() && dueDate.isBlank() && project.isBlank() && owner.isBlank() && location.isBlank() && topic.isBlank()) {
            return "";
        }
        StringBuilder entry = new StringBuilder(TASK_FACT_MARKER).append(' ');
        appendFactSegment(entry, "当前事项", task);
        appendFactSegment(entry, "项目", project);
        appendFactSegment(entry, "目标", goal.equals(task) ? "" : goal);
        appendFactSegment(entry, "主题", topic.equals(task) ? "" : topic);
        appendFactSegment(entry, "截止时间", dueDate);
        appendFactSegment(entry, "负责人", owner);
        appendFactSegment(entry, "地点", location);
        return cap(entry.toString(), 220);
    }

    private void appendFactSegment(StringBuilder entry, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (entry.length() > TASK_FACT_MARKER.length() + 1) {
            entry.append('；');
        }
        entry.append(label).append('：').append(cap(value.trim(), 80));
    }

    private String summarizePayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        payload.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    if (builder.length() > 0) {
                        builder.append(", ");
                    }
                    builder.append(entry.getKey()).append('=').append(entry.getValue());
                });
        return cap(builder.toString(), 120);
    }

    private String humanizedContextScope(String contextScope) {
        if (contextScope == null || contextScope.isBlank()) {
            return "";
        }
        return switch (contextScope) {
            case "continuation" -> "延续上文";
            case "realtime" -> "需要最新信息";
            case "memory" -> "围绕历史内容";
            case "standalone" -> "独立请求";
            default -> "";
        };
    }

    private String sanitizeProfileValue(Object value) {
        if (value == null) {
            return null;
        }
        String normalized = String.valueOf(value).trim();
        if (normalized.isBlank()) {
            return null;
        }
        String canonical = normalized.toLowerCase(Locale.ROOT);
        if (Set.of("unknown", "null", "n/a", "na", "tbd", "todo", "随便", "不知道", "待定").contains(canonical)) {
            return null;
        }
        return normalized;
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

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String cap(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength - 3)) + "...";
    }
}
