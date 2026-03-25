package com.zhongbo.mindos.assistant.memory;

import com.zhongbo.mindos.assistant.memory.model.ConversationTurn;
import com.zhongbo.mindos.assistant.memory.model.MemorySyncBatch;
import com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class MemoryConsolidationService {

    private static final int MAX_EMBEDDING_SIZE = 8;
    private static final String PROP_KEY_SIGNAL_CONSTRAINT_TERMS = "mindos.memory.key-signal.constraint-terms";
    private static final String PROP_KEY_SIGNAL_DEADLINE_TERMS = "mindos.memory.key-signal.deadline-terms";
    private static final String PROP_KEY_SIGNAL_CONTACT_TERMS = "mindos.memory.key-signal.contact-terms";

    private static final Pattern CONTROL_EXCEPT_LINE_BREAKS_PATTERN = Pattern.compile("[\\p{Cntrl}&&[^\\n\\t]]");
    private static final Pattern HORIZONTAL_WHITESPACE_PATTERN = Pattern.compile("[ \\t\\x0B\\f\\r]+");
    private static final Pattern TRIMMED_NEWLINE_PATTERN = Pattern.compile(" *\\n+ *");
    private static final Pattern WHITESPACE_SPLIT_PATTERN = Pattern.compile("\\s+");
    private static final Pattern DIGIT_PATTERN = Pattern.compile("\\d");
    private static final Pattern DATE_TIME_PATTERN = Pattern.compile("(\\d{1,2}[:：]\\d{2}|\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}|\\d+\\s*(?:天|周|月|年|小时|分钟)|(?:截止|deadline|due|before|之前|内))", Pattern.CASE_INSENSITIVE);

    private final Set<String> constraintTerms = loadTerms(PROP_KEY_SIGNAL_CONSTRAINT_TERMS,
            "不要", "不能", "禁止", "避免", "务必", "必须", "一定", "优先", "风险", "不可");
    private final Set<String> deadlineTerms = loadTerms(PROP_KEY_SIGNAL_DEADLINE_TERMS,
            "截止", "deadline", "due", "before", "之前", "内");
    private final Set<String> contactTerms = loadTerms(PROP_KEY_SIGNAL_CONTACT_TERMS,
            "http://", "https://", "@", "邮箱", "email", "电话", "phone");

    public MemorySyncBatch consolidateBatch(MemorySyncBatch batch) {
        if (batch == null) {
            return new MemorySyncBatch(null, List.of(), List.of(), List.of());
        }
        return new MemorySyncBatch(
                normalizeIdentifier(batch.eventId()),
                batch.episodic().stream()
                        .map(this::consolidateConversationTurn)
                        .filter(turn -> turn != null && !turn.content().isBlank())
                        .toList(),
                deduplicateSemanticEntries(batch.semantic()),
                batch.procedural().stream()
                        .map(this::consolidateProceduralEntry)
                        .filter(entry -> entry != null && !entry.skillName().isBlank() && !entry.input().isBlank())
                        .toList()
        );
    }

    public ConversationTurn consolidateConversationTurn(ConversationTurn turn) {
        if (turn == null) {
            return null;
        }
        return new ConversationTurn(
                normalizeRole(turn.role()),
                normalizeText(turn.content()),
                turn.createdAt() == null ? Instant.now() : turn.createdAt()
        );
    }

    public SemanticMemoryEntry consolidateSemanticEntry(SemanticMemoryEntry entry) {
        if (entry == null) {
            return null;
        }
        String text = normalizeText(entry.text());
        Instant createdAt = entry.createdAt() == null ? Instant.now() : entry.createdAt();
        return new SemanticMemoryEntry(text, compactEmbedding(entry.embedding(), text), createdAt);
    }

    public ProceduralMemoryEntry consolidateProceduralEntry(ProceduralMemoryEntry entry) {
        if (entry == null) {
            return null;
        }
        return new ProceduralMemoryEntry(
                normalizeIdentifier(entry.skillName()),
                normalizeText(entry.input()),
                entry.success(),
                entry.createdAt() == null ? Instant.now() : entry.createdAt()
        );
    }

    public String semanticKey(String text) {
        return normalizeText(text).toLowerCase(Locale.ROOT);
    }

    public boolean containsKeySignal(String text) {
        return keySignalScore(text) > 0;
    }

    public int keySignalScore(String text) {
        String normalized = normalizeText(text);
        if (normalized.isBlank()) {
            return 0;
        }
        String normalizedLower = normalized.toLowerCase(Locale.ROOT);
        int score = 0;
        if (DIGIT_PATTERN.matcher(normalized).find()) {
            score += 2;
        }
        if (DATE_TIME_PATTERN.matcher(normalized).find()) {
            score += 3;
        }
        if (containsAnyTermNormalized(normalizedLower, constraintTerms)) {
            score += 3;
        }
        if (containsAnyTermNormalized(normalizedLower, deadlineTerms)) {
            score += 2;
        }
        if (containsAnyTermNormalized(normalizedLower, contactTerms)) {
            score += 1;
        }
        return score;
    }

    private Set<String> loadTerms(String propertyKey, String... defaults) {
        String override = System.getProperty(propertyKey);
        if (override == null || override.isBlank()) {
            return Set.of(defaults);
        }
        Set<String> terms = Arrays.stream(override.split(","))
                .map(this::normalizeText)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .filter(value -> !value.isBlank())
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
        if (terms.isEmpty()) {
            return Set.of(defaults);
        }
        return Set.copyOf(terms);
    }

    private boolean containsAnyTermNormalized(String normalizedLower, Set<String> terms) {
        for (String term : terms) {
            if (normalizedLower.contains(term)) {
                return true;
            }
        }
        return false;
    }

    public String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace('\u3000', ' ');
        normalized = CONTROL_EXCEPT_LINE_BREAKS_PATTERN.matcher(normalized).replaceAll(" ");
        normalized = HORIZONTAL_WHITESPACE_PATTERN.matcher(normalized).replaceAll(" ");
        normalized = TRIMMED_NEWLINE_PATTERN.matcher(normalized).replaceAll("\n");
        return normalized.trim();
    }

    private List<SemanticMemoryEntry> deduplicateSemanticEntries(List<SemanticMemoryEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        Map<String, SemanticMemoryEntry> unique = new LinkedHashMap<>();
        for (SemanticMemoryEntry rawEntry : entries) {
            SemanticMemoryEntry entry = consolidateSemanticEntry(rawEntry);
            if (entry == null || entry.text().isBlank()) {
                continue;
            }
            unique.merge(semanticKey(entry.text()), entry, this::mergeSemanticEntries);
        }
        return List.copyOf(unique.values());
    }

    private SemanticMemoryEntry mergeSemanticEntries(SemanticMemoryEntry existing, SemanticMemoryEntry candidate) {
        int existingPriority = semanticPriority(existing);
        int candidatePriority = semanticPriority(candidate);
        SemanticMemoryEntry preferred = candidatePriority >= existingPriority ? candidate : existing;
        SemanticMemoryEntry fallback = preferred == candidate ? existing : candidate;

        String mergedText = preferred.text();
        List<Double> mergedEmbedding = preferred.embedding().size() >= fallback.embedding().size()
                ? preferred.embedding()
                : fallback.embedding();
        Instant createdAt = existing.createdAt().isBefore(candidate.createdAt())
                ? existing.createdAt()
                : candidate.createdAt();
        return new SemanticMemoryEntry(mergedText, mergedEmbedding, createdAt);
    }

    private int semanticPriority(SemanticMemoryEntry entry) {
        if (entry == null) {
            return Integer.MIN_VALUE;
        }
        String text = normalizeText(entry.text());
        int score = keySignalScore(text) * 10;
        score += Math.min(text.length(), 256);
        score += Math.min(entry.embedding().size(), MAX_EMBEDDING_SIZE) * 2;
        return score;
    }

    private List<Double> compactEmbedding(List<Double> embedding, String text) {
        if (embedding == null || embedding.isEmpty()) {
            return buildTextSignature(text);
        }

        List<Double> compacted = new ArrayList<>();
        for (Double value : embedding) {
            if (value == null || !Double.isFinite(value)) {
                continue;
            }
            compacted.add(round(value));
            if (compacted.size() >= MAX_EMBEDDING_SIZE) {
                break;
            }
        }
        return compacted.isEmpty() ? buildTextSignature(text) : List.copyOf(compacted);
    }

    private List<Double> buildTextSignature(String text) {
        String normalized = normalizeText(text);
        if (normalized.isBlank()) {
            return List.of();
        }
        int length = normalized.length();
        long tokenCount = WHITESPACE_SPLIT_PATTERN.split(normalized).length;
        long digitCount = normalized.chars().filter(Character::isDigit).count();
        long cjkCount = normalized.codePoints()
                .filter(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN)
                .count();
        double hashBucket = Math.abs(normalized.hashCode() % 1000) / 1000.0;
        return List.of(
                round(Math.min(length, 512) / 512.0),
                round(Math.min(tokenCount, 64) / 64.0),
                round((double) digitCount / length),
                round((double) cjkCount / length),
                round(hashBucket)
        );
    }

    private String normalizeRole(String role) {
        String normalized = normalizeIdentifier(role).toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return "user";
        }
        return normalized;
    }

    private String normalizeIdentifier(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
    }

    private double round(double value) {
        return Math.round(value * 10_000d) / 10_000d;
    }
}
