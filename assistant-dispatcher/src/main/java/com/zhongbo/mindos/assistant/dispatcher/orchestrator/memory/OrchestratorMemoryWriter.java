package com.zhongbo.mindos.assistant.dispatcher.orchestrator.memory;

import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryCommandService;
import org.springframework.stereotype.Component;

@Component
public class OrchestratorMemoryWriter {

    private final DispatcherMemoryCommandService memoryCommandService;

    public OrchestratorMemoryWriter(DispatcherMemoryCommandService memoryCommandService) {
        this.memoryCommandService = memoryCommandService;
    }

    public void commit(String userId, MemoryWriteBatch batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        for (MemoryWriteOperation operation : batch.operations()) {
            if (operation instanceof MemoryWriteOperation.AppendUserConversation value) {
                memoryCommandService.appendUserConversation(userId, value.userInput());
            } else if (operation instanceof MemoryWriteOperation.AppendAssistantConversation value) {
                memoryCommandService.appendAssistantConversation(userId, value.reply());
            } else if (operation instanceof MemoryWriteOperation.WriteSemantic value) {
                memoryCommandService.writeSemantic(userId, value.text(), value.embedding(), value.bucket());
            } else if (operation instanceof MemoryWriteOperation.WriteProcedural value) {
                memoryCommandService.writeProcedural(userId, value.entry());
            } else if (operation instanceof MemoryWriteOperation.UpdatePreferenceProfile value) {
                memoryCommandService.updatePreferenceProfile(userId, value.profile());
            } else if (operation instanceof MemoryWriteOperation.RecordSkillUsage value) {
                memoryCommandService.recordSkillUsage(userId, value.skillName(), value.input(), value.success());
            } else if (operation instanceof MemoryWriteOperation.RecordProcedureSuccess value) {
                memoryCommandService.recordProcedureSuccess(
                        userId,
                        value.intent(),
                        value.trigger(),
                        value.graph(),
                        value.contextAttributes()
                );
            } else if (operation instanceof MemoryWriteOperation.UpdateLongTaskProgress value) {
                memoryCommandService.updateLongTaskProgress(
                        userId,
                        value.taskId(),
                        value.workerId(),
                        value.focusStep(),
                        value.note(),
                        value.blockedReason(),
                        value.nextCheckAt(),
                        value.markCompletable()
                );
            } else if (operation instanceof MemoryWriteOperation.UpsertGraphNode value) {
                memoryCommandService.upsertGraphNode(userId, value.node());
            } else if (operation instanceof MemoryWriteOperation.LinkGraph value) {
                memoryCommandService.linkGraph(
                        userId,
                        value.fromNodeId(),
                        value.relation(),
                        value.toNodeId(),
                        value.weight(),
                        value.metadata()
                );
            } else if (operation instanceof MemoryWriteOperation.DeleteProcedure value) {
                memoryCommandService.deleteProcedure(userId, value.procedureId());
            }
        }
    }
}
