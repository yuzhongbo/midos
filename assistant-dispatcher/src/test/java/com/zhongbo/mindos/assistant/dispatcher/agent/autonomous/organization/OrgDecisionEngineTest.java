package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.organization;

import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.Goal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OrgDecisionEngineTest {

    @Test
    void shouldExpandPlanningWhenCompletionRateIsLow() {
        OrgDecisionEngine engine = new OrgDecisionEngine();

        OrganizationDecision decision = engine.decide(
                Goal.of("完成复杂交付", 0.9),
                new KPI(0.35, 0.62, 0.28, 0.20)
        );

        assertEquals(OrganizationDecisionType.EXPAND_PLANNING, decision.type());
        assertEquals("planning", decision.targetDepartmentId());
        assertEquals("stabilize", decision.strategyMode());
    }
}
