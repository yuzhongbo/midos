package com.zhongbo.mindos.assistant.dispatcher.agent.multiagent;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.dispatcher.agent.procedure.ProceduralMemory;
import com.zhongbo.mindos.assistant.memory.MemoryGateway;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryFacade;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.memory.MemoryWriteBatch;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.memory.MemoryWriteOperation;
import com.zhongbo.mindos.assistant.memory.graph.MemoryNode;
import com.zhongbo.mindos.assistant.memory.model.ConversationTurn;
import com.zhongbo.mindos.assistant.memory.model.SkillUsageStats;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraph;
import com.zhongbo.mindos.assistant.dispatcher.agent.taskgraph.TaskGraphExecutionResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class DefaultMemoryAgent implements MemoryAgent {

    static final String WRITE_BATCH_KEY = "multiAgent.memory.writeBatch";

    private final DispatcherMemoryFacade dispatcherMemoryFacade;

    @Autowired
    public DefaultMemoryAgent(DispatcherMemoryFacade dispatcherMemoryFacade,
                              com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryCommandService memoryCommandService) {
        this.dispatcherMemoryFacade = dispatcherMemoryFacade;
    }

    public DefaultMemoryAgent(MemoryGateway memoryGateway,
                              com.zhongbo.mindos.assistant.memory.MemoryFacade memoryFacade,
                              ProceduralMemory proceduralMemory) {
        this(new DispatcherMemoryFacade(memoryFacade, memoryGateway, null, proceduralMemory),
                new com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryCommandService(memoryGateway, null, proceduralMemory));
    }

    @Override
    public String name() {
        return "memory-agent";
    }

    @Override
    public AgentRole role() {
        return AgentRole.MEMORY;
    }

    @Override
    public AgentResponse observe(AgentMessage message, AgentContext context) {
        if (message == null || message.type() == null) {
            return AgentResponse.completed(
                    name(),
                    SkillResult.failure(name(), "missing memory task"),
                    false,
                    List.of(),
                    Map.of(),
                    "missing memory task"
            );
        }
        return switch (message.type()) {
            case MEMORY_READ -> handleRead(message, context);
            case MEMORY_WRITE -> handleWrite(message, context);
            default -> AgentResponse.unsupported(name(), "observe");
        };
    }

    @Override
    public AgentResponse plan(AgentMessage message, AgentContext context) {
        return AgentResponse.unsupported(name(), "plan");
    }

    @Override
    public AgentResponse execute(AgentMessage message, AgentContext context) {
        return AgentResponse.unsupported(name(), "execute");
    }

    private AgentResponse handleRead(AgentMessage message, AgentContext context) {
        String userId = firstNonBlank(message.userId(), context.userId());
        Map<String, Object> payload = message.payloadMap();
        String focusKey = firstNonBlank(stringValue(payload.get("focusKey")), firstNonBlank(context.decision() == null ? "" : context.decision().target(), context.decision() == null ? "" : context.decision().intent()));
        List<String> requestedKeys = toStringList(payload.get("requestedKeys"));
        Map<String, Object> effectiveParams = mapValue(payload.get("effectiveParams"));
        Set<String> keysToInfer = new LinkedHashSet<>(requestedKeys);
        if (!focusKey.isBlank()) {
            keysToInfer.add(focusKey);
        }

        List<ConversationTurn> recentHistory = dispatcherMemoryFacade.recentHistory(userId);
        List<SkillUsageStats> skillUsageStats = dispatcherMemoryFacade.getSkillUsageStats(userId);
        List<MemoryNode> relatedNodes = focusKey.isBlank() ? List.of() : dispatcherMemoryFacade.queryRelated(userId, focusKey);
        List<ProceduralMemory.ReusableProcedure> reusableProcedures = new ArrayList<>();
        if (!focusKey.isBlank()) {
            dispatcherMemoryFacade.matchReusableProcedure(
                    userId,
                    context.userInput(),
                    focusKey,
                    effectiveParams
            ).ifPresent(reusableProcedures::add);
        }

        Map<String, Object> inferredFacts = new LinkedHashMap<>();
        for (String key : keysToInfer) {
            if (key == null || key.isBlank() || inferredFacts.containsKey(key)) {
                continue;
            }
            Optional<Object> inferred = dispatcherMemoryFacade.infer(userId, key, context.userInput());
            inferred.ifPresent(value -> inferredFacts.put(key, value));
        }

        SharedMemorySnapshot snapshot = new SharedMemorySnapshot(
                userId,
                recentHistory,
                skillUsageStats,
                relatedNodes,
                reusableProcedures,
                inferredFacts,
                Map.of(
                        "focusKey", focusKey,
                        "requestedKeys", List.copyOf(keysToInfer)
                )
        );

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put(SharedMemorySnapshot.CONTEXT_KEY, snapshot);
        patch.putAll(inferredFacts);
        patch.put("multiAgent.memory.focusKey", focusKey);

        return AgentResponse.progress(name(), "memory snapshot ready", List.of(), patch);
    }

    private AgentResponse handleWrite(AgentMessage message, AgentContext context) {
        String userId = firstNonBlank(message.userId(), context.userId());
        Map<String, Object> payload = message.payloadMap();
        String kind = firstNonBlank(stringValue(payload.get("kind")), "skill-usage");
        SkillResult result = skillResult(payload.get("result"));
        if (result == null) {
            result = skillResult(payload.get("finalResult"));
        }
        TaskGraph graph = objectValue(payload.get("graph"), TaskGraph.class);
        TaskGraphExecutionResult graphResult = objectValue(payload.get("graphResult"), TaskGraphExecutionResult.class);
        Map<String, Object> contextAttributes = mapValue(payload.get("contextAttributes"));
        String intent = firstNonBlank(stringValue(payload.get("intent")), context.decision() == null ? "" : context.decision().intent());
        String trigger = firstNonBlank(stringValue(payload.get("trigger")), context.userInput());
        String skillName = result == null ? firstNonBlank(stringValue(payload.get("skillName")), graphResult == null || graphResult.finalResult() == null ? "" : graphResult.finalResult().skillName()) : result.skillName();
        boolean success = result != null && result.success();

        if (graphResult != null && graphResult.finalResult() != null) {
            result = graphResult.finalResult();
            success = result.success();
            skillName = result.skillName();
        }

        Map<String, Object> patch = new LinkedHashMap<>();

        if (result != null) {
            patch.put(WRITE_BATCH_KEY, MemoryWriteBatch.of(
                    new MemoryWriteOperation.RecordSkillUsage(skillName, trigger, success),
                    result.output() == null || result.output().isBlank()
                            ? null
                            : new MemoryWriteOperation.AppendAssistantConversation(result.output())
            ));
        }

        if ("procedure".equalsIgnoreCase(kind) && success && graph != null) {
            MemoryWriteBatch existing = objectValue(patch.get(WRITE_BATCH_KEY), MemoryWriteBatch.class);
            MemoryWriteBatch procedureWrite = MemoryWriteBatch.of(
                    new MemoryWriteOperation.RecordProcedureSuccess(intent, trigger, graph, contextAttributes)
            );
            patch.put(WRITE_BATCH_KEY, existing == null ? procedureWrite : existing.merge(procedureWrite));
        }

        patch.put("multiAgent.memory.lastWriteKind", kind);
        patch.put("multiAgent.memory.lastSkill", skillName);
        patch.put("multiAgent.memory.lastSuccess", success);

        return AgentResponse.completed(
                name(),
                SkillResult.success(name(), "memory updated"),
                false,
                List.of(),
                patch,
                "memory updated"
        );
    }

    private Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> rawMap) || rawMap.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (entry.getKey() == null || String.valueOf(entry.getKey()).isBlank()) {
                continue;
            }
            if (entry.getValue() == null) {
                continue;
            }
            normalized.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return normalized.isEmpty() ? Map.of() : Map.copyOf(normalized);
    }

    private List<String> toStringList(Object value) {
        if (!(value instanceof List<?> rawList) || rawList.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (Object entry : rawList) {
            String text = stringValue(entry);
            if (!text.isBlank()) {
                normalized.add(text);
            }
        }
        return normalized.isEmpty() ? List.of() : List.copyOf(normalized);
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        if (second != null && !second.isBlank()) {
            return second.trim();
        }
        return "";
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private SkillResult skillResult(Object value) {
        if (value instanceof SkillResult result) {
            return result;
        }
        return null;
    }

    private <T> T objectValue(Object value, Class<T> type) {
        if (type == null || value == null || !type.isInstance(value)) {
            return null;
        }
        return type.cast(value);
    }
}
