package com.zhongbo.mindos.assistant.skill;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillDsl;
import com.zhongbo.mindos.assistant.common.SkillResult;

import java.util.concurrent.CompletableFuture;

public interface SkillExecutionGateway {

    CompletableFuture<SkillResult> executeDslAsync(SkillDsl dsl, SkillContext context);
}
