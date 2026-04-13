package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel;

import java.util.LinkedHashMap;
import java.util.Map;

public record Node(String nodeId,
                   String label,
                   Map<String, Object> metadata) {

    public Node {
        nodeId = nodeId == null || nodeId.isBlank() ? "node:local" : nodeId.trim();
        label = label == null || label.isBlank() ? nodeId : label.trim();
        metadata = metadata == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(metadata));
    }

    public static Node local() {
        return new Node("node:local", "local-runtime", Map.of("kind", "local"));
    }
}
