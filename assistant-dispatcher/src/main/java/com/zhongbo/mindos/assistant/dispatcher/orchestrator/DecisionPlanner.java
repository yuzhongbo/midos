package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;

import java.util.Map;

public interface DecisionPlanner {

    Decision plan(String userInput, String intent, Map<String, Object> params, SkillContext context);
}
