package com.zhongbo.mindos.assistant.dispatcher.agent.multiagent;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.DecisionOrchestrator;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public record AgentContext(String traceId,
                           Decision decision,
                           DecisionOrchestrator.OrchestrationRequest request,
                           Map<String, Object> sharedState,
                           AgentGateway gateway) {

    public AgentContext {
        traceId = traceId == null ? "" : traceId.trim();
        sharedState = sharedState == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(sharedState));
    }

    public String userId() {
        return request == null ? "" : request.userId();
    }

    public String userInput() {
        return request == null ? "" : request.userInput();
    }

    public SkillContext mergedSkillContext() {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (request != null && request.skillContext() != null && request.skillContext().attributes() != null) {
            merged.putAll(request.skillContext().attributes());
        }
        merged.putAll(sharedState);
        return new SkillContext(userId(), userInput(), merged);
    }

    public DecisionOrchestrator.OrchestrationRequest mergedRequest() {
        if (request == null) {
            return new DecisionOrchestrator.OrchestrationRequest(userId(), userInput(), mergedSkillContext(), Map.of());
        }
        return new DecisionOrchestrator.OrchestrationRequest(
                request.userId(),
                request.userInput(),
                mergedSkillContext(),
                request.safeProfileContext()
        );
    }

    public Optional<SharedMemorySnapshot> memorySnapshot() {
        Object snapshot = sharedState.get(SharedMemorySnapshot.CONTEXT_KEY);
        if (snapshot instanceof SharedMemorySnapshot memorySnapshot) {
            return Optional.of(memorySnapshot);
        }
        return Optional.empty();
    }

    public <T> Optional<T> sharedValue(String key, Class<T> type) {
        if (key == null || key.isBlank() || type == null) {
            return Optional.empty();
        }
        Object value = sharedState.get(key);
        if (type.isInstance(value)) {
            return Optional.of(type.cast(value));
        }
        return Optional.empty();
    }
}
