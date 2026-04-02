package com.zhongbo.mindos.assistant.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhongbo.mindos.assistant.memory.model.MemoryCompressionPlan;
import com.zhongbo.mindos.assistant.memory.model.MemoryCompressionStep;
import com.zhongbo.mindos.assistant.memory.model.MemoryStyleProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryCompressionPlanningServiceTest {

    private static String contentByStage(MemoryCompressionPlan plan, String stage) {
        return plan.steps().stream()
                .filter(step -> stage.equals(step.stage()))
                .map(MemoryCompressionStep::content)
                .findFirst()
                .orElse("");
    }

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

    @Test
    void shouldKeepCurrentStyleWhenOnlyPartialFieldsAreProvided() {
        MemoryConsolidationService consolidationService = new MemoryConsolidationService();
        MemoryCompressionPlanningService service = new MemoryCompressionPlanningService(consolidationService);

        service.updateStyleProfile("u3", new MemoryStyleProfile("story", "warm", "bullet"));
        MemoryStyleProfile updated = service.updateStyleProfile("u3", new MemoryStyleProfile(null, "calm", null));

        assertEquals("story", updated.styleName());
        assertEquals("calm", updated.tone());
        assertEquals("bullet", updated.outputFormat());
    }

    @Test
    void shouldNormalizeStyleAliasesToStableValues() {
        MemoryConsolidationService consolidationService = new MemoryConsolidationService();
        MemoryCompressionPlanningService service = new MemoryCompressionPlanningService(consolidationService);

        MemoryStyleProfile updated = service.updateStyleProfile("u4", new MemoryStyleProfile("教学", "warm", "列表"));
        assertEquals("coach", updated.styleName());
        assertEquals("bullet", updated.outputFormat());
    }

    @Test
    void shouldApplyFocusLabelToStyledStep() {
        MemoryConsolidationService consolidationService = new MemoryConsolidationService();
        MemoryCompressionPlanningService service = new MemoryCompressionPlanningService(consolidationService);

        MemoryCompressionPlan plan = service.buildPlan(
                "u5",
                "先完成需求梳理，再拆分任务，最后确认验收。",
                new MemoryStyleProfile("action", "direct", "plain"),
                "task"
        );

        assertTrue(plan.steps().get(3).content().contains("[任务聚焦]"));
    }

    @Test
    void shouldAutoTuneStyleWhenRequested() {
        MemoryConsolidationService consolidationService = new MemoryConsolidationService();
        MemoryCompressionPlanningService service = new MemoryCompressionPlanningService(consolidationService);

        MemoryStyleProfile tuned = service.updateStyleProfile(
                "u6",
                new MemoryStyleProfile(null, null, null),
                true,
                "请帮我按步骤拆分任务清单"
        );

        assertEquals("action", tuned.styleName());
        assertEquals("warm", tuned.tone());
    }

    @Test
    void shouldPreserveCriticalConstraintLinesWhenCompressing() {
        MemoryConsolidationService consolidationService = new MemoryConsolidationService();
        MemoryCompressionPlanningService service = new MemoryCompressionPlanningService(consolidationService);

        String source = "先梳理背景。\n整理学习材料。\n输出初稿。\n和同学讨论。\n"
                + "截止时间是2026-04-01 20:00。\n"
                + "不能遗漏高风险模块。\n"
                + "完成后复盘。";
        MemoryCompressionPlan plan = service.buildPlan("u7", source, new MemoryStyleProfile("concise", "direct", "plain"));

        String condensed = contentByStage(plan, "CONDENSED");
        String brief = contentByStage(plan, "BRIEF");
        assertTrue(condensed.contains("截止时间是2026-04-01 20:00"));
        assertTrue(condensed.contains("不能遗漏高风险模块"));
        assertTrue(brief.contains("截止时间是2026-04-01 20:00") || brief.contains("不能遗漏高风险模块"));
    }

    @Test
    void shouldPreferRecentActionLinesAfterKeepingKeySignals() {
        MemoryConsolidationService consolidationService = new MemoryConsolidationService();
        MemoryCompressionPlanningService service = new MemoryCompressionPlanningService(consolidationService);

        String source = "背景说明。\n"
                + "列出可选方案。\n"
                + "拆解学习任务。\n"
                + "同步依赖人。\n"
                + "截止时间是2026-04-01 20:00。\n"
                + "复核上线清单。\n"
                + "确认最终负责人。\n"
                + "发送最新周报。";
        MemoryCompressionPlan plan = service.buildPlan("u8", source, new MemoryStyleProfile("concise", "direct", "plain"));

        String brief = contentByStage(plan, "BRIEF");
        assertTrue(brief.contains("截止时间是2026-04-01 20:00"));
        assertTrue(brief.contains("确认最终负责人") || brief.contains("发送最新周报"));
    }

    @Test
    void shouldPersistStyleProfilesAcrossRestarts(@TempDir Path tempDir) {
        MemoryConsolidationService consolidationService = new MemoryConsolidationService();
        MemoryStateStore stateStore = new FileMemoryStateStore(true, tempDir, new ObjectMapper());
        MemoryCompressionPlanningService first = new MemoryCompressionPlanningService(consolidationService, stateStore);
        first.updateStyleProfile("u9", new MemoryStyleProfile("教学", "warm", "列表"));

        MemoryCompressionPlanningService second = new MemoryCompressionPlanningService(
                consolidationService,
                new FileMemoryStateStore(true, tempDir, new ObjectMapper())
        );

        MemoryStyleProfile restored = second.getStyleProfile("u9");
        assertEquals("coach", restored.styleName());
        assertEquals("warm", restored.tone());
        assertEquals("bullet", restored.outputFormat());
    }
}
