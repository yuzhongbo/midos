package com.zhongbo.mindos.assistant.memory;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Immutable query object for the new multi-layer memory router.
 */
public final class MemoryQuery {

    private final String userId;
    private final String content;
    private final MemoryLayer layer;
    private final List<MemoryLayer> layers;
    private final String sessionId;
    private final String topic;
    private final Map<String, Object> metadata;
    private final int limit;

    private MemoryQuery(Builder builder) {
        this.userId = builder.userId == null ? "" : builder.userId.trim();
        this.content = builder.content == null ? "" : builder.content.trim();
        this.layer = builder.layer;
        this.layers = builder.layers == null ? List.of() : List.copyOf(builder.layers);
        this.sessionId = builder.sessionId == null ? "" : builder.sessionId.trim();
        this.topic = builder.topic == null ? "" : builder.topic.trim();
        this.metadata = builder.metadata == null ? Map.of() : Map.copyOf(builder.metadata);
        this.limit = builder.limit <= 0 ? 10 : builder.limit;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String userId() {
        return userId;
    }

    public String content() {
        return content;
    }

    public MemoryLayer layer() {
        return layer;
    }

    public List<MemoryLayer> layers() {
        return layers;
    }

    public String sessionId() {
        return sessionId;
    }

    public String topic() {
        return topic;
    }

    public Map<String, Object> metadata() {
        return metadata;
    }

    public int limit() {
        return limit;
    }

    public boolean matchesContent(String candidate) {
        if (content.isBlank()) {
            return true;
        }
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        return candidate.toLowerCase(Locale.ROOT).contains(content.toLowerCase(Locale.ROOT));
    }

    public String metadataText(String key) {
        Object value = metadata.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    public List<MemoryLayer> requestedLayers() {
        if (layer != null) {
            return List.of(layer);
        }
        return layers;
    }

    public static final class Builder {
        private String userId;
        private String content;
        private MemoryLayer layer;
        private List<MemoryLayer> layers;
        private String sessionId;
        private String topic;
        private Map<String, Object> metadata;
        private int limit = 10;

        private Builder() {
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder layer(MemoryLayer layer) {
            this.layer = layer;
            return this;
        }

        public Builder layers(List<MemoryLayer> layers) {
            this.layers = layers;
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder topic(String topic) {
            this.topic = topic;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        public Builder limit(int limit) {
            this.limit = limit;
            return this;
        }

        public MemoryQuery build() {
            return new MemoryQuery(this);
        }
    }
}
