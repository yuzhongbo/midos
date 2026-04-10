package com.zhongbo.mindos.assistant.dispatcher.orchestrator.step5;

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

    private static final Set<String> HARD_HINTS = Set.of(
            "复杂", "多步骤", "规划", "方案", "重构", "分析", "debug", "排查", "总结", "report", "design"
    );

    private final IntentModelRoutingPolicy intentModelRoutingPolicy;
    private final PlannerLearningStore plannerLearningStore;
    private final String localProvider;
    private final String localPreset;
    private final String localModel;
    private final String remoteProvider;
    private final String remotePreset;
    private final String remoteModel;
    private final double localLearningThreshold;
    private final double localCandidateThreshold;
    private final double remoteDecisionThreshold;
    private final String mcpPrefix;

    @Autowired
    public DefaultAgentRouter(IntentModelRoutingPolicy intentModelRoutingPolicy,
                              PlannerLearningStore plannerLearningStore,
                              @Value("${mindos.dispatcher.step5.router.local-provider:local}") String localProvider,
                              @Value("${mindos.dispatcher.step5.router.local-preset:cost}") String localPreset,
                              @Value("${mindos.dispatcher.step5.router.local-model:}") String localModel,
                              @Value("${mindos.dispatcher.step5.router.remote-provider:}") String remoteProvider,
                              @Value("${mindos.dispatcher.step5.router.remote-preset:quality}") String remotePreset,
                              @Value("${mindos.dispatcher.step5.router.remote-model:}") String remoteModel,
                              @Value("${mindos.dispatcher.step5.router.local-learning-threshold:0.62}") double localLearningThreshold,
                              @Value("${mindos.dispatcher.step5.router.local-candidate-threshold:0.72}") double localCandidateThreshold,
                              @Value("${mindos.dispatcher.step5.router.remote-decision-threshold:0.70}") double remoteDecisionThreshold,
                              @Value("${mindos.dispatcher.step5.router.mcp-prefix:mcp.}") String mcpPrefix) {
        this.intentModelRoutingPolicy = intentModelRoutingPolicy;
        this.plannerLearningStore = plannerLearningStore;
        this.localProvider = normalize(localProvider, "local");
        this.localPreset = normalize(localPreset, "cost");
        this.localModel = normalize(localModel, "");
        this.remoteProvider = normalize(remoteProvider, "");
        this.remotePreset = normalize(remotePreset, "quality");
        this.remoteModel = normalize(remoteModel, "");
        this.localLearningThreshold = clamp(localLearningThreshold);
        this.localCandidateThreshold = clamp(localCandidateThreshold);
        this.remoteDecisionThreshold = clamp(remoteDecisionThreshold);
        this.mcpPrefix = normalize(mcpPrefix, "mcp.");
    }

    public DefaultAgentRouter(IntentModelRoutingPolicy intentModelRoutingPolicy,
                              PlannerLearningStore plannerLearningStore) {
        this(intentModelRoutingPolicy, plannerLearningStore, "local", "cost", "", "", "quality", "", 0.62, 0.72, 0.70, "mcp.");
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
        List<String> reasons = new ArrayList<>();
        reasons.add("score=" + round(candidateScore));
        reasons.add("learning=" + round(learning.score()));
        if (!learning.preferredRoute().isBlank()) {
            reasons.add("preferredRoute=" + learning.preferredRoute());
        }

        if (isMcpSkill(skillName)) {
            reasons.add("mcp-namespace");
            return AgentRouteDecision.mcp(
                    "",
                    "",
                    "",
                    candidateScore,
                    reasons,
                    buildPatch("mcp-tool", "", "", "", candidateScore, learning, reasons)
            );
        }

        boolean forceLocal = shouldUseLocalRoute(candidateScore, learning, decision, userInput, safeContext);
        if (forceLocal) {
            reasons.add("route=local");
            return AgentRouteDecision.local(
                    localProvider,
                    localPreset,
                    localModel,
                    candidateScore,
                    reasons,
                    buildPatch("local-model", localProvider, localPreset, localModel, candidateScore, learning, reasons)
            );
        }

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
        return AgentRouteDecision.remote(
                provider,
                preset,
                model,
                candidateScore,
                reasons,
                buildPatch("remote-model", provider, preset, model, candidateScore, learning, reasons)
        );
    }

    private boolean shouldUseLocalRoute(double candidateScore,
                                        PlannerLearningStore.LearningSnapshot learning,
                                        Decision decision,
                                        String userInput,
                                        Map<String, Object> currentContext) {
        double confidence = decision == null ? 0.5 : clamp(decision.confidence());
        if (learning != null && learning.sampleCount() >= 3 && "remote-model".equalsIgnoreCase(learning.preferredRoute())) {
            return false;
        }
        if (containsAny(userInput, HARD_HINTS) || containsAny(asText(currentContext == null ? null : currentContext.get("routePreference")), Set.of("remote", "remote-model", "cloud"))) {
            return false;
        }
        boolean simpleInput = userInput == null || userInput.length() <= 160;
        boolean strongLearning = learning != null && learning.score() >= localLearningThreshold;
        return simpleInput
                && confidence >= remoteDecisionThreshold
                && candidateScore >= localCandidateThreshold
                && (strongLearning || learning == null || learning.sampleCount() == 0L || "local-model".equalsIgnoreCase(learning.preferredRoute()) || "auto".equalsIgnoreCase(learning.preferredRoute()));
    }

    private boolean isMcpSkill(String skillName) {
        return skillName != null && skillName.trim().toLowerCase(Locale.ROOT).startsWith(mcpPrefix.toLowerCase(Locale.ROOT));
    }

    private Map<String, Object> buildPatch(String routeType,
                                           String provider,
                                           String preset,
                                           String model,
                                           double candidateScore,
                                           PlannerLearningStore.LearningSnapshot learning,
                                           List<String> reasons) {
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("routeType", routeType);
        patch.put("routeConfidence", candidateScore);
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
