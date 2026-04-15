package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.common.dto.ExecutionTraceDto;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.ReflectionAgent;
import com.zhongbo.mindos.assistant.dispatcher.agent.autonomous.ReflectionResult;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryCommandService;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryFacade;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.memory.MemoryWriteBatch;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.memory.MemoryWriteOperation;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.memory.OrchestratorMemoryWriter;
import com.zhongbo.mindos.assistant.memory.MemoryGateway;
import com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@ConditionalOnProperty(name = "mindos.autonomous.runtime.enabled", havingValue = "true")
public class PostExecutionMemoryRecorder {

    private final DispatcherMemoryFacade dispatcherMemoryFacade;
    private final ReflectionAgent reflectionAgent;
    private final boolean proceduralLoggingEnabled;
    private final boolean postSkillSummaryEnabled;
    private final Set<String> postSkillSummarySkills;
    private final int postSkillSummaryMaxReplyChars;

    public PostExecutionMemoryRecorder(MemoryGateway memoryGateway,
                                       @Value("${mindos.dispatcher.procedural-logging.enabled:true}") boolean proceduralLoggingEnabled,
                                       @Value("${mindos.memory.post-skill-summary.enabled:false}") boolean postSkillSummaryEnabled,
                                       @Value("${mindos.memory.post-skill-summary.skills:teaching.plan,todo.create,eq.coach,code.generate,file.search}") String postSkillSummarySkills,
                                       @Value("${mindos.memory.post-skill-summary.max-reply-chars:280}") int postSkillSummaryMaxReplyChars) {
        this(new DispatcherMemoryFacade(memoryGateway, null, null),
                null,
                new DispatcherMemoryCommandService(memoryGateway, null, null),
                null,
                proceduralLoggingEnabled,
                postSkillSummaryEnabled,
                postSkillSummarySkills,
                postSkillSummaryMaxReplyChars);
    }

    public PostExecutionMemoryRecorder(DispatcherMemoryFacade dispatcherMemoryFacade,
                                       @Value("${mindos.dispatcher.procedural-logging.enabled:true}") boolean proceduralLoggingEnabled,
                                       @Value("${mindos.memory.post-skill-summary.enabled:false}") boolean postSkillSummaryEnabled,
                                       @Value("${mindos.memory.post-skill-summary.skills:teaching.plan,todo.create,eq.coach,code.generate,file.search}") String postSkillSummarySkills,
                                       @Value("${mindos.memory.post-skill-summary.max-reply-chars:280}") int postSkillSummaryMaxReplyChars) {
        this(dispatcherMemoryFacade, null, null, null, proceduralLoggingEnabled, postSkillSummaryEnabled, postSkillSummarySkills, postSkillSummaryMaxReplyChars);
    }

    public PostExecutionMemoryRecorder(MemoryGateway memoryGateway,
                                       ReflectionAgent reflectionAgent,
                                       @Value("${mindos.dispatcher.procedural-logging.enabled:true}") boolean proceduralLoggingEnabled,
                                       @Value("${mindos.memory.post-skill-summary.enabled:false}") boolean postSkillSummaryEnabled,
                                       @Value("${mindos.memory.post-skill-summary.skills:teaching.plan,todo.create,eq.coach,code.generate,file.search}") String postSkillSummarySkills,
                                       @Value("${mindos.memory.post-skill-summary.max-reply-chars:280}") int postSkillSummaryMaxReplyChars) {
        this(new DispatcherMemoryFacade(memoryGateway, null, null),
                reflectionAgent,
                new DispatcherMemoryCommandService(memoryGateway, null, null),
                null,
                proceduralLoggingEnabled,
                postSkillSummaryEnabled,
                postSkillSummarySkills,
                postSkillSummaryMaxReplyChars);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PostExecutionMemoryRecorder(DispatcherMemoryFacade dispatcherMemoryFacade,
                                       ReflectionAgent reflectionAgent,
                                       DispatcherMemoryCommandService memoryCommandService,
                                       OrchestratorMemoryWriter memoryWriter,
                                       @Value("${mindos.dispatcher.procedural-logging.enabled:true}") boolean proceduralLoggingEnabled,
                                       @Value("${mindos.memory.post-skill-summary.enabled:false}") boolean postSkillSummaryEnabled,
                                       @Value("${mindos.memory.post-skill-summary.skills:teaching.plan,todo.create,eq.coach,code.generate,file.search}") String postSkillSummarySkills,
                                       @Value("${mindos.memory.post-skill-summary.max-reply-chars:280}") int postSkillSummaryMaxReplyChars) {
        this.dispatcherMemoryFacade = dispatcherMemoryFacade;
        this.reflectionAgent = reflectionAgent;
        this.proceduralLoggingEnabled = proceduralLoggingEnabled;
        this.postSkillSummaryEnabled = postSkillSummaryEnabled;
        this.postSkillSummarySkills = Set.copyOf(parseCsvList(postSkillSummarySkills));
        this.postSkillSummaryMaxReplyChars = Math.max(80, postSkillSummaryMaxReplyChars);
    }

    public MemoryWriteBatch record(String userId,
                                   String userInput,
                                   SkillResult result,
                                   ExecutionTraceDto trace) {
        return record(userId, userInput, result, trace, null);
    }

    public MemoryWriteBatch record(String userId,
                                   String userInput,
                                   SkillResult result,
                                   ExecutionTraceDto trace,
                                   ReflectionResult reflection) {
        if (result == null) {
            return MemoryWriteBatch.empty();
        }
        return proceduralLoggingWrite(userInput, result)
                .merge(postSkillSummaryWrite(userInput, result))
                .merge(executionTraceWrite(trace))
                .merge(resolveReflectionWrites(userId, userInput, result, trace, reflection));
    }

    private MemoryWriteBatch executionTraceWrite(ExecutionTraceDto trace) {
        if (trace == null || trace.replanCount() <= 0) {
            return MemoryWriteBatch.empty();
        }
        String summary = "meta-trace strategy=" + trace.strategy()
                + ", replans=" + trace.replanCount()
                + ", critique=" + (trace.critique() == null ? "none" : trace.critique().action());
        List<Double> embedding = List.of(
                (double) summary.length(),
                Math.abs(summary.hashCode() % 1000) / 1000.0
        );
        return MemoryWriteBatch.of(new MemoryWriteOperation.WriteSemantic(summary, embedding, "meta"));
    }

    private MemoryWriteBatch reflectionWrites(String userId, String userInput, SkillResult result, ExecutionTraceDto trace) {
        if (reflectionAgent == null || result == null) {
            return MemoryWriteBatch.empty();
        }
        ReflectionResult reflection = reflectionAgent.reflect(userId, userInput, trace, result, Map.of(), Map.of());
        return reflection == null ? MemoryWriteBatch.empty() : reflection.memoryWrites();
    }

    private MemoryWriteBatch resolveReflectionWrites(String userId,
                                                     String userInput,
                                                     SkillResult result,
                                                     ExecutionTraceDto trace,
                                                     ReflectionResult reflection) {
        if (reflection != null) {
            return reflection.memoryWrites();
        }
        return reflectionWrites(userId, userInput, result, trace);
    }

    private MemoryWriteBatch postSkillSummaryWrite(String userInput, SkillResult result) {
        if (!postSkillSummaryEnabled || result == null || !result.success()) {
            return MemoryWriteBatch.empty();
        }
        String channel = result.skillName();
        if (channel == null || channel.isBlank() || "llm".equals(channel) || "security.guard".equals(channel)) {
            return MemoryWriteBatch.empty();
        }
        if (!matchesConfiguredSkill(channel)) {
            return MemoryWriteBatch.empty();
        }
        String output = capText(result.output() == null ? "" : result.output(), postSkillSummaryMaxReplyChars);
        if (output.isBlank()) {
            return MemoryWriteBatch.empty();
        }
        String summary = "post-skill-summary channel=" + channel
                + ", input=" + capText(userInput == null ? "" : userInput, 120)
                + ", output=" + output;
        List<Double> embedding = List.of(
                (double) summary.length(),
                Math.abs(summary.hashCode() % 1000) / 1000.0
        );
        return MemoryWriteBatch.of(new MemoryWriteOperation.WriteSemantic(summary, embedding, inferMemoryBucket(userInput)));
    }

    private MemoryWriteBatch proceduralLoggingWrite(String userInput, SkillResult result) {
        if (!proceduralLoggingEnabled || result == null || shouldSkipProceduralLogging(result.skillName())) {
            return MemoryWriteBatch.empty();
        }
        return MemoryWriteBatch.of(new MemoryWriteOperation.WriteProcedural(
                ProceduralMemoryEntry.of(
                        result.skillName(),
                        userInput,
                        result.success()
                )
        ));
    }

    private boolean matchesConfiguredSkill(String skillName) {
        if (skillName == null || skillName.isBlank()) {
            return false;
        }
        if (postSkillSummarySkills.isEmpty()) {
            return true;
        }
        String normalized = normalize(skillName);
        for (String configured : postSkillSummarySkills) {
            if (configured == null || configured.isBlank()) {
                continue;
            }
            String candidate = normalize(configured);
            if (candidate.endsWith(".*")) {
                String prefix = candidate.substring(0, candidate.length() - 1);
                if (!prefix.isBlank() && normalized.startsWith(prefix)) {
                    return true;
                }
                continue;
            }
            if (normalized.equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldSkipProceduralLogging(String skillName) {
        String normalized = normalize(skillName);
        if (normalized.isEmpty()) {
            return true;
        }
        if ("llm".equals(normalized)) {
            return true;
        }
        if (normalized.startsWith("memory.")) {
            return true;
        }
        if (normalized.startsWith("semantic.")) {
            return true;
        }
        if (normalized.startsWith("security.")) {
            return true;
        }
        return false;
    }

    private String capText(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(0, maxChars - 14)) + "\n...[truncated]";
    }

    private String inferMemoryBucket(String input) {
        String normalized = normalize(input);
        if (normalized.isBlank()) {
            return "general";
        }
        if (containsAny(normalized,
                "学习计划", "教学规划", "复习计划", "备考", "课程", "学科", "数学", "英语", "物理", "化学")) {
            return "learning";
        }
        if (containsAny(normalized,
                "情商", "沟通", "同事", "关系", "冲突", "安抚", "eq", "coach")) {
            return "eq";
        }
        if (containsAny(normalized,
                "待办", "todo", "截止", "任务", "清单", "优先级", "计划")) {
            return "task";
        }
        return "general";
    }

    private boolean containsAny(String input, String... keywords) {
        if (input == null || input.isBlank()) {
            return false;
        }
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank() && input.contains(keyword.trim().toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private List<String> parseCsvList(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        String[] parts = csv.split("[,;]");
        Map<String, String> ordered = new LinkedHashMap<>();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            String trimmed = part.trim();
            ordered.putIfAbsent(trimmed, trimmed);
        }
        return List.copyOf(ordered.values());
    }
}
