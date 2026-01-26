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
- **Benchmark & Results:** Complete experiment runner for thesis proof-of-work:
  - Run experiments with configurable temperature sweep
  - Automated metrics collection (latency, memory, CPU)
  - Export results to CSV/JSON
  - Visualize results with charts
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
- **Benchmark Framework:** Measurement module with structured logging

## 📋 Prerequisites

- **IDE:** Android Studio Iguana or newer
- **JDK:** Java 17 (Required by AGP 8.0+)
- **NDK:** Version 26.1.x
- **CMake:** 3.22.1+
- **Python:** 3.x (for model conversion scripts)
- **llama.cpp:** Cloned locally (path configured in `local.properties`)

## 📥 Setup & Build

### 1. Clone the Repository
```bash
git clone https://github.com/aksoyh/EdgeQ-SLM.git
cd EdgeQ-SLM
```

### 2. Configure llama.cpp Path
Add to `local.properties`:
```properties
LLAMA_CPP_PATH=/path/to/your/llama.cpp
```
Or set environment variable:
```bash
export LLAMA_CPP_PATH=/path/to/your/llama.cpp
```

### 3. Build and Run
```bash
./gradlew composeApp:assembleDebug
adb install composeApp/build/outputs/apk/debug/composeApp-debug.apk
```

Or open the project in Android Studio and run the `composeApp` configuration.

### 4. Download Model

**Option A: In-App Download**
1. Launch the app
2. Check the "🔧 Debug: Force 'No Model'" checkbox to show download button
3. Tap "☁️ Download" to download from HuggingFace
4. Progress bar shows download speed and completion percentage

**Option B: Manual via ADB**
```bash
# Create directory
adb shell mkdir -p /sdcard/Android/data/com.aksoyapps.edgeqslm/files/

# Push model
adb push qwen-q8_0.gguf /sdcard/Android/data/com.aksoyapps.edgeqslm/files/
```

### 5. Prepare Your Own Model (Optional)
If you want to quantize a model yourself:

1. **Download Model:**
   ```bash
   pip3 install huggingface_hub
   huggingface-cli download Qwen/Qwen1.5-1.8B --local-dir models/Qwen1.5-1.8B
   ```

2. **Convert & Quantize (using llama.cpp tools):**
   ```bash
   cd $LLAMA_CPP_PATH
   python3 convert_hf_to_gguf.py /path/to/models/Qwen1.5-1.8B --outfile qwen-f16.gguf
   cmake -B build && cmake --build build --config Release
   ./build/bin/llama-quantize qwen-f16.gguf qwen-q8_0.gguf Q8_0
   ```

## 📱 Usage

### Basic Inference
1. **Launch App** - The app will check for available models
2. **Select Model** - Use the dropdown to choose from available `.gguf` files
3. **Load Model** - Tap "📦 Load Model" to initialize the inference engine
4. **Generate** - Enter a prompt and tap "⚡ Generate"
5. **View Metrics** - Real-time performance metrics are displayed below

### Running Benchmarks
1. Navigate to the **📊 Benchmark** tab
2. Review the default configuration (temperatures, repeats)
3. Ensure model is loaded on the LLM tab first
4. Tap **"Full Benchmark"** for complete experiment (18 prompts × 7 temps × 5 repeats)
5. Or tap **"⚡ Quick Test"** for a fast 3-prompt test
6. Monitor progress in real-time
7. When complete, tap **"📁 Export CSV/JSON"** to save results
8. Tap **"📤 Share"** to send files via email/drive

### Exported Files
Results are saved to `/data/data/com.aksoyapps.edgeqslm/files/results/`:
- `latency_memory_cpu_[timestamp].csv` - Raw metrics
- `aggregated_stats_[timestamp].csv` - Statistical summaries
- `sanity_checks_[timestamp].csv` - Output quality checks
- `sample_outputs_[timestamp].json` - Example outputs
- `run_metadata_[timestamp].json` - Experiment configuration
- `device_info_[timestamp].txt` - Device specifications

## 📊 Benchmark Framework

### Metrics Collected
| Metric | Unit | Method |
|--------|------|--------|
| E2E Latency | ms | System clock |
| TTFT (Prefill) | ms | llama.cpp native |
| Decode Time | ms | llama.cpp native |
| Tokens/sec | tok/s | Calculated |
| Peak Memory | MB | Debug.MemoryInfo (PSS) |
| CPU Utilization | % | /proc/stat |

### Temperature Sweep
Default: `[0.1, 0.3, 0.7, 1.0, 1.3, 1.7, 2.0]`

### Prompt Categories
- Factual Q&A (5 prompts)
- Instruction Following (5 prompts)
- Creative Writing (5 prompts)
- Edge Cases (3 prompts)

### Output Quality Checks (RQ2)
- Empty output detection
- Repetition score (3-gram analysis)
- Coherence validation

## 📚 Documentation

| Document | Description |
|----------|-------------|
| [GUIDELINE.md](GUIDELINE.md) | Measurement and logging guidelines |
| [docs/EXPERIMENT_PROTOCOL.md](docs/EXPERIMENT_PROTOCOL.md) | Academic experiment methodology |
| [docs/PROOF_OF_WORK_TEMPLATE.md](docs/PROOF_OF_WORK_TEMPLATE.md) | Thesis report template |
| [docs/NOTES_TR.md](docs/NOTES_TR.md) | Turkish notes with English terms |
| [docs/PROGRESS_REPORT.md](docs/PROGRESS_REPORT.md) | Development log |
| [docs/README_FOR_SUPERVISOR.md](docs/README_FOR_SUPERVISOR.md) | Supervisor overview |

## 🗂️ Key Files

| File | Description |
|------|-------------|
| `shared/.../benchmark/BenchmarkRunner.kt` | Experiment orchestrator |
| `shared/.../benchmark/BenchmarkModels.kt` | Data classes for results |
| `shared/.../benchmark/PromptSet.kt` | Fixed prompt set (18 prompts) |
| `shared/.../benchmark/SanityChecker.kt` | Output quality validation |
| `composeApp/.../benchmark/BenchmarkScreen.kt` | Benchmark UI with charts |
| `shared/.../LlmEngine.kt` | Cross-platform inference interface |
| `shared/.../AndroidLlamaCppEngine.kt` | Android llama.cpp implementation |
| `shared/src/androidMain/cpp/llama_jni.cpp` | JNI bridge to llama.cpp |

## 🔧 Troubleshooting

| Issue | Solution |
|-------|----------|
| "Model not found" | Use ADB push to copy model to app's files directory |
| "Download failed" | Check internet connection, try again |
| "Load failed" | Ensure model file is valid GGUF format |
| Permission errors | Model must be in `/sdcard/Android/data/com.aksoyapps.edgeqslm/files/` |
| Build fails with LLAMA_CPP_PATH | Set path in `local.properties` or as environment variable |
| Benchmark crashes | Ensure model is loaded before starting benchmark |

## 🔮 Future Work

- [ ] INT4 quantization comparison
- [ ] Unquantized baseline (FP16)
- [ ] iOS implementation
- [ ] NPU acceleration
- [ ] Full LLM evaluation benchmarks

## ⚖️ License

MIT License
