package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.common.SkillResult;
import org.springframework.stereotype.Component;

@Component
public class SimpleConversationLoop implements ConversationLoop {

    @Override
    public SkillResult requestClarification(String target, String message) {
        String channel = target == null || target.isBlank() ? "semantic.clarify" : target;
        String reply = message == null || message.isBlank()
                ? "需要补充信息后才能继续执行。"
                : message;
        return SkillResult.success(channel, reply);
    }
}
