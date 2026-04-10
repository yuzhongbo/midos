package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import java.util.List;

public record ScoredCandidate(String skillName,
                              double finalScore,
                              double keywordScore,
                              double memoryScore,
                              double successRate,
                              List<String> reasons) {

    public ScoredCandidate {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
}
