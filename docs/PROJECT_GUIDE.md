# EdgeQ-SLM Project Guide

## Overview

EdgeQ-SLM is a Kotlin Multiplatform project demonstrating on-device AI capabilities:
- **LLM Chat**: Text generation with Qwen 1.5 1.8B (INT8 quantized)
- **Photo Search**: OCR + CLIP visual search
- **Performance Monitoring**: Real-time CPU/RAM metrics

## Quick Start

### 1. Build the Project
```bash
cd EdgeQ-SLM
./gradlew :composeApp:assembleDebug
```

### 2. Install on Device
```bash
adb install -r composeApp/build/outputs/apk/debug/composeApp-debug.apk
```

### 3. Deploy Models
```bash
# LLM Model (required for LLM tab)
adb push qwen-q8_0.gguf /sdcard/Android/data/com.aksoyapps.edgeqslm/files/

# CLIP Models (required for visual search)
adb push clip-vit-b32-image.onnx /sdcard/Android/data/com.aksoyapps.edgeqslm/files/models/
adb push clip-vit-b32-text.onnx /sdcard/Android/data/com.aksoyapps.edgeqslm/files/models/
adb push -r clip_tokenizer/ /sdcard/Android/data/com.aksoyapps.edgeqslm/files/models/
```

### 4. Grant Permissions
- Open Android Settings → Apps → EdgeQ-SLM → Permissions
- Enable "All Files Access"

## Project Structure

```
EdgeQ-SLM/
├── composeApp/                    # UI Layer
│   └── src/
│       ├── commonMain/kotlin/     # Shared Compose UI (App.kt)
│       └── androidMain/kotlin/    # Android UI (MainActivity, PhotoSearchScreen)
├── shared/                        # Business Logic
│   └── src/
│       ├── commonMain/kotlin/     # Shared interfaces (LlmEngine, ViewModel)
│       ├── androidMain/kotlin/    # Android implementations
│       │   ├── AndroidLlamaCppEngine.kt  # LLM JNI wrapper
│       │   └── photos/            # Photo search module
│       └── androidMain/cpp/       # Native code (llama_jni.cpp)
├── docs/                          # Documentation
└── models/                        # Model files (gitignored)
```

## Features

### LLM Chat (🤖 Tab)
- Load and run Qwen 1.5 1.8B model
- ChatML format for conversation
- Real-time metrics: TTFT, tokens/sec, memory
- In-app model download from HuggingFace

### Photo Search (📷 Tab)
| Feature | Description |
|---------|-------------|
| OCR Indexing | Extract text from photos using ML Kit |
| CLIP Search | Find photos by visual content |
| Hybrid Search | Combined OCR + CLIP results |
| Match Badges | OCR (blue), CLIP (purple), HYBRID (orange) |
| Force Index | 5-second long-press to re-index |
| Fullscreen View | Pinch-to-zoom photo preview |

### Resource Monitor (Bottom Bar)
| Metric | Description |
|--------|-------------|
| CPU | System load (may show 0% on Android 8+) |
| App CPU | Application CPU usage |
| RAM | Device memory (used/total) |
| App RAM | Application memory usage |

## Key Files

| File | Purpose |
|------|---------|
| `MainActivity.kt` | Navigation, resource bar, permissions |
| `PhotoSearchScreen.kt` | Photo search UI, match badges |
| `PhotoIndexer.kt` | OCR + CLIP indexing logic |
| `PhotoVectorStore.kt` | SQLite storage, similarity search |
| `ClipImageEncoder.kt` | ONNX image embedding |
| `ClipTextEncoder.kt` | ONNX text embedding with tokenizer |
| `AndroidLlamaCppEngine.kt` | JNI bridge to llama.cpp |
| `llama_jni.cpp` | Native C++ inference code |

## Development

### Adding a New Feature
1. Create/modify files in `shared/src/androidMain/kotlin/`
2. Update UI in `composeApp/src/androidMain/kotlin/`
3. Build and test: `./gradlew :composeApp:assembleDebug`

### Debugging
```bash
# View app logs
adb logcat | grep -E "PhotoIndexer|ClipTextEncoder|PhotoSearchVM|LlamaJNI"

# Clear app data
adb shell pm clear com.aksoyapps.edgeqslm

# Force stop app
adb shell am force-stop com.aksoyapps.edgeqslm
```

### Git Workflow
```bash
# Current development branch
git checkout feature/clip-visual-search

# Commit changes
git add .
git commit -m "Description"
git push origin feature/clip-visual-search

# Merge to main when ready
git checkout main
git merge feature/clip-visual-search
git push origin main
```

## Known Limitations

| Limitation | Reason | Workaround |
|------------|--------|------------|
| CLIP English only | Model trained on English | Use English search terms |
| System CPU 0% | Android SELinux blocks /proc | App CPU works correctly |
| BPE tokenizer basic | Simple word-level impl | English whole words work best |

## Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| llama.cpp | Latest | LLM inference engine |
| ONNX Runtime | 1.18.0 | CLIP model inference |
| ML Kit | Latest | OCR text recognition |
| Coil | 3.0+ | Image loading |
| Compose Multiplatform | 1.6+ | UI framework |

## Resources

- [llama.cpp](https://github.com/ggerganov/llama.cpp)
- [CLIP](https://github.com/openai/CLIP)
- [Qwen 1.5](https://huggingface.co/Qwen)
- [ONNX Runtime](https://onnxruntime.ai/)

---

*Last updated: 2026-01-21*