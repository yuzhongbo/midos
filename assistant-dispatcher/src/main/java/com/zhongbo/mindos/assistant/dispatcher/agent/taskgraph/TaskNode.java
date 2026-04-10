package com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph;

import java.util.List;
import java.util.Map;

public record TaskNode(String id,
                       String target,
                       Map<String, Object> params,
                       List<String> dependsOn,
                       String saveAs,
                       boolean optional) {

    public TaskNode {
        target = target == null ? "" : target.trim();
        id = id == null || id.isBlank() ? target : id.trim();
        params = params == null ? Map.of() : Map.copyOf(params);
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        saveAs = saveAs == null ? "" : saveAs.trim();
    }
}
