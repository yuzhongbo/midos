package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.common.SkillResult;

public interface ConversationLoop {

    SkillResult requestClarification(String target, String message);
}
