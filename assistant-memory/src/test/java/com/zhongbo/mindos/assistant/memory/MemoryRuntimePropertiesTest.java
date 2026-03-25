package com.zhongbo.mindos.assistant.memory;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;

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
        assertTrue(properties.getWriteGate().getMinLengthByBucket().isEmpty());

        assertEquals(72.0, properties.getSearch().getDecayHalfLifeHours());
        assertEquals(2, properties.getSearch().getCrossBucketMax());
        assertEquals(0.5, properties.getSearch().getCrossBucketRatio());
    }

    @Test
    void shouldBindFromApplicationProperties() {
        MemoryRuntimeProperties properties = bind(
                Map.of(
                        "mindos.memory.write-gate.enabled", "true",
                        "mindos.memory.write-gate.min-length", "16",
                        "mindos.memory.search.decay-half-life-hours", "96",
                        "mindos.memory.search.cross-bucket.max", "5",
                        "mindos.memory.search.cross-bucket.ratio", "0.25"
                ),
                Map.of()
        );

        assertTrue(properties.getWriteGate().isEnabled());
        assertEquals(16, properties.getWriteGate().getMinLength());

        assertEquals(96.0, properties.getSearch().getDecayHalfLifeHours());
        assertEquals(5, properties.getSearch().getCrossBucketMax());
        assertEquals(0.25, properties.getSearch().getCrossBucketRatio());
    }

    @Test
    void shouldPreferSystemPropertiesOverApplicationProperties() {
        MemoryRuntimeProperties properties = bind(
                Map.of(
                        "mindos.memory.write-gate.enabled", "false",
                        "mindos.memory.write-gate.min-length", "12",
                        "mindos.memory.search.decay-half-life-hours", "80",
                        "mindos.memory.search.cross-bucket.max", "3",
                        "mindos.memory.search.cross-bucket.ratio", "0.2"
                ),
                Map.of(
                        "mindos.memory.write-gate.enabled", "true",
                        "mindos.memory.write-gate.min-length", "20",
                        "mindos.memory.search.decay-half-life-hours", "48",
                        "mindos.memory.search.cross-bucket.max", "7",
                        "mindos.memory.search.cross-bucket.ratio", "0.75"
                )
        );

        assertTrue(properties.getWriteGate().isEnabled());
        assertEquals(20, properties.getWriteGate().getMinLength());

        assertEquals(48.0, properties.getSearch().getDecayHalfLifeHours());
        assertEquals(7, properties.getSearch().getCrossBucketMax());
        assertEquals(0.75, properties.getSearch().getCrossBucketRatio());
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
}

