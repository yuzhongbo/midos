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
        private final SemanticDuplicate semanticDuplicate = new SemanticDuplicate();
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
            return semanticDuplicate.isEnabled();
        }

        public void setSemanticDuplicateEnabled(boolean semanticDuplicateEnabled) {
            semanticDuplicate.setEnabled(semanticDuplicateEnabled);
        }

        public double getSemanticDuplicateThreshold() {
            return semanticDuplicate.getThreshold();
        }

        public void setSemanticDuplicateThreshold(double semanticDuplicateThreshold) {
            semanticDuplicate.setThreshold(semanticDuplicateThreshold);
        }

        public SemanticDuplicate getSemanticDuplicate() {
            return semanticDuplicate;
        }

        public static class SemanticDuplicate {
            private boolean enabled = false;
            private double threshold = 0.82;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public double getThreshold() {
                return threshold;
            }

            public void setThreshold(double threshold) {
                if (!Double.isFinite(threshold)) {
                    this.threshold = 0.82;
                    return;
                }
                this.threshold = Math.max(0.0, Math.min(1.0, threshold));
            }
        }
    }

    public static class Search {
        private double decayHalfLifeHours = 72.0;
        private final Coarse coarse = new Coarse();
        private final CrossBucket crossBucket = new CrossBucket();

        public double getDecayHalfLifeHours() {
            return decayHalfLifeHours;
        }

        public void setDecayHalfLifeHours(double decayHalfLifeHours) {
            this.decayHalfLifeHours = Double.isFinite(decayHalfLifeHours) && decayHalfLifeHours > 0 ? decayHalfLifeHours : 72.0;
        }

        public int getCoarseMinCandidates() {
            return coarse.getMinCandidates();
        }

        public void setCoarseMinCandidates(int coarseMinCandidates) {
            coarse.setMinCandidates(coarseMinCandidates);
        }

        public int getCoarseMultiplier() {
            return coarse.getMultiplier();
        }

        public void setCoarseMultiplier(int coarseMultiplier) {
            coarse.setMultiplier(coarseMultiplier);
        }

        public Coarse getCoarse() {
            return coarse;
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

        public static class Coarse {
            private int minCandidates = 128;
            private int multiplier = 8;

            public int getMinCandidates() {
                return minCandidates;
            }

            public void setMinCandidates(int minCandidates) {
                this.minCandidates = minCandidates > 0 ? minCandidates : 128;
            }

            public int getMultiplier() {
                return multiplier;
            }

            public void setMultiplier(int multiplier) {
                this.multiplier = multiplier > 0 ? multiplier : 8;
            }
        }
    }
}

