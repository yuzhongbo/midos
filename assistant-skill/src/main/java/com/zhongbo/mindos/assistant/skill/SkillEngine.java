package com.zhongbo.mindos.assistant.skill;

import com.zhongbo.mindos.assistant.skill.mcp.DefaultMcpToolCatalog;
import com.zhongbo.mindos.assistant.skill.mcp.McpToolCatalog;
import com.zhongbo.mindos.assistant.skill.mcp.McpToolExecutor;
import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillDsl;
import com.zhongbo.mindos.assistant.common.SkillResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class SkillEngine implements SkillEngineFacade {

    private final SkillRegistry skillRegistry;
    private final SkillDslExecutor dslExecutor;
    private final McpToolCatalog mcpToolCatalog;
    private final McpToolExecutor mcpToolExecutor;

    public SkillEngine(SkillRegistry skillRegistry,
                       SkillDslExecutor dslExecutor) {
        this(skillRegistry, dslExecutor, new DefaultMcpToolCatalog(), new McpToolExecutor());
    }

    @Autowired
    public SkillEngine(SkillRegistry skillRegistry,
                       SkillDslExecutor dslExecutor,
                       McpToolCatalog mcpToolCatalog,
                       McpToolExecutor mcpToolExecutor) {
        this.skillRegistry = skillRegistry;
        this.dslExecutor = dslExecutor;
        this.mcpToolCatalog = mcpToolCatalog;
        this.mcpToolExecutor = mcpToolExecutor;
    }

    @Override
    public SkillResult execute(String target, Map<String, Object> params) {
        String normalizedTarget = target == null ? "" : target.trim();
        Map<String, Object> attributes = params == null ? Map.of() : new LinkedHashMap<>(params);
        String input = firstNonBlank(stringValue(attributes.get("input")), stringValue(attributes.get("originalInput")));
        SkillContext context = new SkillContext(stringValue(attributes.get("userId")), input, Map.copyOf(attributes));
        if (skillRegistry.get(normalizedTarget).isPresent()) {
            SkillDsl dsl = attributes.isEmpty() ? SkillDsl.of(normalizedTarget) : new SkillDsl(normalizedTarget, Map.copyOf(attributes));
            return dslExecutor.execute(dsl, context);
        }
        if (mcpToolCatalog != null && mcpToolCatalog.hasTool(normalizedTarget)) {
            return mcpToolExecutor.execute(normalizedTarget, attributes);
        }
        return SkillResult.failure(normalizedTarget.isBlank() ? "skill" : normalizedTarget,
                "Unknown skill target: " + normalizedTarget);
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
