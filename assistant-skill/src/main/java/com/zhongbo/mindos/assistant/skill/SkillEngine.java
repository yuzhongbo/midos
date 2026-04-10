package com.zhongbo.mindos.assistant.skill;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillDsl;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.mcp.DefaultMcpToolCatalog;
import com.zhongbo.mindos.assistant.skill.mcp.McpToolCatalog;
import com.zhongbo.mindos.assistant.skill.mcp.McpToolExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
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
public class SkillEngine implements SkillEngineFacade {

    public record SkillCandidate(String skillName, int score) {
    }

    private static final Logger LOGGER = Logger.getLogger(SkillEngine.class.getName());

    private final SkillRegistry skillRegistry;
    private final SkillDslExecutor dslExecutor;
    private final McpToolCatalog mcpToolCatalog;
    private final ExecutorService skillExecutor = Executors.newFixedThreadPool(4);

    public SkillEngine(SkillRegistry skillRegistry,
                       SkillDslExecutor dslExecutor) {
        this(skillRegistry, dslExecutor, new DefaultMcpToolCatalog(new McpToolExecutor()));
    }

    @Autowired
    public SkillEngine(SkillRegistry skillRegistry,
                       SkillDslExecutor dslExecutor,
                       McpToolCatalog mcpToolCatalog) {
        this.skillRegistry = skillRegistry;
        this.dslExecutor = dslExecutor;
        this.mcpToolCatalog = mcpToolCatalog;
    }

    public Optional<SkillResult> executeDetectedSkill(SkillContext context) {
        return executeDetectedSkillAsync(context).join();
    }

    public Optional<String> detectSkillName(String input) {
        return detectSkillCandidates(input, 1).stream().findFirst().map(SkillCandidate::skillName);
    }

    public List<SkillCandidate> detectSkillCandidates(String input, int limit) {
        if (input == null || input.isBlank() || limit <= 0) {
            return List.of();
        }
        List<SkillCandidate> candidates = new ArrayList<>();
        skillRegistry.detectCandidates(input, limit).forEach(candidate ->
                candidates.add(new SkillCandidate(candidate.skill().name(), candidate.score()))
        );
        mcpToolCatalog.detectCandidates(input, limit).forEach(candidate ->
                candidates.add(new SkillCandidate(candidate.skillName(), candidate.score()))
        );
        candidates.sort(Comparator
                .comparingInt(SkillCandidate::score).reversed()
                .thenComparing(SkillCandidate::skillName));
        int safeLimit = Math.min(limit, candidates.size());
        return safeLimit <= 0 ? List.of() : List.copyOf(candidates.subList(0, safeLimit));
    }

    public CompletableFuture<Optional<SkillResult>> executeDetectedSkillAsync(SkillContext context) {
        Optional<String> detectedSkill = detectSkillName(context.input());
        if (detectedSkill.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return executeSkillByNameAsync(detectedSkill.get(), context);
    }

    public CompletableFuture<Optional<SkillResult>> executeSkillByNameAsync(String skillName, SkillContext context) {
        if (skillName == null || skillName.isBlank()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        Optional<Skill> skill = skillRegistry.getSkill(skillName);
        if (skill.isPresent()) {
            Map<String, Object> parameters = new LinkedHashMap<>(context.attributes());
            return CompletableFuture.supplyAsync(
                    () -> runWithTiming(skillName, context.userId(), context.input(), parameters, () -> skill.get().run(context)),
                    skillExecutor
            ).thenApply(Optional::of);
        }
        if (!mcpToolCatalog.hasTool(skillName)) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        Map<String, Object> parameters = new LinkedHashMap<>(context.attributes());
        return CompletableFuture.supplyAsync(
                () -> runWithTiming(skillName,
                        context.userId(),
                        context.input(),
                        parameters,
                        () -> mcpToolCatalog.executeTool(skillName, context)
                                .orElseGet(() -> SkillResult.failure(skillName, "Skill not found: " + skillName))),
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
        Optional<Skill> skill = skillRegistry.getSkill(dsl.skill());
        if (skill.isPresent()) {
            return CompletableFuture.supplyAsync(
                    () -> runWithTiming(dsl.skill(), context.userId(), context.input(), dslAttributes, () -> dslExecutor.execute(dsl, dslContext)),
                    skillExecutor
            );
        }
        if (mcpToolCatalog.hasTool(dsl.skill())) {
            return CompletableFuture.supplyAsync(
                    () -> runWithTiming(dsl.skill(),
                            context.userId(),
                            context.input(),
                            dslAttributes,
                            () -> mcpToolCatalog.executeTool(dsl.skill(), dslContext)
                                    .orElseGet(() -> SkillResult.failure(dsl.skill(), "Skill not found: " + dsl.skill()))),
                    skillExecutor
            );
        }
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
        List<String> summaries = new ArrayList<>(skillRegistry.getAllSkills().stream()
                .sorted(Comparator.comparing(Skill::name))
                .map(skill -> skill.name() + " - " + (skill.description() == null ? "" : skill.description()))
                .toList());
        summaries.addAll(mcpToolCatalog.listToolSummaries());
        summaries.sort(String::compareTo);
        return List.copyOf(summaries);
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
