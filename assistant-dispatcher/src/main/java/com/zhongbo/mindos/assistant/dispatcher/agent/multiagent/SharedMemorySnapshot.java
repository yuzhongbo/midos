package com.zhongbo.mindos.assistant.dispatcher.agent.multiagent;

import com.zhongbo.mindos.assistant.dispatcher.agent.procedure.ProceduralMemory;
import com.zhongbo.mindos.assistant.memory.graph.MemoryNode;
import com.zhongbo.mindos.assistant.memory.model.ConversationTurn;
import com.zhongbo.mindos.assistant.memory.model.SkillUsageStats;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record SharedMemorySnapshot(String userId,
                                  List<ConversationTurn> recentHistory,
                                  List<SkillUsageStats> skillUsageStats,
                                  List<MemoryNode> relatedNodes,
                                  List<ProceduralMemory.ReusableProcedure> reusableProcedures,
                                  Map<String, Object> inferredFacts,
                                  Map<String, Object> metadata) {

    public static final String CONTEXT_KEY = "multiAgent.memory.snapshot";

    public SharedMemorySnapshot {
        userId = userId == null ? "" : userId.trim();
        recentHistory = recentHistory == null ? List.of() : List.copyOf(recentHistory);
        skillUsageStats = skillUsageStats == null ? List.of() : List.copyOf(skillUsageStats);
        relatedNodes = relatedNodes == null ? List.of() : List.copyOf(relatedNodes);
        reusableProcedures = reusableProcedures == null ? List.of() : List.copyOf(reusableProcedures);
        inferredFacts = inferredFacts == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(inferredFacts));
        metadata = metadata == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(metadata));
    }

    public boolean isEmpty() {
        return recentHistory.isEmpty()
                && skillUsageStats.isEmpty()
                && relatedNodes.isEmpty()
                && reusableProcedures.isEmpty()
                && inferredFacts.isEmpty()
                && metadata.isEmpty();
    }
}
