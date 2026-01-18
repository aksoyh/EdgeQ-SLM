# EdgeQ-SLM: On-Device SLM Inference

**EdgeQ-SLM** is a Proof of Concept (PoC) application demonstrating efficient quantization and low-latency inference of Small Language Models (SLMs) on mobile devices using **Kotlin Multiplatform** and **llama.cpp**.

This project serves as the implementation part of the thesis: *"Efficient Quantization and Low-Latency Inference of Small Language Models on Mobile Devices"*.

## 🚀 Features

- **On-Device Inference:** Runs quantized GGUF models (e.g., Qwen 1.5 1.8B) entirely offline.
- **In-App Model Download:** Download models directly from HuggingFace with progress tracking.
- **Performance Metrics:** Real-time dashboard displaying:
  - **TTFT (Time To First Token):** Prefill latency.
  - **Decode Speed:** Tokens per second generation rate.
  - **Memory Usage:** Peak RAM consumption.
- **Cross-Platform Architecture:** Built with Kotlin Multiplatform (KMP) and Jetpack Compose.
- **Advanced JNI Integration:** Custom C++ bridge to `llama.cpp` supporting:
  - ChatML templates
  - Stop token detection
  - Configurable sampling (Temperature, Top-P, Repeat Penalty)
- **Quantization Support:** Optimized for INT8 (Q8_0) and INT4 quantization schemes.

## 🛠️ Architecture

- **UI Layer:** Compose Multiplatform (Android/iOS*)
- **Business Logic:** Kotlin Shared Module (ViewModel, State Management)
- **Model Management:** ModelRepository with expect/actual pattern
- **Native Bridge:** JNI (Java Native Interface) implementation
- **Inference Engine:** `llama.cpp` (C++) cross-compiled using CMake

## 📋 Prerequisites

- **IDE:** Android Studio Iguana or newer
- **JDK:** Java 17 (Required by AGP 8.0+)
- **NDK:** Version 26.1.x
- **CMake:** 3.22.1+
- **Python:** 3.x (for model conversion scripts)

## 📥 Setup & Build

### 1. Clone the Repository
```bash
git clone https://github.com/aksoyh/EdgeQ-SLM.git
cd EdgeQ-SLM
```

### 2. Build and Run
Open the project in Android Studio and run the `composeApp` configuration on your Android device.

### 3. Download Model (Option A: In-App)
The app includes a built-in download feature:
1. Launch the app
2. Check the "🔧 Debug: Force 'No Model'" checkbox to show download button
3. Tap "☁️ Download" to download from HuggingFace
4. Progress bar shows download speed and completion percentage

### 3. Deploy Model (Option B: Manual via ADB)
If you prefer to use your own quantized model:

```bash
# Create directory
adb shell mkdir -p /sdcard/Android/data/com.aksoyapps.edgeqslm/files/

# Push model
adb push qwen-q8_0.gguf /sdcard/Android/data/com.aksoyapps.edgeqslm/files/
```

### 4. Prepare Your Own Model (Optional)
If you want to quantize a model yourself:

1.  **Download Model:**
    ```bash
    pip3 install huggingface_hub
    huggingface-cli download Qwen/Qwen1.5-1.8B --local-dir models/Qwen1.5-1.8B
    ```

2.  **Convert & Quantize (using llama.cpp tools):**
    ```bash
    # Clone llama.cpp if you haven't
    git clone https://github.com/ggerganov/llama.cpp
    cd llama.cpp
    
    # Convert to GGUF (FP16)
    python3 convert_hf_to_gguf.py ../models/Qwen1.5-1.8B --outfile qwen-f16.gguf
    
    # Quantize to INT8 (Q8_0)
    cmake -B build && cmake --build build --config Release
    ./build/bin/llama-quantize qwen-f16.gguf qwen-q8_0.gguf Q8_0
    ```

## 📱 Usage

1. **Launch App** - The app will check for available models
2. **Select Model** - Use the dropdown to choose from available `.gguf` files
3. **Load Model** - Tap "📦 Load Model" to initialize the inference engine
4. **Generate** - Enter a prompt and tap "⚡ Generate"
5. **View Metrics** - Real-time performance metrics are displayed below

## 📊 Documentation

- [Progress Report](docs/PROGRESS_REPORT.md): Detailed log of development, challenges, and solutions.
- [Project Structure](PROJECT_GUIDE.md): Technical overview of the codebase.

## 🗂️ Key Files

| File | Description |
|------|-------------|
| `shared/src/commonMain/kotlin/.../ModelRepository.kt` | Cross-platform model management interface |
| `shared/src/androidMain/kotlin/.../ModelRepository.android.kt` | Android download with HttpURLConnection |
| `shared/src/commonMain/kotlin/.../LlmViewModel.kt` | State management and download coordination |
| `composeApp/src/commonMain/kotlin/.../App.kt` | UI with download progress and model selection |
| `shared/src/androidMain/cpp/llama_jni.cpp` | JNI bridge to llama.cpp |

## 🔧 Troubleshooting

| Issue | Solution |
|-------|----------|
| "Model not found" | Use ADB push to copy model to app's files directory |
| "Download failed" | Check internet connection, try again |
| "Load failed" | Ensure model file is valid GGUF format |
| Permission errors | Model must be in `/sdcard/Android/data/com.aksoyapps.edgeqslm/files/` |

## ⚖️ License

MIT License
