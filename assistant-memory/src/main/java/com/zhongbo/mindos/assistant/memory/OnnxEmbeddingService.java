package com.zhongbo.mindos.assistant.memory;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@ConditionalOnProperty(prefix = "mindos.memory.embedding.onnx", name = "enabled", havingValue = "true")
public class OnnxEmbeddingService implements EmbeddingService, DisposableBean {

    private static final String CLASSPATH_PREFIX = "classpath:";
    private static final String BGE_MICRO_MODEL_RESOURCE = "models/bge-micro/model_quantized.onnx";
    private static final String BGE_MICRO_TOKENIZER_RESOURCE = "models/bge-micro/tokenizer.json";

    private static final ThreadLocal<MessageDigest> SHA_256 = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    });

    private final MemoryConsolidationService memoryConsolidationService;
    private final MemoryRuntimeProperties properties;
    private final Cache<String, float[]> cache;
    private final BatchInferenceRunner batchInferenceRunner;
    private final int dimensions;

    @Autowired
    public OnnxEmbeddingService(MemoryConsolidationService memoryConsolidationService,
                                MemoryRuntimeProperties properties) throws OrtException, IOException {
        this(memoryConsolidationService,
                applyPresetDefaults(properties),
                createCache(properties),
                new OrtBatchInferenceRunner(properties));
    }

    OnnxEmbeddingService(MemoryConsolidationService memoryConsolidationService,
                         MemoryRuntimeProperties properties,
                         Cache<String, float[]> cache,
                         BatchInferenceRunner batchInferenceRunner) {
        this.memoryConsolidationService = memoryConsolidationService;
        this.properties = applyPresetDefaults(properties);
        this.cache = cache;
        this.batchInferenceRunner = batchInferenceRunner;
        this.dimensions = Math.max(1, properties.getEmbedding().getOnnx().getDimensions());
    }

    @Override
    public float[] embed(String text) {
        return embedBatch(List.of(text)).stream().findFirst().orElseGet(this::emptyVector);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<float[]> results = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            results.add(null);
        }
        Map<String, PendingText> pendingByKey = new LinkedHashMap<>();
        for (int index = 0; index < texts.size(); index++) {
            String normalized = normalize(texts.get(index));
            if (normalized.isBlank()) {
                results.set(index, emptyVector());
                continue;
            }
            String key = cacheKey(normalized);
            float[] cached = cache.getIfPresent(key);
            if (cached != null) {
                results.set(index, copy(cached));
                continue;
            }
            pendingByKey.computeIfAbsent(key, ignored -> new PendingText(normalized)).indexes().add(index);
        }
        if (!pendingByKey.isEmpty()) {
            List<Map.Entry<String, PendingText>> pendingEntries = new ArrayList<>(pendingByKey.entrySet());
            int batchSize = Math.max(1, properties.getEmbedding().getOnnx().getBatchSize());
            int offset = 0;
            while (offset < pendingEntries.size()) {
                int end = Math.min(offset + batchSize, pendingEntries.size());
                List<Map.Entry<String, PendingText>> chunk = pendingEntries.subList(offset, end);
                List<String> batchTexts = chunk.stream().map(Map.Entry::getValue).map(PendingText::text).toList();
                List<float[]> fresh = batchInferenceRunner.embedBatch(batchTexts);
                for (int i = 0; i < chunk.size(); i++) {
                    Map.Entry<String, PendingText> entry = chunk.get(i);
                    float[] vector = i < fresh.size() ? normalizeVector(fresh.get(i)) : emptyVector();
                    cache.put(entry.getKey(), copy(vector));
                    for (int index : entry.getValue().indexes()) {
                        results.set(index, copy(vector));
                    }
                }
                offset = end;
            }
        }
        return results.stream().map(this::copy).toList();
    }

    @Override
    public void destroy() throws Exception {
        batchInferenceRunner.close();
    }

    private String normalize(String text) {
        return memoryConsolidationService.normalizeForEmbedding(text, properties.getEmbedding());
    }

    private float[] normalizeVector(float[] vector) {
        if (vector == null || vector.length == 0) {
            return emptyVector();
        }
        if (vector.length == dimensions) {
            return copy(vector);
        }
        float[] resized = new float[dimensions];
        System.arraycopy(vector, 0, resized, 0, Math.min(vector.length, dimensions));
        return resized;
    }

    private float[] emptyVector() {
        return new float[dimensions];
    }

    private float[] copy(float[] source) {
        return source == null ? emptyVector() : Arrays.copyOf(source, source.length);
    }

    private String cacheKey(String normalizedText) {
        MessageDigest digest = SHA_256.get();
        digest.reset();
        byte[] bytes = normalizedText.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] hash = digest.digest(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }

    private static Cache<String, float[]> createCache(MemoryRuntimeProperties properties) {
        MemoryRuntimeProperties.Embedding.Onnx.Cache cacheProperties = properties.getEmbedding().getOnnx().getCache();
        Caffeine<Object, Object> builder = Caffeine.newBuilder()
                .maximumSize(cacheProperties.isEnabled() ? cacheProperties.getMaximumSize() : 0);
        if (cacheProperties.getExpireAfterAccessSeconds() > 0) {
            builder.expireAfterAccess(Duration.ofSeconds(cacheProperties.getExpireAfterAccessSeconds()));
        }
        return builder.build();
    }

    private static MemoryRuntimeProperties applyPresetDefaults(MemoryRuntimeProperties properties) {
        MemoryRuntimeProperties.Embedding.Onnx onnx = properties.getEmbedding().getOnnx();
        String preset = onnx.getPreset();
        if ("bge-micro".equalsIgnoreCase(preset) || "bge_micro".equalsIgnoreCase(preset)) {
            if (onnx.getDimensions() <= 0) {
                onnx.setDimensions(384);
            }
            if (onnx.getMaxLength() <= 0) {
                onnx.setMaxLength(512);
            }
            if (isBlank(onnx.getOutputName())) {
                onnx.setOutputName("sentence_embedding");
            }
            if (isBlank(onnx.getModelPath())) {
                onnx.setModelPath(CLASSPATH_PREFIX + BGE_MICRO_MODEL_RESOURCE);
            }
            if (isBlank(onnx.getTokenizerPath())) {
                onnx.setTokenizerPath(CLASSPATH_PREFIX + BGE_MICRO_TOKENIZER_RESOURCE);
            }
        }
        return properties;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record PendingText(String text, List<Integer> indexes) {
        private PendingText(String text) {
            this(text, new ArrayList<>());
        }
    }

    interface BatchInferenceRunner extends AutoCloseable {
        List<float[]> embedBatch(List<String> texts);

        @Override
        void close() throws Exception;
    }

    static final class OrtBatchInferenceRunner implements BatchInferenceRunner {

        private static final String INPUT_IDS = "input_ids";
        private static final String ATTENTION_MASK = "attention_mask";
        private static final String TOKEN_TYPE_IDS = "token_type_ids";

        private static final ConcurrentMap<String, SharedRuntimeHolder> SHARED_RUNTIMES = new ConcurrentHashMap<>();
        private static final ConcurrentMap<String, Path> CLASSPATH_RESOURCE_CACHE = new ConcurrentHashMap<>();

        private final String runtimeKey;
        private final SharedRuntime sharedRuntime;

        OrtBatchInferenceRunner(MemoryRuntimeProperties properties) throws OrtException, IOException {
            MemoryRuntimeProperties.Embedding.Onnx onnx = properties.getEmbedding().getOnnx();
            if (onnx.getModelPath() == null || onnx.getModelPath().isBlank()) {
                throw new IllegalStateException("ONNX model path must be configured via mindos.memory.embedding.onnx.model-path when mindos.memory.embedding.onnx.enabled=true");
            }
            if (onnx.getTokenizerPath() == null || onnx.getTokenizerPath().isBlank()) {
                throw new IllegalStateException("ONNX tokenizer path must be configured via mindos.memory.embedding.onnx.tokenizer-path when mindos.memory.embedding.onnx.enabled=true");
            }
            Path modelPath = resolveRuntimePath(onnx.getModelPath(), "model");
            Path tokenizerPath = resolveRuntimePath(onnx.getTokenizerPath(), "tokenizer");
            this.runtimeKey = runtimeKey(modelPath, tokenizerPath, onnx);
            this.sharedRuntime = acquireSharedRuntime(runtimeKey, modelPath, tokenizerPath, onnx);
        }

        private Path resolveRuntimePath(String configuredPath, String kind) throws IOException {
            String normalized = configuredPath == null ? "" : configuredPath.trim();
            if (normalized.isBlank()) {
                throw new IllegalStateException("ONNX " + kind + " path is blank");
            }
            if (normalized.startsWith(CLASSPATH_PREFIX)) {
                String resourcePath = normalized.substring(CLASSPATH_PREFIX.length()).trim();
                if (resourcePath.isBlank()) {
                    throw new IllegalStateException("ONNX " + kind + " classpath path is blank: " + configuredPath);
                }
                return extractClasspathResource(resourcePath, kind);
            }
            return Path.of(normalized);
        }

        private Path extractClasspathResource(String resourcePath, String kind) throws IOException {
            Path cached = CLASSPATH_RESOURCE_CACHE.get(resourcePath);
            if (cached != null) {
                return cached;
            }
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            if (classLoader == null) {
                classLoader = OnnxEmbeddingService.class.getClassLoader();
            }
            final ClassLoader finalClassLoader = classLoader;
            try {
                return CLASSPATH_RESOURCE_CACHE.computeIfAbsent(resourcePath, ignored -> {
                    InputStream inputStream = finalClassLoader.getResourceAsStream(resourcePath);
                    if (inputStream == null) {
                        throw new IllegalStateException("ONNX " + kind + " classpath resource not found: " + resourcePath
                                + ". Put preset files under assistant-memory/src/main/resources/" + resourcePath + " or set explicit file paths.");
                    }
                    String suffix = resourcePath.contains(".") ? resourcePath.substring(resourcePath.lastIndexOf('.')) : ".bin";
                    try {
                        Path tempFile = Files.createTempFile("mindos-onnx-" + kind + "-", suffix);
                        try (InputStream stream = inputStream) {
                            Files.copy(stream, tempFile, StandardCopyOption.REPLACE_EXISTING);
                        }
                        tempFile.toFile().deleteOnExit();
                        return tempFile;
                    } catch (IOException ex) {
                        throw new IllegalStateException("Failed to extract ONNX " + kind + " resource: " + resourcePath, ex);
                    }
                });
            } catch (IllegalStateException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof IOException ioException) {
                    throw ioException;
                }
                throw ex;
            }
        }

        @Override
        public synchronized List<float[]> embedBatch(List<String> texts) {
            if (texts == null || texts.isEmpty()) {
                return List.of();
            }
            Encoding[] encodings = sharedRuntime.tokenizer().batchEncode(texts);
            long[][] inputIds = new long[encodings.length][];
            long[][] attentionMasks = new long[encodings.length][];
            long[][] tokenTypeIds = new long[encodings.length][];
            for (int i = 0; i < encodings.length; i++) {
                inputIds[i] = pad(encodings[i].getIds(), sharedRuntime.maxLength());
                attentionMasks[i] = pad(encodings[i].getAttentionMask(), sharedRuntime.maxLength());
                long[] typeIds = encodings[i].getTypeIds();
                tokenTypeIds[i] = typeIds == null || typeIds.length == 0
                        ? new long[sharedRuntime.maxLength()]
                        : pad(typeIds, sharedRuntime.maxLength());
            }
            try (OnnxTensor inputIdsTensor = OnnxTensor.createTensor(sharedRuntime.environment(), inputIds);
                 OnnxTensor attentionMaskTensor = OnnxTensor.createTensor(sharedRuntime.environment(), attentionMasks)) {
                Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
                inputs.put(INPUT_IDS, inputIdsTensor);
                inputs.put(ATTENTION_MASK, attentionMaskTensor);
                OnnxTensor typeIdsTensor = null;
                if (sharedRuntime.session().getInputInfo().containsKey(TOKEN_TYPE_IDS)) {
                    typeIdsTensor = OnnxTensor.createTensor(sharedRuntime.environment(), tokenTypeIds);
                    inputs.put(TOKEN_TYPE_IDS, typeIdsTensor);
                }
                try (OrtSession.Result result = sharedRuntime.session().run(inputs)) {
                    OnnxValue output = result.get(sharedRuntime.outputName()).orElse(null);
                    if (output == null) {
                        output = result.get(0);
                    }
                    return extractEmbeddings(output, attentionMasks);
                } finally {
                    if (typeIdsTensor != null) {
                        typeIdsTensor.close();
                    }
                }
            } catch (OrtException ex) {
                throw new IllegalStateException("Failed to compute ONNX embeddings", ex);
            }
        }

        @Override
        public void close() throws Exception {
            releaseSharedRuntime(runtimeKey);
        }

        private String runtimeKey(Path modelPath,
                                  Path tokenizerPath,
                                  MemoryRuntimeProperties.Embedding.Onnx onnx) {
            return modelPath.toAbsolutePath().normalize()
                    + "|" + tokenizerPath.toAbsolutePath().normalize()
                    + "|" + onnx.getInterOpThreads()
                    + "|" + onnx.getIntraOpThreads()
                    + "|" + onnx.getMaxLength()
                    + "|" + safeOutputName(onnx.getOutputName());
        }

        private SharedRuntime acquireSharedRuntime(String key,
                                                   Path modelPath,
                                                   Path tokenizerPath,
                                                   MemoryRuntimeProperties.Embedding.Onnx onnx) throws OrtException, IOException {
            try {
                SharedRuntimeHolder holder = SHARED_RUNTIMES.compute(key, (ignored, existing) -> {
                    if (existing != null) {
                        existing.references().incrementAndGet();
                        return existing;
                    }
                    try {
                        return new SharedRuntimeHolder(newSharedRuntime(modelPath, tokenizerPath, onnx), new AtomicInteger(1));
                    } catch (OrtException | IOException ex) {
                        throw new RuntimeException(ex);
                    }
                });
                return holder.runtime();
            } catch (RuntimeException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof OrtException ortException) {
                    throw ortException;
                }
                if (cause instanceof IOException ioException) {
                    throw ioException;
                }
                throw ex;
            }
        }

        private void releaseSharedRuntime(String key) throws Exception {
            SharedRuntimeHolder holder = SHARED_RUNTIMES.get(key);
            if (holder == null) {
                return;
            }
            if (holder.references().decrementAndGet() > 0) {
                return;
            }
            if (SHARED_RUNTIMES.remove(key, holder)) {
                holder.runtime().close();
            }
        }

        private SharedRuntime newSharedRuntime(Path modelPath,
                                               Path tokenizerPath,
                                               MemoryRuntimeProperties.Embedding.Onnx onnx) throws OrtException, IOException {
            OrtEnvironment environment = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            options.setInterOpNumThreads(onnx.getInterOpThreads());
            options.setIntraOpNumThreads(onnx.getIntraOpThreads());
            OrtSession session = environment.createSession(modelPath.toString(), options);
            HuggingFaceTokenizer tokenizer = HuggingFaceTokenizer.newInstance(tokenizerPath);
            String outputName = resolveOutputName(session, safeOutputName(onnx.getOutputName()));
            return new SharedRuntime(environment, session, tokenizer, onnx.getMaxLength(), outputName);
        }

        private String safeOutputName(String configuredOutputName) {
            return configuredOutputName == null ? "" : configuredOutputName.trim();
        }

        private static String resolveOutputName(OrtSession session, String configuredOutputName) throws OrtException {
            if (configuredOutputName != null && !configuredOutputName.isBlank()) {
                return configuredOutputName.trim();
            }
            if (session.getOutputInfo().containsKey("sentence_embedding")) {
                return "sentence_embedding";
            }
            return session.getOutputInfo().keySet().stream().findFirst().orElse("sentence_embedding");
        }

        private List<float[]> extractEmbeddings(OnnxValue value, long[][] attentionMasks) throws OrtException {
            Object raw = value.getValue();
            if (raw instanceof float[][] matrix) {
                return Arrays.stream(matrix).map(this::normalize).toList();
            }
            if (raw instanceof float[][][] tensor3d) {
                List<float[]> pooled = new ArrayList<>(tensor3d.length);
                for (int i = 0; i < tensor3d.length; i++) {
                    pooled.add(meanPool(tensor3d[i], attentionMasks[i]));
                }
                return pooled;
            }
            throw new IllegalStateException("Unsupported ONNX embedding output type: " + raw.getClass());
        }

        private float[] meanPool(float[][] tokenEmbeddings, long[] attentionMask) {
            if (tokenEmbeddings == null || tokenEmbeddings.length == 0) {
                return new float[0];
            }
            int dimension = tokenEmbeddings[0].length;
            float[] pooled = new float[dimension];
            float count = 0.0f;
            for (int token = 0; token < tokenEmbeddings.length && token < attentionMask.length; token++) {
                if (attentionMask[token] <= 0) {
                    continue;
                }
                float[] embedding = tokenEmbeddings[token];
                for (int dim = 0; dim < Math.min(dimension, embedding.length); dim++) {
                    pooled[dim] += embedding[dim];
                }
                count += 1.0f;
            }
            if (count <= 0.0f) {
                return pooled;
            }
            for (int dim = 0; dim < pooled.length; dim++) {
                pooled[dim] /= count;
            }
            return normalize(pooled);
        }

        private float[] normalize(float[] vector) {
            double norm = 0.0d;
            for (float value : vector) {
                norm += value * value;
            }
            if (norm <= 0.0d) {
                return Arrays.copyOf(vector, vector.length);
            }
            float scale = (float) Math.sqrt(norm);
            float[] normalized = new float[vector.length];
            for (int i = 0; i < vector.length; i++) {
                normalized[i] = vector[i] / scale;
            }
            return normalized;
        }

        private long[] pad(long[] values, int length) {
            long[] padded = new long[length];
            if (values == null || values.length == 0) {
                return padded;
            }
            System.arraycopy(values, 0, padded, 0, Math.min(values.length, length));
            return padded;
        }

        private record SharedRuntime(OrtEnvironment environment,
                                     OrtSession session,
                                     HuggingFaceTokenizer tokenizer,
                                     int maxLength,
                                     String outputName) implements AutoCloseable {
            @Override
            public void close() throws Exception {
                session.close();
                tokenizer.close();
            }
        }

        private record SharedRuntimeHolder(SharedRuntime runtime, AtomicInteger references) {
        }
    }
}
