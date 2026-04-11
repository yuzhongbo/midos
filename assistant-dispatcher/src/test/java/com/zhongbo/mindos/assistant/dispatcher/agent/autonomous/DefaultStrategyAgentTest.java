package com.zhongbo.mindos.assistant.dispatcher.agent.autonomous;

import com.zhongbo.mindos.assistant.memory.model.ConversationTurn;
import com.zhongbo.mindos.assistant.memory.model.SkillUsageStats;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultStrategyAgentTest {

    @Test
    void shouldGenerateScheduleConflictStrategy() {
        AutonomousTestFixtures.MemoryGatewayStub memoryGateway = new AutonomousTestFixtures.MemoryGatewayStub(
                List.of(
                        ConversationTurn.user("排课冲突太多了，需要自动检测"),
                        ConversationTurn.user("每次都要手工重排，太慢"),
                        ConversationTurn.assistant("我来继续优化")
                ),
                List.of(
                        new SkillUsageStats("schedule.optimize", 24, 8, 16),
                        new SkillUsageStats("todo.create", 12, 11, 1)
                )
        );
        DefaultStrategyAgent agent = new DefaultStrategyAgent(memoryGateway);

        StrategicGoal goal = agent.strategize("u-1");

        assertEquals("减少排课冲突", goal.goal());
        assertTrue(goal.priority() > 0.85);
        assertTrue(goal.actions().contains("优化排课算法"));
        assertTrue(goal.actions().contains("增加自动冲突检测"));
        assertEquals(1, memoryGateway.semanticTexts.size());
        assertEquals(1, memoryGateway.proceduralEntries.size());
        assertEquals("strategy.longterm", memoryGateway.semanticBuckets.get(0));
    }

    @Test
    void shouldGenerateFailureReductionStrategy() {
        AutonomousTestFixtures.MemoryGatewayStub memoryGateway = new AutonomousTestFixtures.MemoryGatewayStub(
                List.of(
                        ConversationTurn.user("这个技能失败了，重试还是异常"),
                        ConversationTurn.user("错误一直出现，可能是输入校验不够")
                ),
                List.of(
                        new SkillUsageStats("code.generate", 10, 2, 8),
                        new SkillUsageStats("file.search", 4, 4, 0)
                )
        );
        DefaultStrategyAgent agent = new DefaultStrategyAgent(memoryGateway);

        StrategicGoal goal = agent.strategize("u-2");

        assertEquals("降低高频失败流程", goal.goal());
        assertTrue(goal.actions().contains("增加 fallback/retry"));
        assertTrue(goal.reasons().stream().anyMatch(reason -> reason.contains("failureRate")));
    }

    @Test
    void shouldGenerateHighFrequencyBehaviorStrategy() {
        AutonomousTestFixtures.MemoryGatewayStub memoryGateway = new AutonomousTestFixtures.MemoryGatewayStub(
                List.of(
                        ConversationTurn.user("继续优化待办流程"),
                        ConversationTurn.user("保持这个高频路径稳定")
                ),
                List.of(
                        new SkillUsageStats("todo.create", 32, 31, 1),
                        new SkillUsageStats("file.search", 5, 5, 0)
                )
        );
        DefaultStrategyAgent agent = new DefaultStrategyAgent(memoryGateway);

        StrategicGoal goal = agent.strategize("u-3");

        assertEquals("固化高频行为", goal.goal());
        assertTrue(goal.priority() > 0.75);
        assertTrue(goal.actions().contains("提炼为可复用流程"));
        assertTrue(goal.actions().contains("写入 procedural memory 供复用"));
    }
}
