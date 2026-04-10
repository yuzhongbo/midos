package com.zhongbo.mindos.assistant.dispatcher.agent.search;

import java.util.List;
import java.util.Map;

public record SearchCandidate(List<String> path,
                              double score,
                              List<String> reasons,
                              Map<String, Object> metadata) {

    public SearchCandidate {
        path = path == null ? List.of() : List.copyOf(path);
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
