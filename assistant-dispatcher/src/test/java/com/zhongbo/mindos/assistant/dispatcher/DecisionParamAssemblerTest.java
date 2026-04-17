package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DecisionParamAssemblerTest {

    @Test
    void shouldNormalizeCanonicalSlotsWithoutDroppingSkillSpecificParams() {
        DecisionParamAssembler assembler = new DecisionParamAssembler();
        SkillContext context = new SkillContext(
                "u1",
                "请帮我整理周报",
                Map.of(
                        SemanticAnalysisResult.ATTR_PAYLOAD, Map.of(
                                "task", "提交周报",
                                "dueDate", "周五前",
                                "artifact", "风险说明版周报",
                                "audience", "管理层",
                                "requirements", "三页内",
                                "source", "引用本周指标",
                                "successCriteria", "负责人确认可发"
                        )
                )
        );

        Map<String, Object> params = assembler.assembleParams("todo.create", "semantic", "请帮我整理周报", context);

        assertEquals("提交周报", params.get("task"));
        assertEquals("周五前", params.get("dueDate"));
        assertEquals("提交周报", params.get("goal"));
        assertEquals("周五前", params.get("deadline"));
        assertEquals("风险说明版周报", params.get("deliverable"));
        assertEquals("管理层", params.get("audience"));
        assertEquals("三页内", params.get("constraints"));
        assertEquals("引用本周指标", params.get("sourceRequirement"));
        assertEquals("负责人确认可发", params.get("doneDefinition"));
    }
}
