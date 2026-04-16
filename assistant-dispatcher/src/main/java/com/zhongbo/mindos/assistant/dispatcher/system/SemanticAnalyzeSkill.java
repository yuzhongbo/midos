package com.zhongbo.mindos.assistant.dispatcher.system;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.Skill;
import com.zhongbo.mindos.assistant.skill.SkillDescriptor;
import com.zhongbo.mindos.assistant.skill.SkillDescriptorProvider;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SemanticAnalyzeSkill implements Skill, SkillDescriptorProvider {

    private final SemanticAnalyzeExecutor executor;

    @Autowired
    public SemanticAnalyzeSkill(SemanticAnalysisService semanticAnalysisService) {
        this(new SemanticAnalyzeExecutor(semanticAnalysisService));
    }

    SemanticAnalyzeSkill(SemanticAnalyzeExecutor executor) {
        this.executor = executor;
    }

    @Override
    public String name() {
        return "semantic.analyze";
    }

    @Override
    public String description() {
        return "Analyzes the user request and returns semantic hints without selecting execution targets.";
    }

    @Override
    public SkillDescriptor skillDescriptor() {
        return new SkillDescriptor(name(), description(), List.of("semantic", "semantic.analyze", "语义分析", "分析我的语义"));
    }

    @Override
    public SkillResult run(SkillContext context) {
        return executor.execute(name(), context);
    }
}
