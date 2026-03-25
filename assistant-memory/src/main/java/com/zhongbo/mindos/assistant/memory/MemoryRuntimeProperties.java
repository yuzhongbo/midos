package com.zhongbo.mindos.assistant.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "mindos.memory")
public class MemoryRuntimeProperties {

    private final WriteGate writeGate = new WriteGate();
    private final Search search = new Search();

    public WriteGate getWriteGate() {
        return writeGate;
    }

    public Search getSearch() {
        return search;
    }

    public static MemoryRuntimeProperties fromSystemProperties() {
        MemoryRuntimeProperties properties = new MemoryRuntimeProperties();
        properties.getWriteGate().setEnabled(Boolean.parseBoolean(System.getProperty("mindos.memory.write-gate.enabled", "false")));
        properties.getWriteGate().setMinLength(parsePositiveInt(System.getProperty("mindos.memory.write-gate.min-length"), 10));
        properties.getWriteGate().setSemanticDuplicateEnabled(Boolean.parseBoolean(System.getProperty(
                "mindos.memory.write-gate.semantic-duplicate.enabled", "false")));
        properties.getWriteGate().setSemanticDuplicateThreshold(parseRatio(
                System.getProperty("mindos.memory.write-gate.semantic-duplicate.threshold"), 0.82));

        Map<String, Integer> bucketMinLength = new LinkedHashMap<>();
        System.getProperties().forEach((key, value) -> {
            String propertyKey = String.valueOf(key);
            if (!propertyKey.startsWith("mindos.memory.write-gate.min-length.")) {
                return;
            }
            String bucket = propertyKey.substring("mindos.memory.write-gate.min-length.".length()).trim();
            if (bucket.isBlank()) {
                return;
            }
            bucketMinLength.put(bucket, parsePositiveInt(String.valueOf(value), properties.getWriteGate().getMinLength()));
        });
        properties.getWriteGate().setMinLengthByBucket(bucketMinLength);

        properties.getSearch().setDecayHalfLifeHours(parsePositiveDouble(System.getProperty("mindos.memory.search.decay-half-life-hours"), 72.0));
        properties.getSearch().setCrossBucketMax(parsePositiveInt(System.getProperty("mindos.memory.search.cross-bucket.max"), 2));
        properties.getSearch().setCrossBucketRatio(parseRatio(System.getProperty("mindos.memory.search.cross-bucket.ratio"), 0.5));
        properties.getSearch().setCoarseMinCandidates(parsePositiveInt(
                System.getProperty("mindos.memory.search.coarse.min-candidates"), 128));
        properties.getSearch().setCoarseMultiplier(parsePositiveInt(
                System.getProperty("mindos.memory.search.coarse.multiplier"), 8));
        return properties;
    }

    private static int parsePositiveInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static double parsePositiveDouble(String raw, double fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            double value = Double.parseDouble(raw.trim());
            if (Double.isFinite(value) && value > 0) {
                return value;
            }
            return fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static double parseRatio(String raw, double fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            double value = Double.parseDouble(raw.trim());
            if (!Double.isFinite(value)) {
                return fallback;
            }
            return Math.max(0.0, Math.min(1.0, value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    public static class WriteGate {
        private boolean enabled = false;
        private int minLength = 10;
        private boolean semanticDuplicateEnabled = false;
        private double semanticDuplicateThreshold = 0.82;
        private Map<String, Integer> minLengthByBucket = new LinkedHashMap<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMinLength() {
            return minLength;
        }

        public void setMinLength(int minLength) {
            this.minLength = minLength > 0 ? minLength : 10;
        }

        public Map<String, Integer> getMinLengthByBucket() {
            return minLengthByBucket;
        }

        public void setMinLengthByBucket(Map<String, Integer> minLengthByBucket) {
            this.minLengthByBucket = minLengthByBucket == null ? new LinkedHashMap<>() : new LinkedHashMap<>(minLengthByBucket);
        }

        public boolean isSemanticDuplicateEnabled() {
            return semanticDuplicateEnabled;
        }

        public void setSemanticDuplicateEnabled(boolean semanticDuplicateEnabled) {
            this.semanticDuplicateEnabled = semanticDuplicateEnabled;
        }

        public double getSemanticDuplicateThreshold() {
            return semanticDuplicateThreshold;
        }

        public void setSemanticDuplicateThreshold(double semanticDuplicateThreshold) {
            if (!Double.isFinite(semanticDuplicateThreshold)) {
                this.semanticDuplicateThreshold = 0.82;
                return;
            }
            this.semanticDuplicateThreshold = Math.max(0.0, Math.min(1.0, semanticDuplicateThreshold));
        }
    }

    public static class Search {
        private double decayHalfLifeHours = 72.0;
        private int coarseMinCandidates = 128;
        private int coarseMultiplier = 8;
        private final CrossBucket crossBucket = new CrossBucket();

        public double getDecayHalfLifeHours() {
            return decayHalfLifeHours;
        }

        public void setDecayHalfLifeHours(double decayHalfLifeHours) {
            this.decayHalfLifeHours = Double.isFinite(decayHalfLifeHours) && decayHalfLifeHours > 0 ? decayHalfLifeHours : 72.0;
        }

        public int getCoarseMinCandidates() {
            return coarseMinCandidates;
        }

        public void setCoarseMinCandidates(int coarseMinCandidates) {
            this.coarseMinCandidates = coarseMinCandidates > 0 ? coarseMinCandidates : 128;
        }

        public int getCoarseMultiplier() {
            return coarseMultiplier;
        }

        public void setCoarseMultiplier(int coarseMultiplier) {
            this.coarseMultiplier = coarseMultiplier > 0 ? coarseMultiplier : 8;
        }

        public CrossBucket getCrossBucket() {
            return crossBucket;
        }

        public int getCrossBucketMax() {
            return crossBucket.getMax();
        }

        public void setCrossBucketMax(int crossBucketMax) {
            crossBucket.setMax(crossBucketMax);
        }

        public double getCrossBucketRatio() {
            return crossBucket.getRatio();
        }

        public void setCrossBucketRatio(double crossBucketRatio) {
            crossBucket.setRatio(crossBucketRatio);
        }

        public static class CrossBucket {
            private int max = 2;
            private double ratio = 0.5;

            public int getMax() {
                return max;
            }

            public void setMax(int max) {
                this.max = max > 0 ? max : 2;
            }

            public double getRatio() {
                return ratio;
            }

            public void setRatio(double ratio) {
                if (!Double.isFinite(ratio)) {
                    this.ratio = 0.5;
                    return;
                }
                this.ratio = Math.max(0.0, Math.min(1.0, ratio));
            }
        }
    }
}

