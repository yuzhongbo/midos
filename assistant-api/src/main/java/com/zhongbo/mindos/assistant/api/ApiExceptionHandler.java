package com.zhongbo.mindos.assistant.api;

import com.zhongbo.mindos.assistant.dispatcher.SkillDslValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(SkillDslValidationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleSkillDslValidationException(SkillDslValidationException ex) {
        return Map.of(
                "code", "INVALID_SKILL_DSL",
                "message", ex.getMessage(),
                "timestamp", Instant.now().toString()
        );
    }
}

