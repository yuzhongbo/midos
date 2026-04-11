package com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.market;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.AgentContext;
import com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.AgentMessage;
import com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.AgentRole;
import com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.AgentResponse;
import com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.AgentTask;
import com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.AgentTaskType;
import com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.ExecutorAgent;
import com.zhongbo.mindos.assistant.dispatcher.agent.multiagent.PlannerAgent;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.DecisionOrchestrator;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultAgentMarketTest {

    @Test
    void shouldSelectBestPlanningProposal() {
        DefaultAgentMarket market = new DefaultAgentMarket();
        AgentMessage message = AgentMessage.of(
                "market",
                "planner",
                AgentTask.of(AgentTaskType.PLAN_REQUEST, "u1", "优化排课效率", Map.of())
        );
        AgentContext context = context("trace-plan", "u1", "优化排课效率");

        PlannerAgent stronger = planner("planner-alpha",
                AgentResponse.progress(
                        "planner-alpha",
                        "生成可执行 DAG",
                        List.of(AgentMessage.of("planner-alpha", "executor-agent", AgentTask.of(AgentTaskType.EXECUTE_GRAPH, "u1", "优化排课效率", Map.of()))),
                        Map.of(
                                "graph", TaskGraph.linear(List.of("student.get", "schedule.optimize"), Map.of("studentId", "stu-42")),
                                "rationale", "graph ready"
                        )
                )
        );
        PlannerAgent weaker = planner("planner-beta",
                AgentResponse.completed(
                        "planner-beta",
                        SkillResult.failure("planner-beta", "missing context"),
                        false,
                        List.of(),
                        Map.of(),
                        "missing context"
                )
        );

        AgentMarketResult result = market.compete(message, context, List.of(weaker, stronger));

        assertTrue(result.hasWinner());
        assertEquals("planner-alpha", result.winnerAgent());
        assertEquals(2, result.rankedProposals().size());
        assertEquals("planner-alpha", result.rankedProposals().get(0).agentName());
        assertTrue(result.rankedProposals().get(0).score() > result.rankedProposals().get(1).score());
        assertTrue(result.summary().contains("winner=planner-alpha"));
    }

    @Test
    void shouldSelectBestExecutionProposal() {
        DefaultAgentMarket market = new DefaultAgentMarket();
        AgentMessage message = AgentMessage.of(
                "market",
                "executor",
                AgentTask.of(AgentTaskType.EXECUTE_GRAPH, "u1", "执行任务", Map.of())
        );
        AgentContext context = context("trace-exec", "u1", "执行任务");

        ExecutorAgent winner = executor("executor-alpha",
                AgentResponse.completed(
                        "executor-alpha",
                        SkillResult.success("executor-alpha", "done"),
                        false,
                        List.of(),
                        Map.of("result", "done"),
                        "execution success"
                )
        );
        ExecutorAgent fallback = executor("executor-beta",
                AgentResponse.completed(
                        "executor-beta",
                        SkillResult.failure("executor-beta", "fallback"),
                        true,
                        List.of(),
                        Map.of(),
                        "fallback"
                )
        );

        AgentMarketResult result = market.compete(message, context, List.of(fallback, winner));

        assertEquals("executor-alpha", result.winnerAgent());
        assertTrue(result.winnerResponse().result().success());
        assertTrue(result.rankedProposals().get(0).score() > result.rankedProposals().get(1).score());
    }

    @Test
    void shouldReturnEmptyResultWhenNoContestants() {
        DefaultAgentMarket market = new DefaultAgentMarket();
        AgentMarketResult result = market.compete(
                AgentMessage.of("market", "planner", AgentTask.of(AgentTaskType.PLAN_REQUEST, "u1", "优化排课效率", Map.of())),
                context("trace-empty", "u1", "优化排课效率"),
                List.of()
        );

        assertFalse(result.hasWinner());
        assertTrue(result.rankedProposals().isEmpty());
        assertTrue(result.summary().contains("no contestants"));
    }

    private AgentContext context(String traceId, String userId, String userInput) {
        Decision decision = new Decision("优化排课效率", "schedule.optimize", Map.of(), 0.8, false);
        return new AgentContext(
                traceId,
                decision,
                new DecisionOrchestrator.OrchestrationRequest(
                        userId,
                        userInput,
                        new SkillContext(userId, userInput, Map.of()),
                        Map.of()
                ),
                Map.of(),
                null
        );
    }

    private PlannerAgent planner(String name, AgentResponse response) {
        return new PlannerAgent() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public AgentRole role() {
                return AgentRole.PLANNER;
            }

            @Override
            public AgentResponse plan(AgentMessage message, AgentContext context) {
                return response;
            }
        };
    }

    private ExecutorAgent executor(String name, AgentResponse response) {
        return new ExecutorAgent() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public AgentRole role() {
                return AgentRole.EXECUTOR;
            }

            @Override
            public AgentResponse execute(AgentMessage message, AgentContext context) {
                return response;
            }
        };
    }
}
