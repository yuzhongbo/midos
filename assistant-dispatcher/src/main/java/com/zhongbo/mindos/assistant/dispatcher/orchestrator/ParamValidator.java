package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.common.SkillResult;

import java.util.List;
import java.util.Map;

public interface ParamValidator {

    ValidationResult validate(String target, Map<String, Object> params, DecisionOrchestrator.OrchestrationRequest request);

    default ValidationResult validate(String target, Map<String, Object> params) {
        return validate(target, params, null);
    }

    default ValidationResult repairAfterFailure(String target,
                                                Map<String, Object> params,
                                                SkillResult failure,
                                                DecisionOrchestrator.OrchestrationRequest request) {
        return validate(target, params, request);
    }

    record ValidationResult(boolean valid,
                            boolean needsClarification,
                            Map<String, Object> normalizedParams,
                            List<String> missingParams,
                            Map<String, Object> autofilledParams,
                            String message) {
        public static ValidationResult ok() {
            return new ValidationResult(true, false, Map.of(), List.of(), Map.of(), "");
        }

        public static ValidationResult ok(Map<String, Object> normalizedParams,
                                          Map<String, Object> autofilledParams) {
            return new ValidationResult(true,
                    false,
                    normalizedParams == null ? Map.of() : Map.copyOf(normalizedParams),
                    List.of(),
                    autofilledParams == null ? Map.of() : Map.copyOf(autofilledParams),
                    "");
        }

        public static ValidationResult error(String message) {
            return clarify(message, List.of(), Map.of(), Map.of());
        }

        public static ValidationResult clarify(String message,
                                               List<String> missingParams,
                                               Map<String, Object> normalizedParams,
                                               Map<String, Object> autofilledParams) {
            return new ValidationResult(false,
                    true,
                    normalizedParams == null ? Map.of() : Map.copyOf(normalizedParams),
                    missingParams == null ? List.of() : List.copyOf(missingParams),
                    autofilledParams == null ? Map.of() : Map.copyOf(autofilledParams),
                    message == null ? "invalid parameters" : message);
        }
    }
}
