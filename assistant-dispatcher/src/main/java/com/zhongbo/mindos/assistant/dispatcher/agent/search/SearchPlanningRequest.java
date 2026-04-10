package com.zhongbo.mindos.assistant.dispatcher.agent.search;

import java.util.Map;

public record SearchPlanningRequest(String userId,
                                    String userInput,
                                    String suggestedTarget,
                                    Map<String, Object> params,
                                    int beamWidth,
                                    int maxDepth) {

    public SearchPlanningRequest {
        userId = userId == null ? "" : userId.trim();
        userInput = userInput == null ? "" : userInput.trim();
        suggestedTarget = suggestedTarget == null ? "" : suggestedTarget.trim();
        params = params == null ? Map.of() : Map.copyOf(params);
        beamWidth = Math.max(1, beamWidth);
        maxDepth = Math.max(1, maxDepth);
    }
}
