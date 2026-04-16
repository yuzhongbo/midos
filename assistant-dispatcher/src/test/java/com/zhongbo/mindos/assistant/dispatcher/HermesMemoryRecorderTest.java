package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryCommandService;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryFacade;
import com.zhongbo.mindos.assistant.memory.MemoryGateway;
import com.zhongbo.mindos.assistant.memory.model.PreferenceProfile;
import com.zhongbo.mindos.assistant.skill.semantic.SemanticAnalysisResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HermesMemoryRecorderTest {

    @Test
    void shouldWriteReusableTaskFactMemoryForStructuredTaskExecution() {
        DispatcherMemoryFacade dispatcherMemoryFacade = new DispatcherMemoryFacade((MemoryGateway) null, null, null);
        RecordingMemoryCommandService commandService = new RecordingMemoryCommandService(dispatcherMemoryFacade);
        HermesMemoryRecorder recorder = new HermesMemoryRecorder(dispatcherMemoryFacade, commandService, null, null);

        SemanticAnalysisResult semanticAnalysis = new SemanticAnalysisResult(
                "heuristic",
                "创建待办或提醒事项",
                "帮我创建一个待办，周五前提交周报",
                "todo.create",
                Map.of("task", "提交周报", "dueDate", "周五前", "project", "运营周报"),
                List.of("待办", "周报"),
                "用户希望创建周报待办",
                0.88
        );

        recorder.record(
                "u1",
                "帮我创建一个待办，周五前提交周报",
                SkillResult.success("todo.create", "已创建"),
                Map.of(),
                semanticAnalysis,
                "todo.create",
                true,
                true
        );

        SemanticWrite taskFact = commandService.semanticWrites().stream()
                .filter(write -> "task".equals(write.bucket()))
                .findFirst()
                .orElseThrow();

        assertTrue(taskFact.text().contains("[任务事实]"));
        assertTrue(taskFact.text().contains("当前事项：提交周报"));
        assertTrue(taskFact.text().contains("截止时间：周五前"));
        assertTrue(taskFact.text().contains("项目：运营周报"));
    }

    @Test
    void shouldSkipTaskFactMemoryForRealtimeSearch() {
        DispatcherMemoryFacade dispatcherMemoryFacade = new DispatcherMemoryFacade((MemoryGateway) null, null, null);
        RecordingMemoryCommandService commandService = new RecordingMemoryCommandService(dispatcherMemoryFacade);
        HermesMemoryRecorder recorder = new HermesMemoryRecorder(dispatcherMemoryFacade, commandService, null, null);

        SemanticAnalysisResult semanticAnalysis = new SemanticAnalysisResult(
                "heuristic",
                "获取最新新闻资讯",
                "帮我看今天的国际新闻",
                "news_search",
                Map.of("query", "今天的国际新闻", "domain", "news"),
                List.of("新闻", "国际"),
                "用户请求获取实时新闻资讯",
                0.90
        );

        recorder.record(
                "u1",
                "帮我看今天的国际新闻",
                SkillResult.success("news_search", "已完成"),
                Map.of(),
                semanticAnalysis,
                "news_search",
                true,
                true
        );

        assertFalse(commandService.semanticWrites().stream().anyMatch(write -> "task".equals(write.bucket())));
        assertEquals(1, commandService.semanticWrites().stream().filter(write -> "conversation-rollup".equals(write.bucket())).count());
    }

    private record SemanticWrite(String text, String bucket) {
    }

    private static final class RecordingMemoryCommandService extends DispatcherMemoryCommandService {
        private final List<SemanticWrite> semanticWrites = new ArrayList<>();

        private RecordingMemoryCommandService(DispatcherMemoryFacade dispatcherMemoryFacade) {
            super(dispatcherMemoryFacade, null);
        }

        @Override
        public void appendUserConversation(String userId, String userInput) {
        }

        @Override
        public void appendAssistantConversation(String userId, String reply) {
        }

        @Override
        public void writeSemantic(String userId, String text, List<Double> embedding, String bucket) {
            semanticWrites.add(new SemanticWrite(text, bucket));
        }

        @Override
        public PreferenceProfile updatePreferenceProfile(String userId, PreferenceProfile profile) {
            return profile;
        }

        @Override
        public void recordSkillUsage(String userId, String skillName, String input, boolean success) {
        }

        private List<SemanticWrite> semanticWrites() {
            return semanticWrites;
        }
    }
}
