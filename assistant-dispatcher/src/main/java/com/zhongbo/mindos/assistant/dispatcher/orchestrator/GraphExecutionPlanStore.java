package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class GraphExecutionPlanStore {

    private final ThreadLocal<StoredGraphPlan> currentPlan = new ThreadLocal<>();

    public void store(TaskGraphPlan plan, DecisionOrchestrator.OrchestrationRequest request) {
        currentPlan.set(new StoredGraphPlan(plan, request));
    }

    public StoredGraphPlan consume(TaskGraph graph) {
        StoredGraphPlan stored = currentPlan.get();
        currentPlan.remove();
        if (stored == null || stored.plan() == null) {
            return null;
        }
        return Objects.equals(stored.plan().taskGraph(), graph) ? stored : null;
    }

    public void clear() {
        currentPlan.remove();
    }

    public record StoredGraphPlan(TaskGraphPlan plan,
                                  DecisionOrchestrator.OrchestrationRequest request) {
    }
}
