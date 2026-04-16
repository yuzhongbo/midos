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
        assertEquals("none", attributes.get(SemanticAnalysisResult.ATTR_INTENT_STATE));
        assertEquals("chat", attributes.get(SemanticAnalysisResult.ATTR_INTENT_PHASE));
        assertEquals("standalone", attributes.get(SemanticAnalysisResult.ATTR_THREAD_RELATION));
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
        assertTrue(prompt.contains("- intentState: start"));
        assertTrue(prompt.contains("- intentPhase: execution"));
        assertTrue(prompt.contains("- threadRelation: new"));
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
        assertEquals("continue", result.intentState());
        assertEquals("execution", result.intentPhase());
        assertEquals("continue", result.threadRelation());
        assertEquals("continuation", result.followUpMode());
        assertEquals("提交周报", result.taskFocus());
        assertEquals("提交周报", result.asAttributes().get(SemanticAnalysisResult.ATTR_TASK_FOCUS));
    }

    @Test
    void shouldClassifySelectionFollowUpsAndDetectAmbiguousChoices() {
        SemanticAnalysisResult result = new SemanticAnalysisResult(
                "llm",
                "在当前事项中选择候选方案或执行方向",
                "第二种吧，当前事项：提交周报",
                "task.manage",
                Map.of("task", "提交周报"),
                List.of("第二种", "周报"),
                "用户在为当前事项选择执行方案：提交周报",
                0.68,
                List.of(
                        new SemanticAnalysisResult.CandidateIntent("task.manage", 0.74),
                        new SemanticAnalysisResult.CandidateIntent("learning.plan", 0.71),
                        new SemanticAnalysisResult.CandidateIntent("docs.lookup", 0.34)
                )
        );

        assertEquals("selection", result.followUpMode());
        assertEquals("update", result.intentState());
        assertEquals("decision", result.intentPhase());
        assertEquals("continue", result.threadRelation());
        assertTrue(result.hasAmbiguousSkillChoice());
        assertEquals(
                List.of("task.manage", "learning.plan"),
                result.ambiguousCandidateIntents().stream()
                        .limit(2)
                        .map(SemanticAnalysisResult.CandidateIntent::intent)
                        .toList()
        );
    }

    @Test
    void shouldDetectPauseAndCompleteIntentStates() {
        SemanticAnalysisResult pause = new SemanticAnalysisResult(
                "heuristic",
                "先这样，暂停这个任务",
                "先这样，暂停这个任务",
                "",
                Map.of("task", "整理季度复盘"),
                List.of("暂停"),
                "用户希望先暂停当前任务",
                0.72
        );
        SemanticAnalysisResult done = new SemanticAnalysisResult(
                "heuristic",
                "这个任务已经完成了",
                "这个任务已经完成了",
                "",
                Map.of("task", "提交周报"),
                List.of("完成"),
                "用户表示当前任务已完成",
                0.72
        );

        assertEquals("pause", pause.intentState());
        assertEquals("complete", done.intentState());
        assertEquals("decision", pause.intentPhase());
        assertEquals("reporting", done.intentPhase());
    }

    @Test
    void shouldDeriveBlockingPlanningAndThreadRelationMetadata() {
        SemanticAnalysisResult blocked = new SemanticAnalysisResult(
                "heuristic",
                "围绕当前任务说明阻塞并寻求推进",
                "当前事项遇到阻塞：提交周报",
                "",
                Map.of("task", "提交周报"),
                List.of("卡住", "报错"),
                "用户表示当前事项遇到阻塞",
                0.79
        );
        SemanticAnalysisResult planning = new SemanticAnalysisResult(
                "heuristic",
                "围绕当前任务整理方案或步骤",
                "为当前事项制定下一步方案：提交周报",
                "",
                Map.of("taskFocus", "提交周报"),
                List.of("方案", "步骤"),
                "用户想先明确方案",
                0.77
        );
        SemanticAnalysisResult resume = new SemanticAnalysisResult(
                "heuristic",
                "回到刚才那个任务继续处理",
                "回到刚才那个周报",
                "",
                Map.of("task", "提交周报"),
                List.of("回到", "刚才"),
                "回到上一个任务",
                0.75
        );

        assertEquals("blocked", blocked.intentState());
        assertEquals("blocking", blocked.intentPhase());
        assertEquals("planning", planning.intentPhase());
        assertEquals("提交周报", planning.taskFocus());
        assertEquals("resume", resume.threadRelation());
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
