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

## Implementation Status (June 2026)

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

### Phase 4: SLM Planner for Structured Retrieval ✅

This phase adds a third search mode to the photo search screen — alongside the existing ML (CLIP+OCR) and VLM modes — using a quantized TinyLlama model as an on-device query planner.

The core idea came from a specific research question I wanted to explore: can a small, text-only language model meaningfully improve retrieval quality on a mobile device, without adding the heavy cost of a full vision-language model? The SLM planner is my attempt at answering that.

| Feature | Description |
|---------|-------------|
| SLM Search Mode | Third retrieval mode button in the Photos screen ("🧩 SLM") |
| On-Device Planner | `edgeq_planner_tinyllama_q4_k_m.gguf` runs locally via llama.cpp |
| Structured Query Plan | Model outputs a JSON plan with `query_terms`, `ocr_required`, `prefer_screenshot`, `top_k` |
| Deterministic Enrichment | Rule-based `QueryEnricher` covers Turkish/English vocabulary to compensate for quantization noise |
| Weighted Keyword Scoring | `SlmSearchEngine` scores all indexed photos in-memory against enriched terms |
| Debug Panel | The enriched plan is shown in the UI so the retrieval logic is transparent |

**How it works end-to-end:**

```
User query
  → TinyLlama planner (llama.cpp, on-device)
  → raw JSON plan (parsed, with fallback if malformed)
  → QueryEnricher (deterministic rules for Turkish & English)
  → enriched term list
  → all indexed photos loaded from SQLite
  → SlmSearchEngine scores each record (ocr_text, vlm_description, vlm_tags, file_name, file_path)
  → top-k results shown in existing Photos grid
```

**Why a separate enrichment step?**  
Quantized models at this size tend to be inconsistent with terminology, especially for non-English inputs. The `QueryEnricher` acts as a safety net — even if TinyLlama returns something generic like `["screenshot"]` for a query about a bank statement, the enricher expands it to the full finance vocabulary. The planner output still matters because it sets `ocr_required` and `prefer_screenshot` flags, which affect the scoring weights.

**Model file location:**

```
/sdcard/Android/data/com.aksoyapps.edgeqslm/files/edgeq_planner_tinyllama_q4_k_m.gguf
```

The app also checks `/storage/emulated/0/EdgeQSLM/models/` as an alternative path.

**New files added:**

| File | Purpose |
|------|---------|
| `SearchPlan.kt` | Data class + JSON parser with alternative key support |
| `QueryEnricher.kt` | Deterministic term expansion (Turkish/English) |
| `SlmSearchEngine.kt` | In-memory weighted scoring over `PhotoRecord` list |

---

### Phase 4b: A Runtime Crash and What It Revealed About the JNI Bridge ✅

While integrating the SLM planner, I ran into a native crash that turned out to be more informative than most bugs I've dealt with during this project. Understanding why it happened led to a meaningful architectural change.

**The crash**

After adding the SLM button, the app started crashing with SIGSEGV (signal 11) originating from `libllama_jni.so`. The stack trace from `adb logcat -b crash` showed the fault happening during model loading, not during inference. At first I thought it was a corrupted model file or a memory issue specific to the device, but those were both easy to rule out.

**Root cause: shared global state in the JNI bridge**

Looking at `llama_jni.cpp`, the native bridge uses a single set of global pointers for the active model:

```cpp
static llama_model*   g_model    = nullptr;
static llama_context* g_context  = nullptr;
static mtmd_context*  g_mtmd_ctx = nullptr;
```

The `loadModelNative()` function frees whatever is currently in `g_model` before loading a new one:

```cpp
if (g_model) { llama_model_free(g_model); g_model = nullptr; }
```

This is fine if only one model is ever loaded at a time. The problem was that I had two `AndroidLlamaCppEngine` instances — one for VLM, one for SLM — and both were starting their `loadModelNative()` calls around the same time on startup. When the second call ran, it freed the memory that the first engine was still using, and then the first engine tried to access that freed memory. That's the SIGSEGV.

The llama.cpp library was designed around a single model being active at a time, and the JNI bridge reflects that constraint directly in its global state. Having two engine instances share the same C++ globals without any synchronization is simply not safe.

**The fix: load models only when the user asks for them**

The clearest solution was to stop loading VLM and SLM at startup altogether. Both are now loaded on demand — only when the user taps the corresponding button on the photo search screen. This is implemented as a callback pattern in `PhotoSearchViewModel`:

```kotlin
private var vlmLoader: (suspend () -> Unit)? = null
private var slmLoader: (suspend () -> Unit)? = null
```

`MainActivity` registers the actual loading logic through `setVlmLoader()` and `setSlmLoader()` at startup, but neither callback runs until the user explicitly switches to that mode. Since only one mode can be active at a time, there is never a situation where two engines load concurrently.

**The button states**

Because loading a model takes a few seconds (the VLM stack is around 2 GB, TinyLlama is around 700 MB), the UI needs to communicate what's happening. I added three visual states to both the VLM and SLM buttons:

| State | What the button shows | Meaning |
|-------|----------------------|---------|
| Model file present, not yet loaded | `🧠 VLM ↓` / `🧩 SLM ↓` | Tap to load and activate |
| Loading in progress | `⟳ VLM…` with spinner | Model is being read into memory |
| Model ready | `🧠 VLM` / `🧩 SLM` | Active, queries are processed immediately |

The `↓` is intentional — it suggests that something is about to be pulled into memory, similar to a download indicator. Once loading completes, if the user had already typed a query, the search runs automatically without needing a second tap.

**Why this turned out to be the better design anyway**

The original approach of loading everything in `onCreate` was only there because it seemed simpler. But loading ~2 GB of model weights before the user even opens the photo tab is wasteful — it spikes memory and adds to startup time for something that may never be used in that session. The lazy loading approach is actually what should have been there from the beginning. The crash just made it obvious.

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
│   │   ├── photos/
│   │   │   ├── PhotoIndexer.kt       # CLIP + OCR indexing
│   │   │   ├── VlmIndexer.kt         # Vision LLM indexing
│   │   │   ├── SearchPlan.kt         # SLM planner output schema
│   │   │   ├── QueryEnricher.kt      # Deterministic term expansion
│   │   │   ├── SlmSearchEngine.kt    # Weighted keyword scoring
│   │   │   └── PhotoVectorStore.kt   # SQLite storage layer
│   │   └── benchmark/                # Android benchmark implementation
│   └── src/androidMain/cpp/
│       └── llama_jni.cpp             # Native JNI bridge
├── composeApp/
│   └── src/androidMain/kotlin/
│       ├── MainActivity.kt           # Navigation, model loading
│       ├── photos/                   # Photo search UI
│       └── benchmark/                # Benchmark screen
└── docs/                             # Documentation
```

### Photo Search: Three Retrieval Modes

The photo search module supports three independent retrieval strategies, selectable from the search screen:

| Mode | Button | Model | Approach |
|------|--------|-------|----------|
| ML | 🔬 ML (CLIP+OCR) | CLIP ViT-B/32 + ML Kit OCR | Embedding similarity + text matching |
| VLM | 🧠 VLM | Qwen2.5-VL-3B + mmproj | Direct image captioning and description |
| SLM | 🧩 SLM | TinyLlama Q4_K_M (planner) | Structured query planning + keyword scoring |

Each mode uses a separate engine instance, but only one is ever loaded into the native llama.cpp layer at a time. See Phase 4b for the reasoning behind this constraint.

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

*Last Updated: June 2026*  
*Repository: [github.com/aksoyh/edgeq-slm](https://github.com/aksoyh/edgeq-slm)*
