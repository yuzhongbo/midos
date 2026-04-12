package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous;

import com.zhongbo.mindos.assistant.dispatcher.orchestrator.memory.MemoryWriteBatch;

import java.util.List;

public record MemoryEvolutionResult(String goalId,
                                    double reward,
                                    double normalizedReward,
                                    boolean semanticWritten,
                                    boolean procedureRecorded,
                                    boolean taskUpdated,
                                    boolean graphUpdated,
                                    String summary,
                                    List<String> reasons,
                                    MemoryWriteBatch memoryWrites) {

    public MemoryEvolutionResult {
        goalId = goalId == null ? "" : goalId.trim();
        summary = summary == null ? "" : summary.trim();
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        memoryWrites = memoryWrites == null ? MemoryWriteBatch.empty() : memoryWrites;
        if (Double.isNaN(reward) || Double.isInfinite(reward)) {
            reward = 0.0;
        }
        if (Double.isNaN(normalizedReward) || Double.isInfinite(normalizedReward)) {
            normalizedReward = 0.5;
        }
    }
}
