package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.dispatcher.FinalPlanner;
import com.zhongbo.mindos.assistant.dispatcher.decision.Decision;

import java.util.LinkedHashMap;
import java.util.Map;

final class DecisionInputMetadata {

    private static final String PREFIX = "_orchestrator.";
    private static final String USER_ID = PREFIX + "userId";
    private static final String USER_INPUT = PREFIX + "userInput";
    private static final String CONTEXT_ATTRIBUTES = PREFIX + "contextAttributes";
    private static final String PROFILE_CONTEXT = PREFIX + "profileContext";
    private static final String VALIDATION_MESSAGE = PREFIX + "validationMessage";

    private DecisionInputMetadata() {
    }

    static Decision enrich(Decision decision, DecisionOrchestrator.UserInput input) {
        if (decision == null) {
            return null;
        }
        DecisionOrchestrator.UserInput safeInput = DecisionOrchestrator.UserInput.safe(input);
        Map<String, Object> enriched = new LinkedHashMap<>(forwardedParams(decision.params()));
        enriched.put(USER_ID, safeInput.userId());
        enriched.put(USER_INPUT, safeInput.userInput());
        enriched.put(CONTEXT_ATTRIBUTES, safeInput.skillContext() == null ? Map.of() : safeInput.skillContext().attributes());
        enriched.put(PROFILE_CONTEXT, safeInput.profileContext());
        return new Decision(
                decision.intent(),
                decision.target(),
                enriched,
                decision.confidence(),
                decision.requireClarify()
        );
    }

    static Decision stripMetadata(Decision decision) {
        if (decision == null) {
            return null;
        }
        return new Decision(
                decision.intent(),
                decision.target(),
                businessParams(decision.params()),
                decision.confidence(),
                decision.requireClarify()
        );
    }

    static Decision mergeBusinessParams(Decision decision,
                                        Map<String, Object> businessParams,
                                        boolean requireClarify) {
        return mergeBusinessParams(decision, businessParams, requireClarify, "");
    }

    static Decision mergeBusinessParams(Decision decision,
                                        Map<String, Object> businessParams,
                                        boolean requireClarify,
                                        String validationMessage) {
        if (decision == null) {
            return null;
        }
        Map<String, Object> merged = new LinkedHashMap<>(metadataParams(decision.params()));
        if (validationMessage != null && !validationMessage.isBlank()) {
            merged.put(VALIDATION_MESSAGE, validationMessage);
        }
        merged.putAll(businessParams == null ? Map.of() : businessParams);
        return new Decision(
                decision.intent(),
                decision.target(),
                merged,
                decision.confidence(),
                decision.requireClarify() || requireClarify
        );
    }

    static String validationMessageOf(Decision decision) {
        if (decision == null || decision.params() == null) {
            return "";
        }
        String validationMessage = stringValue(decision.params().get(VALIDATION_MESSAGE));
        if (!validationMessage.isBlank()) {
            return validationMessage;
        }
        return stringValue(decision.params().get(FinalPlanner.PLANNER_CLARIFY_MESSAGE_KEY));
    }

    static DecisionOrchestrator.OrchestrationRequest requestOf(Decision decision) {
        Map<String, Object> params = decision == null ? Map.of() : decision.params();
        String userId = stringValue(params.get(USER_ID));
        String userInput = stringValue(params.get(USER_INPUT));
        SkillContext context = new SkillContext(userId, userInput, mapValue(params.get(CONTEXT_ATTRIBUTES)));
        return new DecisionOrchestrator.OrchestrationRequest(
                userId,
                userInput,
                context,
                mapValue(params.get(PROFILE_CONTEXT))
        );
    }

    static Map<String, Object> businessParams(Map<String, Object> params) {
        Map<String, Object> business = new LinkedHashMap<>();
        if (params == null || params.isEmpty()) {
            return Map.of();
        }
        params.forEach((key, value) -> {
            if (!isReservedKey(key)) {
                business.put(key, value);
            }
        });
        return business.isEmpty() ? Map.of() : Map.copyOf(business);
    }

    private static Map<String, Object> forwardedParams(Map<String, Object> params) {
        Map<String, Object> forwarded = new LinkedHashMap<>(businessParams(params));
        if (params == null || params.isEmpty()) {
            return forwarded.isEmpty() ? Map.of() : Map.copyOf(forwarded);
        }
        params.forEach((key, value) -> {
            if (isPlannerKey(key)) {
                forwarded.put(key, value);
            }
        });
        return forwarded.isEmpty() ? Map.of() : Map.copyOf(forwarded);
    }

    private static Map<String, Object> metadataParams(Map<String, Object> params) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (params == null || params.isEmpty()) {
            return Map.of();
        }
        params.forEach((key, value) -> {
            if (isReservedKey(key)) {
                metadata.put(key, value);
            }
        });
        return metadata.isEmpty() ? Map.of() : Map.copyOf(metadata);
    }

    private static boolean isReservedKey(String key) {
        return isOrchestratorKey(key) || isPlannerKey(key);
    }

    private static boolean isOrchestratorKey(String key) {
        return key != null && key.startsWith(PREFIX);
    }

    private static boolean isPlannerKey(String key) {
        return key != null
                && (key.startsWith(FinalPlanner.PLANNER_METADATA_PREFIX)
                || FinalPlanner.PLANNER_ROUTE_SOURCE_KEY.equals(key));
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> source) || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        source.forEach((key, item) -> {
            if (key != null) {
                normalized.put(String.valueOf(key), item);
            }
        });
        return normalized.isEmpty() ? Map.of() : Map.copyOf(normalized);
    }
}
