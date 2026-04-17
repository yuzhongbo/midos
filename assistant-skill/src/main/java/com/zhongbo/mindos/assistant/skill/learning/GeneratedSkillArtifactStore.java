package com.zhongbo.mindos.assistant.skill.learning;

import com.fasterxml.jackson.core.type.TypeReference;
import com.zhongbo.mindos.assistant.memory.MemoryStateStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class GeneratedSkillArtifactStore {

    private static final String STATE_FILE = "generated-skill-artifacts.json";

    private final ConcurrentHashMap<String, ToolGenerationResult> artifactsBySkillName = new ConcurrentHashMap<>();
    private volatile MemoryStateStore memoryStateStore = MemoryStateStore.noOp();

    public GeneratedSkillArtifactStore() {
    }

    GeneratedSkillArtifactStore(MemoryStateStore memoryStateStore) {
        configurePersistence(memoryStateStore);
    }

    @Autowired(required = false)
    void setMemoryStateStore(MemoryStateStore memoryStateStore) {
        configurePersistence(memoryStateStore);
    }

    public void remember(ToolGenerationResult artifact) {
        if (artifact == null || artifact.skillName().isBlank()) {
            return;
        }
        artifactsBySkillName.put(artifact.skillName(), artifact);
        persistState();
    }

    public Optional<ToolGenerationResult> find(String skillName) {
        if (skillName == null || skillName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(artifactsBySkillName.get(skillName.trim()));
    }

    public Set<String> knownSkillNames() {
        return Set.copyOf(new LinkedHashSet<>(artifactsBySkillName.keySet()));
    }

    void configurePersistence(MemoryStateStore memoryStateStore) {
        this.memoryStateStore = memoryStateStore == null ? MemoryStateStore.noOp() : memoryStateStore;
        restoreState();
    }

    private synchronized void restoreState() {
        Map<String, ToolGenerationResult> persisted = memoryStateStore.readState(
                STATE_FILE,
                new TypeReference<>() {
                },
                this::snapshotState
        );
        artifactsBySkillName.clear();
        if (persisted != null) {
            persisted.forEach((skillName, artifact) -> {
                String normalizedSkillName = normalize(skillName);
                if (!normalizedSkillName.isBlank() && artifact != null) {
                    artifactsBySkillName.put(normalizedSkillName, artifact);
                }
            });
        }
    }

    private synchronized void persistState() {
        memoryStateStore.writeState(STATE_FILE, snapshotState());
    }

    private Map<String, ToolGenerationResult> snapshotState() {
        LinkedHashMap<String, ToolGenerationResult> snapshot = new LinkedHashMap<>();
        artifactsBySkillName.forEach((skillName, artifact) -> {
            String normalizedSkillName = normalize(skillName);
            if (!normalizedSkillName.isBlank() && artifact != null) {
                snapshot.put(normalizedSkillName, artifact);
            }
        });
        return snapshot.isEmpty() ? Map.of() : Map.copyOf(snapshot);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
