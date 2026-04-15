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
        entry.append("intent=").append(firstNonBlank(semanticAnalysis.intent(), "unknown"));
        entry.append("; intentType=").append(semanticAnalysis.intentType());
        entry.append("; contextScope=").append(semanticAnalysis.contextScope());
        if (finalResult != null && finalResult.skillName() != null && !finalResult.skillName().isBlank()) {
            entry.append("; channel=").append(finalResult.skillName());
        }
        entry.append("; outcome=").append(finalResult.success() ? "success" : "failed");
        entry.append("; summary=").append(summary);
        String paramsDigest = summarizePayload(semanticAnalysis.payload());
        if (!paramsDigest.isBlank()) {
            entry.append("; params=").append(paramsDigest);
        }
        if (userInput != null && !userInput.isBlank()) {
            entry.append("; input=").append(cap(userInput, 120));
        }
        return cap(entry.toString(), 320);
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
