package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryCommandService;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryFacade;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.memory.MemoryWriteBatch;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.memory.MemoryWriteOperation;
import com.zhongbo.mindos.assistant.memory.model.PreferenceProfile;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class HermesMemoryRecorder {

    private static final String ASSISTANT_CONTEXT_MARKER = "[助手上下文]";
    private static final String TASK_FACT_MARKER = "[任务事实]";
    private static final String TASK_STATE_MARKER = "[任务状态]";
    private static final String LEARNING_SIGNAL_MARKER = "[学习信号]";
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
        record(userId,
                userInput,
                finalResult,
                profileContext,
                semanticAnalysis,
                attemptedSkill,
                attemptedSuccess,
                Map.of(),
                memoryEnabled);
    }

    void record(String userId,
                String userInput,
                SkillResult finalResult,
                Map<String, Object> profileContext,
                SemanticAnalysisResult semanticAnalysis,
                String attemptedSkill,
                Boolean attemptedSuccess,
                Map<String, Object> executionParams,
                boolean memoryEnabled) {
        if (!memoryEnabled || userId == null || userId.isBlank() || dispatcherMemoryFacade == null) {
            return;
        }
        Map<String, Object> effectivePayload = resolveTaskPayload(semanticAnalysis, executionParams);
        String effectiveTaskFocus = resolveTaskFocus(semanticAnalysis, effectivePayload);
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
        batch = batch.merge(buildTaskFactBatch(semanticAnalysis, finalResult, effectivePayload, effectiveTaskFocus));
        batch = batch.merge(buildTaskStateBatch(semanticAnalysis, finalResult, effectiveTaskFocus));
        batch = batch.merge(buildLearningSignalBatch(userInput, semanticAnalysis, finalResult, effectiveTaskFocus));

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
        String phase = humanizedIntentPhase(semanticAnalysis.intentPhase());
        if (!phase.isBlank()) {
            entry.append("；阶段：").append(phase);
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

    private MemoryWriteBatch buildTaskFactBatch(SemanticAnalysisResult semanticAnalysis,
                                                SkillResult finalResult,
                                                Map<String, Object> effectivePayload,
                                                String effectiveTaskFocus) {
        if (semanticAnalysis == null || finalResult == null || !finalResult.success()) {
            return MemoryWriteBatch.empty();
        }
        if (!shouldRecordSemanticSummary(finalResult.skillName())
                || "realtime".equals(semanticAnalysis.contextScope())
                || !"none".equals(semanticAnalysis.memoryOperation())) {
            return MemoryWriteBatch.empty();
        }
        String entry = buildTaskFactEntry(semanticAnalysis, effectivePayload, effectiveTaskFocus);
        if (entry.isBlank()) {
            return MemoryWriteBatch.empty();
        }
        return MemoryWriteBatch.of(new MemoryWriteOperation.WriteSemantic(entry, List.of(), "task"));
    }

    private String buildTaskFactEntry(SemanticAnalysisResult semanticAnalysis,
                                      Map<String, Object> effectivePayload,
                                      String effectiveTaskFocus) {
        Map<String, Object> payload = effectivePayload == null ? Map.of() : effectivePayload;
        if (payload == null || payload.isEmpty()) {
            return "";
        }
        String task = firstNonBlank(
                stringValue(payload.get("task")),
                stringValue(payload.get("title")),
                stringValue(payload.get("goal")),
                effectiveTaskFocus,
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

    private MemoryWriteBatch buildTaskStateBatch(SemanticAnalysisResult semanticAnalysis,
                                                 SkillResult finalResult,
                                                 String effectiveTaskFocus) {
        if (semanticAnalysis == null || finalResult == null || !finalResult.success()) {
            return MemoryWriteBatch.empty();
        }
        if ("realtime".equals(semanticAnalysis.contextScope()) || "recall".equals(semanticAnalysis.memoryOperation())) {
            return MemoryWriteBatch.empty();
        }
        String entry = buildTaskStateEntry(semanticAnalysis, finalResult, effectiveTaskFocus);
        if (entry.isBlank()) {
            return MemoryWriteBatch.empty();
        }
        return MemoryWriteBatch.of(new MemoryWriteOperation.WriteSemantic(entry, List.of(), "task"));
    }

    private String buildTaskStateEntry(SemanticAnalysisResult semanticAnalysis,
                                       SkillResult finalResult,
                                       String effectiveTaskFocus) {
        String task = firstNonBlank(effectiveTaskFocus, semanticAnalysis == null ? "" : semanticAnalysis.taskFocus());
        if (task.isBlank()) {
            return "";
        }
        String state = resolveTaskState(semanticAnalysis, finalResult, task);
        if (state.isBlank()) {
            return "";
        }
        StringBuilder entry = new StringBuilder(TASK_STATE_MARKER).append(' ');
        appendFactSegment(entry, "当前事项", task);
        appendFactSegment(entry, "状态", state);
        appendFactSegment(entry, "下一步", resolveNextAction(semanticAnalysis, finalResult, task, state));
        appendFactSegment(entry, "执行方式", finalResult.skillName());
        return cap(entry.toString(), 240);
    }

    private MemoryWriteBatch buildLearningSignalBatch(String userInput,
                                                      SemanticAnalysisResult semanticAnalysis,
                                                      SkillResult finalResult,
                                                      String effectiveTaskFocus) {
        if (semanticAnalysis == null || finalResult == null || !finalResult.success()) {
            return MemoryWriteBatch.empty();
        }
        String entry = buildLearningSignalEntry(userInput, semanticAnalysis, effectiveTaskFocus);
        if (entry.isBlank()) {
            return MemoryWriteBatch.empty();
        }
        String bucket = effectiveTaskFocus.isBlank() ? "general" : "task";
        return MemoryWriteBatch.of(new MemoryWriteOperation.WriteSemantic(entry, List.of(), bucket));
    }

    private String buildLearningSignalEntry(String userInput,
                                            SemanticAnalysisResult semanticAnalysis,
                                            String effectiveTaskFocus) {
        String task = firstNonBlank(effectiveTaskFocus, semanticAnalysis.taskFocus());
        String intentState = semanticAnalysis.intentState();
        String intentPhase = semanticAnalysis.intentPhase();
        if ("blocking".equals(intentPhase) && !task.isBlank()) {
            return cap(LEARNING_SIGNAL_MARKER
                    + " 当前事项：" + task
                    + "；信号：用户会直接描述阻塞点而不是抽象求助"
                    + "；偏好：先定位卡点，再给能继续推进的下一步", 220);
        }
        if ("planning".equals(intentPhase) && !task.isBlank()) {
            return cap(LEARNING_SIGNAL_MARKER
                    + " 当前事项：" + task
                    + "；信号：用户常先要方案或步骤，再决定是否执行"
                    + "；偏好：先给结构化推进方案，再进入执行", 220);
        }
        if ("reporting".equals(intentPhase) && !task.isBlank()) {
            return cap(LEARNING_SIGNAL_MARKER
                    + " 当前事项：" + task
                    + "；信号：用户会用自然语言同步任务进展"
                    + "；偏好：接收进展后继续围绕同一事项推进", 220);
        }
        if ("continue".equals(intentState) && !task.isBlank()) {
            return cap(LEARNING_SIGNAL_MARKER
                    + " 当前事项：" + task
                    + "；信号：用户会用简短跟进延续当前任务"
                    + "；偏好：上下文明确时直接推进，少澄清", 220);
        }
        if ("update".equals(intentState) && !task.isBlank()) {
            return cap(LEARNING_SIGNAL_MARKER
                    + " 当前事项：" + task
                    + "；信号：用户倾向通过补充字段来修正当前任务"
                    + "；偏好：保留原任务线程并吸收新约束", 220);
        }
        if ("pause".equals(intentState) && !task.isBlank()) {
            return cap(LEARNING_SIGNAL_MARKER
                    + " 当前事项：" + task
                    + "；信号：用户会自然表达暂停或搁置"
                    + "；偏好：记录状态变化，但不要丢失当前任务上下文", 220);
        }
        if ("remind".equals(intentState) && userInput != null && !userInput.isBlank()) {
            return cap(LEARNING_SIGNAL_MARKER
                    + " 信号：用户会把提醒和推进要求混在自然表达里"
                    + "；偏好：优先保留任务线程，再补充提醒信息", 220);
        }
        return "";
    }

    private Map<String, Object> resolveTaskPayload(SemanticAnalysisResult semanticAnalysis, Map<String, Object> executionParams) {
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>();
        if (executionParams != null && !executionParams.isEmpty()) {
            merged.putAll(executionParams);
        }
        if (semanticAnalysis != null && semanticAnalysis.payload() != null && !semanticAnalysis.payload().isEmpty()) {
            semanticAnalysis.payload().forEach(merged::putIfAbsent);
        }
        return merged.isEmpty() ? Map.of() : Map.copyOf(merged);
    }

    private String resolveTaskFocus(SemanticAnalysisResult semanticAnalysis, Map<String, Object> effectivePayload) {
        Map<String, Object> payload = effectivePayload == null ? Map.of() : effectivePayload;
        return firstNonBlank(
                stringValue(payload.get("task")),
                stringValue(payload.get("title")),
                stringValue(payload.get("goal")),
                stringValue(payload.get("topic")),
                semanticAnalysis == null ? "" : semanticAnalysis.taskFocus()
        );
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

    private String resolveTaskState(SemanticAnalysisResult semanticAnalysis,
                                    SkillResult finalResult,
                                    String taskFocus) {
        if (semanticAnalysis == null || finalResult == null || !finalResult.success()) {
            return "";
        }
        return switch (semanticAnalysis.intentState()) {
            case "complete" -> "已完成";
            case "pause" -> "已暂停";
            case "remind" -> "待提醒";
            case "blocked" -> "受阻";
            case "update" -> "已更新";
            case "continue" -> "进行中";
            case "start" -> "已开始";
            default -> taskFocus == null || taskFocus.isBlank() ? "" : "进行中";
        };
    }

    private String resolveNextAction(SemanticAnalysisResult semanticAnalysis,
                                     SkillResult finalResult,
                                     String task,
                                     String state) {
        if (semanticAnalysis == null || task == null || task.isBlank()) {
            return "";
        }
        String payloadNext = firstNonBlank(
                stringValue(semanticAnalysis.payload().get("nextAction")),
                stringValue(semanticAnalysis.payload().get("next_step"))
        );
        if (!payloadNext.isBlank()) {
            return payloadNext;
        }
        if ("已完成".equals(state) || "已暂停".equals(state)) {
            return "";
        }
        if ("受阻".equals(state)) {
            return "先处理阻塞点后继续推进";
        }
        if ("待提醒".equals(state)) {
            return "等待提醒后继续推进";
        }
        if (finalResult != null && finalResult.skillName() != null && !finalResult.skillName().isBlank()) {
            return "继续通过 " + finalResult.skillName() + " 推进 " + task;
        }
        return "继续推进 " + task;
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

    private String humanizedIntentPhase(String intentPhase) {
        if (intentPhase == null || intentPhase.isBlank()) {
            return "";
        }
        return switch (intentPhase) {
            case "execution" -> "正在执行";
            case "planning" -> "先要方案";
            case "reporting" -> "同步进展";
            case "blocking" -> "说明阻塞";
            case "decision" -> "调整方向";
            case "memory" -> "围绕记忆";
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
