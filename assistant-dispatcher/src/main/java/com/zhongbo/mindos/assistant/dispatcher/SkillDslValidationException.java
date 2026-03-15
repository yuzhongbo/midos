package com.zhongbo.mindos.assistant.dispatcher;

public class SkillDslValidationException extends RuntimeException {

    public SkillDslValidationException(String message) {
        super(message);
    }

    public SkillDslValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}

