package com.zhongbo.mindos.assistant.memory;

import com.zhongbo.mindos.assistant.memory.model.ConversationTurn;
import com.zhongbo.mindos.assistant.memory.model.MemorySyncBatch;
import com.zhongbo.mindos.assistant.memory.model.ProceduralMemoryEntry;
import com.zhongbo.mindos.assistant.memory.model.SemanticMemoryEntry;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class MemoryConsolidationService {

    private static final int MAX_EMBEDDING_SIZE = 8;

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

    public String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replace('\u3000', ' ')
                .replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", " ")
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll(" *\\n+ *", "\n")
                .trim();
    }

    private List<SemanticMemoryEntry> deduplicateSemanticEntries(List<SemanticMemoryEntry> entries) {
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
        String mergedText = candidate.text().length() >= existing.text().length()
                ? candidate.text()
                : existing.text();
        List<Double> mergedEmbedding = candidate.embedding().size() >= existing.embedding().size()
                ? candidate.embedding()
                : existing.embedding();
        Instant createdAt = existing.createdAt().isBefore(candidate.createdAt())
                ? existing.createdAt()
                : candidate.createdAt();
        return new SemanticMemoryEntry(mergedText, mergedEmbedding, createdAt);
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
        long tokenCount = normalized.split("\\s+").length;
        long digitCount = normalized.chars().filter(Character::isDigit).count();
        long cjkCount = normalized.codePoints()
                .filter(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN)
                .count();
        double hashBucket = Math.abs(normalized.hashCode() % 1000) / 1000.0;
        return List.of(
                round(Math.min(length, 512) / 512.0),
                round(Math.min(tokenCount, 64) / 64.0),
                round(length == 0 ? 0.0 : (double) digitCount / length),
                round(length == 0 ? 0.0 : (double) cjkCount / length),
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
