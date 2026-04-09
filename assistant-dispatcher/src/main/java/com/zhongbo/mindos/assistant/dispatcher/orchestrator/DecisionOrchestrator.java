package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.common.SkillDsl;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;

import java.util.Map;
import java.util.Optional;

public interface DecisionOrchestrator {

    Optional<SkillDsl> toSkillDsl(Decision decision, Map<String, Object> profileContext);
}
