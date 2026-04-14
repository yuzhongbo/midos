package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillDsl;

import java.util.Map;
import java.util.Optional;

public interface DispatchSkillDslResolver {

    Optional<SkillDsl> resolve(String userId, String userInput, Map<String, Object> profileContext);
}
