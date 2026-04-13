package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel;

import java.util.List;

public record ExecutionHistory(TaskHandle handle,
                               List<RuntimeState> snapshots) {

    public ExecutionHistory {
        handle = handle == null ? new TaskHandle("") : handle;
        snapshots = snapshots == null ? List.of() : List.copyOf(snapshots);
    }

    public RuntimeState latest() {
        return snapshots.isEmpty() ? null : snapshots.get(snapshots.size() - 1);
    }

    public int cycleCount() {
        return snapshots.size();
    }
}
