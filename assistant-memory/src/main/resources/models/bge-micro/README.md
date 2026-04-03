# bge-micro preset assets

MindOS ONNX preset `bge-micro` now defaults to classpath paths:

- `classpath:models/bge-micro/model_quantized.onnx`
- `classpath:models/bge-micro/tokenizer.json`

To use local ONNX inference, place these two files in this directory before packaging:

- `assistant-memory/src/main/resources/models/bge-micro/model_quantized.onnx`
- `assistant-memory/src/main/resources/models/bge-micro/tokenizer.json`

If you prefer external files, override:

- `mindos.memory.embedding.onnx.model-path`
- `mindos.memory.embedding.onnx.tokenizer-path`

