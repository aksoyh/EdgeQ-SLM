# EdgeQ-SLM Progress Report

## Thesis: Efficient Quantization and Low-Latency Inference of Small Language Models on Mobile Devices

**Author:** Hasan Aksoy  
**Last Updated:** 2026-01-21  
**Project Repository:** EdgeQ-SLM

---

## 1. Project Timeline

| Date | Milestone | Description |
|------|-----------|-------------|
| 2025-11-19 | Project Init | Created KMP + Compose Multiplatform project structure |
| 2026-01-17 | Model Prep | Downloaded Qwen 1.5 1.8B, converted to GGUF, quantized to INT8 |
| 2026-01-17 | Desktop Test | Tested model on macOS with llama.cpp CLI |
| 2026-01-17 | JNI Bridge | Created llama_jni.cpp with full inference pipeline |
| 2026-01-17 | CMake Setup | Configured Android NDK build for ARM64 |
| 2026-01-18 | UI Integration | Connected LlmEngine to Compose UI with metrics dashboard |
| 2026-01-18 | Chat Template | Added ChatML format and stop token detection |
| 2026-01-18 | Device Deploy | Model deployment and permission debugging |
| 2026-01-18 | Model Download | Implemented HuggingFace download with progress UI |
| 2026-01-18 | Package Rename | Changed package from com.example to com.aksoyapps |
| 2026-01-19 | Photo Search | Added OCR-based photo indexing and search |
| 2026-01-19 | CLIP Integration | Added visual search with CLIP ViT-B/32 |
| 2026-01-19 | Hybrid Search | Combined OCR + CLIP search with match badges |
| 2026-01-19 | Resource Monitor | Added CPU/RAM monitoring bar |
| 2026-01-21 | Documentation | Comprehensive docs update |

---

## 2. Environment Setup

### 2.1 Development Environment

| Component | Version |
|-----------|---------|
| Host OS | macOS (Apple Silicon) |
| Build System | Gradle 8.7, AGP 8.2.2 |
| NDK | 26.1.10909125 |
| CMake | 3.22.1 |
| Java | 17 (JBR-17.0.14) |
| ONNX Runtime | 1.18.0 |

### 2.2 Build Issues and Resolutions

**Issue 1: Makefile Deprecated**
```
Makefile:10: *** The Makefile build is deprecated. Use the CMake build instead.
```
Resolution: Used CMake build instead of make.

**Issue 2: Java Version Incompatibility**
```
Failed to determine Java version from '25.0.1'
```
Resolution: Added to gradle.properties:
```properties
org.gradle.java.home=/Users/hasanaksoy/Library/Java/JavaVirtualMachines/jbr-17.0.14/Contents/Home
```

**Issue 3: ONNX Runtime IR Version**
```
This is an invalid ONNX model. The highest supported IR version is 9
```
Resolution: Updated ONNX Runtime from 1.16.3 to 1.18.0 for IR version 10 support.

---

## 3. Model Preparation

### 3.1 LLM Quantization Results

| Metric | Value |
|--------|-------|
| Original Size (FP16) | 3.67 GB |
| Quantized Size (Q8_0) | 1.86 GB |
| Compression Ratio | 1.97x |

### 3.2 CLIP Models

| Model | Size | Format |
|-------|------|--------|
| clip-vit-b32-image.onnx | ~330 MB | ONNX |
| clip-vit-b32-text.onnx | ~330 MB | ONNX |
| vocab.json | ~2 MB | JSON |
| merges.txt | ~500 KB | Text |

---

## 4. Desktop Baseline (macOS Apple Silicon)

| Metric | Value |
|--------|-------|
| Prefill Speed | 168.6 - 582.1 t/s |
| Decode Speed | 43.5 - 56.3 t/s |
| Context Size | 2048 tokens |

---

## 5. Android Integration

### 5.1 Architecture

```
EdgeQ-SLM/
├── shared/
│   ├── src/commonMain/kotlin/    # LlmEngine interface, ViewModel, ModelRepository
│   │   └── photos/               # PhotoSearchUiState
│   ├── src/androidMain/kotlin/   # AndroidLlamaCppEngine (JNI wrapper)
│   │   └── photos/               # PhotoIndexer, PhotoVectorStore, CLIP encoders
│   ├── src/iosMain/kotlin/       # IosLlamaCppEngine
│   └── src/androidMain/cpp/      # JNI bridge (llama_jni.cpp, CMakeLists.txt)
├── composeApp/
│   ├── src/commonMain/kotlin/    # App.kt (LLM UI)
│   └── src/androidMain/kotlin/   # MainActivity, PhotoSearchScreen
└── docs/                         # Documentation
```

### 5.2 Photo Search Module

| Component | File | Description |
|-----------|------|-------------|
| Indexer | `PhotoIndexer.kt` | OCR + CLIP embedding generation |
| Vector Store | `PhotoVectorStore.kt` | SQLite storage, similarity search |
| Image Encoder | `ClipImageEncoder.kt` | CLIP image → 512-dim vector |
| Text Encoder | `ClipTextEncoder.kt` | CLIP text → 512-dim vector (BPE tokenizer) |
| ViewModel | `PhotoSearchViewModel.kt` | State management, search logic |
| UI | `PhotoSearchScreen.kt` | Photo grid, search UI, match badges |

---

## 6. Photo Search Feature (2026-01-19)

### 6.1 OCR Indexing

| Component | Technology | Purpose |
|-----------|------------|---------|
| OCR Engine | Google ML Kit | Extract text from photos |
| Database | SQLite | Store OCR text, file metadata |
| Search | SQL LIKE | Full-text search in OCR content |

### 6.2 CLIP Visual Search

| Component | Technology | Purpose |
|-----------|------------|---------|
| Image Encoder | CLIP ViT-B/32 | Photo → 512-dim embedding |
| Text Encoder | CLIP ViT-B/32 | Query → 512-dim embedding |
| Similarity | Cosine | Compare embeddings |
| Tokenizer | BPE | Byte-Pair Encoding for text |

### 6.3 Match Type Badges

| Badge | Color | Description |
|-------|-------|-------------|
| OCR | Blue (#58A6FF) | Matched via text content |
| CLIP | Purple (#8957E5) | Matched via visual similarity |
| HYBRID | Orange (#D29922) | Both OCR and CLIP matched |

### 6.4 Force Index Feature

- **Normal click**: Regular indexing (skip already indexed)
- **5-second long press**: Force re-index all photos
- Progress bar fills from left to right during hold
- Button text changes to "⚡ Force Indexing..."

---

## 7. Resource Monitoring (2026-01-19)

### 7.1 Status Bar Components

| Metric | Source | Update Interval |
|--------|--------|-----------------|
| Device RAM | ActivityManager.MemoryInfo | 2 seconds |
| App RAM | Debug.getNativeHeapAllocatedSize + Runtime | 2 seconds |
| App CPU | Debug.threadCpuTimeNanos | 2 seconds |
| System CPU | /proc/loadavg (limited on Android 8+) | 2 seconds |

### 7.2 Known Limitations

- **System CPU shows 0%**: Android SELinux restrictions block /proc/loadavg access
- **App CPU**: Only measures main thread, not worker threads

---

## 8. Model Download Feature (2026-01-18)

### 8.1 Feature Overview

Implemented in-app model download from HuggingFace with:
- Progress bar with percentage
- Download speed (Mbps)
- Downloaded/Total size (MB)
- System notification with progress
- Model selection dropdown

### 8.2 Model Sources

| Source | Status |
|--------|--------|
| HuggingFace (Qwen) | ✅ Working |
| CLIP models (manual) | ✅ Manual push via ADB |

---

## 9. Current Status

### Completed ✅
- [x] llama.cpp desktop build
- [x] Model download and INT8 quantization
- [x] Desktop baseline measurements
- [x] JNI bridge implementation
- [x] CMake cross-compilation for ARM64
- [x] Kotlin/Compose UI with metrics dashboard
- [x] ChatML template support
- [x] Stop token detection
- [x] APK build and installation on device
- [x] Model loading on Android device
- [x] In-app model download from HuggingFace
- [x] Download progress UI with speed/size info
- [x] Model selection dropdown
- [x] Package rename to com.aksoyapps.edgeqslm
- [x] **Photo Search with OCR indexing**
- [x] **CLIP visual search integration**
- [x] **Hybrid search (OCR + CLIP)**
- [x] **Match type badges (OCR/CLIP/HYBRID)**
- [x] **Force Index with long-press**
- [x] **Resource monitoring bar (CPU/RAM)**
- [x] **Scrollable Photo Search page**
- [x] **Fullscreen photo viewer**

### In Progress 🔄
- [ ] CLIP BPE tokenizer optimization
- [ ] Android LLM performance measurements

### Pending 📋
- [ ] INT4 quantization comparison
- [ ] GPU/NPU acceleration tests
- [ ] Multiple device benchmarks
- [ ] iOS implementation testing
- [ ] RAG integration (LLM + Photo context)

---

## 10. Metrics Summary Table

| Metric | Desktop (M-series) | Android (Expected) |
|--------|-------------------|-------------------|
| LLM Model Size | 1.86 GB | 1.86 GB |
| CLIP Models Size | ~660 MB | ~660 MB |
| Prefill Speed | ~300 t/s | TBD |
| Decode Speed | ~50 t/s | TBD |
| TTFT | ~50 ms | TBD |
| Memory Usage | ~2.5 GB | ~2 GB (observed) |

---

## 11. Git Branch Structure

| Branch | Description |
|--------|-------------|
| `main` | Stable base (needs merge) |
| `feature/model-download` | Model download feature |
| `feature/photo-search-ocr` | OCR photo search |
| `feature/clip-visual-search` | **Current** - CLIP + OCR + UI improvements |

---

## 12. Next Steps

1. **Merge to Main**: Merge feature/clip-visual-search to main
2. **CLIP Tokenizer**: Implement proper BPE tokenizer for better accuracy
3. **Collect Metrics**: Measure TTFT, tokens/sec, memory on device
4. **INT4 Testing**: Quantize to Q4_0, compare speed vs accuracy
5. **RAG Integration**: Connect photo search results to LLM context
6. **Multi-Device Benchmark**: Test on various Android devices

---

## Appendix A: Key Files

| File | Purpose |
|------|---------|
| `shared/src/androidMain/kotlin/.../photos/PhotoIndexer.kt` | OCR + CLIP indexing |
| `shared/src/androidMain/kotlin/.../photos/PhotoVectorStore.kt` | SQLite storage, search |
| `shared/src/androidMain/kotlin/.../photos/ClipImageEncoder.kt` | ONNX image inference |
| `shared/src/androidMain/kotlin/.../photos/ClipTextEncoder.kt` | ONNX text inference |
| `composeApp/src/androidMain/kotlin/.../photos/PhotoSearchScreen.kt` | Photo search UI |
| `composeApp/src/androidMain/kotlin/.../MainActivity.kt` | Navigation, resource bar |

---

*Report updated: 2026-01-21*
