package com.zhongbo.mindos.assistant.skill;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillDsl;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.mcp.DefaultMcpToolCatalog;
import com.zhongbo.mindos.assistant.skill.mcp.McpToolCatalog;
import com.zhongbo.mindos.assistant.skill.mcp.McpToolExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

@Service
public class DefaultSkillExecutionGateway implements SkillExecutionGateway {

    private static final Logger LOGGER = Logger.getLogger(DefaultSkillExecutionGateway.class.getName());

    private final SkillRegistry skillRegistry;
    private final SkillDslExecutor dslExecutor;
    private final McpToolCatalog mcpToolCatalog;
    private final ExecutorService skillExecutor = Executors.newFixedThreadPool(4);

    public DefaultSkillExecutionGateway(SkillRegistry skillRegistry,
                                        SkillDslExecutor dslExecutor) {
        this(skillRegistry, dslExecutor, new DefaultMcpToolCatalog(new McpToolExecutor()));
    }

    @Autowired
    public DefaultSkillExecutionGateway(SkillRegistry skillRegistry,
                                        SkillDslExecutor dslExecutor,
                                        McpToolCatalog mcpToolCatalog) {
        this.skillRegistry = skillRegistry;
        this.dslExecutor = dslExecutor;
        this.mcpToolCatalog = mcpToolCatalog;
    }

    @Override
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
