package com.zhongbo.mindos.assistant.memory;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryRuntimePropertiesTest {

    @Test
    void shouldUseDefaultsWhenNoPropertiesProvided() {
        MemoryRuntimeProperties properties = bind(Map.of(), Map.of());

        assertFalse(properties.getWriteGate().isEnabled());
        assertEquals(10, properties.getWriteGate().getMinLength());
        assertFalse(properties.getWriteGate().isSemanticDuplicateEnabled());
        assertEquals(0.82, properties.getWriteGate().getSemanticDuplicateThreshold());
        assertTrue(properties.getWriteGate().getMinLengthByBucket().isEmpty());

        assertEquals(72.0, properties.getSearch().getDecayHalfLifeHours());
        assertEquals(128, properties.getSearch().getCoarseMinCandidates());
        assertEquals(8, properties.getSearch().getCoarseMultiplier());
        assertEquals(2, properties.getSearch().getCrossBucketMax());
        assertEquals(0.5, properties.getSearch().getCrossBucketRatio());
        assertFalse(properties.getSearch().getHybrid().isEnabled());
        assertEquals(0.55, properties.getSearch().getHybrid().getLexicalWeight());
        assertEquals(1.2, properties.getSearch().getHybrid().getK1());
        assertEquals(0.75, properties.getSearch().getHybrid().getB());

        assertFalse(properties.getEmbedding().getLocal().isEnabled());
        assertEquals(16, properties.getEmbedding().getLocal().getDimensions());

        assertFalse(properties.getLayers().isEnabled());
        assertEquals(6, properties.getLayers().getBufferHours());
        assertEquals(72, properties.getLayers().getWorkingHours());
        assertEquals(160, properties.getLayers().getFactMaxChars());
    }

    @Test
    void shouldBindFromApplicationProperties() {
        MemoryRuntimeProperties properties = bind(
                linkedMapOf(
                        "mindos.memory.write-gate.enabled", "true",
                        "mindos.memory.write-gate.min-length", "16",
                        "mindos.memory.write-gate.semantic-duplicate.enabled", "true",
                        "mindos.memory.write-gate.semantic-duplicate.threshold", "0.9",
                        "mindos.memory.search.decay-half-life-hours", "96",
                        "mindos.memory.search.coarse.min-candidates", "256",
                        "mindos.memory.search.coarse.multiplier", "12",
                        "mindos.memory.search.cross-bucket.max", "5",
                        "mindos.memory.search.cross-bucket.ratio", "0.25",
                        "mindos.memory.search.hybrid.enabled", "true",
                        "mindos.memory.search.hybrid.lexical-weight", "0.6",
                        "mindos.memory.search.hybrid.k1", "1.5",
                        "mindos.memory.search.hybrid.b", "0.7",
                        "mindos.memory.embedding.local.enabled", "true",
                        "mindos.memory.embedding.local.dimensions", "24",
                        "mindos.memory.layers.enabled", "true",
                        "mindos.memory.layers.buffer-hours", "4",
                        "mindos.memory.layers.working-hours", "48",
                        "mindos.memory.layers.fact-max-chars", "96"
                ),
                Map.of()
        );

        assertTrue(properties.getWriteGate().isEnabled());
        assertEquals(16, properties.getWriteGate().getMinLength());
        assertTrue(properties.getWriteGate().isSemanticDuplicateEnabled());
        assertEquals(0.9, properties.getWriteGate().getSemanticDuplicateThreshold());

        assertEquals(96.0, properties.getSearch().getDecayHalfLifeHours());
        assertEquals(256, properties.getSearch().getCoarseMinCandidates());
        assertEquals(12, properties.getSearch().getCoarseMultiplier());
        assertEquals(5, properties.getSearch().getCrossBucketMax());
        assertEquals(0.25, properties.getSearch().getCrossBucketRatio());
        assertTrue(properties.getSearch().getHybrid().isEnabled());
        assertEquals(0.6, properties.getSearch().getHybrid().getLexicalWeight());
        assertEquals(1.5, properties.getSearch().getHybrid().getK1());
        assertEquals(0.7, properties.getSearch().getHybrid().getB());

        assertTrue(properties.getEmbedding().getLocal().isEnabled());
        assertEquals(24, properties.getEmbedding().getLocal().getDimensions());

        assertTrue(properties.getLayers().isEnabled());
        assertEquals(4, properties.getLayers().getBufferHours());
        assertEquals(48, properties.getLayers().getWorkingHours());
        assertEquals(96, properties.getLayers().getFactMaxChars());
    }

    @Test
    void shouldPreferSystemPropertiesOverApplicationProperties() {
        MemoryRuntimeProperties properties = bind(
                linkedMapOf(
                        "mindos.memory.write-gate.enabled", "false",
                        "mindos.memory.write-gate.min-length", "12",
                        "mindos.memory.write-gate.semantic-duplicate.enabled", "false",
                        "mindos.memory.write-gate.semantic-duplicate.threshold", "0.7",
                        "mindos.memory.search.decay-half-life-hours", "80",
                        "mindos.memory.search.coarse.min-candidates", "96",
                        "mindos.memory.search.coarse.multiplier", "6",
                        "mindos.memory.search.cross-bucket.max", "3",
                        "mindos.memory.search.cross-bucket.ratio", "0.2",
                        "mindos.memory.search.hybrid.enabled", "false",
                        "mindos.memory.search.hybrid.lexical-weight", "0.2",
                        "mindos.memory.embedding.local.enabled", "false",
                        "mindos.memory.embedding.local.dimensions", "12",
                        "mindos.memory.layers.enabled", "false",
                        "mindos.memory.layers.working-hours", "24"
                ),
                linkedMapOf(
                        "mindos.memory.write-gate.enabled", "true",
                        "mindos.memory.write-gate.min-length", "20",
                        "mindos.memory.write-gate.semantic-duplicate.enabled", "true",
                        "mindos.memory.write-gate.semantic-duplicate.threshold", "0.95",
                        "mindos.memory.search.decay-half-life-hours", "48",
                        "mindos.memory.search.coarse.min-candidates", "320",
                        "mindos.memory.search.coarse.multiplier", "16",
                        "mindos.memory.search.cross-bucket.max", "7",
                        "mindos.memory.search.cross-bucket.ratio", "0.75",
                        "mindos.memory.search.hybrid.enabled", "true",
                        "mindos.memory.search.hybrid.lexical-weight", "0.9",
                        "mindos.memory.embedding.local.enabled", "true",
                        "mindos.memory.embedding.local.dimensions", "40",
                        "mindos.memory.layers.enabled", "true",
                        "mindos.memory.layers.working-hours", "120"
                )
        );

        assertTrue(properties.getWriteGate().isEnabled());
        assertEquals(20, properties.getWriteGate().getMinLength());
        assertTrue(properties.getWriteGate().isSemanticDuplicateEnabled());
        assertEquals(0.95, properties.getWriteGate().getSemanticDuplicateThreshold());

        assertEquals(48.0, properties.getSearch().getDecayHalfLifeHours());
        assertEquals(320, properties.getSearch().getCoarseMinCandidates());
        assertEquals(16, properties.getSearch().getCoarseMultiplier());
        assertEquals(7, properties.getSearch().getCrossBucketMax());
        assertEquals(0.75, properties.getSearch().getCrossBucketRatio());
        assertTrue(properties.getSearch().getHybrid().isEnabled());
        assertEquals(0.9, properties.getSearch().getHybrid().getLexicalWeight());
        assertTrue(properties.getEmbedding().getLocal().isEnabled());
        assertEquals(40, properties.getEmbedding().getLocal().getDimensions());
        assertTrue(properties.getLayers().isEnabled());
        assertEquals(120, properties.getLayers().getWorkingHours());
    }

    private MemoryRuntimeProperties bind(Map<String, Object> applicationProperties,
                                         Map<String, Object> systemProperties) {
        StandardEnvironment environment = new StandardEnvironment();
        MutablePropertySources propertySources = environment.getPropertySources();

        propertySources.addLast(new MapPropertySource("applicationConfig", applicationProperties));
        propertySources.addFirst(new MapPropertySource("systemProperties", systemProperties));

        Binder binder = Binder.get(environment);
        return binder.bind("mindos.memory", MemoryRuntimeProperties.class)
                .orElseGet(MemoryRuntimeProperties::new);
    }

    private Map<String, Object> linkedMapOf(Object... entries) {
        if (entries.length % 2 != 0) {
            throw new IllegalArgumentException("linkedMapOf requires an even number of arguments");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            values.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return values;
    }
}
