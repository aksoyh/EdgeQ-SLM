# EdgeQ-SLM: On-Device Small Language Model Proof of Concept

## Overview
This project serves as a Proof of Concept (PoC) for the thesis research on running Small Language Models (SLMs) locally on mobile devices. The primary objective is to demonstrate the feasibility of executing quantized Large Language Models (specifically Qwen 1.5 1.8B INT8) on Android and iOS devices using `llama.cpp`, while measuring critical performance metrics such as inference latency and memory consumption.

## Research Context
As mobile hardware capabilities increase, the potential for on-device AI grows. However, running LLMs on resource-constrained devices requires efficient quantization and optimized inference engines. This PoC validates:
1.  **Feasibility**: Running a 1.8B parameter model on consumer mobile hardware.
2.  **Quantization**: Utilizing GGUF format (INT8 quantization) to reduce memory footprint.
3.  **Performance Measurement**: Establishing a baseline for latency (tokens/sec) and memory usage to guide future optimizations (e.g., NPU offloading, INT4 quantization).

## Current Implementation Status (as of 2026-01-18)

### ✅ Completed Features
| Feature | Description |
|---------|-------------|
| **Model Loading** | Load quantized GGUF models via JNI bridge to llama.cpp |
| **Text Generation** | Full inference pipeline with ChatML template support |
| **Performance Metrics** | Real-time TTFT, tokens/sec, memory usage display |
| **In-App Download** | Download models directly from HuggingFace with progress UI |
| **Model Selection** | Choose from multiple available models via dropdown |
| **Cross-Platform UI** | Compose Multiplatform for Android (iOS placeholder) |

### Model Download Feature
The application now supports downloading models directly from HuggingFace:
- Progress bar with percentage, MB downloaded, and speed (Mbps)
- System notification with download status
- Automatic detection of already-downloaded models
- Debug mode for testing download flow

## Architecture
The project follows a **Kotlin Multiplatform (KMP)** architecture with **Compose Multiplatform** for the UI, ensuring code sharing across Android and iOS while allowing for platform-specific optimizations.

### Modules
```
EdgeQ-SLM/
├── shared/
│   ├── src/commonMain/kotlin/
│   │   ├── LlmEngine.kt           # Inference engine interface
│   │   ├── LlmViewModel.kt        # State management
│   │   └── ModelRepository.kt     # Model download/management (expect)
│   ├── src/androidMain/kotlin/
│   │   ├── AndroidLlamaCppEngine.kt  # JNI wrapper
│   │   └── ModelRepository.android.kt # HuggingFace download impl
│   ├── src/androidMain/cpp/
│   │   ├── llama_jni.cpp          # Native JNI bridge
│   │   └── CMakeLists.txt         # Cross-compile config
│   └── src/iosMain/kotlin/
│       └── ModelRepository.ios.kt  # iOS implementation (placeholder)
├── composeApp/
│   ├── src/commonMain/kotlin/
│   │   └── App.kt                  # Shared Compose UI
│   ├── src/androidMain/kotlin/
│   │   └── MainActivity.kt         # Android entry point
│   └── src/iosMain/kotlin/
│       └── MainViewController.kt   # iOS entry point
```

### Key Components
-   **LlmEngine**: Interface abstracting the underlying inference engine (`llama.cpp`).
-   **ModelRepository**: Cross-platform model management with expect/actual pattern.
-   **Performance Metrics**:
    -   **TTFT (Time To First Token)**: Prefill latency measurement
    -   **Decode Speed**: Tokens per second during generation
    -   **Memory**: Native heap consumption monitoring

## Setup and Execution

### Prerequisites
- Android Studio Iguana or later
- JDK 17
- NDK 26.1.x
- Physical ARM64 Android device (emulator not supported due to architecture)

### Running the Application
1. Clone the repository and open in Android Studio
2. Connect an ARM64 Android device
3. Run the `composeApp` configuration
4. **First Run**: The app will show the download option. Tap "Download" to fetch the model from HuggingFace (~1.8GB)
5. **Subsequent Runs**: The app will detect the existing model and allow direct loading

### Model Paths
The app stores models in its private external storage:
```
/sdcard/Android/data/com.aksoyapps.edgeqslm/files/
```
This location requires no special permissions on Android 11+.

## Technical Decisions and Rationale

### Why HttpURLConnection over Ktor?
Initial implementation used Ktor client for downloads, but it caused "connection abort" errors for large files (~1.8GB). Switching to native `HttpURLConnection` resolved this issue and provides more reliable streaming for large downloads.

### Why App-Specific Storage?
Android's scoped storage (Android 11+) restricts access to public directories like `/sdcard/Download`. Using the app's external files directory (`/sdcard/Android/data/...`) avoids permission issues entirely.

### Why HuggingFace over Google Drive?
Google Drive inserts a virus scanning warning page for files >100MB, breaking automated downloads. HuggingFace provides direct binary downloads without any intermediary pages.

## Future Work
This PoC lays the groundwork for:
-   **NPU Integration**: Extending `LlmEngine` to utilize Android NNAPI or iOS CoreML via `llama.cpp` hardware acceleration options.
-   **Advanced Metrics**: Energy profiling and detailed memory analysis.
-   **Lower Precision**: Testing INT4 quantization to analyze speed vs. accuracy trade-offs.
-   **iOS Completion**: Full implementation of iOS inference and download.

## Model Information

| Property | Value |
|----------|-------|
| Model | Qwen 1.5 1.8B Chat |
| Quantization | Q8_0 (INT8) |
| File Size | ~1.86 GB |
| Format | GGUF |
| Source | HuggingFace (Qwen/Qwen1.5-1.8B-Chat-GGUF) |

## Package Information

| Property | Value |
|----------|-------|
| Package Name | com.aksoyapps.edgeqslm |
| Min SDK | 24 (Android 7.0) |
| Target SDK | 34 (Android 14) |
| Architecture | arm64-v8a only |

---

*Last Updated: 2026-01-18*
