package com.zhongbo.mindos.assistant.dispatcher;

import com.zhongbo.mindos.assistant.common.dto.PromptMemoryContextDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class IntentModelRoutingPolicy {

    private final boolean enabled;
    private final String defaultProvider;
    private final String codeProvider;
    private final String realtimeProvider;
    private final String emotionalProvider;
    private final String generalModelEasy;
    private final String generalModelMedium;
    private final String generalModelHard;
    private final String codeModelEasy;
    private final String codeModelMedium;
    private final String codeModelHard;
    private final String realtimeModelEasy;
    private final String realtimeModelMedium;
    private final String realtimeModelHard;
    private final String emotionalModelEasy;
    private final String emotionalModelMedium;
    private final String emotionalModelHard;
    private final Set<String> emotionalTerms;
    private final int hardInputLengthThreshold;

    public IntentModelRoutingPolicy(
            @Value("${mindos.dispatcher.intent-routing.enabled:true}") boolean enabled,
            @Value("${mindos.dispatcher.intent-routing.default-provider:gpt}") String defaultProvider,
            @Value("${mindos.dispatcher.intent-routing.code-provider:gpt}") String codeProvider,
            @Value("${mindos.dispatcher.intent-routing.realtime-provider:grok}") String realtimeProvider,
            @Value("${mindos.dispatcher.intent-routing.emotional-provider:gemini}") String emotionalProvider,
            @Value("${mindos.dispatcher.intent-routing.model.general.easy:}") String generalModelEasy,
            @Value("${mindos.dispatcher.intent-routing.model.general.medium:}") String generalModelMedium,
            @Value("${mindos.dispatcher.intent-routing.model.general.hard:}") String generalModelHard,
            @Value("${mindos.dispatcher.intent-routing.model.code.easy:}") String codeModelEasy,
            @Value("${mindos.dispatcher.intent-routing.model.code.medium:}") String codeModelMedium,
            @Value("${mindos.dispatcher.intent-routing.model.code.hard:}") String codeModelHard,
            @Value("${mindos.dispatcher.intent-routing.model.realtime.easy:}") String realtimeModelEasy,
            @Value("${mindos.dispatcher.intent-routing.model.realtime.medium:}") String realtimeModelMedium,
            @Value("${mindos.dispatcher.intent-routing.model.realtime.hard:}") String realtimeModelHard,
            @Value("${mindos.dispatcher.intent-routing.model.emotional.easy:}") String emotionalModelEasy,
            @Value("${mindos.dispatcher.intent-routing.model.emotional.medium:}") String emotionalModelMedium,
            @Value("${mindos.dispatcher.intent-routing.model.emotional.hard:}") String emotionalModelHard,
            @Value("${mindos.dispatcher.intent-routing.emotional-terms:情绪,情感,难受,焦虑,压力,内耗,失眠,崩溃,安慰,沟通,关系,吵架,冷战,道歉,挽回}") String emotionalTerms,
            @Value("${mindos.dispatcher.intent-routing.hard-input-length-threshold:180}") int hardInputLengthThreshold) {
        this.enabled = enabled;
        this.defaultProvider = normalizeOptional(defaultProvider);
        this.codeProvider = normalizeOptional(codeProvider);
        this.realtimeProvider = normalizeOptional(realtimeProvider);
        this.emotionalProvider = normalizeOptional(emotionalProvider);
        this.generalModelEasy = normalizeOptional(generalModelEasy);
        this.generalModelMedium = normalizeOptional(generalModelMedium);
        this.generalModelHard = normalizeOptional(generalModelHard);
        this.codeModelEasy = normalizeOptional(codeModelEasy);
        this.codeModelMedium = normalizeOptional(codeModelMedium);
        this.codeModelHard = normalizeOptional(codeModelHard);
        this.realtimeModelEasy = normalizeOptional(realtimeModelEasy);
        this.realtimeModelMedium = normalizeOptional(realtimeModelMedium);
        this.realtimeModelHard = normalizeOptional(realtimeModelHard);
        this.emotionalModelEasy = normalizeOptional(emotionalModelEasy);
        this.emotionalModelMedium = normalizeOptional(emotionalModelMedium);
        this.emotionalModelHard = normalizeOptional(emotionalModelHard);
        this.emotionalTerms = parseCsvSet(emotionalTerms);
        this.hardInputLengthThreshold = Math.max(80, hardInputLengthThreshold);
    }

    public void applyForFallback(String userInput,
                                 PromptMemoryContextDto promptMemoryContext,
                                 boolean realtimeIntentInput,
                                 Map<String, Object> resolvedProfileContext,
                                 Map<String, Object> llmContext) {
        if (!enabled || llmContext == null) {
            return;
        }
        if (hasExplicitLlmOverride(resolvedProfileContext)) {
            return;
        }
        IntentType intentType = detectIntentType(userInput, realtimeIntentInput);
        Difficulty difficulty = detectDifficulty(userInput, promptMemoryContext);

        String provider = providerFor(intentType);
        if (provider != null) {
            llmContext.put("llmProvider", provider);
        }

        String model = modelFor(intentType, difficulty);
        if (model != null) {
            llmContext.put("model", model);
        }
    }

    private IntentType detectIntentType(String userInput, boolean realtimeIntentInput) {
        String normalized = normalize(userInput);
        if (normalized.isBlank()) {
            return IntentType.GENERAL;
        }
        if (realtimeIntentInput) {
            return IntentType.REALTIME;
        }
        if (looksCoding(normalized)) {
            return IntentType.CODE;
        }
        if (looksEmotional(normalized)) {
            return IntentType.EMOTIONAL;
        }
        return IntentType.GENERAL;
    }

    private Difficulty detectDifficulty(String userInput, PromptMemoryContextDto promptMemoryContext) {
        String text = userInput == null ? "" : userInput.trim();
        int score = 0;
        if (text.length() >= hardInputLengthThreshold) {
            score += 2;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        if (containsAny(normalized,
                "架构", "tradeoff", "trade-off", "多步骤", "分阶段", "深度", "复杂", "设计", "重构", "系统")) {
            score += 2;
        }
        if (containsAny(normalized,
                "为什么", "原理", "分析", "对比", "方案", "实现", "如何", "怎么")) {
            score += 1;
        }
        if (text.contains("\n") || text.contains("```")) {
            score += 1;
        }
        if (promptMemoryContext != null) {
            String contextText = normalize(promptMemoryContext.recentConversation())
                    + normalize(promptMemoryContext.semanticContext())
                    + normalize(promptMemoryContext.proceduralHints());
            if (contextText.length() > 1200) {
                score += 1;
            }
        }

        if (score >= 3) {
            return Difficulty.HARD;
        }
        if (score <= 0) {
            return Difficulty.EASY;
        }
        return Difficulty.MEDIUM;
    }

    private String providerFor(IntentType intentType) {
        return switch (intentType) {
            case CODE -> fallbackIfNull(codeProvider, defaultProvider);
            case REALTIME -> fallbackIfNull(realtimeProvider, defaultProvider);
            case EMOTIONAL -> fallbackIfNull(emotionalProvider, defaultProvider);
            case GENERAL -> defaultProvider;
        };
    }

    private String modelFor(IntentType intentType, Difficulty difficulty) {
        return switch (intentType) {
            case CODE -> modelByDifficulty(difficulty, codeModelEasy, codeModelMedium, codeModelHard);
            case REALTIME -> modelByDifficulty(difficulty, realtimeModelEasy, realtimeModelMedium, realtimeModelHard);
            case EMOTIONAL -> modelByDifficulty(difficulty, emotionalModelEasy, emotionalModelMedium, emotionalModelHard);
            case GENERAL -> modelByDifficulty(difficulty, generalModelEasy, generalModelMedium, generalModelHard);
        };
    }

    private String modelByDifficulty(Difficulty difficulty, String easy, String medium, String hard) {
        return switch (difficulty) {
            case EASY -> fallbackIfNull(easy, medium, hard);
            case MEDIUM -> fallbackIfNull(medium, hard, easy);
            case HARD -> fallbackIfNull(hard, medium, easy);
        };
    }

    private boolean looksCoding(String normalizedInput) {
        return containsAny(normalizedInput,
                "写代码", "代码", "bug", "debug", "接口", "api", "sql", "java", "python", "typescript",
                "generate code", "implement", "refactor", "fix", "stacktrace");
    }

    private boolean looksEmotional(String normalizedInput) {
        if (containsAny(normalizedInput,
                "eq", "coach", "情商", "心理", "情绪", "沟通", "关系", "冲突", "道歉", "安慰")) {
            return true;
        }
        for (String term : emotionalTerms) {
            if (!term.isBlank() && normalizedInput.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasExplicitLlmOverride(Map<String, Object> profileContext) {
        if (profileContext == null || profileContext.isEmpty()) {
            return false;
        }
        String provider = asText(profileContext.get("llmProvider"));
        if (provider != null && !"auto".equalsIgnoreCase(provider)) {
            return true;
        }
        return asText(profileContext.get("llmPreset")) != null;
    }

    private String asText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private String fallbackIfNull(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean containsAny(String normalized, String... terms) {
        for (String term : terms) {
            if (normalized.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private Set<String> parseCsvSet(String rawCsv) {
        if (rawCsv == null || rawCsv.isBlank()) {
            return Set.of();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String part : rawCsv.split(",")) {
            String normalized = normalize(part);
            if (!normalized.isBlank()) {
                values.add(normalized);
            }
        }
        return values.isEmpty() ? Set.of() : Set.copyOf(values);
    }

    private enum IntentType {
        GENERAL,
        CODE,
        REALTIME,
        EMOTIONAL
    }

    private enum Difficulty {
        EASY,
        MEDIUM,
        HARD
    }
}

