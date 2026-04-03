package com.zhongbo.mindos.assistant.memory;

import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class KeywordContentFilter implements ContentFilter {

    private final MemoryRuntimeProperties properties;
    private final MemoryConsolidationService memoryConsolidationService;

    public KeywordContentFilter(MemoryRuntimeProperties properties,
                                MemoryConsolidationService memoryConsolidationService) {
        this.properties = properties;
        this.memoryConsolidationService = memoryConsolidationService;
    }

    @Override
    public boolean isAllowed(String text) {
        if (!properties.getFilter().isEnabled()) {
            return true;
        }
        String normalized = memoryConsolidationService.normalizeText(text).toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return true;
        }
        for (String allow : properties.getFilter().getAllowTerms()) {
            if (normalized.contains(allow.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        for (String block : properties.getFilter().getBlockTerms()) {
            if (normalized.contains(block.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }
}
