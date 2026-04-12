package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryFacade;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.memory.MemoryWriteBatch;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.memory.MemoryWriteOperation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DispatchMemoryLifecycle {

    private static final Pattern EXPLICIT_MEMORY_STORE_PATTERN = Pattern.compile(
            "^(?:remember\\s*[:：]?|please remember\\s*[:：]?|请记住\\s*[:：]?|帮我记住\\s*[:：]?|记住\\s*[:：]?|记一下\\s*[:：]?)(.+)$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern EXPLICIT_MEMORY_BUCKET_PATTERN = Pattern.compile(
            "^(task|learning|eq|coding|general|任务|学习|情商|沟通|代码|编程|通用)\\s*[:：]\\s*(.+)$",
            Pattern.CASE_INSENSITIVE
    );

    private final BehaviorRoutingSupport behaviorRoutingSupport;
    private final Function<String, String> memoryBucketResolver;

    DispatchMemoryLifecycle(DispatcherMemoryFacade dispatcherMemoryFacade,
                            com.zhongbo.mindos.assistant.dispatcher.memory.DispatcherMemoryCommandService memoryCommandService,
                            BehaviorRoutingSupport behaviorRoutingSupport,
                            Function<String, String> memoryBucketResolver) {
        this.behaviorRoutingSupport = behaviorRoutingSupport;
        this.memoryBucketResolver = memoryBucketResolver;
    }

    MemoryWriteBatch recordUserInput(String userId, String userInput) {
        return MemoryWriteBatch.of(
                new MemoryWriteOperation.AppendUserConversation(userInput),
                rememberedKnowledgeWrite(userInput)
        );
    }

    MemoryWriteBatch recordAssistantReply(String userId, String reply) {
        return MemoryWriteBatch.of(new MemoryWriteOperation.AppendAssistantConversation(reply == null ? "" : reply));
    }

    MemoryWriteBatch recordSkillOutcome(String userId, SkillResult result) {
        if (result == null) {
            return MemoryWriteBatch.empty();
        }
        return recordAssistantReply(userId, result.output())
                .merge(behaviorRoutingSupport.maybeStoreBehaviorProfile(userId, result));
    }

    private MemoryWriteOperation rememberedKnowledgeWrite(String input) {
        String knowledge = extractRememberedKnowledge(input);
        if (knowledge == null || knowledge.isBlank()) {
            return null;
        }

        String explicitBucket = extractExplicitMemoryBucket(knowledge);
        if (explicitBucket != null) {
            knowledge = stripExplicitMemoryBucket(knowledge);
        }
        if (knowledge.isBlank()) {
            return null;
        }
        String memoryBucket = explicitBucket == null ? resolveMemoryBucket(knowledge) : explicitBucket;
        Map<String, Object> embeddingSeed = new LinkedHashMap<>();
        embeddingSeed.put("length", knowledge.length());
        embeddingSeed.put("hash", Math.abs(knowledge.hashCode() % 1000));
        List<Double> embedding = List.of(
                (double) ((Integer) embeddingSeed.get("length")),
                ((Integer) embeddingSeed.get("hash")) / 1000.0
        );
        return new MemoryWriteOperation.WriteSemantic(knowledge, embedding, memoryBucket);
    }

    private String resolveMemoryBucket(String knowledge) {
        return memoryBucketResolver == null ? "general" : memoryBucketResolver.apply(knowledge);
    }

    private String extractRememberedKnowledge(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        Matcher matcher = EXPLICIT_MEMORY_STORE_PATTERN.matcher(input.trim());
        if (!matcher.matches()) {
            return null;
        }
        String knowledge = matcher.group(1);
        return knowledge == null ? null : knowledge.trim();
    }

    private String extractExplicitMemoryBucket(String knowledge) {
        if (knowledge == null || knowledge.isBlank()) {
            return null;
        }
        Matcher matcher = EXPLICIT_MEMORY_BUCKET_PATTERN.matcher(knowledge.trim());
        if (!matcher.matches()) {
            return null;
        }
        return normalizeExplicitMemoryBucket(matcher.group(1));
    }

    private String stripExplicitMemoryBucket(String knowledge) {
        Matcher matcher = EXPLICIT_MEMORY_BUCKET_PATTERN.matcher(knowledge.trim());
        if (!matcher.matches()) {
            return knowledge.trim();
        }
        return matcher.group(2) == null ? "" : matcher.group(2).trim();
    }

    private String normalizeExplicitMemoryBucket(String bucket) {
        if (bucket == null || bucket.isBlank()) {
            return null;
        }
        return switch (bucket.trim().toLowerCase(Locale.ROOT)) {
            case "task", "任务" -> "task";
            case "learning", "学习" -> "learning";
            case "eq", "情商", "沟通" -> "eq";
            case "coding", "代码", "编程" -> "coding";
            default -> "general";
        };
    }
}
