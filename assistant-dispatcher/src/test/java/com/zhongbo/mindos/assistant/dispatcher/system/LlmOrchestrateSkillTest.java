package com.zhongbo.mindos.assistant.dispatcher.system;

import com.zhongbo.mindos.assistant.common.LlmClient;
import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmOrchestrateSkillTest {

    @Test
    void shouldUsePreferredProviderWithSharedContext() {
        RecordingLlmClient llm = new RecordingLlmClient();
        llm.stub("p1", "answer from p1");
        llm.stub("p2", "answer from p2");

        LlmOrchestrateSkill skill = new LlmOrchestrateSkill(llm, List.of("p1", "p2"), 2, 1200, 4);
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("input", "问答");
        attrs.put("preferredProvider", "p1");
        attrs.put("memoryContext", "memo-ctx");
        attrs.put("chatHistory", List.of(Map.of("role", "user", "content", "hi")));
        SkillResult result = skill.run(new SkillContext("u1", "问答", attrs));

        assertTrue(result.success());
        assertTrue(result.output().contains("provider=p1"));
        assertTrue(llm.capturedContexts().stream().anyMatch(ctx -> "memo-ctx".equals(ctx.get("memoryContext"))));
        assertEquals(1, llm.capturedContexts().size());
    }

    @Test
    void shouldFailWhenAllProvidersBad() {
        RecordingLlmClient llm = new RecordingLlmClient();
        llm.stub("only", "[LLM error] missing");

        LlmOrchestrateSkill skill = new LlmOrchestrateSkill(llm, List.of("only"), 1, 800, 4);
        SkillResult result = skill.run(new SkillContext("u1", "", Map.of("input", "ping", "preferredProvider", "only")));

        assertTrue(result.output().contains("provider=only"));
        assertEquals(1, llm.capturedContexts().size());
    }

    private static final class RecordingLlmClient implements LlmClient {
        private final Map<String, String> stubs = new LinkedHashMap<>();
        private final List<Map<String, Object>> contexts = new ArrayList<>();

        void stub(String provider, String output) {
            stubs.put(provider, output);
        }

        @Override
        public String generateResponse(String prompt, Map<String, Object> context) {
            contexts.add(context);
            Object provider = context.get("llmProvider");
            return stubs.getOrDefault(provider == null ? "" : provider.toString(), "no-output");
        }

        List<Map<String, Object>> capturedContexts() {
            return contexts;
        }
    }
}
