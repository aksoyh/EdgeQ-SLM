# EdgeQ-SLM: On-Device Small Language Model Proof of Concept

**Thesis:** Efficient Quantization and Low-Latency Inference of Small Language Models on Mobile Devices  
**Author:** Hasan Aksoy (140130)  
**Program:** MSc Advanced Analytics – Big Data  
**Institution:** SGH Warsaw School of Economics  
**Contact:** ha140130@student.sgh.waw.pl

## Overview

This project serves as a Proof of Concept (PoC) for research on running Small Language Models (SLMs) locally on mobile devices. The primary objective is to demonstrate the feasibility of executing quantized language models on Android devices while measuring critical performance metrics.

## Research Context

As mobile hardware capabilities increase, the potential for on-device machine learning grows. However, running language models on resource-constrained devices requires efficient quantization and optimized inference engines. This PoC validates:

1. **LLM Feasibility:** Running a 1.8B parameter model (Qwen 1.5) on consumer mobile hardware.
2. **Vision-Language Models:** Running CLIP for visual search without cloud dependency.
3. **Quantization:** Utilizing GGUF INT8 format to reduce memory footprint.
4. **Performance Measurement:** Establishing baselines for latency and memory usage.

---

## Implementation Status (January 2026)

### Phase 1: LLM Integration ✅

| Feature | Description |
|---------|-------------|
| Model Loading | Load quantized GGUF models via JNI bridge to llama.cpp |
| Text Generation | Full inference pipeline with ChatML template support |
| Performance Metrics | Real-time TTFT, tokens/sec, memory usage display |
| Model Download | Download models directly from HuggingFace |
| Cross-Platform UI | Compose Multiplatform for Android |

### Phase 2: Photo Search ✅

| Feature | Description |
|---------|-------------|
| OCR Engine | Google ML Kit for on-device text recognition |
| CLIP ViT-B/32 | Visual search with image/text embeddings |
| Hybrid Search | Combined OCR + CLIP results |
| SQLite Storage | Indexed photo database |

### Phase 3: Benchmark Framework ✅

| Feature | Description |
|---------|-------------|
| Configurable Experiments | Temperature sweep, token limits, repeats |
| Automated Metrics | Latency, memory, CPU utilization |
| Export | CSV/JSON results for analysis |
| In-App Visualization | Charts for latency and token speed |

---

## Architecture

```
EdgeQ-SLM/
├── shared/
│   ├── src/commonMain/kotlin/
│   │   ├── LlmEngine.kt              # Inference engine interface
│   │   ├── LlmViewModel.kt           # State management
│   │   └── benchmark/                # Benchmark framework
│   ├── src/androidMain/kotlin/
│   │   ├── AndroidLlamaCppEngine.kt  # JNI wrapper for llama.cpp
│   │   ├── photos/                   # Photo indexing (OCR + CLIP)
│   │   └── benchmark/                # Android benchmark implementation
│   └── src/androidMain/cpp/
│       └── llama_jni.cpp             # Native JNI bridge
├── composeApp/
│   └── src/androidMain/kotlin/
│       ├── MainActivity.kt           # Navigation, resource bar
│       ├── photos/                   # Photo search UI
│       └── benchmark/                # Benchmark screen
└── docs/                             # Documentation
```

---

## Key Technologies

| Component | Technology | Purpose |
|-----------|------------|---------|
| LLM Runtime | llama.cpp | Efficient CPU inference |
| Quantization | GGUF Q8_0 | 50% size reduction |
| Vision Model | CLIP ViT-B/32 | Visual understanding |
| ONNX Runtime | 1.18.0 | Cross-platform inference |
| OCR | Google ML Kit | Text recognition |
| UI | Compose Multiplatform | Android UI |
| Database | SQLite | Photo index storage |

---

## Performance Metrics

| Metric | LLM (Qwen Q8) | CLIP |
|--------|---------------|------|
| Model Size | 1.86 GB | 660 MB |
| Load Time | ~5s | ~3s |
| Inference | 15-25 t/s | ~100ms/image |
| Memory | ~2 GB | ~1.5 GB |

---

## Benchmark Results

Benchmark experiments measure:

- **Latency:** End-to-end, TTFT, decode time
- **Throughput:** Tokens per second
- **Memory:** Peak PSS during inference
- **CPU:** Process utilization
- **Quality:** Repetition score, coherence checks

Results are exported to `/collected_data/` for analysis. See [EXPERIMENT_PROTOCOL.md](EXPERIMENT_PROTOCOL.md) for methodology details.

---

## Setup and Execution

### Prerequisites

- Android Studio Iguana or later
- JDK 17
- NDK 26.1.x
- Physical ARM64 Android device

### Model Deployment

```bash
# LLM Model (~1.8GB)
adb push qwen-q8_0.gguf /sdcard/Android/data/com.aksoyapps.edgeqslm/files/

# CLIP Models (~660MB total)
adb push clip-vit-b32-image.onnx /sdcard/Android/data/com.aksoyapps.edgeqslm/files/models/
adb push clip-vit-b32-text.onnx /sdcard/Android/data/com.aksoyapps.edgeqslm/files/models/
```

### Running Benchmarks

1. Build and install the app
2. Load model on the LLM tab
3. Navigate to Benchmark tab
4. Run "Full Benchmark"
5. Export results

---

## Known Limitations

1. **Single Device:** Results may not generalize to other devices.
2. **Quantized Only:** No unquantized baseline due to memory constraints.
3. **Process CPU:** System-wide CPU measurement restricted by Android.
4. **Sanity Checks:** Not substitutes for formal LLM evaluation benchmarks.

---

## Future Work

- INT4 quantization comparison
- Multi-device benchmark study
- NPU acceleration testing
- iOS implementation

---

## Related Documents

- [EXPERIMENT_PROTOCOL.md](EXPERIMENT_PROTOCOL.md) - Experimental methodology
- [PROOF_OF_WORK_TEMPLATE.md](PROOF_OF_WORK_TEMPLATE.md) - Report template

---

*Last Updated: January 2026*  
*Repository: [github.com/aksoyh/edgeq-slm](https://github.com/aksoyh/edgeq-slm)*
