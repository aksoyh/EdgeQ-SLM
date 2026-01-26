# EdgeQ-SLM: On-Device Small Language Model Proof of Concept

## Overview
This project serves as a Proof of Concept (PoC) for the thesis research on running Small Language Models (SLMs) locally on mobile devices. The primary objective is to demonstrate the feasibility of executing quantized Large Language Models and Vision-Language Models on Android devices, while measuring critical performance metrics.

## Research Context
As mobile hardware capabilities increase, the potential for on-device AI grows. However, running LLMs on resource-constrained devices requires efficient quantization and optimized inference engines. This PoC validates:

1. **LLM Feasibility**: Running a 1.8B parameter model (Qwen 1.5) on consumer mobile hardware
2. **Vision-Language Models**: Running CLIP for visual search without cloud dependency
3. **Quantization**: Utilizing GGUF INT8 and ONNX formats to reduce memory footprint
4. **Performance Measurement**: Establishing baselines for latency and memory usage

## Current Implementation Status (as of 2026-01-21)

### ✅ Phase 1: LLM Integration (Completed)
| Feature | Description |
|---------|-------------|
| **Model Loading** | Load quantized GGUF models via JNI bridge to llama.cpp |
| **Text Generation** | Full inference pipeline with ChatML template support |
| **Performance Metrics** | Real-time TTFT, tokens/sec, memory usage display |
| **In-App Download** | Download models directly from HuggingFace with progress UI |
| **Model Selection** | Choose from multiple available models via dropdown |
| **Cross-Platform UI** | Compose Multiplatform for Android (iOS placeholder) |

### ✅ Phase 2: Photo Search with OCR (Completed)
| Feature | Description |
|---------|-------------|
| **OCR Engine** | Google ML Kit for on-device text recognition |
| **Photo Indexing** | Scan folder, extract text, store in SQLite |
| **Text Search** | Full-text search in photo OCR content |
| **Fullscreen Viewer** | Pinch-to-zoom photo preview |

### ✅ Phase 3: CLIP Visual Search (Completed)
| Feature | Description |
|---------|-------------|
| **CLIP ViT-B/32** | Vision-Language model for image understanding |
| **Image Embeddings** | Convert photos to 512-dim vectors |
| **Text Embeddings** | Convert search queries to 512-dim vectors |
| **Visual Search** | Find photos by semantic content (e.g., "airplane") |
| **Hybrid Search** | Combined OCR + CLIP results |
| **Match Badges** | Visual indicators: OCR (blue), CLIP (purple), HYBRID (orange) |

### ✅ Phase 4: UX Improvements (Completed)
| Feature | Description |
|---------|-------------|
| **Resource Monitor** | CPU/RAM usage bar above navigation |
| **Force Index** | 5-second long-press to re-index all photos |
| **Scrollable UI** | Full-page scroll for Photo Search |
| **CLIP Loading Indicator** | Spinner while models load |

## Architecture

```
EdgeQ-SLM/
├── shared/
│   ├── src/commonMain/kotlin/
│   │   ├── LlmEngine.kt           # Inference engine interface
│   │   ├── LlmViewModel.kt        # LLM state management
│   │   ├── ModelRepository.kt     # Model download interface
│   │   └── photos/
│   │       └── PhotoSearchUiState.kt  # Photo search state
│   ├── src/androidMain/kotlin/
│   │   ├── AndroidLlamaCppEngine.kt   # JNI wrapper for llama.cpp
│   │   ├── ModelRepository.android.kt # HuggingFace download impl
│   │   └── photos/
│   │       ├── PhotoIndexer.kt        # OCR + CLIP indexing
│   │       ├── PhotoVectorStore.kt    # SQLite + vector search
│   │       ├── ClipImageEncoder.kt    # ONNX image inference
│   │       ├── ClipTextEncoder.kt     # ONNX text inference
│   │       └── PhotoSearchViewModel.kt # Photo search logic
│   └── src/androidMain/cpp/
│       ├── llama_jni.cpp          # Native JNI bridge
│       └── CMakeLists.txt         # Cross-compile config
├── composeApp/
│   ├── src/commonMain/kotlin/
│   │   └── App.kt                 # LLM chat UI
│   └── src/androidMain/kotlin/
│       ├── MainActivity.kt        # Navigation + resource bar
│       └── photos/
│           └── PhotoSearchScreen.kt # Photo search UI
└── docs/                          # Documentation
```

## Key Technologies

| Component | Technology | Purpose |
|-----------|------------|---------|
| LLM Runtime | llama.cpp | Efficient CPU inference |
| Quantization | GGUF Q8_0 | 50% size reduction, minimal quality loss |
| Vision Model | CLIP ViT-B/32 | Visual understanding |
| ONNX Runtime | 1.18.0 | Cross-platform model inference |
| OCR | Google ML Kit | On-device text recognition |
| UI | Compose Multiplatform | Cross-platform UI |
| Database | SQLite | Photo index storage |

## Setup and Execution

### Prerequisites
- Android Studio Iguana or later
- JDK 17
- NDK 26.1.x
- Physical ARM64 Android device

### Model Deployment

```bash
# LLM Model (~1.8GB)
# Option 1: Download in-app from HuggingFace
# Option 2: Manual push
adb push qwen-q8_0.gguf /sdcard/Android/data/com.aksoyapps.edgeqslm/files/

# CLIP Models (~660MB total)
adb push clip-vit-b32-image.onnx /sdcard/Android/data/com.aksoyapps.edgeqslm/files/models/
adb push clip-vit-b32-text.onnx /sdcard/Android/data/com.aksoyapps.edgeqslm/files/models/
adb push -r clip_tokenizer/ /sdcard/Android/data/com.aksoyapps.edgeqslm/files/models/
```

### Running the Application
1. Clone the repository and open in Android Studio
2. Connect an ARM64 Android device
3. Run the `composeApp` configuration
4. Grant "All Files Access" permission in Android Settings
5. Navigate to **Photos** tab → **Index** to scan photos

## Performance Metrics

| Metric | LLM (Qwen Q8) | CLIP |
|--------|---------------|------|
| Model Size | 1.86 GB | 660 MB |
| Load Time | ~5s | ~3s |
| Inference | 15-25 t/s | ~100ms/image |
| Memory | ~2 GB | ~1.5 GB |

## Known Limitations

1. **CLIP English Only**: CLIP is trained on English text; Turkish queries won't work
2. **BPE Tokenizer**: Simple word-level tokenization implemented; full BPE would improve accuracy
3. **System CPU**: Shows 0% due to Android SELinux restrictions
4. **iOS**: Placeholder implementation only

## Future Work

1. **RAG Integration**: Connect photo context to LLM queries
2. **Multimodal LLM**: Integrate LLaVA or Qwen-VL for direct image understanding
3. **INT4 Quantization**: Further reduce model size
4. **NPU Offloading**: Utilize hardware accelerators

## Research Value

This PoC demonstrates:
- Feasibility of running 1.8B parameter LLMs on mobile
- On-device multimodal AI (text + vision)
- Quantization impact on performance vs. quality
- Privacy-preserving AI (all data stays on device)

---

*Last Updated: 2026-01-21*
