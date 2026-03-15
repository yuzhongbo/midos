package com.zhongbo.mindos.assistant.skill.loader;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * JSON-based custom skill definition.
 *
 * Example file (my-skill.json):
 * <pre>
 * {
 *   "name": "greet",
 *   "description": "Greet the user warmly",
 *   "triggers": ["greet", "hello"],
 *   "response": "Hello {{user}}, nice to hear from you! You said: {{input}}"
 * }
 * </pre>
 *
 * Special values for "response":
 *   "llm"  — delegate the full input to the configured LLM client
 *   any other text — treated as a template; supports {{input}} and {{user}} placeholders
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScriptSkillDefinition(
        String name,
        String description,
        List<String> triggers,
        String response
) {
    public ScriptSkillDefinition {
        triggers = triggers == null ? List.of() : List.copyOf(triggers);
        response = response == null ? "" : response;
    }
}

