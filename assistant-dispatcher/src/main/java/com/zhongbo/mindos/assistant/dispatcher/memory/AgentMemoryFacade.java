package com.zhongbo.mindos.assistant.dispatcher.memory;

import com.zhongbo.mindos.assistant.dispatcher.agent.procedure.Procedure;
import com.zhongbo.mindos.assistant.dispatcher.agent.procedure.ProceduralMemory;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import com.zhongbo.mindos.assistant.memory.MemoryGateway;
import com.zhongbo.mindos.assistant.memory.graph.GraphMemoryGateway;
import com.zhongbo.mindos.assistant.memory.graph.MemoryEdge;
import com.zhongbo.mindos.assistant.memory.graph.MemoryNode;
import com.zhongbo.mindos.assistant.memory.model.LongTask;
import com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public class AgentMemoryFacade {

    private final MemoryGateway memoryGateway;
    private final GraphMemoryGateway graphMemoryGateway;
    private final ProceduralMemory proceduralMemory;

    public AgentMemoryFacade(MemoryGateway memoryGateway,
                             GraphMemoryGateway graphMemoryGateway,
                             ProceduralMemory proceduralMemory) {
        this.memoryGateway = memoryGateway;
        this.graphMemoryGateway = graphMemoryGateway;
        this.proceduralMemory = proceduralMemory;
    }

    public boolean recordSkillUsage(String userId, String skillName, String trigger, boolean success) {
        if (memoryGateway == null) {
            return false;
        }
        memoryGateway.recordSkillUsage(userId, skillName, trigger, success);
        return true;
    }

    public boolean appendAssistantConversation(String userId, String message) {
        if (memoryGateway == null) {
            return false;
        }
        memoryGateway.appendAssistantConversation(userId, message == null ? "" : message);
        return true;
    }

    public boolean writeProcedural(String userId, ProceduralMemoryEntry entry) {
        if (memoryGateway == null || entry == null) {
            return false;
        }
        memoryGateway.writeProcedural(userId, entry);
        return true;
    }

    public boolean writeSemantic(String userId, String text, List<Double> embedding, String bucket) {
        if (memoryGateway == null) {
            return false;
        }
        memoryGateway.writeSemantic(userId, text, embedding == null ? List.of() : embedding, bucket);
        return true;
    }

    public Procedure recordProcedureSuccess(String userId,
                                            String intent,
                                            String trigger,
                                            TaskGraph graph,
                                            Map<String, Object> contextAttributes) {
        if (proceduralMemory == null) {
            return null;
        }
        return proceduralMemory.recordSuccess(
                userId,
                intent,
                trigger,
                graph,
                contextAttributes == null ? Map.of() : contextAttributes
        );
    }

    public LongTask updateLongTaskProgress(String userId,
                                           String taskId,
                                           String workerId,
                                           String completedStep,
                                           String note,
                                           String blockedReason,
                                           Instant nextCheckAt,
                                           boolean markCompleted) {
        if (memoryGateway == null) {
            return null;
        }
        return memoryGateway.updateLongTaskProgress(
                userId,
                taskId,
                workerId,
                completedStep,
                note,
                blockedReason,
                nextCheckAt,
                markCompleted
        );
    }

    public boolean hasGraphMemory() {
        return graphMemoryGateway != null;
    }

    public MemoryNode upsertGraphNode(String userId, MemoryNode node) {
        if (graphMemoryGateway == null || node == null) {
            return null;
        }
        return graphMemoryGateway.upsertNode(userId, node);
    }

    public MemoryEdge linkGraph(String userId,
                                String from,
                                String relation,
                                String to,
                                double weight,
                                Map<String, Object> data) {
        if (graphMemoryGateway == null) {
            return null;
        }
        return graphMemoryGateway.link(userId, from, relation, to, weight, data == null ? Map.of() : data);
    }

    public boolean deleteGraphNode(String userId, String nodeId) {
        return graphMemoryGateway != null && graphMemoryGateway.deleteNode(userId, nodeId);
    }
}
