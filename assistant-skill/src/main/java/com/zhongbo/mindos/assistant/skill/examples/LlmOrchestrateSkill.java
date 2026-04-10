package com.zhongbo.mindos.assistant.skill.examples;

import com.zhongbo.mindos.assistant.common.LlmClient;
import com.zhongbo.mindos.assistant.common.SkillContext;
import com.zhongbo.mindos.assistant.common.SkillResult;
import com.zhongbo.mindos.assistant.skill.Skill;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Component
public class LlmOrchestrateSkill implements Skill {

    private final LlmClient llmClient;
    private final List<String> defaultProviders;
    private final int promptMaxChars;
    private final int historyMaxItems;

    @Autowired
    public LlmOrchestrateSkill(LlmClient llmClient,
                               @Value("${mindos.llm.orchestrate.providers:openai,deepseek,qwen}") String providers,
                               @Value("${mindos.llm.orchestrate.max-hops:2}") int maxHops,
                               @Value("${mindos.llm.orchestrate.prompt.max-chars:1600}") int promptMaxChars,
                               @Value("${mindos.llm.orchestrate.history.max-items:6}") int historyMaxItems) {
        this.llmClient = llmClient;
        this.defaultProviders = parseProviders(providers);
        this.promptMaxChars = Math.max(400, promptMaxChars);
        this.historyMaxItems = Math.max(1, historyMaxItems);
    }

    // Test helper constructor
    LlmOrchestrateSkill(LlmClient llmClient,
                        List<String> providers,
                        int maxHops,
                        int promptMaxChars,
                        int historyMaxItems) {
        this.llmClient = llmClient;
        this.defaultProviders = providers == null ? List.of() : List.copyOf(providers);
        this.promptMaxChars = Math.max(400, promptMaxChars);
        this.historyMaxItems = Math.max(1, historyMaxItems);
    }

    @Override
    public String name() {
        return "llm.orchestrate";
    }

    @Override
    public String description() {
        return "Builds a single LLM prompt from recent context and forwards it to the selected provider.";
    }

    @Override
    public List<String> routingKeywords() {
        return List.of("llm.orchestrate", "调用模型", "llm prompt", "模型总结", "模型回复");
    }

    @Override
    public boolean supports(String input) {
        if (input == null || input.isBlank()) {
            return false;
        }
        String normalized = input.trim().toLowerCase();
        return normalized.startsWith("llm.orchestrate")
                || normalized.contains("调用模型")
                || normalized.contains("模型总结")
                || normalized.contains("模型回复");
    }

    @Override
    public SkillResult run(SkillContext context) {
        if (llmClient == null) {
            return SkillResult.failure(name(), "[llm.orchestrate] LLM client unavailable.");
        }
        String userInput = context.input() == null ? "" : context.input();
        Map<String, Object> attrs = context.attributes();
        String memoryContext = asText(attrs.get("memoryContext"));
        List<Map<String, Object>> chatHistory = limitHistory(asHistory(attrs.get("chatHistory")));
        String preferred = firstNonBlank(asText(attrs.get("preferredProvider")), asText(attrs.get("llmProvider")));
        String provider = resolveProvider(preferred, defaultProviders);
        String prompt = buildPrompt(memoryContext, chatHistory, userInput);
        Map<String, Object> llmContext = buildLlmContext(context.userId(), memoryContext, chatHistory, userInput, provider);
        String output = llmClient.generateResponse(prompt, llmContext);
        if (isAcceptable(output)) {
            return SkillResult.success(name(), formatSuccess(output, provider));
        }
        return SkillResult.failure(name(), formatFailure(provider, output));
    }

    private List<String> parseProviders(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        Set<String> ordered = new LinkedHashSet<>();
        for (String token : raw.split(",")) {
            String normalized = token == null ? "" : token.trim();
            if (!normalized.isBlank()) {
                ordered.add(normalized);
            }
        }
        return List.copyOf(ordered);
    }

    private String resolveProvider(String preferred, List<String> defaults) {
        LinkedHashSet<String> chain = new LinkedHashSet<>();
        if (preferred != null && !preferred.isBlank() && !"auto".equalsIgnoreCase(preferred)) {
            chain.add(preferred.trim());
        }
        chain.addAll(defaults);
        if (chain.isEmpty()) {
            chain.add("openai");
        }
        return chain.iterator().next();
    }

    private Map<String, Object> buildLlmContext(String userId,
                                                String memoryContext,
                                                List<Map<String, Object>> chatHistory,
                                                String userInput,
                                                String provider) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("userId", userId);
        ctx.put("routeStage", "llm-orchestrate");
        ctx.put("input", userInput);
        ctx.put("llmProvider", provider);
        if (memoryContext != null && !memoryContext.isBlank()) {
            ctx.put("memoryContext", memoryContext);
        }
        if (chatHistory != null && !chatHistory.isEmpty()) {
            ctx.put("chatHistory", chatHistory);
        }
        return ctx;
    }

    private String buildPrompt(String memoryContext,
                               List<Map<String, Object>> history,
                               String userInput) {
        StringBuilder builder = new StringBuilder();
        builder.append("You are a focused assistant. Use the supplied context to answer clearly and concisely.\n");
        if (history != null && !history.isEmpty()) {
            builder.append("Recent chat history:\n");
            for (Map<String, Object> turn : history) {
                String role = asText(turn.get("role"));
                String content = asText(turn.get("content"));
                if (content != null && !content.isBlank()) {
                    builder.append("- ").append(role == null ? "assistant" : role).append(": ")
                            .append(cap(content, 220)).append('\n');
                }
            }
        }
        if (memoryContext != null && !memoryContext.isBlank()) {
            builder.append("Context:\n").append(cap(memoryContext, 600)).append('\n');
        }
        builder.append("User input: ").append(cap(userInput, 400));
        return cap(builder.toString(), promptMaxChars);
    }

    private boolean isAcceptable(String output) {
        if (output == null || output.isBlank()) {
            return false;
        }
        String normalized = output.toLowerCase();
        return !normalized.contains("[llm error]") && !normalized.contains("no api key resolved");
    }

    private String formatSuccess(String output, String provider) {
        return "[llm provider=" + provider + "]\n" + output;
    }

    private String formatFailure(String provider, String output) {
        return "[llm.orchestrate] provider=" + provider + " 返回了不可用结果: " + cap(output, 260);
    }

    private String asText(Object value) {
        if (value == null) {
            return null;
        }
        String normalized = String.valueOf(value).trim();
        return normalized.isBlank() ? null : normalized;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asHistory(Object value) {
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (Object item : raw) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> normalized = new LinkedHashMap<>();
                map.forEach((k, v) -> normalized.put(Objects.toString(k, ""), v));
                results.add(normalized);
            }
        }
        return List.copyOf(results);
    }

    private List<Map<String, Object>> limitHistory(List<Map<String, Object>> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        int fromIndex = Math.max(0, history.size() - historyMaxItems);
        return List.copyOf(history.subList(fromIndex, history.size()));
    }

    private String cap(String value, int max) {
        if (value == null) {
            return "";
        }
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, Math.max(0, max - 12)) + "...(truncated)";
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
}
