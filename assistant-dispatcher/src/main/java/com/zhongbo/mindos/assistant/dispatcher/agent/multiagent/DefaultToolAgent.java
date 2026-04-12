package com.zhongbo.mindos.assistant.dispatcher.agent.multiagent;

import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillDsl;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.DecisionOrchestrator;
import com.zhongbo.mindos.assistant.dispatcher.orchestrator.ParamValidator;
import com.zhongbo.mindos.assistant.skill.SkillExecutionGateway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class DefaultToolAgent implements ToolAgent {

    private final SkillExecutionGateway skillExecutionGateway;
    private final ParamValidator paramValidator;

    public DefaultToolAgent(SkillExecutionGateway skillExecutionGateway) {
        this(skillExecutionGateway, null);
    }

    @Autowired
    public DefaultToolAgent(SkillExecutionGateway skillExecutionGateway,
                            ParamValidator paramValidator) {
        this.skillExecutionGateway = skillExecutionGateway;
        this.paramValidator = paramValidator;
    }

    @Override
    public String name() {
        return "tool-agent";
    }

    @Override
    public AgentRole role() {
        return AgentRole.TOOL;
    }

    @Override
    public AgentResponse execute(AgentMessage message, AgentContext context) {
        Map<String, Object> payload = message == null ? Map.of() : message.payloadMap();
        String skillName = firstNonBlank(stringValue(payload.get("skillName")), message == null ? "" : message.to());
        Map<String, Object> params = mapValue(payload.get("params"));
        boolean usedFallback = booleanValue(payload.get("usedFallback"));
        if (skillName.isBlank()) {
            return AgentResponse.completed(
                    name(),
                    SkillResult.failure(name(), "missing skill name"),
                    false,
                    List.of(),
                    Map.of(),
                    "missing skill name"
            );
        }
        if (skillExecutionGateway == null) {
            return AgentResponse.completed(
                    name(),
                    SkillResult.failure(skillName, "skill execution gateway unavailable"),
                    usedFallback,
                    List.of(),
                    Map.of(),
                    "skill execution gateway unavailable"
            );
        }

        DecisionOrchestrator.OrchestrationRequest baseRequest = context == null
                ? new DecisionOrchestrator.OrchestrationRequest("", "", new SkillContext("", "", Map.of()), Map.of())
                : context.mergedRequest();
        SkillContext skillContext = new SkillContext(baseRequest.userId(), baseRequest.userInput(), params);
        DecisionOrchestrator.OrchestrationRequest request = new DecisionOrchestrator.OrchestrationRequest(
                baseRequest.userId(),
                baseRequest.userInput(),
                skillContext,
                baseRequest.safeProfileContext()
        );
        ParamValidator.ValidationResult validation = paramValidator == null
                ? ParamValidator.ValidationResult.ok(params, Map.of())
                : paramValidator.validate(skillName, params, request);
        if (!validation.valid()) {
            return AgentResponse.completed(
                    name(),
                    SkillResult.failure(skillName, validation.message()),
                    usedFallback,
                    List.of(),
                    Map.of(
                            "multiAgent.tool.skillName", skillName,
                            "multiAgent.tool.valid", false
                    ),
                    validation.message()
            );
        }
        Map<String, Object> normalizedParams = validation.normalizedParams().isEmpty()
                ? params
                : validation.normalizedParams();
        skillContext = new SkillContext(baseRequest.userId(), baseRequest.userInput(), normalizedParams);
        SkillResult result = skillExecutionGateway.executeDslAsync(new SkillDsl(skillName, normalizedParams), skillContext).join();

        Map<String, Object> memoryPayload = new LinkedHashMap<>();
        memoryPayload.put("kind", "skill-usage");
        memoryPayload.put("skillName", skillName);
        memoryPayload.put("success", result.success());
        memoryPayload.put("result", result);
        memoryPayload.put("userInput", context.userInput());
        memoryPayload.put("usedFallback", usedFallback);
        memoryPayload.put("params", normalizedParams);
        AgentMessage memoryMessage = AgentMessage.reply(
                message,
                name(),
                "memory-agent",
                AgentTask.of(AgentTaskType.MEMORY_WRITE, context.userId(), context.userInput(), memoryPayload)
        );

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("multiAgent.tool.skillName", skillName);
        patch.put("multiAgent.tool.success", result.success());
        patch.put("multiAgent.tool.usedFallback", usedFallback);
        patch.put("multiAgent.tool.valid", true);

        return AgentResponse.completed(
                name(),
                result,
                usedFallback,
                List.of(memoryMessage),
                patch,
                result.success() ? "tool success" : "tool failed"
        );
    }

    @Override
    public AgentResponse plan(AgentMessage message, AgentContext context) {
        return AgentResponse.unsupported(name(), "plan");
    }

    @Override
    public AgentResponse observe(AgentMessage message, AgentContext context) {
        return AgentResponse.unsupported(name(), "observe");
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
            Object entryValue = entry.getValue();
            if (entryValue == null) {
                continue;
            }
            normalized.put(String.valueOf(entry.getKey()), entryValue);
        }
        return normalized.isEmpty() ? Map.of() : Map.copyOf(normalized);
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
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

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }
}
