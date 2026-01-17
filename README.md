# EdgeQ-SLM: On-Device SLM Inference

**EdgeQ-SLM** is a Proof of Concept (PoC) application demonstrating efficient quantization and low-latency inference of Small Language Models (SLMs) on mobile devices using **Kotlin Multiplatform** and **llama.cpp**.

This project serves as the implementation part of the thesis: *"Efficient Quantization and Low-Latency Inference of Small Language Models on Mobile Devices"*.

## 🚀 Features

- **On-Device Inference:** Runs quantized GGUF models (e.g., Qwen 1.5 1.8B) entirely offline.
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

### 2. Prepare the Model
You need a GGUF quantized model. We recommend **Qwen 1.5 1.8B**.

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

### 3. Deploy Model to Device (Android)
Due to Android permission restrictions, copy the model to the app's external files directory:

```bash
# Create directory
adb shell mkdir -p /sdcard/Android/data/com.example.edgeqslm/files/

# Push model
adb push qwen-q8_0.gguf /sdcard/Android/data/com.example.edgeqslm/files/
```

### 4. Build and Run
Open the project in Android Studio and run the `composeApp` configuration on your Android device.

## 📊 Documentation

- [Progress Report](docs/PROGRESS_REPORT.md): Detailed log of development, challenges, and solutions.
- [Project Structure](PROJECT_GUIDE.md): Technical overview of the codebase.

## ⚖️ License

MIT License
