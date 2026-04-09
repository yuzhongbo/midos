package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.common.SkillResult;
import org.springframework.stereotype.Component;

@Component
public class SimpleConversationLoop implements ConversationLoop {

    @Override
    public SkillResult requestClarification(String target, String message) {
        String channel = "semantic.clarify";
        StringBuilder reply = new StringBuilder();
        reply.append("我理解你想执行 `").append(target == null ? "" : target).append("`");
        String reason = message == null || message.isBlank()
                ? "需要补充信息后才能继续执行。"
                : message;
        reply.append("，但还缺少关键信息：").append(reason);
        return SkillResult.success(channel, reply.toString());
    }
}
