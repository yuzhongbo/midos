package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.CanonicalTaskFields;
import com.zhongbo.mindos.assistant.common.LegacyRoleSupport;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryCommandService;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryFacade;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.memory.MemoryWriteBatch;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.memory.MemoryWriteOperation;
import com.zhongbo.mindos.assistant.memory.graph.MemoryNode;
import com.zhongbo.mindos.assistant.memory.model.PreferenceProfile;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
    private final HermesDecisionPolicy decisionPolicy;

    HermesMemoryRecorder(DispatcherMemoryFacade dispatcherMemoryFacade,
                         DispatcherMemoryCommandService memoryCommandService,
                         DispatchMemoryLifecycle dispatchMemoryLifecycle,
                         HermesDecisionPolicy decisionPolicy) {
        this.dispatcherMemoryFacade = dispatcherMemoryFacade;
        this.memoryCommandService = memoryCommandService;
        this.dispatchMemoryLifecycle = dispatchMemoryLifecycle;
        this.decisionPolicy = decisionPolicy;
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
        HermesSkillIdentity skillIdentity = HermesSkillIdentity.fromRecordedOutcome(finalResult, attemptedSkill);
        String recordedSkill = skillIdentity.recordableSkill();
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
        if (!recordedSkill.isBlank() && attemptedSuccess != null && shouldRecordSkillUsage(recordedSkill)) {
            batch = batch.append(new MemoryWriteOperation.RecordSkillUsage(recordedSkill, userInput, attemptedSuccess));
        }

        PreferenceProfile learnedProfile = buildLearnedProfile(profileContext);
        if (!PreferenceProfile.empty().equals(learnedProfile)) {
            PreferenceProfile merged = dispatcherMemoryFacade.getPreferenceProfile(userId).merge(learnedProfile);
            batch = batch.append(new MemoryWriteOperation.UpdatePreferenceProfile(merged));
        }

        if (decisionPolicy != null
                && finalResult != null
                && shouldRecordSemanticSummary(firstNonBlank(recordedSkill, finalResult.skillName()))) {
            batch = batch.merge(decisionPolicy.maybeStoreSemanticSummary(userId, userInput, semanticAnalysis));
        }
        batch = batch.merge(buildTaskFactBatch(semanticAnalysis, finalResult, effectivePayload, effectiveTaskFocus, recordedSkill));
        batch = batch.merge(buildTaskStateBatch(semanticAnalysis, finalResult, effectivePayload, effectiveTaskFocus, recordedSkill));
        batch = batch.merge(buildLearningSignalBatch(userInput, semanticAnalysis, finalResult, effectiveTaskFocus));
        batch = batch.merge(buildExecutionGraphBatch(
                userId,
                userInput,
                finalResult,
                semanticAnalysis,
                effectivePayload,
                effectiveTaskFocus,
                skillIdentity
        ));

        String rollup = buildConversationRollup(userInput, semanticAnalysis, finalResult, recordedSkill);
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
            } else if (operation instanceof MemoryWriteOperation.UpsertGraphNode value) {
                commandService().upsertGraphNode(userId, value.node());
            } else if (operation instanceof MemoryWriteOperation.LinkGraph value) {
                commandService().linkGraph(
                        userId,
                        value.fromNodeId(),
                        value.relation(),
                        value.toNodeId(),
                        value.weight(),
                        value.metadata()
                );
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
                sanitizeProfileValue(LegacyRoleSupport.explicitRole(safeContext.get("role"))),
                sanitizeProfileValue(safeContext.get("style")),
                sanitizeProfileValue(safeContext.get("language")),
                sanitizeProfileValue(safeContext.get("timezone")),
                null
        );
    }

    private String buildConversationRollup(String userInput,
                                           SemanticAnalysisResult semanticAnalysis,
                                           SkillResult finalResult,
                                           String effectiveSkillName) {
        if (semanticAnalysis == null) {
            return "";
        }
        if (finalResult == null || !shouldRecordSemanticSummary(firstNonBlank(effectiveSkillName, finalResult.skillName()))) {
            return "";
        }
        String summary = firstNonBlank(semanticAnalysis.summary(), semanticAnalysis.intent());
        if (summary.isBlank()) {
            return "";
        }
        StringBuilder entry = new StringBuilder();
        entry.append(ASSISTANT_CONTEXT_MARKER).append(' ');
        entry.append("用户刚才在处理：").append(summary);
        if (effectiveSkillName != null && !effectiveSkillName.isBlank()) {
            entry.append("；执行方式：").append(effectiveSkillName);
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
                                                String effectiveTaskFocus,
                                                String effectiveSkillName) {
        if (semanticAnalysis == null || finalResult == null || !finalResult.success()) {
            return MemoryWriteBatch.empty();
        }
        if (!shouldRecordSemanticSummary(firstNonBlank(effectiveSkillName, finalResult.skillName()))
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
                CanonicalTaskFields.text(payload, CanonicalTaskFields.GOAL),
                effectiveTaskFocus,
                semanticAnalysis.taskFocus()
        );
        String goal = CanonicalTaskFields.text(payload, CanonicalTaskFields.GOAL);
        String dueDate = CanonicalTaskFields.text(payload, CanonicalTaskFields.DEADLINE);
        String project = CanonicalTaskFields.text(payload, CanonicalTaskFields.PROJECT);
        String owner = CanonicalTaskFields.text(payload, CanonicalTaskFields.OWNER);
        String location = CanonicalTaskFields.text(payload, CanonicalTaskFields.LOCATION);
        String topic = CanonicalTaskFields.text(payload, CanonicalTaskFields.TOPIC);
        String deliverable = CanonicalTaskFields.text(payload, CanonicalTaskFields.DELIVERABLE);
        String audience = CanonicalTaskFields.text(payload, CanonicalTaskFields.AUDIENCE);
        String constraints = CanonicalTaskFields.text(payload, CanonicalTaskFields.CONSTRAINTS);
        String sourceRequirement = CanonicalTaskFields.text(payload, CanonicalTaskFields.SOURCE_REQUIREMENT);
        String doneDefinition = CanonicalTaskFields.text(payload, CanonicalTaskFields.DONE_DEFINITION);
        if (task.isBlank() && dueDate.isBlank() && project.isBlank() && owner.isBlank()
                && location.isBlank() && topic.isBlank() && deliverable.isBlank()
                && audience.isBlank() && constraints.isBlank() && sourceRequirement.isBlank()
                && doneDefinition.isBlank()) {
            return "";
        }
        StringBuilder entry = new StringBuilder(TASK_FACT_MARKER).append(' ');
        appendFactSegment(entry, "当前事项", task);
        appendFactSegment(entry, "项目", project);
        appendFactSegment(entry, "目标", goal.equals(task) ? "" : goal);
        appendFactSegment(entry, "主题", topic.equals(task) ? "" : topic);
        appendFactSegment(entry, "截止时间", dueDate);
        appendFactSegment(entry, "交付物", deliverable);
        appendFactSegment(entry, "受众", audience);
        appendFactSegment(entry, "约束", constraints);
        appendFactSegment(entry, "来源要求", sourceRequirement);
        appendFactSegment(entry, "完成标准", doneDefinition);
        appendFactSegment(entry, "负责人", owner);
        appendFactSegment(entry, "地点", location);
        return cap(entry.toString(), 220);
    }

    private MemoryWriteBatch buildTaskStateBatch(SemanticAnalysisResult semanticAnalysis,
                                                 SkillResult finalResult,
                                                 Map<String, Object> effectivePayload,
                                                 String effectiveTaskFocus,
                                                 String effectiveSkillName) {
        if (semanticAnalysis == null || finalResult == null || !finalResult.success()) {
            return MemoryWriteBatch.empty();
        }
        if ("realtime".equals(semanticAnalysis.contextScope()) || "recall".equals(semanticAnalysis.memoryOperation())) {
            return MemoryWriteBatch.empty();
        }
        String entry = buildTaskStateEntry(semanticAnalysis, finalResult, effectivePayload, effectiveTaskFocus, effectiveSkillName);
        if (entry.isBlank()) {
            return MemoryWriteBatch.empty();
        }
        return MemoryWriteBatch.of(new MemoryWriteOperation.WriteSemantic(entry, List.of(), "task"));
    }

    private String buildTaskStateEntry(SemanticAnalysisResult semanticAnalysis,
                                       SkillResult finalResult,
                                       Map<String, Object> effectivePayload,
                                       String effectiveTaskFocus,
                                       String effectiveSkillName) {
        String task = firstNonBlank(effectiveTaskFocus, semanticAnalysis == null ? "" : semanticAnalysis.taskFocus());
        if (task.isBlank()) {
            return "";
        }
        String state = resolveTaskState(semanticAnalysis, finalResult, task);
        if (state.isBlank()) {
            return "";
        }
        Map<String, Object> payload = effectivePayload == null ? Map.of() : effectivePayload;
        StringBuilder entry = new StringBuilder(TASK_STATE_MARKER).append(' ');
        appendFactSegment(entry, "当前事项", task);
        appendFactSegment(entry, "状态", state);
        appendFactSegment(entry, "下一步", resolveNextAction(semanticAnalysis, finalResult, payload, task, state));
        appendFactSegment(entry, "阻塞点", CanonicalTaskFields.text(payload, CanonicalTaskFields.BLOCKER));
        appendFactSegment(entry, "完成标准", CanonicalTaskFields.text(payload, CanonicalTaskFields.DONE_DEFINITION));
        appendFactSegment(entry, "执行方式", firstNonBlank(effectiveSkillName, finalResult.skillName()));
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

    private MemoryWriteBatch buildExecutionGraphBatch(String userId,
                                                      String userInput,
                                                      SkillResult finalResult,
                                                      SemanticAnalysisResult semanticAnalysis,
                                                      Map<String, Object> effectivePayload,
                                                      String effectiveTaskFocus,
                                                      HermesSkillIdentity skillIdentity) {
        if (!dispatcherMemoryFacade.hasGraphMemory() || finalResult == null || !finalResult.success()) {
            return MemoryWriteBatch.empty();
        }
        Instant recordedAt = Instant.now();
        String channel = firstNonBlank(finalResult.skillName(), skillIdentity.executionTarget(), skillIdentity.decisionTarget());
        String routedSkill = shouldRecordSkillUsage(skillIdentity.recordableSkill()) ? skillIdentity.recordableSkill() : "";
        String taskFocus = effectiveTaskFocus == null ? "" : effectiveTaskFocus;
        String executionNodeId = executionNodeId(userId, userInput, channel, recordedAt);

        LinkedHashMap<String, Object> executionData = new LinkedHashMap<>();
        putGraphValue(executionData, "name", firstNonBlank(taskFocus, routedSkill, channel, cap(userInput, 60)));
        putGraphValue(executionData, "channel", channel);
        putGraphValue(executionData, "skillName", routedSkill);
        putGraphValue(executionData, "decisionTarget", skillIdentity.decisionTarget());
        putGraphValue(executionData, "executionTarget", skillIdentity.executionTarget());
        putGraphValue(executionData, "canonicalSkill", skillIdentity.canonicalSkill());
        putGraphValue(executionData, "task", cap(taskFocus, 120));
        putGraphValue(executionData, "userInput", cap(userInput, 160));
        putGraphValue(executionData, "intent", semanticAnalysis == null ? "" : semanticAnalysis.intent());
        putGraphValue(executionData, "summary", semanticAnalysis == null ? "" : cap(semanticAnalysis.summary(), 160));
        putGraphValue(executionData, "contextScope", semanticAnalysis == null ? "" : semanticAnalysis.contextScope());
        putGraphValue(executionData, "intentPhase", semanticAnalysis == null ? "" : semanticAnalysis.intentPhase());
        putGraphValue(executionData, "query", cap(finalResult.artifactText("query"), 120));
        putGraphValue(executionData, "detailTitle", cap(finalResult.artifactText("detailTitle"), 160));
        putGraphValue(executionData, "detailLink", cap(finalResult.artifactText("detailLink"), 200));
        putGraphValue(executionData, "detailSummary", cap(finalResult.artifactText("detailSummary"), 200));
        putGraphValue(executionData, "workflow", finalResult.metadataText("systemWorkflow"));
        putGraphValue(executionData, "workflowRecipe", finalResult.metadataText("systemWorkflowRecipe"));
        putGraphValue(executionData, "output", cap(finalResult.output(), 200));
        executionData.put("success", Boolean.TRUE);

        MemoryWriteBatch batch = MemoryWriteBatch.of(
                new MemoryWriteOperation.UpsertGraphNode(new MemoryNode(
                        executionNodeId,
                        "hermes.execution",
                        executionData,
                        recordedAt,
                        recordedAt
                ))
        );

        if (!routedSkill.isBlank()) {
            String skillNodeId = stableNodeId("hermes:skill", routedSkill);
            LinkedHashMap<String, Object> skillData = new LinkedHashMap<>();
            putGraphValue(skillData, "name", routedSkill);
            putGraphValue(skillData, "skillName", routedSkill);
            putGraphValue(skillData, "decisionTarget", skillIdentity.decisionTarget());
            putGraphValue(skillData, "executionTarget", skillIdentity.executionTarget());
            putGraphValue(skillData, "canonicalSkill", skillIdentity.canonicalSkill());
            putGraphValue(skillData, "lastTask", cap(taskFocus, 120));
            putGraphValue(skillData, "lastUserInput", cap(userInput, 160));
            putGraphValue(skillData, "lastIntent", semanticAnalysis == null ? "" : semanticAnalysis.intent());
            putGraphValue(skillData, "lastSummary", semanticAnalysis == null ? "" : cap(semanticAnalysis.summary(), 160));
            putGraphValue(skillData, "lastWorkflow", finalResult.metadataText("systemWorkflow"));
            putGraphValue(skillData, "lastWorkflowRecipe", finalResult.metadataText("systemWorkflowRecipe"));
            putGraphValue(skillData, "lastOutput", cap(finalResult.output(), 160));
            batch = batch.append(new MemoryWriteOperation.UpsertGraphNode(new MemoryNode(
                    skillNodeId,
                    "hermes.skill",
                    skillData,
                    recordedAt,
                    recordedAt
            )));
            batch = batch.append(new MemoryWriteOperation.LinkGraph(
                    executionNodeId,
                    "result-of",
                    skillNodeId,
                    1.0,
                    graphEdgeMetadata(channel, taskFocus, finalResult, skillIdentity)
            ));
        }

        if (!taskFocus.isBlank()) {
            String taskNodeId = stableNodeId("hermes:task", taskFocus);
            LinkedHashMap<String, Object> taskData = new LinkedHashMap<>();
            putGraphValue(taskData, "name", taskFocus);
            putGraphValue(taskData, "task", taskFocus);
            putGraphValue(taskData, "goal", CanonicalTaskFields.text(effectivePayload, CanonicalTaskFields.GOAL));
            putGraphValue(taskData, "project", CanonicalTaskFields.text(effectivePayload, CanonicalTaskFields.PROJECT));
            putGraphValue(taskData, "topic", CanonicalTaskFields.text(effectivePayload, CanonicalTaskFields.TOPIC));
            putGraphValue(taskData, "dueDate", CanonicalTaskFields.text(effectivePayload, CanonicalTaskFields.DEADLINE));
            putGraphValue(taskData, "nextAction", resolveNextAction(semanticAnalysis, finalResult, effectivePayload, taskFocus, resolveTaskState(semanticAnalysis, finalResult, taskFocus)));
            putGraphValue(taskData, "channel", channel);
            putGraphValue(taskData, "skillName", routedSkill);
            putGraphValue(taskData, "decisionTarget", skillIdentity.decisionTarget());
            putGraphValue(taskData, "executionTarget", skillIdentity.executionTarget());
            putGraphValue(taskData, "canonicalSkill", skillIdentity.canonicalSkill());
            putGraphValue(taskData, "intent", semanticAnalysis == null ? "" : semanticAnalysis.intent());
            putGraphValue(taskData, "summary", semanticAnalysis == null ? "" : cap(semanticAnalysis.summary(), 160));
            batch = batch.append(new MemoryWriteOperation.UpsertGraphNode(new MemoryNode(
                    taskNodeId,
                    "hermes.task",
                    taskData,
                    recordedAt,
                    recordedAt
            )));
            batch = batch.append(new MemoryWriteOperation.LinkGraph(
                    taskNodeId,
                    "latest-result",
                    executionNodeId,
                    1.0,
                    graphEdgeMetadata(channel, taskFocus, finalResult, skillIdentity)
            ));
            if (!routedSkill.isBlank()) {
                batch = batch.append(new MemoryWriteOperation.LinkGraph(
                        taskNodeId,
                        "uses-skill",
                        stableNodeId("hermes:skill", routedSkill),
                        0.85,
                        graphEdgeMetadata(channel, taskFocus, finalResult, skillIdentity)
                ));
            }
        }

        return batch;
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
        return CanonicalTaskFields.normalize(merged);
    }

    private String resolveTaskFocus(SemanticAnalysisResult semanticAnalysis, Map<String, Object> effectivePayload) {
        Map<String, Object> payload = effectivePayload == null ? Map.of() : effectivePayload;
        return firstNonBlank(
                stringValue(payload.get("task")),
                stringValue(payload.get("title")),
                CanonicalTaskFields.text(payload, CanonicalTaskFields.GOAL),
                CanonicalTaskFields.text(payload, CanonicalTaskFields.TOPIC),
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
                                     Map<String, Object> payload,
                                     String task,
                                     String state) {
        if (semanticAnalysis == null || task == null || task.isBlank()) {
            return "";
        }
        String payloadNext = CanonicalTaskFields.text(payload, CanonicalTaskFields.NEXT_ACTION);
        if (!payloadNext.isBlank()) {
            return payloadNext;
        }
        if ("已完成".equals(state) || "已暂停".equals(state)) {
            return "";
        }
        if ("受阻".equals(state)) {
            String blocker = CanonicalTaskFields.text(payload, CanonicalTaskFields.BLOCKER);
            if (!blocker.isBlank()) {
                return "先处理阻塞点：" + blocker;
            }
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

    private void putGraphValue(Map<String, Object> target, String key, String value) {
        if (target == null || key == null || key.isBlank() || value == null || value.isBlank()) {
            return;
        }
        target.put(key, value.trim());
    }

    private Map<String, Object> graphEdgeMetadata(String channel,
                                                  String taskFocus,
                                                  SkillResult finalResult,
                                                  HermesSkillIdentity skillIdentity) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        putGraphValue(metadata, "channel", channel);
        putGraphValue(metadata, "task", cap(taskFocus, 120));
        putGraphValue(metadata, "decisionTarget", skillIdentity == null ? "" : skillIdentity.decisionTarget());
        putGraphValue(metadata, "executionTarget", skillIdentity == null ? "" : skillIdentity.executionTarget());
        putGraphValue(metadata, "canonicalSkill", skillIdentity == null ? "" : skillIdentity.canonicalSkill());
        putGraphValue(metadata, "workflow", finalResult == null ? "" : finalResult.metadataText("systemWorkflow"));
        metadata.put("success", finalResult != null && finalResult.success());
        return Map.copyOf(metadata);
    }

    private String stableNodeId(String prefix, String value) {
        return prefix + ":" + UUID.nameUUIDFromBytes(normalize(value).getBytes(StandardCharsets.UTF_8));
    }

    private String executionNodeId(String userId, String userInput, String channel, Instant recordedAt) {
        String raw = firstNonBlank(userId, "anonymous")
                + "|"
                + recordedAt.toEpochMilli()
                + "|"
                + firstNonBlank(channel, "unknown")
                + "|"
                + cap(firstNonBlank(userInput, ""), 120);
        return "hermes:execution:" + UUID.nameUUIDFromBytes(raw.getBytes(StandardCharsets.UTF_8));
    }
}
