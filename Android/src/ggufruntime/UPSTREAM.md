# GGUF runtime provenance

This Android library builds against `ggml-org/llama.cpp` at the immutable commit:

`cb295bf59663cd3577389315636772f4060bd1f5`

Source: <https://github.com/ggml-org/llama.cpp>

License: MIT. The upstream license is fetched with the pinned source during the CMake build:
<https://github.com/ggml-org/llama.cpp/blob/cb295bf59663cd3577389315636772f4060bd1f5/LICENSE>

`gguf_bridge.cpp` wraps the upstream Android sample and `libmtmd` for local text and image
inference. GPU offload is intentionally disabled in this release.
