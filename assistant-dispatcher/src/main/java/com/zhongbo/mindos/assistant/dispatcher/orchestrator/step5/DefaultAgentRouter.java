package com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5;

import com.zhongbo.mindos.assistant.common.SkillCostTelemetry;
import com.zhongbo.mindos.assistant.common.dto.CostModel;
import com.zhongbo.mindos.assistant.common.dto.PromptMemoryContextDto;
import com.zhongbo.mindos.assistant.dispatcher.IntentModelRoutingPolicy;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.DecisionOrchestrator;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.ScoredCandidate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class DefaultAgentRouter implements AgentRouter {

    private static final Set<String> COMPLEX_HINTS = Set.of(
            "复杂", "多步骤", "规划", "方案", "重构", "分析", "推理", "比较", "debug", "排查", "总结", "report", "design"
    );

    private static final Set<String> EXTERNAL_DATA_HINTS = Set.of(
            "搜索", "查询", "新闻", "天气", "实时", "最新", "网页", "外部", "api", "lookup", "fetch"
    );

    private final IntentModelRoutingPolicy intentModelRoutingPolicy;
    private final PlannerLearningStore plannerLearningStore;
    private final SkillCostTelemetry skillCostTelemetry;
    private final String localProvider;
    private final String localPreset;
    private final String localModel;
    private final String remoteProvider;
    private final String remotePreset;
    private final String remoteModel;
    private final double localLearningThreshold;
    private final double localCandidateThreshold;
    private final double remoteDecisionThreshold;
    private final double localComplexityThreshold;
    private final double remoteComplexityThreshold;
    private final int localTokenThreshold;
    private final int remoteTokenThreshold;
    private final double costMargin;
    private final String mcpPrefix;

    @Autowired
    public DefaultAgentRouter(IntentModelRoutingPolicy intentModelRoutingPolicy,
                              PlannerLearningStore plannerLearningStore,
                              SkillCostTelemetry skillCostTelemetry,
                              @Value("${mindos.dispatcher.step5.router.local-provider:local}") String localProvider,
                              @Value("${mindos.dispatcher.step5.router.local-preset:cost}") String localPreset,
                              @Value("${mindos.dispatcher.step5.router.local-model:}") String localModel,
                              @Value("${mindos.dispatcher.step5.router.remote-provider:}") String remoteProvider,
                              @Value("${mindos.dispatcher.step5.router.remote-preset:quality}") String remotePreset,
                              @Value("${mindos.dispatcher.step5.router.remote-model:}") String remoteModel,
                              @Value("${mindos.dispatcher.step5.router.local-learning-threshold:0.62}") double localLearningThreshold,
                              @Value("${mindos.dispatcher.step5.router.local-candidate-threshold:0.72}") double localCandidateThreshold,
                              @Value("${mindos.dispatcher.step5.router.remote-decision-threshold:0.70}") double remoteDecisionThreshold,
                              @Value("${mindos.dispatcher.step5.router.local-complexity-threshold:0.42}") double localComplexityThreshold,
                              @Value("${mindos.dispatcher.step5.router.remote-complexity-threshold:0.66}") double remoteComplexityThreshold,
                              @Value("${mindos.dispatcher.step5.router.local-token-threshold:160}") int localTokenThreshold,
                              @Value("${mindos.dispatcher.step5.router.remote-token-threshold:240}") int remoteTokenThreshold,
                              @Value("${mindos.dispatcher.step5.router.cost-margin:0.04}") double costMargin,
                              @Value("${mindos.dispatcher.step5.router.mcp-prefix:mcp.}") String mcpPrefix) {
        this.intentModelRoutingPolicy = intentModelRoutingPolicy;
        this.plannerLearningStore = plannerLearningStore;
        this.skillCostTelemetry = skillCostTelemetry;
        this.localProvider = normalize(localProvider, "local");
        this.localPreset = normalize(localPreset, "cost");
        this.localModel = normalize(localModel, "");
        this.remoteProvider = normalize(remoteProvider, "");
        this.remotePreset = normalize(remotePreset, "quality");
        this.remoteModel = normalize(remoteModel, "");
        this.localLearningThreshold = clamp(localLearningThreshold);
        this.localCandidateThreshold = clamp(localCandidateThreshold);
        this.remoteDecisionThreshold = clamp(remoteDecisionThreshold);
        this.localComplexityThreshold = clamp(localComplexityThreshold);
        this.remoteComplexityThreshold = clamp(remoteComplexityThreshold);
        this.localTokenThreshold = Math.max(1, localTokenThreshold);
        this.remoteTokenThreshold = Math.max(this.localTokenThreshold, remoteTokenThreshold);
        this.costMargin = Math.max(0.0, costMargin);
        this.mcpPrefix = normalize(mcpPrefix, "mcp.");
    }

    public DefaultAgentRouter(IntentModelRoutingPolicy intentModelRoutingPolicy,
                              PlannerLearningStore plannerLearningStore) {
        this(intentModelRoutingPolicy, plannerLearningStore, null, "local", "cost", "", "", "quality", "", 0.62, 0.72, 0.70, 0.42, 0.66, 160, 240, 0.04, "mcp.");
    }

    @Override
    public AgentRouteDecision route(Decision decision,
                                    DecisionOrchestrator.OrchestrationRequest request,
                                    ScoredCandidate candidate,
                                    Map<String, Object> currentContext) {
        Map<String, Object> safeContext = currentContext == null ? Map.of() : currentContext;
        String userId = request == null ? "" : request.userId();
        String userInput = request == null ? "" : request.userInput();
        String skillName = candidate == null ? safeTarget(decision) : candidate.skillName();
        double candidateScore = candidate == null ? 0.5 : candidate.finalScore();
        PlannerLearningStore.LearningSnapshot learning = snapshot(userId, skillName);
        RouteDecision routeDecision = decide(decision, request, candidate, safeContext, learning);
        Map<String, Double> routeCosts = estimateRouteCosts(userId, skillName, userInput, decision, safeContext, candidateScore, learning);
        List<String> reasons = new ArrayList<>();
        reasons.add(routeDecision.reason().isBlank() ? defaultReason(routeDecision.routeType()) : routeDecision.reason());
        reasons.add("complexity=" + round(routeDecision.complexity()));
        reasons.add("tokenEstimate=" + routeDecision.tokenEstimate());
        reasons.add("selectedCost=" + round(routeDecision.estimatedCost()));
        reasons.add("localCost=" + round(routeCosts.getOrDefault("local", routeDecision.estimatedCost())));
        reasons.add("remoteCost=" + round(routeCosts.getOrDefault("remote", routeDecision.estimatedCost())));
        reasons.add("mcpCost=" + round(routeCosts.getOrDefault("mcp", routeDecision.estimatedCost())));
        reasons.add("score=" + round(candidateScore));
        reasons.add("learning=" + round(learning.score()));
        if (!learning.preferredRoute().isBlank()) {
            reasons.add("preferredRoute=" + learning.preferredRoute());
        }

        Map<String, Object> patch;
        switch (routeDecision.routeType()) {
            case LOCAL -> {
                reasons.add("route=local");
                patch = buildPatch(routeDecision, "local", localProvider, localPreset, localModel, learning, routeCosts, reasons);
                return AgentRouteDecision.local(localProvider, localPreset, localModel, confidenceOf(routeDecision), reasons, patch);
            }
            case MCP -> {
                reasons.add("route=mcp");
                patch = buildPatch(routeDecision, "mcp", "", "", "", learning, routeCosts, reasons);
                return AgentRouteDecision.mcp("", "", "", confidenceOf(routeDecision), reasons, patch);
            }
            case REMOTE -> {
                Map<String, Object> profileContext = buildProfileContext(currentContext);
                Map<String, Object> llmContext = new LinkedHashMap<>();
                if (intentModelRoutingPolicy != null) {
                    intentModelRoutingPolicy.applyForFallback(
                            userInput,
                            buildPromptMemoryContext(safeContext),
                            isRealtimeIntent(userInput),
                            profileContext,
                            llmContext
                    );
                }
                String provider = firstNonBlank(asText(llmContext.get("llmProvider")), remoteProvider, localProvider);
                String preset = firstNonBlank(asText(llmContext.get("llmPreset")), remotePreset);
                String model = firstNonBlank(asText(llmContext.get("model")), remoteModel, localModel);
                reasons.add("route=remote");
                if (!provider.isBlank()) {
                    reasons.add("provider=" + provider);
                }
                patch = buildPatch(routeDecision, "remote", provider, preset, model, learning, routeCosts, reasons);
                return AgentRouteDecision.remote(provider, preset, model, confidenceOf(routeDecision), reasons, patch);
            }
            default -> {
                reasons.add("route=remote");
                patch = buildPatch(routeDecision, "remote", remoteProvider, remotePreset, remoteModel, learning, routeCosts, reasons);
                return AgentRouteDecision.remote(remoteProvider, remotePreset, remoteModel, confidenceOf(routeDecision), reasons, patch);
            }
        }
    }

    @Override
    public RouteDecision decide(Decision decision,
                                DecisionOrchestrator.OrchestrationRequest request,
                                ScoredCandidate candidate,
                                Map<String, Object> currentContext) {
        Map<String, Object> safeContext = currentContext == null ? Map.of() : currentContext;
        String userId = request == null ? "" : request.userId();
        String userInput = request == null ? "" : request.userInput();
        String skillName = candidate == null ? safeTarget(decision) : candidate.skillName();
        double candidateScore = candidate == null ? 0.5 : candidate.finalScore();
        PlannerLearningStore.LearningSnapshot learning = snapshot(userId, skillName);
        int tokenEstimate = estimateTokenCount(userInput, decision, safeContext);
        double complexity = estimateComplexity(decision, candidateScore, userInput, safeContext, tokenEstimate, skillName);
        CostModel skillCost = resolveSkillCost(userId, skillName);
        boolean needsExternalData = needsExternalData(skillName, userInput, safeContext);
        Map<String, Double> costs = estimateRouteCosts(userId, skillName, userInput, decision, safeContext, candidateScore, learning, tokenEstimate, complexity, skillCost);
        String preferredRoute = normalizePreferredRoute(learning.preferredRoute());

        if (needsExternalData || isMcpSkill(skillName)) {
            return RouteDecision.mcp("needs external data", complexity, tokenEstimate, costs.getOrDefault("mcp", 0.5));
        }

        boolean strongLocalLearning = learning != null
                && learning.sampleCount() > 0
                && learning.score() >= localLearningThreshold
                && ("local".equals(preferredRoute) || "auto".equals(preferredRoute));
        boolean strongRemoteLearning = learning != null
                && learning.sampleCount() >= 3
                && learning.score() >= localLearningThreshold
                && "remote".equals(preferredRoute);
        boolean complexReasoning = complexity >= remoteComplexityThreshold
                || tokenEstimate >= remoteTokenThreshold
                || containsAny(userInput, COMPLEX_HINTS)
                || (decision != null && clamp(decision.confidence()) < remoteDecisionThreshold);
        boolean simpleQuery = complexity <= localComplexityThreshold
                && tokenEstimate <= localTokenThreshold
                && candidateScore >= localCandidateThreshold
                && !containsAny(userInput, COMPLEX_HINTS);

        double localCost = costs.getOrDefault("local", 0.5);
        double remoteCost = costs.getOrDefault("remote", 0.5);
        if (strongRemoteLearning || complexReasoning || remoteCost + costMargin < localCost) {
            String reason = strongRemoteLearning ? "learned remote preference"
                    : complexReasoning ? "complex reasoning"
                    : "remote cheaper";
            return RouteDecision.remote(reason, complexity, tokenEstimate, remoteCost);
        }
        if (strongLocalLearning || simpleQuery || localCost + costMargin <= remoteCost) {
            String reason = strongLocalLearning ? "learned local preference"
                    : simpleQuery ? "simple query"
                    : "local cheaper";
            return RouteDecision.local(reason, complexity, tokenEstimate, localCost);
        }
        return RouteDecision.remote("balanced towards remote", complexity, tokenEstimate, remoteCost);
    }

    private boolean isMcpSkill(String skillName) {
        return skillName != null && skillName.trim().toLowerCase(Locale.ROOT).startsWith(mcpPrefix.toLowerCase(Locale.ROOT));
    }

    private Map<String, Object> buildPatch(RouteDecision routeDecision,
                                           String routeType,
                                           String provider,
                                           String preset,
                                           String model,
                                           PlannerLearningStore.LearningSnapshot learning,
                                           Map<String, Double> routeCosts,
                                           List<String> reasons) {
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("routeType", routeType);
        patch.put("routeReason", routeDecision == null ? "" : routeDecision.reason());
        patch.put("routeConfidence", confidenceOf(routeDecision));
        patch.put("routeComplexity", routeDecision == null ? 0.5 : routeDecision.complexity());
        patch.put("routeTokenEstimate", routeDecision == null ? 0 : routeDecision.tokenEstimate());
        patch.put("routeEstimatedCost", routeDecision == null ? 0.5 : routeDecision.estimatedCost());
        if (routeCosts != null) {
            patch.put("estimatedLocalCost", routeCosts.getOrDefault("local", routeDecision == null ? 0.5 : routeDecision.estimatedCost()));
            patch.put("estimatedRemoteCost", routeCosts.getOrDefault("remote", routeDecision == null ? 0.5 : routeDecision.estimatedCost()));
            patch.put("estimatedMcpCost", routeCosts.getOrDefault("mcp", routeDecision == null ? 0.5 : routeDecision.estimatedCost()));
        }
        patch.put("routeReasons", reasons == null ? List.of() : List.copyOf(reasons));
        patch.put("plannerLearningScore", learning == null ? 0.5 : learning.score());
        patch.put("plannerPreferredRoute", learning == null ? "auto" : learning.preferredRoute());
        if (provider != null && !provider.isBlank()) {
            patch.put("llmProvider", provider);
        }
        if (preset != null && !preset.isBlank()) {
            patch.put("llmPreset", preset);
        }
        if (model != null && !model.isBlank()) {
            patch.put("model", model);
        }
        return Map.copyOf(patch);
    }

    private Map<String, Double> estimateRouteCosts(String userId,
                                                   String skillName,
                                                   String userInput,
                                                   Decision decision,
                                                   Map<String, Object> currentContext,
                                                   double candidateScore,
                                                   PlannerLearningStore.LearningSnapshot learning) {
        int tokenEstimate = estimateTokenCount(userInput, decision, currentContext);
        double complexity = estimateComplexity(decision, candidateScore, userInput, currentContext, tokenEstimate, skillName);
        CostModel skillCost = resolveSkillCost(userId, skillName);
        return estimateRouteCosts(userId, skillName, userInput, decision, currentContext, candidateScore, learning, tokenEstimate, complexity, skillCost);
    }

    private Map<String, Double> estimateRouteCosts(String userId,
                                                   String skillName,
                                                   String userInput,
                                                   Decision decision,
                                                   Map<String, Object> currentContext,
                                                   double candidateScore,
                                                   PlannerLearningStore.LearningSnapshot learning,
                                                   int tokenEstimate,
                                                   double complexity,
                                                   CostModel skillCost) {
        double tokenLoad = clamp(tokenEstimate / (double) remoteTokenThreshold);
        double skillCostValue = skillCost == null ? 0.5 : skillCost.cost();
        double learningBoost = learning == null ? 0.0 : clamp(learning.score());
        double localCost = clamp(0.18
                + 0.42 * complexity
                + 0.25 * tokenLoad
                + 0.10 * skillCostValue
                + 0.05 * (1.0 - learningBoost));
        double remoteCost = clamp(0.24
                + 0.52 * complexity
                + 0.20 * tokenLoad
                + 0.08 * (1.0 - skillCostValue)
                + 0.04 * (1.0 - learningBoost));
        double mcpCost = clamp(0.16
                + 0.22 * complexity
                + 0.28 * tokenLoad
                + 0.04 * skillCostValue
                + 0.02 * (1.0 - learningBoost));
        Map<String, Double> costs = new LinkedHashMap<>();
        costs.put("local", localCost);
        costs.put("remote", remoteCost);
        costs.put("mcp", mcpCost);
        return Map.copyOf(costs);
    }

    private RouteDecision decide(Decision decision,
                                 DecisionOrchestrator.OrchestrationRequest request,
                                 ScoredCandidate candidate,
                                 Map<String, Object> currentContext,
                                 PlannerLearningStore.LearningSnapshot learning) {
        String userInput = request == null ? "" : request.userInput();
        String userId = request == null ? "" : request.userId();
        String skillName = candidate == null ? safeTarget(decision) : candidate.skillName();
        double candidateScore = candidate == null ? 0.5 : candidate.finalScore();
        int tokenEstimate = estimateTokenCount(userInput, decision, currentContext);
        double complexity = estimateComplexity(decision, candidateScore, userInput, currentContext, tokenEstimate, skillName);
        CostModel skillCost = resolveSkillCost(userId, skillName);
        Map<String, Double> costs = estimateRouteCosts(userId, skillName, userInput, decision, currentContext, candidateScore, learning, tokenEstimate, complexity, skillCost);

        if (needsExternalData(skillName, userInput, currentContext) || isMcpSkill(skillName)) {
            return RouteDecision.mcp("needs external data", complexity, tokenEstimate, costs.getOrDefault("mcp", 0.5));
        }

        String preferredRoute = normalizePreferredRoute(learning == null ? "auto" : learning.preferredRoute());
        boolean strongLocalLearning = learning != null
                && learning.sampleCount() > 0
                && learning.score() >= localLearningThreshold
                && ("local".equals(preferredRoute) || "auto".equals(preferredRoute) || "LOCAL".equalsIgnoreCase(preferredRoute));
        boolean strongRemoteLearning = learning != null
                && learning.sampleCount() >= 3
                && learning.score() >= localLearningThreshold
                && ("remote".equals(preferredRoute) || "REMOTE".equalsIgnoreCase(preferredRoute));
        boolean complexReasoning = complexity >= remoteComplexityThreshold
                || tokenEstimate >= remoteTokenThreshold
                || containsAny(userInput, COMPLEX_HINTS)
                || (decision != null && clamp(decision.confidence()) < remoteDecisionThreshold);
        boolean simpleQuery = complexity <= localComplexityThreshold
                && tokenEstimate <= localTokenThreshold
                && candidateScore >= localCandidateThreshold
                && !containsAny(userInput, COMPLEX_HINTS);

        double localCost = costs.getOrDefault("local", 0.5);
        double remoteCost = costs.getOrDefault("remote", 0.5);
        if (strongRemoteLearning || complexReasoning || remoteCost + costMargin < localCost) {
            String reason = strongRemoteLearning ? "learned remote preference"
                    : complexReasoning ? "complex reasoning"
                    : "remote cheaper";
            return RouteDecision.remote(reason, complexity, tokenEstimate, remoteCost);
        }
        if (strongLocalLearning || simpleQuery || localCost + costMargin <= remoteCost) {
            String reason = strongLocalLearning ? "learned local preference"
                    : simpleQuery ? "simple query"
                    : "local cheaper";
            return RouteDecision.local(reason, complexity, tokenEstimate, localCost);
        }
        return RouteDecision.remote("balanced towards remote", complexity, tokenEstimate, remoteCost);
    }

    private int estimateTokenCount(String userInput, Decision decision, Map<String, Object> currentContext) {
        int inputTokens = estimateTokens(userInput);
        int paramsTokens = estimateTokens(decision == null || decision.params() == null ? "" : decision.params().toString());
        int contextTokens = estimateTokens(filterContextForTokenEstimate(currentContext));
        return Math.max(1, inputTokens + paramsTokens + contextTokens);
    }

    private int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return Math.max(1, (text.length() + 3) / 4);
    }

    private String filterContextForTokenEstimate(Map<String, Object> currentContext) {
        if (currentContext == null || currentContext.isEmpty()) {
            return "";
        }
        return String.valueOf(currentContext.entrySet().stream()
                .filter(entry -> entry.getKey() != null && (
                        entry.getKey().contains("task")
                                || entry.getKey().contains("route")
                                || entry.getKey().contains("need")
                                || entry.getKey().contains("query")
                                || entry.getKey().contains("input")))
                .toList());
    }

    private double estimateComplexity(Decision decision,
                                      double candidateScore,
                                      String userInput,
                                      Map<String, Object> currentContext,
                                      int tokenEstimate,
                                      String skillName) {
        double lengthScore = clamp((userInput == null ? 0 : userInput.length()) / 240.0);
        double tokenScore = clamp(tokenEstimate / (double) remoteTokenThreshold);
        double hintScore = containsAny(userInput, COMPLEX_HINTS) ? 0.28 : 0.0;
        double contextScore = 0.0;
        if (currentContext != null && !currentContext.isEmpty()) {
            if (isTruthy(currentContext.get("needsExternalData")) || isTruthy(currentContext.get("requiresExternalData"))) {
                contextScore += 0.20;
            }
            if (isTruthy(currentContext.get("multiStep")) || isTruthy(currentContext.get("complexReasoning"))) {
                contextScore += 0.18;
            }
        }
        double confidenceScore = decision == null ? 0.0 : clamp(1.0 - decision.confidence());
        double candidateScorePenalty = candidateScore <= 0.0 ? 0.10 : clamp(1.0 - candidateScore) * 0.18;
        double skillPenalty = isMcpSkill(skillName) ? 0.15 : 0.0;
        return clamp(
                0.30 * lengthScore
                        + 0.22 * tokenScore
                        + 0.18 * confidenceScore
                        + 0.16 * candidateScorePenalty
                        + 0.08 * contextScore
                        + 0.06 * skillPenalty
                        + hintScore
        );
    }

    private CostModel resolveSkillCost(String userId, String skillName) {
        if (skillCostTelemetry == null || userId == null || userId.isBlank() || skillName == null || skillName.isBlank()) {
            return CostModel.neutral();
        }
        return skillCostTelemetry.costModels(userId).getOrDefault(skillName, CostModel.neutral());
    }

    private boolean needsExternalData(String skillName,
                                      String userInput,
                                      Map<String, Object> currentContext) {
        if (isMcpSkill(skillName)) {
            return true;
        }
        if (currentContext != null && !currentContext.isEmpty()) {
            if (isTruthy(currentContext.get("needsExternalData"))
                    || isTruthy(currentContext.get("requiresExternalData"))
                    || isTruthy(currentContext.get("useMcp"))) {
                return true;
            }
        }
        return containsAny(userInput, EXTERNAL_DATA_HINTS);
    }

    private boolean isTruthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return "true".equals(text) || "1".equals(text) || "yes".equals(text) || "y".equals(text);
    }

    private String normalizePreferredRoute(String preferredRoute) {
        String normalized = asText(preferredRoute).toLowerCase(Locale.ROOT);
        if (normalized.contains("local")) {
            return "local";
        }
        if (normalized.contains("remote")) {
            return "remote";
        }
        if (normalized.contains("mcp")) {
            return "mcp";
        }
        return normalized.isBlank() ? "auto" : normalized;
    }

    private double confidenceOf(RouteDecision routeDecision) {
        if (routeDecision == null) {
            return 0.5;
        }
        return clamp(1.0 - routeDecision.estimatedCost() + (routeDecision.routeType() == RouteType.MCP ? 0.05 : 0.0));
    }

    private String defaultReason(RouteType routeType) {
        if (routeType == null) {
            return "balanced";
        }
        return switch (routeType) {
            case LOCAL -> "simple query";
            case REMOTE -> "complex reasoning";
            case MCP -> "needs external data";
        };
    }

    private Map<String, Object> buildProfileContext(Map<String, Object> currentContext) {
        if (currentContext == null || currentContext.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> profile = new LinkedHashMap<>();
        Object existingProfile = currentContext.get("profile");
        if (existingProfile instanceof Map<?, ?> map) {
            map.forEach((key, value) -> profile.put(String.valueOf(key), value));
        }
        return profile.isEmpty() ? Map.of() : Map.copyOf(profile);
    }

    private PromptMemoryContextDto buildPromptMemoryContext(Map<String, Object> currentContext) {
        if (currentContext == null || currentContext.isEmpty()) {
            return new PromptMemoryContextDto("", "", "", Map.of(), List.of());
        }
        return new PromptMemoryContextDto(
                asText(currentContext.get("recentConversation")),
                asText(currentContext.get("semanticContext")),
                asText(currentContext.get("proceduralHints")),
                Map.of(),
                List.of()
        );
    }

    private PlannerLearningStore.LearningSnapshot snapshot(String userId, String skillName) {
        if (plannerLearningStore == null) {
            return PlannerLearningStore.LearningSnapshot.neutral();
        }
        return plannerLearningStore.snapshot(userId, skillName);
    }

    private boolean isRealtimeIntent(String userInput) {
        String normalized = asText(userInput).toLowerCase(Locale.ROOT);
        return normalized.contains("实时") || normalized.contains("news") || normalized.contains("天气") || normalized.contains("快讯");
    }

    private boolean containsAny(String input, Set<String> keywords) {
        if (input == null || input.isBlank() || keywords == null || keywords.isEmpty()) {
            return false;
        }
        String normalized = input.trim().toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank() && normalized.contains(keyword.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String safeTarget(Decision decision) {
        if (decision == null || decision.target() == null) {
            return "";
        }
        return decision.target().trim();
    }

    private String asText(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.5;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private String normalize(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String normalized = value.trim();
        return normalized.isBlank() ? fallback : normalized;
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
