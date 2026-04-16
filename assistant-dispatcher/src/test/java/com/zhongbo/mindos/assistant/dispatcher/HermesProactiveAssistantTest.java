package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.common.dto.PromptMemoryContextDto;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HermesProactiveAssistantTest {

    @Test
    void shouldAppendNextStepHintForClearTaskThread() {
        HermesProactiveAssistant assistant = new HermesProactiveAssistant();
        HermesDecisionContext context = decisionContext(
                new SemanticAnalysisResult(
                        "heuristic",
                        "继续当前任务",
                        "继续推进：提交周报",
                        "todo.create",
                        Map.of("task", "提交周报"),
                        List.of("继续", "周报"),
                        "继续推进周报",
                        0.86
                ),
                Map.of(
                        "activeTask", "提交周报",
                        "activeTaskNextAction", "同步项目风险"
                )
        );

        HermesProactiveAssistant.Augmentation augmentation = assistant.maybeAugment(
                "继续",
                context,
                SkillResult.success("todo.create", "好的，已经接上这个任务。")
        );

        assertTrue(augmentation.applied());
        assertTrue(augmentation.result().output().contains("下一步建议：先同步项目风险。"));
        assertTrue(augmentation.result().output().contains("需要的话我可以直接继续做这一步"));
    }

    @Test
    void shouldSkipRealtimeReplies() {
        HermesProactiveAssistant assistant = new HermesProactiveAssistant();
        HermesDecisionContext context = decisionContext(
                new SemanticAnalysisResult(
                        "heuristic",
                        "获取最新新闻",
                        "查看今天新闻",
                        "news_search",
                        Map.of("query", "今天新闻", "domain", "news"),
                        List.of("新闻"),
                        "用户需要最近新闻",
                        0.92
                ),
                Map.of()
        );

        HermesProactiveAssistant.Augmentation augmentation = assistant.maybeAugment(
                "查看今天新闻",
                context,
                SkillResult.success("news_search", "[news_search]\n关键词: 新闻\n摘要: builtin news result")
        );

        assertFalse(augmentation.applied());
    }

    @Test
    void shouldSkipWhenReplyAlreadyContainsNextStepHint() {
        HermesProactiveAssistant assistant = new HermesProactiveAssistant();
        HermesDecisionContext context = decisionContext(
                new SemanticAnalysisResult(
                        "heuristic",
                        "继续当前任务",
                        "继续推进：提交周报",
                        "todo.create",
                        Map.of("task", "提交周报"),
                        List.of("继续", "周报"),
                        "继续推进周报",
                        0.86
                ),
                Map.of("activeTask", "提交周报")
        );

        HermesProactiveAssistant.Augmentation augmentation = assistant.maybeAugment(
                "继续",
                context,
                SkillResult.success("todo.create", "好的。下一步建议：先同步项目风险。")
        );

        assertFalse(augmentation.applied());
    }

    private HermesDecisionContext decisionContext(SemanticAnalysisResult semanticAnalysis, Map<String, Object> attributes) {
        SkillContext skillContext = new SkillContext("u1", "继续", attributes);
        return new HermesDecisionContext(
                "u1",
                "继续",
                "继续",
                Map.of(),
                true,
                DispatcherAnswerMode.BALANCED,
                new PromptMemoryContextDto("", "", "", Map.of(), List.of()),
                "",
                List.of(),
                List.of(),
                semanticAnalysis,
                Map.of(),
                Map.of(),
                skillContext
        );
    }
}
