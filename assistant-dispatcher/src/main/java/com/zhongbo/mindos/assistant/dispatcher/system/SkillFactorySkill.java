package com.zhongbo.mindos.assistant.dispatcher.system;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.Skill;
import com.zhongbo.mindos.assistant.skill.SkillDescriptor;
import com.zhongbo.mindos.assistant.skill.SkillDescriptorProvider;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SkillFactorySkill implements Skill, SkillDescriptorProvider {

    private final SkillStudioWorkflowService workflowService;

    public SkillFactorySkill(SkillStudioWorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @Override
    public String name() {
        return "skill.factory";
    }

    @Override
    public String description() {
        return "Creates draft plans for new skills, imports, and external skill adaptations.";
    }

    @Override
    public SkillDescriptor skillDescriptor() {
        return new SkillDescriptor(
                name(),
                description(),
                List.of(
                        "skill factory",
                        "skill studio",
                        "开发技能",
                        "创建技能",
                        "导入技能",
                        "学习开源技能",
                        "扩展技能"
                )
        );
    }

    @Override
    public SkillResult run(SkillContext context) {
        return workflowService.execute(name(), context);
    }
}
