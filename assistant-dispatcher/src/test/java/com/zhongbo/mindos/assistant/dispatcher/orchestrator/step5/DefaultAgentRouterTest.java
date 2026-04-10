package com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.DecisionOrchestrator;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.ScoredCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultAgentRouterTest {

    private final DefaultAgentRouter router = new DefaultAgentRouter(null, new InMemoryPlannerLearningStore());

    @Test
    void shouldRouteSimpleQueryToLocal() {
        Decision decision = new Decision("remember", "memory.save", Map.of(), 0.95, false);
        ScoredCandidate candidate = new ScoredCandidate("skill.local", 0.92, 0.88, 0.24, 0.60, List.of("keyword"));
        DecisionOrchestrator.OrchestrationRequest request = request("记个备忘");

        AgentRouter.RouteDecision routeDecision = router.decide(decision, request, candidate, Map.of());

        assertEquals(AgentRouter.RouteType.LOCAL, routeDecision.routeType());
        assertEquals("simple query", routeDecision.reason());
        assertTrue(routeDecision.tokenEstimate() > 0);
    }

    @Test
    void shouldRouteComplexReasoningToRemote() {
        Decision decision = new Decision("analyze", "analysis.plan", Map.of(), 0.62, false);
        ScoredCandidate candidate = new ScoredCandidate("skill.analyze", 0.78, 0.66, 0.35, 0.58, List.of("keyword"));
        DecisionOrchestrator.OrchestrationRequest request = request("请分析这个项目重构方案，比较三种实现路径并给出详细建议和风险点。");

        AgentRouter.RouteDecision routeDecision = router.decide(decision, request, candidate, Map.of());

        assertEquals(AgentRouter.RouteType.REMOTE, routeDecision.routeType());
        assertEquals("complex reasoning", routeDecision.reason());
        assertTrue(routeDecision.complexity() >= 0.0);
    }

    @Test
    void shouldRouteExternalDataTaskToMcp() {
        Decision decision = new Decision("search", "mcp.search.webSearch", Map.of(), 0.88, false);
        ScoredCandidate candidate = new ScoredCandidate("mcp.search.webSearch", 0.91, 0.70, 0.20, 0.55, List.of("keyword"));
        DecisionOrchestrator.OrchestrationRequest request = request("帮我查询最新新闻");

        AgentRouter.RouteDecision routeDecision = router.decide(decision, request, candidate, Map.of("needsExternalData", true));

        assertEquals(AgentRouter.RouteType.MCP, routeDecision.routeType());
        assertEquals("needs external data", routeDecision.reason());
    }

    private DecisionOrchestrator.OrchestrationRequest request(String input) {
        return new DecisionOrchestrator.OrchestrationRequest("u1", input, new SkillContext("u1", input, Map.of()), Map.of());
    }
}
