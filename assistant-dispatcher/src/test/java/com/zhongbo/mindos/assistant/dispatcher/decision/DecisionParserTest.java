package com.zhongbo.mindos.assistant.dispatcher.decision;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DecisionParserTest {

    private final DecisionParser parser = new DecisionParser();

    @Test
    void shouldParseMinimalDecision() {
        String json = """
                {"target":"todo.create","params":{"task":"demo"},"confidence":0.8}
                """;
        Optional<Decision> parsed = parser.parse(json);
        assertTrue(parsed.isPresent());
        assertEquals("todo.create", parsed.get().target());
        assertEquals("demo", parsed.get().params().get("task"));
        assertFalse(parsed.get().requireClarify());
    }

    @Test
    void shouldReturnEmptyForNonJson() {
        assertTrue(parser.parse("skill:todo.create task=demo").isEmpty());
    }

    @Test
    void shouldRejectLegacySkillDslShape() {
        String json = """
                {"skill":"echo","input":{"text":"hi"}}
                """;
        assertTrue(parser.parse(json).isEmpty());
    }

    @Test
    void shouldRejectNonObjectParams() {
        String json = """
                {"target":"echo","params":"bad"}
                """;
        assertTrue(parser.parse(json).isEmpty());
    }

    @Test
    void shouldParseRequireClarifyField() {
        String json = """
                {"target":"todo.create","params":{"task":"demo"},"requireClarify":true}
                """;
        Optional<Decision> parsed = parser.parse(json);
        assertTrue(parsed.isPresent());
        assertTrue(parsed.get().requireClarify());
    }
}
