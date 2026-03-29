package com.zhongbo.mindos.assistant.skill;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillDsl;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.memory.MemoryManager;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class SkillEngine {

    private static final Logger LOGGER = Logger.getLogger(SkillEngine.class.getName());

    private final SkillRegistry skillRegistry;
    private final SkillDslExecutor dslExecutor;
    private final MemoryManager memoryManager;
    private final ExecutorService skillExecutor = Executors.newFixedThreadPool(4);

    public SkillEngine(SkillRegistry skillRegistry,
                       SkillDslExecutor dslExecutor,
                       MemoryManager memoryManager) {
        this.skillRegistry = skillRegistry;
        this.dslExecutor = dslExecutor;
        this.memoryManager = memoryManager;
    }

    public Optional<SkillResult> executeDetectedSkill(SkillContext context) {
        return executeDetectedSkillAsync(context).join();
    }

    public CompletableFuture<Optional<SkillResult>> executeDetectedSkillAsync(SkillContext context) {
        Optional<Skill> detectedSkill = skillRegistry.detect(context.input());
        if (detectedSkill.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        Skill skill = detectedSkill.get();
        Map<String, Object> parameters = new LinkedHashMap<>(context.attributes());
        return CompletableFuture.supplyAsync(
                () -> runWithTiming(skill.name(), context.userId(), context.input(), parameters, () -> skill.run(context)),
                skillExecutor
        ).thenApply(Optional::of);
    }

    public SkillResult executeDsl(SkillDsl dsl, SkillContext context) {
        return executeDslAsync(dsl, context).join();
    }

    public CompletableFuture<SkillResult> executeDslAsync(SkillDsl dsl, SkillContext context) {
        Map<String, Object> dslAttributes = new LinkedHashMap<>(context.attributes());
        dslAttributes.putAll(dsl.input());
        SkillContext dslContext = new SkillContext(context.userId(), context.input(), dslAttributes);
        return CompletableFuture.supplyAsync(
                () -> runWithTiming(dsl.skill(), context.userId(), context.input(), dslAttributes, () -> dslExecutor.execute(dsl, dslContext)),
                skillExecutor
        );
    }

    // Explicit Future API for callers that want a Future<SkillResult> contract.
    public Future<SkillResult> executeSkillAsync(SkillDsl dsl, SkillContext context) {
        return executeDslAsync(dsl, context);
    }

    public String describeAvailableSkills() {
        return listAvailableSkillSummaries().stream().collect(Collectors.joining(", "));
    }

    public List<String> listAvailableSkillSummaries() {
        return skillRegistry.getAllSkills().stream()
                .sorted(Comparator.comparing(Skill::name))
                .map(skill -> skill.name() + " - " + (skill.description() == null ? "" : skill.description()))
                .toList();
    }

    private SkillResult runWithTiming(String skillName,
                                      String userId,
                                      String input,
                                      Map<String, Object> parameters,
                                      SkillExecution action) {
        Instant startTime = Instant.now();
        LOGGER.info("Skill execution started: skill=" + skillName
                + ", userId=" + userId
                + ", parameters=" + clipParameters(parameters)
                + ", startTime=" + startTime);

        SkillResult result;
        try {
            result = action.execute();
        } catch (RuntimeException ex) {
            LOGGER.log(Level.SEVERE,
                    "Skill execution failed: skill=" + skillName
                            + ", userId=" + userId
                            + ", parameters=" + clipParameters(parameters),
                    ex);
            result = SkillResult.failure(skillName, "Skill execution failed: " + ex.getMessage());
        }

        Instant endTime = Instant.now();
        long durationMs = Duration.between(startTime, endTime).toMillis();
        LOGGER.info("Skill execution finished: skill=" + skillName
                + ", userId=" + userId
                + ", parameters=" + clipParameters(parameters)
                + ", startTime=" + startTime
                + ", endTime=" + endTime
                + ", durationMs=" + durationMs
                + ", success=" + result.success());

        memoryManager.logSkillUsage(userId, result.skillName(), input, result.success());
        return result;
    }

    private String clipParameters(Map<String, Object> parameters) {
        String raw = String.valueOf(parameters == null ? Map.of() : parameters);
        int max = 300;
        return raw.length() <= max ? raw : raw.substring(0, max) + "...";
    }


    @FunctionalInterface
    private interface SkillExecution {
        SkillResult execute();
    }
}
