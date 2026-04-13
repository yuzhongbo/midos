package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel;

import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;

import java.util.LinkedHashMap;
import java.util.Map;

public record CognitivePluginOutput(TaskGraph proposedGraph,
                                    Map<String, Object> attributes,
                                    double confidence,
                                    String summary) {

    public CognitivePluginOutput {
        proposedGraph = proposedGraph == null ? new TaskGraph(java.util.List.of(), java.util.List.of()) : proposedGraph;
        attributes = attributes == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(attributes));
        confidence = clamp(confidence);
        summary = summary == null ? "" : summary.trim();
    }

    public static CognitivePluginOutput empty() {
        return new CognitivePluginOutput(new TaskGraph(java.util.List.of(), java.util.List.of()), Map.of(), 0.0, "");
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}
