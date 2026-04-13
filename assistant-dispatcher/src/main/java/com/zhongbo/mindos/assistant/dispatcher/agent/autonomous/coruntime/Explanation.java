package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.coruntime;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record Explanation(String summary,
                          List<String> reasons,
                          List<String> risks,
                          Map<String, Object> evidence,
                          Instant createdAt) {

    public Explanation {
        summary = summary == null ? "" : summary.trim();
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        risks = risks == null ? List.of() : List.copyOf(risks);
        evidence = evidence == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(evidence));
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    public static Explanation empty() {
        return new Explanation("", List.of(), List.of(), Map.of(), Instant.now());
    }
}
