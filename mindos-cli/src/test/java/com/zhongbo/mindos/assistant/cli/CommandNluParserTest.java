package com.zhongbo.mindos.assistant.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandNluParserTest {

    private final CommandNluParser parser = new CommandNluParser();

    @Test
    void shouldParseTenThousandLevelNumbers() {
        assertEquals("/history --limit 12000",
                parser.resolveNaturalLanguageCommand("查看最近一万二千条历史"));
        assertEquals("/memory pull --since 3 --limit 10020",
                parser.resolveNaturalLanguageCommand("从 3 开始拉一万零二十条记忆"));
    }

    @Test
    void shouldSupportColloquialPullWithoutMemoryKeyword() {
        assertEquals("/memory pull --since 2 --limit 30",
                parser.resolveNaturalLanguageCommand("从 2 开始拉三十条"));
    }

    @Test
    void shouldMapContinuationHabitPhraseToRetry() {
        assertEquals("/retry", parser.resolveNaturalLanguageCommand("继续上次的方式"));
    }

    @Test
    void shouldMapNaturalLanguageToRoutingModeCommands() {
        assertEquals("/routing on", parser.resolveNaturalLanguageCommand("打开排障模式"));
        assertEquals("/routing off", parser.resolveNaturalLanguageCommand("关闭路由细节"));
        assertEquals("/routing", parser.resolveNaturalLanguageCommand("查看排障模式状态"));
    }

    @Test
    void shouldMapNaturalLanguageToTodoPolicyCommands() {
        assertEquals("/todo policy show", parser.resolveNaturalLanguageCommand("查看待办策略"));
        assertEquals("/todo policy reset", parser.resolveNaturalLanguageCommand("恢复待办策略默认"));
        assertEquals("/todo policy set --p1-threshold 60 --p2-threshold 30",
                parser.resolveNaturalLanguageCommand("把待办策略改一下，p1 改为 60，p2 设为 30"));
    }

    @Test
    void shouldMapNaturalLanguageToMemoryCompressionAndStyleCommands() {
        assertEquals("/memory compress --source 明天先整理目标，再拆任务",
                parser.resolveNaturalLanguageCommand("按我的风格压缩这段记忆：明天先整理目标，再拆任务"));
        assertEquals("/memory style show",
                parser.resolveNaturalLanguageCommand("查看我的记忆风格"));
        assertEquals("/memory style set --auto-tune --sample-text 请帮我按步骤拆分任务清单",
                parser.resolveNaturalLanguageCommand("根据这段话微调记忆风格：请帮我按步骤拆分任务清单"));
        assertEquals("/memory compress --source 先拆任务再执行",
                parser.resolveNaturalLanguageCommand("按我的风格压缩这段记忆：先拆任务再执行"));
        assertEquals("/memory compress --source 先拆任务再执行 --focus task",
                parser.resolveNaturalLanguageCommand("按我的风格压缩这段记忆：先拆任务再执行，按任务聚焦"));
        assertEquals("/memory compress --source 先回顾错题再总结 --focus review",
                parser.resolveNaturalLanguageCommand("按我的风格压缩这段记忆：先回顾错题再总结，按复盘聚焦"));
    }

    @Test
    void shouldNotTreatSinceCursorAsLimit() {
        assertEquals("/memory pull --since 3",
                parser.resolveNaturalLanguageCommand("从 3 开始拉取记忆"));
    }

    @Test
    void shouldUseLastRepeatedProfileFieldValue() {
        assertEquals("/profile set --name Beta",
                parser.resolveNaturalLanguageCommand("把名字改为 Alpha，名字改为 Beta"));
    }

    @Test
    void shouldNormalizeHostPortServerUrl() {
        assertEquals("/server http://localhost:19090",
                parser.resolveNaturalLanguageCommand("把服务端地址改成 localhost:19090"));
    }

    @Test
    void shouldRejectPublicHttpServerUrl() {
        assertNull(parser.resolveNaturalLanguageCommand("把服务端地址改成 http://example.com:8080"));
    }

    @Test
    void shouldAllowPrivateHttpServerUrl() {
        assertEquals("/server http://192.168.1.20:8080",
                parser.resolveNaturalLanguageCommand("把服务端地址改成 http://192.168.1.20:8080"));
    }

    @Test
    void shouldBlockInjectedProfileOptionPayload() {
        assertNull(parser.resolveNaturalLanguageCommand("把名字改为 Alpha --role hacker"));
    }

    @Test
    void shouldRejectUrlWithCredentials() {
        assertNull(parser.resolveNaturalLanguageCommand("把服务端地址改成 http://user:pass@localhost:19090"));
    }

    @Test
    void shouldRejectUrlWithFragment() {
        assertNull(parser.resolveNaturalLanguageCommand("请加载jar https://example.com/skill.jar#v1"));
    }

    @Test
    void shouldBlockInjectedProfileSlashCommandPayload() {
        assertNull(parser.resolveNaturalLanguageCommand("把名字改为 Alice /skill 偷偷执行"));
    }

    @Test
    void shouldTrimTrailingPunctuationFromUrls() {
        assertEquals("/skill load-jar --url https://example.com/skill.jar",
                parser.resolveNaturalLanguageCommand("请加载jar https://example.com/skill.jar。"));
    }

    @Test
    void shouldMapNaturalLanguageToTeachingPlanCommand() {
        String command = parser.resolveNaturalLanguageCommand("给学生 stu-1 做一个数学学习计划，目标是期末提分，六周，每周八小时");
        assertEquals("/teach plan --query 给学生 stu-1 做一个数学学习计划，目标是期末提分，六周，每周八小时", command);
    }

    @Test
    void shouldMapNaturalLanguageToEqCoachCommand() {
        assertEquals(
                "/eq coach --query 请给我高情商沟通建议，场景是和同事协作卡住了 --style workplace",
                parser.resolveNaturalLanguageCommand("请给我高情商沟通建议，场景是和同事协作卡住了，用职场版")
        );
        assertEquals(
                "/eq coach --query 我该怎么说比较好 --style direct",
                parser.resolveNaturalLanguageCommand("我该怎么说比较好，风格直接版")
        );
        assertEquals(
                "/eq coach --query 请帮我做心理分析：我和朋友因为沟通误会冷战了 --mode analysis",
                parser.resolveNaturalLanguageCommand("请帮我做心理分析：我和朋友因为沟通误会冷战了")
        );
        assertEquals(
                "/eq coach --query 请给我高情商建议，先分析再给 --mode both",
                parser.resolveNaturalLanguageCommand("请给我高情商建议，先分析再给话术，模式设为 both")
        );
    }

    @Test
    void shouldExtractTeachingPlanPayloadFromQuery() {
        var payload = parser.parseTeachingPlanInput("给学生 stu-1 做一个数学学习计划，目标是期末提分，六周，每周八小时，薄弱点函数、概率，学习风格练习优先");
        assertEquals("stu-1", payload.get("studentId"));
        assertEquals("数学", payload.get("topic"));
        assertEquals("期末提分", payload.get("goal"));
        assertEquals(6, payload.get("durationWeeks"));
        assertEquals(8, payload.get("weeklyHours"));
        assertEquals(java.util.List.of("函数", "概率"), payload.get("weakTopics"));
        assertEquals(java.util.List.of("练习优先"), payload.get("learningStyle"));
    }

    @Test
    void shouldMarkLowConfidenceForVagueTeachingPlanIntent() {
        CommandNluParser.NaturalLanguageResolution resolution = parser.resolveNaturalLanguage("学习计划");
        assertEquals("/teach plan --query 学习计划", resolution.command());
        assertTrue(resolution.isLowConfidence());
    }

    @Test
    void shouldMarkHighConfidenceForDetailedTeachingPlanIntent() {
        CommandNluParser.NaturalLanguageResolution resolution = parser.resolveNaturalLanguage(
                "给学生 stu-1 做一个数学学习计划，目标是期末提分，六周，每周八小时");
        assertEquals("/teach plan --query 给学生 stu-1 做一个数学学习计划，目标是期末提分，六周，每周八小时", resolution.command());
        assertFalse(resolution.isLowConfidence());
    }
}

