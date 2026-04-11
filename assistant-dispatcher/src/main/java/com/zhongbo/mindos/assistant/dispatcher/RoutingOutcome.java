package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.common.dto.RoutingDecisionDto;

import java.util.Optional;

record RoutingOutcome(Optional<SkillResult> result, RoutingDecisionDto routingDecision) {
}
