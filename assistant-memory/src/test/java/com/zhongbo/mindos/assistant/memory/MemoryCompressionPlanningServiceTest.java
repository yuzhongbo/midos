package com.zhongbo.mindos.assistant.memory;

import com.zhongbo.mindos.assistant.memory.model.MemoryCompressionPlan;
import com.zhongbo.mindos.assistant.memory.model.MemoryStyleProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryCompressionPlanningServiceTest {

    @Test
    void shouldBuildGradualCompressionPlanUsingStoredStyle() {
        MemoryConsolidationService consolidationService = new MemoryConsolidationService();
        MemoryCompressionPlanningService service = new MemoryCompressionPlanningService(consolidationService);

        service.updateStyleProfile("u1", new MemoryStyleProfile("action", "calm", "bullet"));
        MemoryCompressionPlan plan = service.buildPlan(
                "u1",
                "第一步先整理学习目标。第二步拆出本周任务。第三步每天复盘并记录问题。",
                null
        );

        assertEquals("action", plan.styleProfile().styleName());
        assertEquals(4, plan.steps().size());
        assertEquals("RAW", plan.steps().get(0).stage());
        assertEquals("CONDENSED", plan.steps().get(1).stage());
        assertEquals("BRIEF", plan.steps().get(2).stage());
        assertEquals("STYLED", plan.steps().get(3).stage());
        assertTrue(plan.steps().get(3).content().contains("- "));
    }

    @Test
    void shouldAllowStyleOverrideForSinglePlan() {
        MemoryConsolidationService consolidationService = new MemoryConsolidationService();
        MemoryCompressionPlanningService service = new MemoryCompressionPlanningService(consolidationService);

        service.updateStyleProfile("u2", new MemoryStyleProfile("concise", "direct", "plain"));
        MemoryCompressionPlan plan = service.buildPlan(
                "u2",
                "先确定目标，再分解任务，最后复盘。",
                new MemoryStyleProfile("coach", null, null)
        );

        assertEquals("coach", plan.styleProfile().styleName());
        assertTrue(plan.steps().get(3).content().contains("建议按步骤执行"));
    }
}

