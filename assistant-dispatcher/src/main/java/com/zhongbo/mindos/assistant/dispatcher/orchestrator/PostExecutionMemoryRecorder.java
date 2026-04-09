package com.zhongbo.mindos.assistant.dispatcher.orchestrator;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.common.dto.ExecutionTraceDto;
import com.zhongbo.mindos.assistant.memory.MemoryGateway;
import com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class PostExecutionMemoryRecorder {

    private final MemoryGateway memoryGateway;
    private final boolean proceduralLoggingEnabled;
    private final boolean postSkillSummaryEnabled;
    private final Set<String> postSkillSummarySkills;
    private final int postSkillSummaryMaxReplyChars;

    public PostExecutionMemoryRecorder(MemoryGateway memoryGateway,
                                       @Value("${mindos.dispatcher.procedural-logging.enabled:true}") boolean proceduralLoggingEnabled,
                                       @Value("${mindos.memory.post-skill-summary.enabled:false}") boolean postSkillSummaryEnabled,
                                       @Value("${mindos.memory.post-skill-summary.skills:teaching.plan,todo.create,eq.coach,code.generate,file.search}") String postSkillSummarySkills,
                                       @Value("${mindos.memory.post-skill-summary.max-reply-chars:280}") int postSkillSummaryMaxReplyChars) {
        this.memoryGateway = memoryGateway;
        this.proceduralLoggingEnabled = proceduralLoggingEnabled;
        this.postSkillSummaryEnabled = postSkillSummaryEnabled;
        this.postSkillSummarySkills = Set.copyOf(parseCsvList(postSkillSummarySkills));
        this.postSkillSummaryMaxReplyChars = Math.max(80, postSkillSummaryMaxReplyChars);
    }

    public void record(String userId,
                       String userInput,
                       SkillResult result,
                       ExecutionTraceDto trace) {
        if (result == null) {
            return;
        }
        if (proceduralLoggingEnabled && !shouldSkipProceduralLogging(result.skillName())) {
            memoryGateway.writeProcedural(userId, ProceduralMemoryEntry.of(
                    result.skillName(),
                    userInput,
                    result.success()
            ));
        }
        maybeStorePostSkillSummary(userId, userInput, result);
        maybeStoreExecutionTraceMemory(userId, trace);
    }

    private void maybeStoreExecutionTraceMemory(String userId, ExecutionTraceDto trace) {
        if (trace == null || trace.replanCount() <= 0) {
            return;
        }
        String summary = "meta-trace strategy=" + trace.strategy()
                + ", replans=" + trace.replanCount()
                + ", critique=" + (trace.critique() == null ? "none" : trace.critique().action());
        List<Double> embedding = List.of(
                (double) summary.length(),
                Math.abs(summary.hashCode() % 1000) / 1000.0
        );
        memoryGateway.writeSemantic(userId, summary, embedding, "meta");
    }

    private void maybeStorePostSkillSummary(String userId, String userInput, SkillResult result) {
        if (!postSkillSummaryEnabled || result == null || !result.success()) {
            return;
        }
        String channel = result.skillName();
        if (channel == null || channel.isBlank() || "llm".equals(channel) || "security.guard".equals(channel)) {
            return;
        }
        if (!matchesConfiguredSkill(channel)) {
            return;
        }
        String output = capText(result.output() == null ? "" : result.output(), postSkillSummaryMaxReplyChars);
        if (output.isBlank()) {
            return;
        }
        String summary = "post-skill-summary channel=" + channel
                + ", input=" + capText(userInput == null ? "" : userInput, 120)
                + ", output=" + output;
        List<Double> embedding = List.of(
                (double) summary.length(),
                Math.abs(summary.hashCode() % 1000) / 1000.0
        );
        memoryGateway.writeSemantic(userId, summary, embedding, inferMemoryBucket(userInput));
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
