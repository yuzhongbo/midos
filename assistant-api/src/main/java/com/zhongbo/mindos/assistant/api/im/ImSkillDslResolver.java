package com.zhongbo.mindos.assistant.api.im;

import com.zhongbo.mindos.assistant.common.SkillDsl;
import com.zhongbo.mindos.assistant.dispatcher.DispatchSkillDslResolver;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

@Component
class ImSkillDslResolver implements DispatchSkillDslResolver {

    private final ImConversationCommandService commandService;

    ImSkillDslResolver(ImConversationCommandService commandService) {
        this.commandService = commandService;
    }

    @Override
    public Optional<SkillDsl> resolve(String userId, String userInput, Map<String, Object> profileContext) {
        return commandService.resolveSkillDsl(userId, userInput, profileContext);
    }
}
