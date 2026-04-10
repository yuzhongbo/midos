package com.zhongbo.mindos.assistant.memory;

import com.zhongbo.mindos.assistant.memory.graph.GraphMemory;
import com.zhongbo.mindos.assistant.memory.graph.MemoryNode;
import com.zhongbo.mindos.assistant.memory.model.VectorSearchResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class MemoryFacade {

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("([A-Za-z]{2,}[-_][A-Za-z0-9_-]+|stu-[A-Za-z0-9_-]+)");
    private static final Pattern INTEGER_PATTERN = Pattern.compile("(-?\\d+)");
    private static final Pattern DOUBLE_PATTERN = Pattern.compile("(-?\\d+(?:\\.\\d+)?)");

    private final GraphMemory graphMemory;
    private final VectorMemory vectorMemory;
    private final int vectorTopK;

    public MemoryFacade(GraphMemory graphMemory, VectorMemory vectorMemory) {
        this(graphMemory, vectorMemory, 5);
    }

    @Autowired
    public MemoryFacade(GraphMemory graphMemory,
                        VectorMemory vectorMemory,
                        @Value("${mindos.memory.hybrid.vector-top-k:5}") int vectorTopK) {
        this.graphMemory = graphMemory;
        this.vectorMemory = vectorMemory;
        this.vectorTopK = Math.max(1, vectorTopK);
    }

    public Optional<Object> infer(String userId, String key) {
        return infer(userId, key, "", Optional::empty);
    }

    public Optional<Object> infer(String userId, String key, String hint) {
        return infer(userId, key, hint, Optional::empty);
    }

    public Optional<Object> infer(String userId,
                                  String key,
                                  String hint,
                                  Supplier<Optional<Object>> defaultValueSupplier) {
        String normalizedKey = normalize(key);
        if (normalizedKey.isBlank()) {
            return safeDefault(defaultValueSupplier);
        }

        if (graphMemory != null) {
            Optional<Object> graphValue = graphMemory.infer(userId, key, hint);
            if (graphValue.isPresent() && !isBlank(graphValue.get())) {
                return graphValue;
            }
        }

        if (vectorMemory != null) {
            String query = firstNonBlank(hint, key);
            List<VectorSearchResult> results = vectorMemory.search(userId, query, vectorTopK);
            for (VectorSearchResult result : results) {
                Optional<Object> vectorValue = inferFromVectorHit(key, hint, result);
                if (vectorValue.isPresent() && !isBlank(vectorValue.get())) {
                    return vectorValue;
                }
            }
        }

        return safeDefault(defaultValueSupplier);
    }

    public List<MemoryNode> queryRelated(String userId, String nodeId) {
        if (graphMemory == null) {
            return List.of();
        }
        return graphMemory.queryRelated(userId, nodeId);
    }

    public List<MemoryNode> queryRelated(String userId, String nodeId, String relation) {
        if (graphMemory == null) {
            return List.of();
        }
        return graphMemory.queryRelated(userId, nodeId, relation);
    }

    private Optional<Object> inferFromVectorHit(String key, String hint, VectorSearchResult result) {
        if (result == null || result.record() == null) {
            return Optional.empty();
        }
        Map<String, Object> metadata = result.record().metadata() == null ? Map.of() : result.record().metadata();
        Object direct = metadata.get(key);
        if (!isBlank(direct)) {
            return Optional.of(direct);
        }
        String normalizedKey = normalize(key);
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            if (normalize(entry.getKey()).equals(normalizedKey) && !isBlank(entry.getValue())) {
                return Optional.of(entry.getValue());
            }
        }
        Object fromContent = inferFromText(key, firstNonBlank(hint, result.record().content()));
        return isBlank(fromContent) ? Optional.empty() : Optional.of(fromContent);
    }

    private Object inferFromText(String key, String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalizedKey = normalize(key);
        if (normalizedKey.endsWith("id")) {
            Matcher matcher = IDENTIFIER_PATTERN.matcher(text);
            return matcher.find() ? matcher.group(1) : null;
        }
        if (normalizedKey.contains("week") || normalizedKey.contains("hour") || normalizedKey.contains("count") || normalizedKey.contains("num")) {
            Matcher matcher = INTEGER_PATTERN.matcher(text);
            return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
        }
        if (normalizedKey.contains("rate") || normalizedKey.contains("score") || normalizedKey.contains("confidence")) {
            Matcher matcher = DOUBLE_PATTERN.matcher(text);
            return matcher.find() ? Double.parseDouble(matcher.group(1)) : null;
        }
        return null;
    }

    private Optional<Object> safeDefault(Supplier<Optional<Object>> defaultValueSupplier) {
        if (defaultValueSupplier == null) {
            return Optional.empty();
        }
        Optional<Object> value = defaultValueSupplier.get();
        return value == null ? Optional.empty() : value.filter(item -> !isBlank(item));
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

    private String normalize(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase();
    }

    private boolean isBlank(Object value) {
        return value == null || String.valueOf(value).isBlank();
    }
}
