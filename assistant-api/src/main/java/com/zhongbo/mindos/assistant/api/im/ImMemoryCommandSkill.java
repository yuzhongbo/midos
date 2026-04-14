package com.zhongbo.mindos.assistant.api.im;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.Skill;
import org.springframework.stereotype.Component;

@Component
class ImMemoryCommandSkill implements Skill {

    private final ImConversationCommandService commandService;

    ImMemoryCommandSkill(ImConversationCommandService commandService) {
        this.commandService = commandService;
    }

    @Override
    public String name() {
        return ImConversationCommandService.MEMORY_COMMAND_SKILL;
    }

    @Override
    public String description() {
        return "Internal IM memory command executor.";
    }

    @Override
    public SkillResult run(SkillContext context) {
        return commandService.executeMemoryCommand(
                context == null ? "" : context.userId(),
                context == null ? null : context.attributes()
        );
    }
}
