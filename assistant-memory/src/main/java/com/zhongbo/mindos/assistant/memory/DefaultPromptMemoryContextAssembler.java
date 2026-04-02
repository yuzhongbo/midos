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

    private static final int RECENT_TURNS_LIMIT = 6;
    private static final int SEMANTIC_LIMIT = 10;
    private static final int DEBUG_ITEMS_LIMIT = 12;

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
            builder.append("- ");
            if (ranked.layer() == MemoryLayer.FACT) {
                builder.append("[fact] ");
            } else if (ranked.layer() == MemoryLayer.WORKING) {
                builder.append("[working] ");
            } else if (ranked.layer() == MemoryLayer.BUFFER) {
                builder.append("[buffer] ");
            }
            builder.append(entry.text()).append('\n');
            double rel = Math.max(lexicalOverlap(normalizedQuery, entry.text()), ranked.lexicalScore());
            double rec = ranked.recencyScore();
            double relia = switch (ranked.layer()) {
                case FACT -> 0.9;
                case WORKING -> 0.82;
                case BUFFER -> 0.78;
                case SEMANTIC -> 0.75;
            };
            double score = score(rel, rec, relia, 0.6);
            candidates.add(new RetrievedMemoryItemDto(
                    "semantic",
                    entry.text(),
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
        if (appended < 6) {
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
            double relia = Math.max(0.2, successRate);
            double score = score(rel, rec, relia, 0.8);

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
