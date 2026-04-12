package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;

import java.util.Map;

public record TaskGraphPlan(Decision decision,
                            TaskGraph taskGraph,
                            Map<String, Object> effectiveParams,
                            String strategy,
                            String intent,
                            String trigger,
                            TaskGraph fallbackTaskGraph,
                            Map<String, Object> fallbackEffectiveParams,
                            String fallbackStrategy,
                            String clarificationTarget,
                            String clarificationMessage) {

    public TaskGraphPlan {
        taskGraph = taskGraph == null ? new TaskGraph(java.util.List.of(), java.util.List.of()) : taskGraph;
        effectiveParams = effectiveParams == null ? Map.of() : Map.copyOf(effectiveParams);
        strategy = strategy == null ? "" : strategy.trim();
        intent = intent == null ? "" : intent.trim();
        trigger = trigger == null ? "" : trigger.trim();
        fallbackTaskGraph = fallbackTaskGraph == null ? new TaskGraph(java.util.List.of(), java.util.List.of()) : fallbackTaskGraph;
        fallbackEffectiveParams = fallbackEffectiveParams == null ? Map.of() : Map.copyOf(fallbackEffectiveParams);
        fallbackStrategy = fallbackStrategy == null ? "" : fallbackStrategy.trim();
        clarificationTarget = clarificationTarget == null ? "" : clarificationTarget.trim();
        clarificationMessage = clarificationMessage == null ? "" : clarificationMessage.trim();
    }

    public static TaskGraphPlan clarification(Decision decision, String target, String message) {
        return new TaskGraphPlan(
                decision,
                new TaskGraph(java.util.List.of(), java.util.List.of()),
                Map.of(),
                "",
                decision == null ? "" : decision.intent(),
                "",
                new TaskGraph(java.util.List.of(), java.util.List.of()),
                Map.of(),
                "",
                target,
                message
        );
    }

    public boolean requiresClarification() {
        return !clarificationTarget.isBlank() || !clarificationMessage.isBlank();
    }

    public boolean hasTaskGraph() {
        return taskGraph != null && !taskGraph.isEmpty();
    }

    public boolean hasFallbackTaskGraph() {
        return fallbackTaskGraph != null && !fallbackTaskGraph.isEmpty();
    }
}
