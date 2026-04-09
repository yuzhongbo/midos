package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import java.util.Map;

public interface ParamValidator {

    ValidationResult validate(String target, Map<String, Object> params);

    record ValidationResult(boolean valid, String message) {
        public static ValidationResult ok() {
            return new ValidationResult(true, "");
        }

        public static ValidationResult error(String message) {
            return new ValidationResult(false, message == null ? "invalid parameters" : message);
        }
    }
}
