package com.zhongbo.mindos.assistant.dispatcher.routing;

import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.skill.SkillDescriptor;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;

public record DispatchPlan(RoutingStage stage,
                           Decision decision,
                           boolean multiAgentRequested,
                           List<SkillDescriptor> skillDescriptors,
                           Map<String, Object> profileContext) {

    public DispatchPlan {
        stage = stage == null ? RoutingStage.PREPARING : stage;
        skillDescriptors = skillDescriptors == null ? List.of() : List.copyOf(skillDescriptors);
        profileContext = profileContext == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(profileContext));
    }

    public boolean usesMultiAgent() {
        return multiAgentRequested || stage == RoutingStage.MULTI_AGENT;
    }
}
