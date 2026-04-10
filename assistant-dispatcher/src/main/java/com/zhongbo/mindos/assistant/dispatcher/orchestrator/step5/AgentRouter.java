package com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5;

import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.DecisionOrchestrator;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.ScoredCandidate;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public interface AgentRouter {

    AgentRouteDecision route(Decision decision,
                             DecisionOrchestrator.OrchestrationRequest request,
                             ScoredCandidate candidate,
                             Map<String, Object> currentContext);

    enum RouteType {
        LOCAL_MODEL,
        REMOTE_MODEL,
        MCP_TOOL
    }

    record AgentRouteDecision(RouteType routeType,
                              String provider,
                              String preset,
                              String model,
                              double confidence,
                              List<String> reasons,
                              Map<String, Object> contextPatch) {

        public AgentRouteDecision {
            routeType = routeType == null ? RouteType.REMOTE_MODEL : routeType;
            provider = normalize(provider);
            preset = normalize(preset);
            model = normalize(model);
            confidence = clamp(confidence);
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
            contextPatch = contextPatch == null ? Map.of() : Map.copyOf(contextPatch);
        }

        public static AgentRouteDecision local(String provider,
                                               String preset,
                                               String model,
                                               double confidence,
                                               List<String> reasons,
                                               Map<String, Object> contextPatch) {
            return new AgentRouteDecision(RouteType.LOCAL_MODEL, provider, preset, model, confidence, reasons, contextPatch);
        }

        public static AgentRouteDecision remote(String provider,
                                                String preset,
                                                String model,
                                                double confidence,
                                                List<String> reasons,
                                                Map<String, Object> contextPatch) {
            return new AgentRouteDecision(RouteType.REMOTE_MODEL, provider, preset, model, confidence, reasons, contextPatch);
        }

        public static AgentRouteDecision mcp(String provider,
                                             String preset,
                                             String model,
                                             double confidence,
                                             List<String> reasons,
                                             Map<String, Object> contextPatch) {
            return new AgentRouteDecision(RouteType.MCP_TOOL, provider, preset, model, confidence, reasons, contextPatch);
        }

        private static double clamp(double value) {
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                return 0.5;
            }
            return Math.max(0.0, Math.min(1.0, value));
        }

        private static String normalize(String value) {
            if (value == null) {
                return "";
            }
            String normalized = value.trim();
            return normalized.isBlank() ? "" : normalized;
        }
    }
}
