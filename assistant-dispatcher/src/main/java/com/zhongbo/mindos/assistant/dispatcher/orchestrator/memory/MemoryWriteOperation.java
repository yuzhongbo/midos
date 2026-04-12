package com.zhongbo.mindos.assistant.dispatcher.orchestrator.memory;

import com.zhongbo.mindos.assistant.dispatcher.agent.procedure.Procedure;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import com.zhongbo.mindos.assistant.memory.graph.MemoryNode;
import com.zhongbo.mindos.assistant.memory.model.PreferenceProfile;
import com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public sealed interface MemoryWriteOperation permits
        MemoryWriteOperation.AppendUserConversation,
        MemoryWriteOperation.AppendAssistantConversation,
        MemoryWriteOperation.WriteSemantic,
        MemoryWriteOperation.WriteProcedural,
        MemoryWriteOperation.UpdatePreferenceProfile,
        MemoryWriteOperation.RecordSkillUsage,
        MemoryWriteOperation.RecordProcedureSuccess,
        MemoryWriteOperation.UpdateLongTaskProgress,
        MemoryWriteOperation.UpsertGraphNode,
        MemoryWriteOperation.LinkGraph,
        MemoryWriteOperation.DeleteProcedure {

    record AppendUserConversation(String userInput) implements MemoryWriteOperation {
    }

    record AppendAssistantConversation(String reply) implements MemoryWriteOperation {
    }

    record WriteSemantic(String text, List<Double> embedding, String bucket) implements MemoryWriteOperation {
    }

    record WriteProcedural(ProceduralMemoryEntry entry) implements MemoryWriteOperation {
    }

    record UpdatePreferenceProfile(PreferenceProfile profile) implements MemoryWriteOperation {
    }

    record RecordSkillUsage(String skillName, String input, boolean success) implements MemoryWriteOperation {
    }

    record RecordProcedureSuccess(String intent,
                                  String trigger,
                                  TaskGraph graph,
                                  Map<String, Object> contextAttributes) implements MemoryWriteOperation {
    }

    record UpdateLongTaskProgress(String taskId,
                                  String workerId,
                                  String focusStep,
                                  String note,
                                  String blockedReason,
                                  Instant nextCheckAt,
                                  boolean markCompletable) implements MemoryWriteOperation {
    }

    record UpsertGraphNode(MemoryNode node) implements MemoryWriteOperation {
    }

    record LinkGraph(String fromNodeId,
                     String relation,
                     String toNodeId,
                     double weight,
                     Map<String, Object> metadata) implements MemoryWriteOperation {
    }

    record DeleteProcedure(String procedureId) implements MemoryWriteOperation {
    }
}
