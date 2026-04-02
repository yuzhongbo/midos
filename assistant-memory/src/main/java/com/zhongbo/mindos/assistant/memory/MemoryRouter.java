package com.zhongbo.mindos.assistant.memory;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Central router for the new multi-layer memory abstraction.
 */
@Component
public class MemoryRouter {

    private final Map<MemoryLayer, MemoryStore> stores = new EnumMap<>(MemoryLayer.class);

    public MemoryRouter(BufferMemoryService bufferMemoryService,
                        WorkingMemoryService workingMemoryService,
                        SemanticMemoryService semanticMemoryService,
                        FactMemoryService factMemoryService) {
        stores.put(MemoryLayer.BUFFER, bufferMemoryService);
        stores.put(MemoryLayer.WORKING, workingMemoryService);
        stores.put(MemoryLayer.SEMANTIC, semanticMemoryService);
        stores.put(MemoryLayer.FACT, factMemoryService);
    }

    public void save(MemoryRecord record) {
        if (record == null) {
            return;
        }
        route(record.layer()).save(record);
    }

    public List<MemoryRecord> query(MemoryQuery query) {
        if (query == null) {
            return List.of();
        }
        List<MemoryLayer> layers = query.requestedLayers().isEmpty()
                ? List.of(MemoryLayer.BUFFER, MemoryLayer.WORKING, MemoryLayer.SEMANTIC, MemoryLayer.FACT)
                : query.requestedLayers();
        List<MemoryRecord> results = new ArrayList<>();
        for (MemoryLayer layer : layers) {
            results.addAll(route(layer).query(query));
        }
        return results.stream()
                .sorted(Comparator.comparing(MemoryRecord::updateTime).reversed()
                        .thenComparing(Comparator.comparingDouble(MemoryRecord::confidence).reversed()))
                .limit(query.limit())
                .toList();
    }

    public MemoryStore route(MemoryLayer layer) {
        MemoryStore store = stores.get(layer);
        if (store == null) {
            throw new IllegalArgumentException("Unsupported memory layer: " + layer);
        }
        return store;
    }
}
