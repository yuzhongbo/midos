package com.zhongbo.mindos.assistant.memory;

import java.util.List;

/**
 * Local embedding boundary for memory search.
 * Keeping this behind an interface lets the current lightweight hash embedder
 * be replaced by an ONNX Runtime implementation later without touching APIs.
 */
public interface LocalEmbeddingService {

    List<Double> embed(String text);
}
