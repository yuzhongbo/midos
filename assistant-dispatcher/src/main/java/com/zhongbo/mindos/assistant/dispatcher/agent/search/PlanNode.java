package com.zhongbo.mindos.assistant.dispatcher.agent.search;

import java.util.List;

public record PlanNode(String skillName,
                       double keywordScore,
                       double successRateScore,
                       double memoryScore,
                       double pathCost,
                       double totalScore,
                       List<String> reasons) {

    public PlanNode {
        skillName = skillName == null ? "" : skillName.trim();
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
}
