# Project Guide - EdgeQ-SLM Technical Overview

## 1. Project Architecture

EdgeQ-SLM is a Kotlin Multiplatform (KMP) project using Compose Multiplatform for UI. It demonstrates on-device inference of Small Language Models (SLMs) using llama.cpp.

### Module Structure
```
EdgeQ-SLM/
├── shared/                      # Business logic (KMP)
│   ├── commonMain/              # Cross-platform code
│   │   ├── LlmEngine.kt         # Inference engine interface
│   │   ├── LlmViewModel.kt      # State management
│   │   └── ModelRepository.kt   # Model management (expect)
│   ├── androidMain/             # Android implementations
│   │   ├── AndroidLlamaCppEngine.kt
│   │   ├── ModelRepository.android.kt
│   │   └── cpp/                 # Native code
│   │       ├── llama_jni.cpp    # JNI bridge to llama.cpp
│   │       └── CMakeLists.txt   # NDK build config
│   └── iosMain/                 # iOS implementations (placeholder)
├── composeApp/                  # UI module
│   ├── commonMain/              # Shared UI (App.kt)
│   ├── androidMain/             # Android entry (MainActivity)
│   └── iosMain/                 # iOS entry (MainViewController)
└── docs/                        # Documentation
```

---

## 2. Key Components

### LlmEngine Interface
```kotlin
interface LlmEngine {
    suspend fun loadModel(path: String): Boolean
    suspend fun generate(
        prompt: String,
        maxTokens: Int = 256,
        temperature: Float = 0.7f,
        topP: Float = 0.9f,
        repeatPenalty: Float = 1.1f,
        useChatTemplate: Boolean = true
    ): String
    fun unloadModel()
    fun isModelLoaded(): Boolean
    // Metrics
    fun getPrefillTimeMs(): Long
    fun getDecodeTimeMs(): Long
    fun getTokensGenerated(): Int
    fun getMemoryUsageMb(): Float
}
```

### ModelRepository (expect/actual)
```kotlin
// Common
expect class ModelRepository() {
    fun getModelPath(): String
    suspend fun isModelDownloaded(): Boolean
    fun downloadModel(): Flow<DownloadStatus>
    suspend fun getAvailableModels(): List<ModelInfo>
    fun setSelectedModel(path: String)
}

// Android: Uses HttpURLConnection for downloads
// iOS: Uses Ktor client (placeholder)
```

### LlmViewModel
Manages UI state including:
- Model loading status
- Download progress (%, MB, Mbps)
- Generation output
- Performance metrics (TTFT, tokens/sec, memory)

---

## 3. Native Integration

### JNI Bridge (llama_jni.cpp)
Located at: `shared/src/androidMain/cpp/llama_jni.cpp`

Functions:
- `Java_com_aksoyapps_edgeqslm_shared_AndroidLlamaCppEngine_loadModelNative`
- `Java_com_aksoyapps_edgeqslm_shared_AndroidLlamaCppEngine_generateNative`
- `Java_com_aksoyapps_edgeqslm_shared_AndroidLlamaCppEngine_unloadNative`
- Metric getters for prefill time, decode time, tokens, memory

### CMake Configuration
```cmake
# Key settings
CMAKE_SYSTEM_NAME = Android
CMAKE_ANDROID_ARCH_ABI = arm64-v8a
CMAKE_ANDROID_NDK = [NDK path]
GGML_OPENMP = OFF
LLAMA_BUILD_COMMON = ON
```

---

## 4. Model Download System

### Flow
1. App starts → `ModelRepository.isModelDownloaded()` check
2. If not found → Show download button
3. User taps download → `ModelRepository.downloadModel()` returns Flow
4. Flow emits `DownloadStatus.Progress(progress, bytes, total, speed)`
5. UI updates progress bar, notification updates
6. On complete → `DownloadStatus.Completed` emitted
7. Model ready for loading

### Download Implementation (Android)
Uses native `HttpURLConnection` instead of Ktor due to memory issues with large files:
- Buffer size: 8192 bytes
- Progress updates: Every 500ms
- Notification: Updates via NotificationCompat
- Target: App's external files directory (no permissions needed)

---

## 5. UI Components

### App.kt Structure
```
EdgeQSLMApp
├── HeaderSection           # App title
├── ModelStatusCard         # Status, model selector, debug checkbox
├── PresetPromptsRow        # Quick prompt chips
├── PromptInputSection      # Text input
├── ActionButtonsRow        # Download/Load/Generate buttons
│   └── Download Progress   # Progress bar with stats
├── MetricsDashboard        # TTFT, tokens/sec, memory
├── OutputSection           # Generated text
└── ErrorCard               # Error display
```

---

## 6. File Locations

### Android Storage
```
/sdcard/Android/data/com.aksoyapps.edgeqslm/files/
├── qwen1_5-1_8b-chat-q8_0.gguf     # Downloaded model
└── qwen1_5-1_8b-chat-q8_0.gguf.tmp # Temp during download
```

### APK Output
```
composeApp/build/outputs/apk/debug/composeApp-debug.apk
```

---

## 7. Build Commands

```bash
# Full build
./gradlew :composeApp:assembleDebug

# Install
adb install -r composeApp/build/outputs/apk/debug/composeApp-debug.apk

# Run
adb shell am start -n com.aksoyapps.edgeqslm/.MainActivity

# Logs
adb logcat -s ModelRepository:D LlamaJNI:V
```

---

## 8. Configuration

### Gradle Properties
```properties
org.gradle.java.home=[Java 17 path]
org.gradle.jvmargs=-Xmx4g
```

### Build Configuration
| Setting | Value |
|---------|-------|
| Package Name | com.aksoyapps.edgeqslm |
| Min SDK | 24 |
| Target SDK | 34 |
| NDK | 26.1.10909125 |
| ABI | arm64-v8a |

---

## 9. Dependencies

### Kotlin/Compose
- Kotlin: 2.1.0
- Compose Multiplatform: 1.7.3
- Coroutines: 1.9.0

### Android
- AGP: 8.2.2
- AndroidX Core: 1.13.1
- Material3: 1.3.1

### Networking
- Ktor: 2.3.8 (iOS only now)

---

*Last Updated: 2026-01-18*