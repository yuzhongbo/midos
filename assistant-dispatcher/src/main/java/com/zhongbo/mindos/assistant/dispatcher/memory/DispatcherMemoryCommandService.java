package com.zhongbo.mindos.assistant.dispatcher.memory;

import com.zhongbo.mindos.assistant.dispatcher.agent.procedure.ProceduralMemory;
import com.zhongbo.mindos.assistant.dispatcher.agent.procedure.Procedure;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import com.zhongbo.mindos.assistant.memory.MemoryGateway;
import com.zhongbo.mindos.assistant.memory.graph.GraphMemoryGateway;
import com.zhongbo.mindos.assistant.memory.graph.MemoryEdge;
import com.zhongbo.mindos.assistant.memory.graph.MemoryNode;
import com.zhongbo.mindos.assistant.memory.model.LongTask;
import com.zhongbo.mindos.assistant.memory.model.PreferenceProfile;
import com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class DispatcherMemoryCommandService {

    private final DispatcherMemoryFacade dispatcherMemoryFacade;
    private final ProceduralMemory proceduralMemory;

    public DispatcherMemoryCommandService() {
        this(null, null);
    }

    public DispatcherMemoryCommandService(MemoryGateway memoryGateway,
                                          GraphMemoryGateway graphMemoryGateway,
                                          ProceduralMemory proceduralMemory) {
        this(new DispatcherMemoryFacade(memoryGateway, graphMemoryGateway, proceduralMemory), proceduralMemory);
    }

    @Autowired
    public DispatcherMemoryCommandService(DispatcherMemoryFacade dispatcherMemoryFacade,
                                          @Autowired(required = false) ProceduralMemory proceduralMemory) {
        this.dispatcherMemoryFacade = dispatcherMemoryFacade == null
                ? new DispatcherMemoryFacade((MemoryGateway) null, null, proceduralMemory)
                : dispatcherMemoryFacade;
        this.proceduralMemory = proceduralMemory;
    }

    public void appendUserConversation(String userId, String userInput) {
        dispatcherMemoryFacade.appendUserConversation(userId, userInput);
    }

    public void appendAssistantConversation(String userId, String reply) {
        dispatcherMemoryFacade.appendAssistantConversation(userId, reply);
    }

    public void writeSemantic(String userId, String text, List<Double> embedding, String bucket) {
        dispatcherMemoryFacade.writeSemantic(userId, text, embedding, bucket);
    }

    public void writeProcedural(String userId, ProceduralMemoryEntry entry) {
        dispatcherMemoryFacade.writeProcedural(userId, entry);
    }

    public PreferenceProfile updatePreferenceProfile(String userId, PreferenceProfile profile) {
        return dispatcherMemoryFacade.updatePreferenceProfile(userId, profile);
    }

    public void recordSkillUsage(String userId, String skillName, String input, boolean success) {
        dispatcherMemoryFacade.recordSkillUsage(userId, skillName, input, success);
    }

    public Procedure recordProcedureSuccess(String userId,
                                            String intent,
                                            String trigger,
                                            TaskGraph graph,
                                            Map<String, Object> contextAttributes) {
        return dispatcherMemoryFacade.recordProcedureSuccess(userId, intent, trigger, graph, contextAttributes);
    }

    public LongTask updateLongTaskProgress(String userId,
                                           String taskId,
                                           String workerId,
                                           String focusStep,
                                           String note,
                                           String blockedReason,
                                           Instant nextCheckAt,
                                           boolean markCompletable) {
        return dispatcherMemoryFacade.updateLongTaskProgress(
                userId,
                taskId,
                workerId,
                focusStep,
                note,
                blockedReason,
                nextCheckAt,
                markCompletable
        );
    }

    public MemoryNode upsertGraphNode(String userId, MemoryNode node) {
        return dispatcherMemoryFacade.upsertGraphNode(userId, node);
    }

    public MemoryEdge linkGraph(String userId,
                                String fromNodeId,
                                String relation,
                                String toNodeId,
                                double weight,
                                Map<String, Object> metadata) {
        return dispatcherMemoryFacade.linkGraph(userId, fromNodeId, relation, toNodeId, weight, metadata);
    }

    public boolean deleteGraphNode(String userId, String nodeId) {
        return dispatcherMemoryFacade.deleteGraphNode(userId, nodeId);
    }

    public boolean deleteProcedure(String userId, String procedureId) {
        return proceduralMemory != null && proceduralMemory.deleteProcedure(userId, procedureId);
    }
}
