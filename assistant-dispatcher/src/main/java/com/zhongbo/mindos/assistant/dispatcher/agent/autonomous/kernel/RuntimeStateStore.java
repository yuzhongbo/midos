package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class RuntimeStateStore {

    private final Map<String, RuntimeState> states = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<RuntimeState>> history = new ConcurrentHashMap<>();

    public RuntimeState save(RuntimeState state) {
        if (state == null || state.handle() == null || state.handle().isEmpty()) {
            return state;
        }
        states.put(state.handle().taskId(), state);
        history.computeIfAbsent(state.handle().taskId(), ignored -> new CopyOnWriteArrayList<>()).add(state);
        return state;
    }

    public Optional<RuntimeState> state(TaskHandle handle) {
        if (handle == null || handle.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(states.get(handle.taskId()));
    }

    public ExecutionHistory history(TaskHandle handle) {
        if (handle == null || handle.isEmpty()) {
            return new ExecutionHistory(new TaskHandle(""), List.of());
        }
        return new ExecutionHistory(handle, List.copyOf(history.getOrDefault(handle.taskId(), new CopyOnWriteArrayList<>())));
    }
}
