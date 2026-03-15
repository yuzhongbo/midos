package com.zhongbo.mindos.assistant.dispatcher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class SkillDslValidator {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public Map<String, Object> validateJson(String skillDslJson) {
        if (skillDslJson == null || skillDslJson.isBlank()) {
            throw new SkillDslValidationException("SkillDSL JSON is empty");
        }

        try {
            Map<String, Object> root = objectMapper.readValue(skillDslJson, new TypeReference<>() {
            });
            validateRoot(root);
            return root;
        } catch (JsonProcessingException e) {
            throw new SkillDslValidationException("Invalid SkillDSL JSON", e);
        }
    }

    @SuppressWarnings("unchecked")
    private void validateRoot(Map<String, Object> root) {
        validateOptionalObjects(root, "$");

        Object skills = root.get("skills");
        if (skills instanceof List<?> skillList) {
            if (skillList.isEmpty()) {
                throw new SkillDslValidationException("'skills' cannot be empty at root");
            }
            for (int i = 0; i < skillList.size(); i++) {
                Object item = skillList.get(i);
                if (!(item instanceof Map<?, ?>)) {
                    throw new SkillDslValidationException("Skill item must be an object at $.skills[" + i + "]");
                }
                validateSkillNode((Map<String, Object>) item, "$.skills[" + i + "]");
            }
            return;
        }

        validateSkillNode(root, "$");
    }

    @SuppressWarnings("unchecked")
    private void validateSkillNode(Map<String, Object> node, String path) {
        Object skill = node.get("skill");
        if (!(skill instanceof String skillName) || skillName.isBlank()) {
            throw new SkillDslValidationException("Missing or invalid 'skill' at " + path);
        }

        validateOptionalObjects(node, path);

        if (!node.containsKey("nestedSkills")) {
            return;
        }

        Object nestedSkills = node.get("nestedSkills");
        if (!(nestedSkills instanceof List<?> nestedList)) {
            throw new SkillDslValidationException("'nestedSkills' must be an array at " + path);
        }

        for (int i = 0; i < nestedList.size(); i++) {
            Object item = nestedList.get(i);
            if (!(item instanceof Map<?, ?>)) {
                throw new SkillDslValidationException("Nested skill item must be an object at " + path + ".nestedSkills[" + i + "]");
            }
            validateSkillNode((Map<String, Object>) item, path + ".nestedSkills[" + i + "]");
        }
    }

    private void validateOptionalObjects(Map<String, Object> node, String path) {
        if (node.containsKey("input") && !(node.get("input") instanceof Map<?, ?>)) {
            throw new SkillDslValidationException("'input' must be an object at " + path);
        }
        if (node.containsKey("metadata") && !(node.get("metadata") instanceof Map<?, ?>)) {
            throw new SkillDslValidationException("'metadata' must be an object at " + path);
        }
        if (node.containsKey("context") && !(node.get("context") instanceof Map<?, ?>)) {
            throw new SkillDslValidationException("'context' must be an object at " + path);
        }
    }
}

