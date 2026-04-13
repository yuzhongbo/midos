package com.zhongbo.mindos.assistant.skill;

public interface SkillEngineFacade {

    com.zhongbo.mindos.assistant.common.SkillResult execute(String target, java.util.Map<String, Object> params);
}
