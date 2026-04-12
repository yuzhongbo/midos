package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous;

import com.zhongbo.mindos.assistant.memory.LongTaskService;
import com.zhongbo.mindos.assistant.memory.model.ConversationTurn;
import com.zhongbo.mindos.assistant.memory.model.LongTask;
import com.zhongbo.mindos.assistant.memory.model.LongTaskStatus;
import com.zhongbo.mindos.assistant.memory.model.SkillUsageStats;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultGoalGeneratorTest {

    @Test
    void shouldPrioritizeLongTaskSkillRecoveryAndMemoryReview() {
        LongTaskService longTaskService = new LongTaskService();
        LongTask task = longTaskService.createTask(
                "u1",
                "推进自治系统",
                "实现自治循环与流程记忆",
                List.of("llm.orchestrate", "semantic.analyze"),
                Instant.now().plusSeconds(7200),
                Instant.now().plusSeconds(600)
        );
        longTaskService.updateStatus("u1", task.taskId(), LongTaskStatus.BLOCKED, "missing memory evolution hook", Instant.now().plusSeconds(900));

        AutonomousTestFixtures.MemoryGatewayStub memoryGateway = new AutonomousTestFixtures.MemoryGatewayStub(
                List.of(
                        ConversationTurn.user("查看最近任务状态"),
                        ConversationTurn.assistant("当前有一个长期任务被阻塞"),
                        ConversationTurn.user("请优先处理记忆进化")
                ),
                List.of(new SkillUsageStats("code.generate", 6, 1, 5))
        );

        DefaultGoalGenerator generator = new DefaultGoalGenerator(memoryGateway, longTaskService, 5, 3);
        List<AutonomousGoal> goals = generator.generate("u1", 3);

        assertEquals(3, goals.size());
        assertEquals(AutonomousGoalType.LONG_TASK, goals.get(0).type());
        assertEquals(AutonomousGoalType.SKILL_RECOVERY, goals.get(1).type());
        assertEquals(AutonomousGoalType.MEMORY_REVIEW, goals.get(2).type());
        assertTrue(goals.get(0).priority() >= goals.get(1).priority());
        assertTrue(goals.get(1).priority() >= goals.get(2).priority());
        assertEquals(task.taskId(), goals.get(0).sourceId());
        assertEquals("code.generate", goals.get(1).sourceId());
        assertTrue(goals.get(2).objective().contains("对话"));
    }

    @Test
    void shouldGenerateOptimizationGoalFromFrequentSuccessfulBehavior() {
        AutonomousTestFixtures.MemoryGatewayStub memoryGateway = new AutonomousTestFixtures.MemoryGatewayStub(
                List.of(),
                List.of(new SkillUsageStats("排课", 12, 11, 1))
        );

        DefaultGoalGenerator generator = new DefaultGoalGenerator(memoryGateway, null);
        List<Goal> goals = generator.generateGoals("u1", 1);

        assertEquals(1, goals.size());
        assertTrue(goals.get(0).goal().contains("优化高频行为"));
        assertTrue(goals.get(0).priority() > 0.7);
        assertEquals(AutonomousGoalType.BEHAVIOR_OPTIMIZATION, generator.generate("u1", 1).get(0).type());
    }

    @Test
    void shouldIncludeStrategicGoalFromStrategyAgent() {
        StrategyAgent strategyAgent = userId -> new StrategicGoal(
                "减少排课冲突",
                0.92,
                List.of("优化排课算法", "增加自动冲突检测"),
                List.of("history-review", "schedule-signal"),
                Instant.now()
        );

        DefaultGoalGenerator generator = new DefaultGoalGenerator(
                (com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryFacade) null,
                null,
                strategyAgent,
                5,
                6
        );
        List<AutonomousGoal> goals = generator.generate("u-strategy", 3);

        assertTrue(goals.stream().anyMatch(goal -> goal.type() == AutonomousGoalType.STRATEGIC));
        AutonomousGoal strategicGoal = goals.stream()
                .filter(goal -> goal.type() == AutonomousGoalType.STRATEGIC)
                .findFirst()
                .orElseThrow();
        assertTrue(strategicGoal.goalId().startsWith("strategy:"));
        assertEquals("llm.orchestrate", strategicGoal.target());
        assertTrue(strategicGoal.priority() >= 70);
    }
}
