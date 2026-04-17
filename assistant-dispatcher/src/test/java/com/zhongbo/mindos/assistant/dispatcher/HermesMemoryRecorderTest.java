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

    @Test
    void shouldRecordCanonicalSkillIdentityForCapabilityAliasOutcome() {
        DispatcherMemoryFacade dispatcherMemoryFacade = new DispatcherMemoryFacade((MemoryGateway) null, new GraphMemory(), null);
        RecordingMemoryCommandService commandService = new RecordingMemoryCommandService(dispatcherMemoryFacade);
        HermesMemoryRecorder recorder = new HermesMemoryRecorder(dispatcherMemoryFacade, commandService, null, new HermesDecisionPolicy(null, null));

        SemanticAnalysisResult semanticAnalysis = new SemanticAnalysisResult(
                "heuristic",
                "创建待办或提醒事项",
                "帮我创建一个待办，周五前提交周报",
                "task.manage",
                Map.of("task", "提交周报"),
                List.of("待办", "周报"),
                "用户希望创建周报待办",
                0.88
        );
        SkillResult result = SkillResult.success("todo.create", "已创建")
                .withMetadata(Map.of(
                        HermesSkillIdentity.DECISION_TARGET_METADATA_KEY, "task.manage",
                        HermesSkillIdentity.EXECUTION_TARGET_METADATA_KEY, "todo.create",
                        HermesSkillIdentity.CANONICAL_SKILL_METADATA_KEY, "todo.create"
                ));

        recorder.record(
                "u1",
                "帮我创建一个待办，周五前提交周报",
                result,
                Map.of(),
                semanticAnalysis,
                "task.manage",
                true,
                true
        );

        assertTrue(commandService.skillUsages().stream().anyMatch(usage -> "todo.create".equals(usage.skillName())));
        assertFalse(commandService.skillUsages().stream().anyMatch(usage -> "task.manage".equals(usage.skillName())));
        assertTrue(commandService.semanticWrites().stream().anyMatch(write ->
                "conversation-rollup".equals(write.bucket()) && write.text().contains("执行方式：todo.create")));
        assertTrue(commandService.graphNodes().stream().anyMatch(node ->
                "hermes.skill".equals(node.type())
                        && "todo.create".equals(node.data().get("skillName"))
                        && "task.manage".equals(node.data().get("decisionTarget"))
                        && "todo.create".equals(node.data().get("executionTarget"))));
        assertTrue(commandService.graphEdges().stream().anyMatch(edge ->
                "todo.create".equals(edge.data().get("canonicalSkill"))));
    }

    @Test
    void shouldRecordStructuredDetailArtifactsInExecutionGraph() {
        DispatcherMemoryFacade dispatcherMemoryFacade = new DispatcherMemoryFacade((MemoryGateway) null, new GraphMemory(), null);
        RecordingMemoryCommandService commandService = new RecordingMemoryCommandService(dispatcherMemoryFacade);
        HermesMemoryRecorder recorder = new HermesMemoryRecorder(dispatcherMemoryFacade, commandService, null, new HermesDecisionPolicy(null, null));

        SemanticAnalysisResult semanticAnalysis = new SemanticAnalysisResult(
                "heuristic",
                "搜索官方文档",
                "帮我查 Spring Boot RestClient 官方文档",
                "docs.lookup",
                Map.of("query", "Spring Boot RestClient 官方文档"),
                List.of("文档", "RestClient"),
                "用户希望搜索官方文档并查看最相关详情",
                0.91
        );
        SkillResult result = SkillResult.success("mcp.docs.searchDocs", "我先看了和你问题最接近的一条网页（最相关详情）")
                .withArtifacts(Map.of(
                        "query", "Spring Boot RestClient 官方文档",
                        "detailApplied", Boolean.TRUE,
                        "detailTitle", "Spring RestClient Reference",
                        "detailLink", "https://docs.spring.io/restclient",
                        "detailSummary", "官方文档参考页"
                ))
                .withMetadata(Map.of(
                        HermesSkillIdentity.DECISION_TARGET_METADATA_KEY, "docs.lookup",
                        HermesSkillIdentity.EXECUTION_TARGET_METADATA_KEY, "mcp.docs.searchDocs",
                        HermesSkillIdentity.CANONICAL_SKILL_METADATA_KEY, "mcp.docs.searchDocs",
                        "systemWorkflow", "skill-graph",
                        "systemWorkflowRecipe", "docs.lookup.detail"
                ));

        recorder.record(
                "u1",
                "帮我查 Spring Boot RestClient 官方文档",
                result,
                Map.of(),
                semanticAnalysis,
                "docs.lookup",
                true,
                true
        );

        assertTrue(commandService.graphNodes().stream().anyMatch(node ->
                "hermes.execution".equals(node.type())
                        && "Spring RestClient Reference".equals(node.data().get("detailTitle"))
                        && "https://docs.spring.io/restclient".equals(node.data().get("detailLink"))
                        && "官方文档参考页".equals(node.data().get("detailSummary"))
                        && "docs.lookup.detail".equals(node.data().get("workflowRecipe"))));
    }

    private record SemanticWrite(String text, String bucket) {
    }

    private record RecordedSkillUsage(String skillName, boolean success) {
    }

    private static final class RecordingMemoryCommandService extends DispatcherMemoryCommandService {
        private final List<SemanticWrite> semanticWrites = new ArrayList<>();
        private final List<MemoryNode> graphNodes = new ArrayList<>();
        private final List<MemoryEdge> graphEdges = new ArrayList<>();
        private final List<RecordedSkillUsage> skillUsages = new ArrayList<>();

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
            skillUsages.add(new RecordedSkillUsage(skillName, success));
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

        private List<RecordedSkillUsage> skillUsages() {
            return skillUsages;
        }
    }
}
