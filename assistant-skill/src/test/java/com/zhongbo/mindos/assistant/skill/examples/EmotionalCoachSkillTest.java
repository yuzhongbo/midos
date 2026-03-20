package com.zhongbo.mindos.assistant.skill.examples;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmotionalCoachSkillTest {

    private static final String RISK_HIGH_TERMS_PROP = "mindos.eq.coach.risk.high-terms";

    private final EmotionalCoachSkill skill = new EmotionalCoachSkill();

    @Test
    void shouldDetectEmotionalCoachingIntent() {
        assertTrue(skill.supports("我想要一些高情商沟通建议"));
        assertTrue(skill.supports("我该怎么说比较好"));
        assertTrue(skill.supports("和同事有冲突，帮我理一下"));
        assertTrue(skill.supports("请帮我做心理分析"));
        assertFalse(skill.supports("echo hello"));
    }

    @Test
    void shouldReturnCoachingPlanForScenario() {
        SkillResult result = skill.run(new SkillContext("u1", "我和同事沟通有点僵，怎么说更好", java.util.Map.of()));

        assertTrue(result.success());
        assertEquals("eq.coach", result.skillName());
        assertTrue(result.output().contains("情商沟通指导"));
        assertTrue(result.output().contains("场景:"));
        assertTrue(result.output().contains("建议话术"));
        assertTrue(result.output().contains("心理分析"));
        assertTrue(result.output().contains("事情分析"));
        assertTrue(result.output().contains("风险等级:"));
        assertTrue(result.output().contains("置信度:"));
        assertTrue(result.output().contains("建议优先级"));
        assertTrue(result.output().contains("24小时行动清单"));
    }

    @Test
    void shouldSupportStyleVariantsFromAttributes() {
        SkillResult workplace = skill.run(new SkillContext(
                "u1",
                "",
                java.util.Map.of("query", "我和同事协作卡住了", "style", "workplace")
        ));
        SkillResult direct = skill.run(new SkillContext(
                "u1",
                "",
                java.util.Map.of("query", "对方一直拖延反馈", "style", "直接版")
        ));
        SkillResult intimate = skill.run(new SkillContext(
                "u1",
                "",
                java.util.Map.of("query", "我和伴侣沟通容易争执", "style", "亲密关系版")
        ));

        assertTrue(workplace.success());
        assertTrue(workplace.output().contains("职场版"));
        assertTrue(workplace.output().contains("心理分析"));
        assertTrue(direct.success());
        assertTrue(direct.output().contains("直接版"));
        assertTrue(direct.output().contains("事情分析"));
        assertTrue(intimate.success());
        assertTrue(intimate.output().contains("亲密关系版"));
    }

    @Test
    void shouldFailWhenScenarioIsBlank() {
        SkillResult result = skill.run(new SkillContext("u1", "   ", java.util.Map.of()));

        assertFalse(result.success());
        assertTrue(result.output().contains("请告诉我一个具体场景"));
    }

    @Test
    void shouldSupportAnalysisAndReplyModeSwitching() {
        SkillResult analysisOnly = skill.run(new SkillContext(
                "u1",
                "",
                java.util.Map.of("query", "我们因为误会冷战了", "mode", "analysis")
        ));
        SkillResult replyOnly = skill.run(new SkillContext(
                "u1",
                "",
                java.util.Map.of("query", "同事反馈总是很慢", "mode", "reply")
        ));

        assertTrue(analysisOnly.success());
        assertTrue(analysisOnly.output().contains("模式: analysis"));
        assertTrue(analysisOnly.output().contains("心理分析"));
        assertFalse(analysisOnly.output().contains("建议话术"));

        assertTrue(replyOnly.success());
        assertTrue(replyOnly.output().contains("模式: reply"));
        assertTrue(replyOnly.output().contains("建议话术"));
        assertFalse(replyOnly.output().contains("心理分析"));
    }

    @Test
    void shouldApplyConfiguredRiskTermsFromSystemProperty() {
        String previous = System.getProperty(RISK_HIGH_TERMS_PROP);
        try {
            System.setProperty(RISK_HIGH_TERMS_PROP, "失眠,panic");
            SkillResult result = skill.run(new SkillContext(
                    "u1",
                    "",
                    java.util.Map.of("query", "最近我因为沟通问题持续失眠", "mode", "analysis")
            ));

            assertTrue(result.success());
            assertTrue(result.output().contains("风险等级: 高"));
        } finally {
            if (previous == null) {
                System.clearProperty(RISK_HIGH_TERMS_PROP);
            } else {
                System.setProperty(RISK_HIGH_TERMS_PROP, previous);
            }
        }
    }

    @Test
    void shouldSupportPriorityFocusOption() {
        SkillResult focused = skill.run(new SkillContext(
                "u1",
                "",
                java.util.Map.of("query", "我和同事沟通冲突升级", "priorityFocus", "p1")
        ));

        assertTrue(focused.success());
        assertTrue(focused.output().contains("建议优先级（已聚焦 P1）"));
        assertTrue(focused.output().contains("- P1:"));
        assertFalse(focused.output().contains("- P2:"));
        assertFalse(focused.output().contains("- P3:"));
    }
}

