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
    private final Embedding embedding = new Embedding();
    private final Layers layers = new Layers();

    public WriteGate getWriteGate() {
        return writeGate;
    }

    public Search getSearch() {
        return search;
    }

    public Embedding getEmbedding() {
        return embedding;
    }

    public Layers getLayers() {
        return layers;
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
        properties.getSearch().getHybrid().setEnabled(Boolean.parseBoolean(
                System.getProperty("mindos.memory.search.hybrid.enabled", "false")));
        properties.getSearch().getHybrid().setLexicalWeight(parseRatio(
                System.getProperty("mindos.memory.search.hybrid.lexical-weight"), 0.55));
        properties.getSearch().getHybrid().setK1(parsePositiveDouble(
                System.getProperty("mindos.memory.search.hybrid.k1"), 1.2));
        properties.getSearch().getHybrid().setB(parsePositiveDouble(
                System.getProperty("mindos.memory.search.hybrid.b"), 0.75));
        properties.getEmbedding().getLocal().setEnabled(Boolean.parseBoolean(
                System.getProperty("mindos.memory.embedding.local.enabled", "false")));
        properties.getEmbedding().getLocal().setDimensions(parsePositiveInt(
                System.getProperty("mindos.memory.embedding.local.dimensions"), 16));
        properties.getEmbedding().getOnnx().setEnabled(Boolean.parseBoolean(
                System.getProperty("mindos.memory.embedding.onnx.enabled", "false")));
        properties.getEmbedding().getOnnx().setModelPath(System.getProperty(
                "mindos.memory.embedding.onnx.model-path", ""));
        properties.getEmbedding().getOnnx().setTokenizerPath(System.getProperty(
                "mindos.memory.embedding.onnx.tokenizer-path", ""));
        properties.getEmbedding().getOnnx().setOutputName(System.getProperty(
                "mindos.memory.embedding.onnx.output-name", "sentence_embedding"));
        properties.getEmbedding().getOnnx().setDimensions(parsePositiveInt(
                System.getProperty("mindos.memory.embedding.onnx.dimensions"), 384));
        properties.getEmbedding().getOnnx().setBatchSize(parsePositiveInt(
                System.getProperty("mindos.memory.embedding.onnx.batch-size"), 16));
        properties.getEmbedding().getOnnx().setMaxLength(parsePositiveInt(
                System.getProperty("mindos.memory.embedding.onnx.max-length"), 512));
        properties.getEmbedding().getOnnx().setIntraOpThreads(parsePositiveInt(
                System.getProperty("mindos.memory.embedding.onnx.intra-op-threads"), 1));
        properties.getEmbedding().getOnnx().setInterOpThreads(parsePositiveInt(
                System.getProperty("mindos.memory.embedding.onnx.inter-op-threads"), 1));
        properties.getEmbedding().getOnnx().getCache().setEnabled(Boolean.parseBoolean(
                System.getProperty("mindos.memory.embedding.onnx.cache.enabled", "true")));
        properties.getEmbedding().getOnnx().getCache().setMaximumSize(parsePositiveInt(
                System.getProperty("mindos.memory.embedding.onnx.cache.maximum-size"), 10_000));
        properties.getEmbedding().getOnnx().getCache().setExpireAfterAccessSeconds(parsePositiveInt(
                System.getProperty("mindos.memory.embedding.onnx.cache.expire-after-access-seconds"), 3600));
        properties.getLayers().setEnabled(Boolean.parseBoolean(
                System.getProperty("mindos.memory.layers.enabled", "false")));
        properties.getLayers().setBufferHours(parsePositiveInt(
                System.getProperty("mindos.memory.layers.buffer-hours"), 6));
        properties.getLayers().setWorkingHours(parsePositiveInt(
                System.getProperty("mindos.memory.layers.working-hours"), 72));
        properties.getLayers().setFactMaxChars(parsePositiveInt(
                System.getProperty("mindos.memory.layers.fact-max-chars"), 160));
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
        private final Hybrid hybrid = new Hybrid();

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

        public Hybrid getHybrid() {
            return hybrid;
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

        public static class Hybrid {
            private boolean enabled = false;
            private double lexicalWeight = 0.55;
            private double k1 = 1.2;
            private double b = 0.75;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public double getLexicalWeight() {
                return lexicalWeight;
            }

            public void setLexicalWeight(double lexicalWeight) {
                if (!Double.isFinite(lexicalWeight)) {
                    this.lexicalWeight = 0.55;
                    return;
                }
                this.lexicalWeight = Math.max(0.0, Math.min(1.0, lexicalWeight));
            }

            public double getK1() {
                return k1;
            }

            public void setK1(double k1) {
                this.k1 = Double.isFinite(k1) && k1 > 0 ? k1 : 1.2;
            }

            public double getB() {
                return b;
            }

            public void setB(double b) {
                if (!Double.isFinite(b)) {
                    this.b = 0.75;
                    return;
                }
                this.b = Math.max(0.0, Math.min(1.0, b));
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

    public static class Embedding {
        private final Local local = new Local();
        private final Onnx onnx = new Onnx();

        public Local getLocal() {
            return local;
        }

        public Onnx getOnnx() {
            return onnx;
        }

        public static class Local {
            private boolean enabled = false;
            private int dimensions = 16;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public int getDimensions() {
                return dimensions;
            }

            public void setDimensions(int dimensions) {
                this.dimensions = dimensions > 0 ? dimensions : 16;
            }
        }

        public static class Onnx {
            private boolean enabled = false;
            private String modelPath = "";
            private String tokenizerPath = "";
            private String outputName = "sentence_embedding";
            private int dimensions = 384;
            private int batchSize = 16;
            private int maxLength = 512;
            private int intraOpThreads = 1;
            private int interOpThreads = 1;
            private final Cache cache = new Cache();

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public String getModelPath() {
                return modelPath;
            }

            public void setModelPath(String modelPath) {
                this.modelPath = modelPath == null ? "" : modelPath.trim();
            }

            public String getTokenizerPath() {
                return tokenizerPath;
            }

            public void setTokenizerPath(String tokenizerPath) {
                this.tokenizerPath = tokenizerPath == null ? "" : tokenizerPath.trim();
            }

            public String getOutputName() {
                return outputName;
            }

            public void setOutputName(String outputName) {
                this.outputName = outputName == null || outputName.isBlank() ? "sentence_embedding" : outputName.trim();
            }

            public int getDimensions() {
                return dimensions;
            }

            public void setDimensions(int dimensions) {
                this.dimensions = dimensions > 0 ? dimensions : 384;
            }

            public int getBatchSize() {
                return batchSize;
            }

            public void setBatchSize(int batchSize) {
                this.batchSize = batchSize > 0 ? batchSize : 16;
            }

            public int getMaxLength() {
                return maxLength;
            }

            public void setMaxLength(int maxLength) {
                this.maxLength = maxLength > 0 ? maxLength : 512;
            }

            public int getIntraOpThreads() {
                return intraOpThreads;
            }

            public void setIntraOpThreads(int intraOpThreads) {
                this.intraOpThreads = intraOpThreads > 0 ? intraOpThreads : 1;
            }

            public int getInterOpThreads() {
                return interOpThreads;
            }

            public void setInterOpThreads(int interOpThreads) {
                this.interOpThreads = interOpThreads > 0 ? interOpThreads : 1;
            }

            public Cache getCache() {
                return cache;
            }

            public static class Cache {
                private boolean enabled = true;
                private int maximumSize = 10_000;
                private int expireAfterAccessSeconds = 3600;

                public boolean isEnabled() {
                    return enabled;
                }

                public void setEnabled(boolean enabled) {
                    this.enabled = enabled;
                }

                public int getMaximumSize() {
                    return maximumSize;
                }

                public void setMaximumSize(int maximumSize) {
                    this.maximumSize = maximumSize > 0 ? maximumSize : 10_000;
                }

                public int getExpireAfterAccessSeconds() {
                    return expireAfterAccessSeconds;
                }

                public void setExpireAfterAccessSeconds(int expireAfterAccessSeconds) {
                    this.expireAfterAccessSeconds = expireAfterAccessSeconds > 0 ? expireAfterAccessSeconds : 3600;
                }
            }
        }
    }

    public static class Layers {
        private boolean enabled = false;
        private int bufferHours = 6;
        private int workingHours = 72;
        private int factMaxChars = 160;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getBufferHours() {
            return bufferHours;
        }

        public void setBufferHours(int bufferHours) {
            this.bufferHours = bufferHours > 0 ? bufferHours : 6;
        }

        public int getWorkingHours() {
            return workingHours;
        }

        public void setWorkingHours(int workingHours) {
            this.workingHours = workingHours > 0 ? workingHours : 72;
        }

        public int getFactMaxChars() {
            return factMaxChars;
        }

        public void setFactMaxChars(int factMaxChars) {
            this.factMaxChars = factMaxChars > 0 ? factMaxChars : 160;
        }
    }
}
