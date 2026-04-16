package com.zhongbo.mindos.assistant.skill.semantic;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticAnalysisResultTest {

    @Test
    void shouldReturnSharedEmptyInstance() {
        SemanticAnalysisResult first = SemanticAnalysisResult.empty();
        SemanticAnalysisResult second = SemanticAnalysisResult.empty();
        assertSame(first, second);
    }

    @Test
    void shouldClampConfidenceAndNormalizeSummary() {
        SemanticAnalysisResult result = new SemanticAnalysisResult(
                "local",
                "intent",
                "rewritten",
                "todo.create",
                Map.of(),
                List.of(),
                "  summary content  ",
                1.9
        );

        assertEquals(1.0, result.confidence());
        assertEquals("summary content", result.summary());

        SemanticAnalysisResult low = new SemanticAnalysisResult(
                "local",
                "intent",
                "rewritten",
                "todo.create",
                Map.of(),
                List.of(),
                "",
                -0.5
        );
        assertEquals(0.0, low.confidence());
    }

    @Test
    void shouldDefensivelyCopyPayloadAndKeywords() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("task", "write report");
        List<String> keywords = new ArrayList<>();
        keywords.add("todo");

        SemanticAnalysisResult result = new SemanticAnalysisResult(
                "local",
                "intent",
                "rewritten",
                "todo.create",
                payload,
                keywords,
                "summary",
                0.9
        );

        payload.put("task", "mutated");
        keywords.add("later");

        assertEquals("write report", result.payload().get("task"));
        assertEquals(List.of("todo"), result.keywords());
        assertThrows(UnsupportedOperationException.class, () -> result.payload().put("x", "y"));
        assertThrows(UnsupportedOperationException.class, () -> result.keywords().add("x"));
    }

    @Test
    void shouldIncludeOnlyNonEmptyOptionalAttributes() {
        SemanticAnalysisResult result = new SemanticAnalysisResult(
                "local",
                "intent",
                "",
                "",
                Map.of(),
                List.of(),
                "",
                0.2
        );

        Map<String, Object> attributes = result.asAttributes();

        assertEquals("local", attributes.get(SemanticAnalysisResult.ATTR_ANALYSIS_SOURCE));
        assertEquals("intent", attributes.get(SemanticAnalysisResult.ATTR_INTENT));
        assertEquals("chat", attributes.get(SemanticAnalysisResult.ATTR_INTENT_TYPE));
        assertEquals("standalone", attributes.get(SemanticAnalysisResult.ATTR_CONTEXT_SCOPE));
        assertEquals(Boolean.FALSE, attributes.get(SemanticAnalysisResult.ATTR_TOOL_REQUIRED));
        assertEquals("none", attributes.get(SemanticAnalysisResult.ATTR_MEMORY_OPERATION));
        assertEquals("standalone", attributes.get(SemanticAnalysisResult.ATTR_FOLLOW_UP_MODE));
        assertFalse(attributes.containsKey(SemanticAnalysisResult.ATTR_SUMMARY));
        assertFalse(attributes.containsKey(SemanticAnalysisResult.ATTR_PAYLOAD));
        assertFalse(attributes.containsKey(SemanticAnalysisResult.ATTR_KEYWORDS));
        assertThrows(UnsupportedOperationException.class, () -> attributes.put("x", "y"));
    }

    @Test
    void shouldRenderPayloadInStableKeyOrderRegardlessOfMapImplementation() {
        Map<String, Object> linkedPayload = new LinkedHashMap<>();
        linkedPayload.put("zeta", "last");
        linkedPayload.put("alpha", "first");

        Map<String, Object> hashPayload = new HashMap<>();
        hashPayload.put("alpha", "first");
        hashPayload.put("zeta", "last");

        SemanticAnalysisResult fromLinked = new SemanticAnalysisResult(
                "local",
                "intent",
                "rewritten",
                "todo.create",
                linkedPayload,
                List.of(),
                "summary",
                0.7
        );
        SemanticAnalysisResult fromHash = new SemanticAnalysisResult(
                "local",
                "intent",
                "rewritten",
                "todo.create",
                hashPayload,
                List.of(),
                "summary",
                0.7
        );

        assertTrue(fromLinked.toPromptSummary().contains("- payload: {alpha=first, zeta=last}"));
        assertTrue(fromHash.toPromptSummary().contains("- payload: {alpha=first, zeta=last}"));
    }

    @Test
    void shouldRenderPromptSummaryWhenAnySemanticFieldsExist() {
        SemanticAnalysisResult result = new SemanticAnalysisResult(
                null,
                "创建待办",
                "请创建一个待办",
                "todo.create",
                Map.of("task", "提交周报"),
                List.of("待办", "周报"),
                "提取了关键任务",
                0.934
        );

        String prompt = result.toPromptSummary();

        assertTrue(prompt.contains("- intent: 创建待办"));
        assertTrue(prompt.contains("- intentType: tool-call"));
        assertTrue(prompt.contains("- suggestedSkill: todo.create"));
        assertTrue(prompt.contains("- toolRequired: true"));
        assertTrue(prompt.contains("- contextScope: standalone"));
        assertTrue(prompt.contains("- memoryOperation: none"));
        assertTrue(prompt.contains("- followUpMode: standalone"));
        assertTrue(prompt.contains("- taskFocus: 提交周报"));
        assertTrue(prompt.contains("- rewrittenInput: 请创建一个待办"));
        assertTrue(prompt.contains("- keywords: 待办, 周报"));
        assertTrue(prompt.contains("- summary: 提取了关键任务"));
        assertTrue(prompt.contains("- payload: {task=提交周报}"));
        assertTrue(prompt.contains("- source: unknown"));
        assertTrue(prompt.contains("- confidence: 0.93"));
    }

    @Test
    void shouldDeriveContinuationMetadataFromFollowUpInputs() {
        SemanticAnalysisResult result = new SemanticAnalysisResult(
                "heuristic",
                "延续当前任务并按已有方案执行",
                "继续推进：提交周报",
                "todo.create",
                Map.of("task", "提交周报", "dueDate", "周五前"),
                List.of("继续", "周报"),
                "用户希望继续推进当前事项：提交周报",
                0.84
        );

        assertEquals("continuation", result.contextScope());
        assertEquals("continuation", result.followUpMode());
        assertEquals("提交周报", result.taskFocus());
        assertEquals("提交周报", result.asAttributes().get(SemanticAnalysisResult.ATTR_TASK_FOCUS));
    }

    @Test
    void shouldDeriveMemoryRecallMetadata() {
        SemanticAnalysisResult result = new SemanticAnalysisResult(
                "llm",
                "根据记忆回顾之前的待办",
                "根据记忆回顾之前的待办",
                "",
                Map.of(),
                List.of("记忆", "待办"),
                "用户要求根据记忆回顾历史信息",
                0.81
        );

        assertEquals("memory-recall", result.intentType());
        assertEquals("memory", result.contextScope());
        assertEquals("recall", result.memoryOperation());
        assertFalse(result.toolRequired());
    }

    @Test
    void shouldUseCandidateIntentConfidenceAsEffectiveConfidence() {
        SemanticAnalysisResult result = new SemanticAnalysisResult(
                "llm",
                "创建待办",
                "帮我弄个待办",
                "todo.create",
                Map.of(),
                List.of("待办"),
                "用户想创建待办",
                0.42,
                List.of(
                        new SemanticAnalysisResult.CandidateIntent("todo.create", 0.86),
                        new SemanticAnalysisResult.CandidateIntent("eq.coach", 0.35)
                )
        );

        assertEquals(0.86, result.effectiveConfidence());
        assertEquals(0.86, result.confidenceForSkill("todo.create"));
        assertTrue(result.isConfident(0.72));
    }

    @Test
    void shouldKeepDirectConfidenceWhenCandidateIntentDoesNotMatchSuggestedSkill() {
        SemanticAnalysisResult result = new SemanticAnalysisResult(
                "llm",
                "创建待办",
                "帮我弄个待办",
                "todo.create",
                Map.of(),
                List.of("待办"),
                "用户想创建待办",
                0.61,
                List.of(new SemanticAnalysisResult.CandidateIntent("eq.coach", 0.95))
        );

        assertEquals(0.0, result.confidenceForSkill("todo.create"));
        assertEquals(0.61, result.effectiveConfidence());
        assertFalse(result.isConfident(0.70));
    }
}
