package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryCommandService;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryFacade;
import com.zhongbo.mindos.assistant.memory.MemoryGateway;
import com.zhongbo.mindos.assistant.memory.graph.GraphMemory;
import com.zhongbo.mindos.assistant.memory.graph.MemoryEdge;
import com.zhongbo.mindos.assistant.memory.graph.MemoryNode;
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
        DispatcherMemoryFacade dispatcherMemoryFacade = new DispatcherMemoryFacade((MemoryGateway) null, new GraphMemory(), null);
        RecordingMemoryCommandService commandService = new RecordingMemoryCommandService(dispatcherMemoryFacade);
        HermesMemoryRecorder recorder = new HermesMemoryRecorder(dispatcherMemoryFacade, commandService, null, new HermesDecisionPolicy(null, null));

        SemanticAnalysisResult semanticAnalysis = new SemanticAnalysisResult(
                "heuristic",
                "创建待办或提醒事项",
                "帮我创建一个待办，周五前提交周报",
                "todo.create",
                Map.of(
                        "task", "提交周报",
                        "dueDate", "周五前",
                        "project", "运营周报",
                        "artifact", "风险说明版周报",
                        "audience", "管理层",
                        "requirements", "三页内",
                        "source", "引用本周指标",
                        "successCriteria", "负责人确认可发"
                ),
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
        assertTrue(taskFact.text().contains("交付物：风险说明版周报"));
        assertTrue(taskFact.text().contains("受众：管理层"));
        assertTrue(taskFact.text().contains("约束：三页内"));
        assertTrue(taskFact.text().contains("来源要求：引用本周指标"));
        assertTrue(taskFact.text().contains("完成标准：负责人确认可发"));
        assertTrue(commandService.semanticWrites().stream().anyMatch(write -> write.text().contains("[任务状态]")));
        assertTrue(commandService.graphNodes().stream().anyMatch(node ->
                "hermes.execution".equals(node.type()) && "todo.create".equals(node.data().get("skillName"))));
        assertTrue(commandService.graphNodes().stream().anyMatch(node ->
                "hermes.task".equals(node.type()) && "提交周报".equals(node.data().get("task"))));
        assertTrue(commandService.graphNodes().stream().anyMatch(node ->
                "hermes.skill".equals(node.type()) && "todo.create".equals(node.data().get("skillName"))));
        assertTrue(commandService.graphEdges().stream().anyMatch(edge -> "latest-result".equals(edge.relation())));
        assertTrue(commandService.graphEdges().stream().anyMatch(edge -> "uses-skill".equals(edge.relation())));
        assertTrue(commandService.graphEdges().stream().anyMatch(edge -> "result-of".equals(edge.relation())));
    }

    @Test
    void shouldSkipTaskFactMemoryForRealtimeSearch() {
        DispatcherMemoryFacade dispatcherMemoryFacade = new DispatcherMemoryFacade((MemoryGateway) null, null, null);
        RecordingMemoryCommandService commandService = new RecordingMemoryCommandService(dispatcherMemoryFacade);
        HermesMemoryRecorder recorder = new HermesMemoryRecorder(dispatcherMemoryFacade, commandService, null, new HermesDecisionPolicy(null, null));

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

    @Test
    void shouldWriteTaskStateAndLearningSignalForContinuationSuccess() {
        DispatcherMemoryFacade dispatcherMemoryFacade = new DispatcherMemoryFacade((MemoryGateway) null, null, null);
        RecordingMemoryCommandService commandService = new RecordingMemoryCommandService(dispatcherMemoryFacade);
        HermesMemoryRecorder recorder = new HermesMemoryRecorder(dispatcherMemoryFacade, commandService, null, new HermesDecisionPolicy(null, null));

        SemanticAnalysisResult semanticAnalysis = new SemanticAnalysisResult(
                "heuristic",
                "延续当前任务并按已有方案执行",
                "继续推进：提交周报",
                "todo.create",
                Map.of("task", "提交周报"),
                List.of("继续", "周报"),
                "用户希望继续推进当前事项：提交周报",
                0.84
        );

        recorder.record(
                "u1",
                "开始吧",
                SkillResult.success("todo.create", "已推进"),
                Map.of(),
                semanticAnalysis,
                "todo.create",
                true,
                true
        );

        assertTrue(commandService.semanticWrites().stream().anyMatch(write ->
                "task".equals(write.bucket())
                        && write.text().contains("[任务状态]")
                        && write.text().contains("状态：进行中")));
        assertTrue(commandService.semanticWrites().stream().anyMatch(write ->
                "task".equals(write.bucket())
                        && write.text().contains("[学习信号]")
                        && write.text().contains("简短跟进延续当前任务")));
    }

    @Test
    void shouldWriteBlockedTaskStateAndLearningSignalForBlockingFollowUp() {
        DispatcherMemoryFacade dispatcherMemoryFacade = new DispatcherMemoryFacade((MemoryGateway) null, null, null);
        RecordingMemoryCommandService commandService = new RecordingMemoryCommandService(dispatcherMemoryFacade);
        HermesMemoryRecorder recorder = new HermesMemoryRecorder(dispatcherMemoryFacade, commandService, null, new HermesDecisionPolicy(null, null));

        SemanticAnalysisResult semanticAnalysis = new SemanticAnalysisResult(
                "heuristic",
                "围绕当前任务说明阻塞并寻求推进",
                "当前事项遇到阻塞：提交周报",
                "",
                Map.of("task", "提交周报", "blocker", "接口一直报错"),
                List.of("卡住", "报错"),
                "用户表示当前事项遇到阻塞",
                0.81
        );

        recorder.record(
                "u1",
                "卡住了，接口一直报错",
                SkillResult.success("llm", "我先帮你定位阻塞点。"),
                Map.of(),
                semanticAnalysis,
                null,
                null,
                true
        );

        assertTrue(commandService.semanticWrites().stream().anyMatch(write ->
                "task".equals(write.bucket())
                        && write.text().contains("[任务状态]")
                        && write.text().contains("状态：受阻")
                        && write.text().contains("阻塞点：接口一直报错")));
        assertTrue(commandService.semanticWrites().stream().anyMatch(write ->
                "task".equals(write.bucket())
                        && write.text().contains("[学习信号]")
                        && write.text().contains("先定位卡点")));
    }

    private record SemanticWrite(String text, String bucket) {
    }

    private static final class RecordingMemoryCommandService extends DispatcherMemoryCommandService {
        private final List<SemanticWrite> semanticWrites = new ArrayList<>();
        private final List<MemoryNode> graphNodes = new ArrayList<>();
        private final List<MemoryEdge> graphEdges = new ArrayList<>();

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

        @Override
        public MemoryNode upsertGraphNode(String userId, MemoryNode node) {
            graphNodes.add(node);
            return node;
        }

        @Override
        public MemoryEdge linkGraph(String userId,
                                    String fromNodeId,
                                    String relation,
                                    String toNodeId,
                                    double weight,
                                    Map<String, Object> metadata) {
            MemoryEdge edge = new MemoryEdge(fromNodeId, toNodeId, relation, weight, metadata, null);
            graphEdges.add(edge);
            return edge;
        }

        private List<MemoryNode> graphNodes() {
            return graphNodes;
        }

        private List<MemoryEdge> graphEdges() {
            return graphEdges;
        }
    }
}
