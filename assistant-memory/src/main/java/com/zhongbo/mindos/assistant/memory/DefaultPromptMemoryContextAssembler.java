package com.zhongbo.mindos.assistant.memory;

import com.zhongbo.mindos.assistant.common.dto.PromptMemoryContextDto;
import com.zhongbo.mindos.assistant.common.dto.RetrievedMemoryItemDto;
import com.zhongbo.mindos.assistant.memory.model.ConversationTurn;
import com.zhongbo.mindos.assistant.memory.model.PreferenceProfile;
import com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SkillUsageStats;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class DefaultPromptMemoryContextAssembler implements PromptMemoryContextAssembler {

    private static final String CONVERSATION_ROLLUP_BUCKET = "conversation-rollup";
    private static final String SEMANTIC_SUMMARY_PREFIX = "semantic-summary ";
    private static final int RECENT_TURNS_LIMIT = 6;
    private static final int SEMANTIC_LIMIT = 10;
    private static final int DEBUG_ITEMS_LIMIT = 12;
    private static final int LAYER_PRIORITY_WINDOW = 6;

    private final EpisodicMemoryService episodicMemoryService;
    private final SemanticMemoryService semanticMemoryService;
    private final ProceduralMemoryService proceduralMemoryService;
    private final PreferenceProfileService preferenceProfileService;

    public DefaultPromptMemoryContextAssembler(EpisodicMemoryService episodicMemoryService,
                                               SemanticMemoryService semanticMemoryService,
                                               ProceduralMemoryService proceduralMemoryService,
                                               PreferenceProfileService preferenceProfileService) {
        this.episodicMemoryService = episodicMemoryService;
        this.semanticMemoryService = semanticMemoryService;
        this.proceduralMemoryService = proceduralMemoryService;
        this.preferenceProfileService = preferenceProfileService;
    }

    @Override
    public PromptMemoryContextDto assemble(String userId, String query, int maxChars, Map<String, Object> profileContext) {
        int safeMaxChars = Math.max(400, maxChars);
        String normalizedQuery = normalize(query);

        List<RetrievedMemoryItemDto> candidates = new ArrayList<>();

        List<ConversationTurn> recentTurns = episodicMemoryService.getRecentConversation(userId, RECENT_TURNS_LIMIT);
        String recentConversation = buildRecentConversation(recentTurns, safeMaxChars * 35 / 100, normalizedQuery, candidates);

        List<RankedSemanticMemory> semanticEntries = semanticMemoryService.searchDetailed(userId, query, SEMANTIC_LIMIT, null);
        String semanticContext = buildSemanticContext(semanticEntries, safeMaxChars * 45 / 100, normalizedQuery, candidates);

        List<ProceduralMemoryEntry> proceduralHistory = proceduralMemoryService.getHistory(userId);
        List<SkillUsageStats> usageStats = proceduralMemoryService.getSkillUsageStats(userId);
        String proceduralHints = buildProceduralHints(proceduralHistory, usageStats, safeMaxChars * 20 / 100, normalizedQuery, candidates);

        Map<String, Object> personaSnapshot = buildPersonaSnapshot(userId, profileContext);

        List<RetrievedMemoryItemDto> debugTopItems = candidates.stream()
                .sorted(Comparator.comparingDouble(RetrievedMemoryItemDto::finalScore).reversed())
                .limit(DEBUG_ITEMS_LIMIT)
                .toList();

        return new PromptMemoryContextDto(
                recentConversation,
                semanticContext,
                proceduralHints,
                personaSnapshot,
                debugTopItems
        );
    }

    private String buildRecentConversation(List<ConversationTurn> turns,
                                           int maxChars,
                                           String normalizedQuery,
                                           List<RetrievedMemoryItemDto> candidates) {
        if (turns.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (ConversationTurn turn : turns) {
            String line = turn.role() + ": " + turn.content();
            builder.append(line).append('\n');
            double rel = lexicalOverlap(normalizedQuery, turn.content());
            double rec = recencyDecayHours(ageHours(turn.createdAt()), 24.0);
            double relia = 0.7;
            double score = score(rel, rec, relia, 0.2);
            candidates.add(new RetrievedMemoryItemDto(
                    "episodic",
                    line,
                    rel,
                    rec,
                    relia,
                    score,
                    turn.createdAt() == null ? 0L : turn.createdAt().toEpochMilli()
            ));
        }
        return clip(builder.toString().trim(), maxChars);
    }

    private String buildSemanticContext(List<RankedSemanticMemory> entries,
                                        int maxChars,
                                        String normalizedQuery,
                                        List<RetrievedMemoryItemDto> candidates) {
        if (entries.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        int appended = 0;
        for (RankedSemanticMemory ranked : entries) {
            if (!shouldIncludeLayeredEntry(ranked.layer(), appended)) {
                continue;
            }
            SemanticMemoryEntry entry = ranked.entry();
            double rel = Math.max(lexicalOverlap(normalizedQuery, entry.text()), ranked.lexicalScore());
            if (!shouldIncludeSemanticEntry(ranked, rel, appended)) {
                continue;
            }
            String label = semanticContextLabel(ranked);
            String candidateType = semanticCandidateType(ranked);
            String text = entry.text();
            builder.append("- ");
            if (!label.isBlank()) {
                builder.append(label).append(' ');
            }
            builder.append(text).append('\n');
            double rec = ranked.recencyScore();
            double relia = semanticReliability(ranked);
            double typeBoost = semanticTypeBoost(ranked);
            double score = "semantic-routing".equals(candidateType)
                    ? semanticRoutingScore(rel, rec, relia, typeBoost)
                    : score(rel, rec, relia, typeBoost);
            candidates.add(new RetrievedMemoryItemDto(
                    candidateType,
                    text,
                    rel,
                    rec,
                    relia,
                    score,
                    entry.createdAt() == null ? 0L : entry.createdAt().toEpochMilli()
            ));
            appended++;
        }
        return clip(builder.toString().trim(), maxChars);
    }

    private boolean shouldIncludeLayeredEntry(MemoryLayer layer, int appended) {
        if (appended < LAYER_PRIORITY_WINDOW) {
            return true;
        }
        return layer == MemoryLayer.FACT || layer == MemoryLayer.WORKING;
    }

    private String buildProceduralHints(List<ProceduralMemoryEntry> history,
                                        List<SkillUsageStats> stats,
                                        int maxChars,
                                        String normalizedQuery,
                                        List<RetrievedMemoryItemDto> candidates) {
        if (history.isEmpty() || stats.isEmpty()) {
            return "";
        }
        Map<String, ProceduralMemoryEntry> latestBySkill = new LinkedHashMap<>();
        for (int i = history.size() - 1; i >= 0; i--) {
            ProceduralMemoryEntry entry = history.get(i);
            latestBySkill.putIfAbsent(entry.skillName(), entry);
        }

        StringBuilder builder = new StringBuilder();
        for (SkillUsageStats stat : stats.stream().sorted(Comparator.comparingLong(SkillUsageStats::successCount).reversed()).toList()) {
            ProceduralMemoryEntry latest = latestBySkill.get(stat.skillName());
            if (latest == null) {
                continue;
            }
            long total = Math.max(1L, stat.totalCount());
            double successRate = stat.successCount() / (double) total;
            double rel = lexicalOverlap(normalizedQuery, stat.skillName() + " " + latest.input());
            double rec = recencyDecayHours(ageHours(latest.createdAt()), 72.0);
            double relia = 0.25 + (0.35 * Math.max(0.0, Math.min(1.0, successRate)));
            double score = proceduralScore(rel, rec, relia);

            String line = "- skill=" + stat.skillName() + ", successRate=" + String.format(Locale.ROOT, "%.2f", successRate);
            builder.append(line).append('\n');
            candidates.add(new RetrievedMemoryItemDto(
                    "procedural",
                    line,
                    rel,
                    rec,
                    relia,
                    score,
                    latest.createdAt() == null ? 0L : latest.createdAt().toEpochMilli()
            ));
        }

        return clip(builder.toString().trim(), maxChars);
    }

    private Map<String, Object> buildPersonaSnapshot(String userId, Map<String, Object> profileContext) {
        PreferenceProfile profile = preferenceProfileService.getProfile(userId);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        putIfHasText(snapshot, "assistantName", profile.assistantName());
        putIfHasText(snapshot, "role", profile.role());
        putIfHasText(snapshot, "style", profile.style());
        putIfHasText(snapshot, "language", profile.language());
        putIfHasText(snapshot, "timezone", profile.timezone());
        putIfHasText(snapshot, "preferredChannel", profile.preferredChannel());
        if (profileContext != null && !profileContext.isEmpty()) {
            putIfHasText(snapshot, "assistantName", asText(profileContext.get("assistantName")));
            putIfHasText(snapshot, "role", asText(profileContext.get("role")));
            putIfHasText(snapshot, "style", asText(profileContext.get("style")));
            putIfHasText(snapshot, "language", asText(profileContext.get("language")));
            putIfHasText(snapshot, "timezone", asText(profileContext.get("timezone")));
            putIfHasText(snapshot, "preferredChannel", asText(profileContext.get("preferredChannel")));
        }
        return snapshot;
    }

    private double score(double rel, double rec, double relia, double typeBoost) {
        return 0.55 * rel + 0.25 * rec + 0.15 * relia + 0.05 * typeBoost;
    }

    private double proceduralScore(double rel, double rec, double relia) {
        return 0.62 * rel + 0.18 * rec + 0.12 * relia + 0.08 * 0.18;
    }

    private double semanticRoutingScore(double rel, double rec, double relia, double typeBoost) {
        return 0.38 * rel + 0.18 * rec + 0.14 * relia + 0.05 * typeBoost;
    }

    private double recencyDecayHours(long ageHours, double halfLifeHours) {
        if (ageHours <= 0) {
            return 1.0;
        }
        double decay = Math.exp(-Math.log(2.0) * (ageHours / halfLifeHours));
        return Math.max(0.0, Math.min(1.0, decay));
    }

    private long ageHours(Instant createdAt) {
        if (createdAt == null) {
            return Long.MAX_VALUE;
        }
        return Math.max(0, Duration.between(createdAt, Instant.now()).toHours());
    }

    private double lexicalOverlap(String normalizedQuery, String text) {
        String normalizedText = normalize(text);
        if (normalizedQuery.isBlank() || normalizedText.isBlank()) {
            return 0.0;
        }
        String[] queryTokens = normalizedQuery.split("\\s+");
        int matched = 0;
        for (String token : queryTokens) {
            if (!token.isBlank() && normalizedText.contains(token)) {
                matched++;
            }
        }
        return queryTokens.length == 0 ? 0.0 : Math.min(1.0, matched / (double) queryTokens.length);
    }

    private boolean shouldIncludeSemanticEntry(RankedSemanticMemory ranked, double relevance, int appended) {
        if (isConversationRollup(ranked)) {
            return relevance >= 0.34 || appended == 0;
        }
        if (isSemanticSummary(ranked)) {
            return relevance >= 0.18 || appended < 2;
        }
        return true;
    }

    private String semanticContextLabel(RankedSemanticMemory ranked) {
        if (isConversationRollup(ranked)) {
            return "[rollup]";
        }
        if (isSemanticSummary(ranked)) {
            return "[routing]";
        }
        return switch (ranked.layer()) {
            case FACT -> "[fact]";
            case WORKING -> "[working]";
            case BUFFER -> "[buffer]";
            case SEMANTIC -> "";
        };
    }

    private String semanticCandidateType(RankedSemanticMemory ranked) {
        return (isConversationRollup(ranked) || isSemanticSummary(ranked)) ? "semantic-routing" : "semantic";
    }

    private double semanticReliability(RankedSemanticMemory ranked) {
        if (isConversationRollup(ranked)) {
            return 0.36;
        }
        if (isSemanticSummary(ranked)) {
            return 0.46;
        }
        return switch (ranked.layer()) {
            case FACT -> 0.9;
            case WORKING -> 0.82;
            case BUFFER -> 0.78;
            case SEMANTIC -> 0.75;
        };
    }

    private double semanticTypeBoost(RankedSemanticMemory ranked) {
        if (isConversationRollup(ranked)) {
            return 0.12;
        }
        if (isSemanticSummary(ranked)) {
            return 0.16;
        }
        return 0.6;
    }

    private boolean isConversationRollup(RankedSemanticMemory ranked) {
        String bucket = ranked == null ? "" : normalize(ranked.bucket());
        return CONVERSATION_ROLLUP_BUCKET.equals(bucket);
    }

    private boolean isSemanticSummary(RankedSemanticMemory ranked) {
        String text = ranked == null || ranked.entry() == null ? "" : ranked.entry().text();
        return normalize(text).startsWith(normalize(SEMANTIC_SUMMARY_PREFIX));
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    private String clip(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text == null ? "" : text;
        }
        if (maxChars <= 1) {
            return text.substring(0, maxChars);
        }
        return text.substring(0, maxChars - 1) + "...";
    }

    private String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private void putIfHasText(Map<String, Object> target, String key, String value) {
        if (value == null) {
            return;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        target.put(key, trimmed);
    }
}
