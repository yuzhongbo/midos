package com.zhongbo.mindos.assistant.api.im;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.Skill;
import org.springframework.stereotype.Component;

@Component
class ImDingtalkTaskQuerySkill implements Skill {

    private final ImConversationCommandService commandService;

    ImDingtalkTaskQuerySkill(ImConversationCommandService commandService) {
        this.commandService = commandService;
    }

    @Override
    public String name() {
        return ImConversationCommandService.DINGTALK_TASK_QUERY_SKILL;
    }

    @Override
    public String description() {
        return "Internal DingTalk async task query executor.";
    }

    @Override
    public SkillResult run(SkillContext context) {
        return commandService.executeDingtalkTaskQuery(
                context == null ? "" : context.userId(),
                context == null ? null : context.attributes()
        );
    }
}
