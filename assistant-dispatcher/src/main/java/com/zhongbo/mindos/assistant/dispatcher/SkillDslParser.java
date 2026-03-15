package com.zhongbo.mindos.assistant.dispatcher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhongbo.mindos.assistant.common.SkillDsl;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Component
public class SkillDslParser {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SkillDslValidator skillDslValidator;

    public SkillDslParser(SkillDslValidator skillDslValidator) {
        this.skillDslValidator = skillDslValidator;
    }

    public Optional<SkillDsl> parse(String input) {
        if (input == null || input.isBlank()) {
            return Optional.empty();
        }

        String trimmed = input.trim();
        if (trimmed.startsWith("{")) {
            return parseSkillDslJson(trimmed);
        }
        if (!trimmed.startsWith("skill:")) {
            return Optional.empty();
        }

        String[] tokens = trimmed.split("\\s+");
        if (tokens.length == 0) {
            return Optional.empty();
        }

        String skillName = tokens[0].substring("skill:".length()).trim();
        Map<String, Object> dslInput = new LinkedHashMap<>();
        for (int i = 1; i < tokens.length; i++) {
            String token = tokens[i];
            int splitIndex = token.indexOf('=');
            if (splitIndex > 0 && splitIndex < token.length() - 1) {
                dslInput.put(token.substring(0, splitIndex), token.substring(splitIndex + 1));
            }
        }
        return Optional.of(new SkillDsl(skillName, dslInput));
    }

    public Optional<SkillDsl> parseSkillDslJson(String skillDslJson) {
        if (skillDslJson == null || skillDslJson.isBlank()) {
            return Optional.empty();
        }
        Map<String, Object> parsed = skillDslValidator.validateJson(skillDslJson);

        if (parsed.containsKey("skills")) {
            Object skills = parsed.get("skills");
            if (skills instanceof java.util.List<?> skillList && !skillList.isEmpty() && skillList.get(0) instanceof Map<?, ?> first) {
                Map<String, Object> flattened = new LinkedHashMap<>();
                first.forEach((k, v) -> flattened.put(String.valueOf(k), v));
                parsed = flattened;
            }
        }

        Object skill = parsed.get("skill");
        if (!(skill instanceof String skillName) || skillName.isBlank()) {
            throw new SkillDslValidationException("Missing root skill after validation");
        }

        Object input = parsed.getOrDefault("input", Map.of());
        Map<String, Object> inputMap;
        if (input instanceof Map<?, ?> rawInput) {
            inputMap = new LinkedHashMap<>();
            rawInput.forEach((key, value) -> inputMap.put(String.valueOf(key), value));
        } else {
            inputMap = new HashMap<>();
        }
        return Optional.of(new SkillDsl(skillName, inputMap));
    }

    public String toSkillDslJson(SkillDsl dsl) {
        try {
            return objectMapper.writeValueAsString(Map.of("skill", dsl.skill(), "input", dsl.input()));
        } catch (JsonProcessingException e) {
            return "{\"skill\":\"" + dsl.skill() + "\",\"input\":{}}";
        }
    }
}
