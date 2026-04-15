package com.zhongbo.mindos.assistant.dispatcher.orchestrator.memory;

import com.zhongbo.mindos.assistant.dispatcher.agent.procedure.ProceduralMemory;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryCommandService;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryFacade;
import com.zhongbo.mindos.assistant.memory.MemoryGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "mindos.autonomous.runtime.enabled", havingValue = "true")
public class OrchestratorMemoryWriter {

    private final DispatcherMemoryCommandService memoryCommandService;
    private DispatcherMemoryCommandService proceduralMemoryCommandService;

    public OrchestratorMemoryWriter(DispatcherMemoryCommandService memoryCommandService) {
        this.memoryCommandService = memoryCommandService;
    }

    public void setProceduralMemory(ProceduralMemory proceduralMemory) {
        this.proceduralMemoryCommandService = proceduralMemory == null
                ? null
                : new DispatcherMemoryCommandService(
                        new DispatcherMemoryFacade((MemoryGateway) null, null, proceduralMemory),
                        proceduralMemory
                );
    }

    public void commit(String userId, MemoryWriteBatch batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        for (MemoryWriteOperation operation : batch.operations()) {
            DispatcherMemoryCommandService commandService = commandService(operation);
            if (operation instanceof MemoryWriteOperation.AppendUserConversation value) {
                commandService.appendUserConversation(userId, value.userInput());
            } else if (operation instanceof MemoryWriteOperation.AppendAssistantConversation value) {
                commandService.appendAssistantConversation(userId, value.reply());
            } else if (operation instanceof MemoryWriteOperation.WriteSemantic value) {
                commandService.writeSemantic(userId, value.text(), value.embedding(), value.bucket());
            } else if (operation instanceof MemoryWriteOperation.WriteProcedural value) {
                commandService.writeProcedural(userId, value.entry());
            } else if (operation instanceof MemoryWriteOperation.UpdatePreferenceProfile value) {
                commandService.updatePreferenceProfile(userId, value.profile());
            } else if (operation instanceof MemoryWriteOperation.RecordSkillUsage value) {
                commandService.recordSkillUsage(userId, value.skillName(), value.input(), value.success());
            } else if (operation instanceof MemoryWriteOperation.RecordProcedureSuccess value) {
                commandService.recordProcedureSuccess(
                        userId,
                        value.intent(),
                        value.trigger(),
                        value.graph(),
                        value.contextAttributes()
                );
            } else if (operation instanceof MemoryWriteOperation.UpdateLongTaskProgress value) {
                commandService.updateLongTaskProgress(
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
                commandService.upsertGraphNode(userId, value.node());
            } else if (operation instanceof MemoryWriteOperation.LinkGraph value) {
                commandService.linkGraph(
                        userId,
                        value.fromNodeId(),
                        value.relation(),
                        value.toNodeId(),
                        value.weight(),
                        value.metadata()
                );
            } else if (operation instanceof MemoryWriteOperation.DeleteProcedure value) {
                commandService.deleteProcedure(userId, value.procedureId());
            }
        }
    }

    private DispatcherMemoryCommandService commandService(MemoryWriteOperation operation) {
        if (proceduralMemoryCommandService == null) {
            return memoryCommandService;
        }
        if (operation instanceof MemoryWriteOperation.RecordProcedureSuccess
                || operation instanceof MemoryWriteOperation.DeleteProcedure) {
            return proceduralMemoryCommandService;
        }
        return memoryCommandService;
    }
}
