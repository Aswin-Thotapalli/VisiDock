# On-device search model

VisiDock bundles the English sentence-transformers/all-MiniLM-L6-v2 model, quantized ONNX export `onnx/model_quint8_avx2.onnx`, and its uncased WordPiece vocabulary. The ONNX graph runs on the CPU through ONNX Runtime; the export filename describes its quantization preset, not a requirement to use an x86 phone. Device tests must still verify each shipped ABI.

Source: https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2

Pinned revision: `1110a243fdf4706b3f48f1d95db1a4f5529b4d41`

License: Apache-2.0, as declared by the model repository. See `app/src/main/assets/semantic/LICENSE.txt`.

SHA-256:

- model.onnx: `b941bf19f1f1283680f449fa6a7336bb5600bdcd5f84d10ddc5cd72218a0fd21`
- vocab.txt: `07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3`

Queries and card fields are encoded on the phone. Embeddings are mean-pooled, normalized and compared by cosine similarity. Exact matches are ranked first; approximate related matches above a provisional 0.32 similarity threshold follow. Date constraints remain explicit. Search results are suggestions, not generated factual answers. The threshold needs evaluation against a larger collection of representative cards.

Long text is chunked with overlap. Embeddings are kept in memory, keyed by card content, and cleared on sign-out. No model API, server component, provider account or API secret is needed. The model is bundled to avoid runtime downloads and offline surprises. English is the supported initial semantic-search language; names and other scripts retain lexical matching.
