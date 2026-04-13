package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.kernel;

import java.util.LinkedHashMap;
import java.util.Map;

public record RuntimeContext(String userId,
                             String input,
                             Map<String, Object> attributes,
                             Node assignedNode) {

    public RuntimeContext {
        userId = userId == null ? "" : userId.trim();
        input = input == null ? "" : input.trim();
        attributes = attributes == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(attributes));
        assignedNode = assignedNode == null ? Node.local() : assignedNode;
    }

    public static RuntimeContext empty() {
        return new RuntimeContext("", "", Map.of(), Node.local());
    }

    public RuntimeContext withAttributes(Map<String, Object> extraAttributes) {
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>(attributes);
        if (extraAttributes != null) {
            merged.putAll(extraAttributes);
        }
        return new RuntimeContext(userId, input, merged, assignedNode);
    }

    public RuntimeContext withAssignedNode(Node nextAssignedNode) {
        return new RuntimeContext(userId, input, attributes, nextAssignedNode);
    }
}
