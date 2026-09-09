# EdgeQ-SLM: On-Device Small Language Model Inference

**EdgeQ-SLM** is a Proof of Concept (PoC) mobile application demonstrating efficient quantization and low-latency inference of Small Language Models (SLMs) on Android devices using **Kotlin Multiplatform** and **llama.cpp**.

This project serves as the implementation component for the Master's thesis:

> **"Efficient Quantization and Low-Latency Inference of Small Language Models on Mobile Devices"**
>
> Hasan Aksoy (140130)  
> MSc Advanced Analytics – Big Data  
> SGH Warsaw School of Economics  
> ha140130@student.sgh.waw.pl

---

## Features

- **On-Device LLM Inference:** Runs quantized GGUF models (Qwen 1.5 1.8B) entirely offline using llama.cpp.
- **Photo Search — Three Retrieval Modes:** ML (CLIP+OCR embeddings), VLM (vision-language captioning), and SLM (TinyLlama query planner with deterministic enrichment).
- **SLM Planner:** A quantized TinyLlama model interprets the user's query and produces a structured JSON search plan, which is then used for weighted keyword retrieval over the local photo index. This works without any internet connection or cloud API.
- **Benchmark Framework:** Systematic performance measurement with configurable experiments.
- **Performance Metrics:** Real-time display of TTFT, tokens/sec, memory, and CPU usage.
- **Export & Analysis:** CSV/JSON export for statistical analysis in Python or Julia.

---

## Benchmark Analysis & Documentation

The `/report/` directory contains the complete analysis of benchmark results. The Julia notebook is preferred for academic presentation.

| Document | Description |
|----------|-------------|
| [EdgeQ_SLM_ProofOfWork_Report.docx](report/EdgeQ_SLM_ProofOfWork_Report.docx) | Main thesis proof-of-work report with methodology, results, and discussion. |
| [EdgeQ_SLM_Analysis_Julia.ipynb](report/EdgeQ_SLM_Analysis_Julia.ipynb) | **Recommended.** Julia Jupyter notebook with statistical analysis and visualizations. |
| [EdgeQ_SLM_Analysis_Julia.html](report/EdgeQ_SLM_Analysis_Julia.html) | Static HTML export of the Julia notebook for viewing without Jupyter. |
| [EdgeQ_SLM_Analysis.ipynb](report/EdgeQ_SLM_Analysis.ipynb) | Python (Pandas/Matplotlib) notebook with similar analysis. |
| [EdgeQ_SLM_Analysis.html](report/EdgeQ_SLM_Analysis.html) | Static HTML export of the Python notebook. |

### What Each File Contains

**EdgeQ_SLM_ProofOfWork_Report.docx**
- Executive summary of findings
- System architecture description
- Experimental methodology
- Key performance results and tables
- Discussion of limitations and future work

**EdgeQ_SLM_Analysis_Julia.ipynb** (Recommended)
- Data loading and preprocessing
- Latency distribution analysis (histograms, box plots)
- Temperature effect on performance
- Memory consumption trends
- Statistical summaries (mean, std, percentiles)
- Publication-ready figures

**EdgeQ_SLM_Analysis.ipynb** (Python Alternative)
- Same analysis as Julia version using Pandas/Matplotlib
- Useful if Julia environment is not available

### When to Use Which

| Use Case | Recommended File |
|----------|------------------|
| Reading final results and methodology | `EdgeQ_SLM_ProofOfWork_Report.docx` |
| Reproducing statistical analysis | `EdgeQ_SLM_Analysis_Julia.ipynb` |
| Viewing analysis without running code | `EdgeQ_SLM_Analysis_Julia.html` |
| Python-based analysis workflow | `EdgeQ_SLM_Analysis.ipynb` |

---

## Project Structure

```
EdgeQ-SLM/
├── report/                           # Analysis & report files
│   ├── EdgeQ_SLM_ProofOfWork_Report.docx
│   ├── EdgeQ_SLM_Analysis_Julia.ipynb
│   ├── EdgeQ_SLM_Analysis_Julia.html
│   ├── EdgeQ_SLM_Analysis.ipynb
│   └── EdgeQ_SLM_Analysis.html
├── collected_data/                   # Raw benchmark data
│   ├── full_benchmark/               # SLM benchmark CSVs
│   ├── indexing_sessions.csv         # Photo indexing logs
│   └── indexing_item_details.csv     # Per-photo metrics
├── docs/                             # Technical documentation
│   ├── README_FOR_SUPERVISOR.md      # Project overview
│   ├── EXPERIMENT_PROTOCOL.md        # Methodology
│   └── PROOF_OF_WORK_TEMPLATE.md     # Report structure
├── screenshots_of_the_app/           # Application screenshots
│   ├── *.jpg                         # General UI screenshots
│   └── benchmark_results/            # Benchmark screen screenshots
├── shared/                           # Kotlin shared module
│   ├── src/commonMain/kotlin/        # Cross-platform interfaces
│   └── src/androidMain/              # Android implementations + JNI
├── composeApp/                       # Android UI (Compose)
└── README.md                         # This file
```

---

## Technical Documentation

| Document | Description |
|----------|-------------|
| [docs/README_FOR_SUPERVISOR.md](docs/README_FOR_SUPERVISOR.md) | High-level project overview for academic review. |
| [docs/EXPERIMENT_PROTOCOL.md](docs/EXPERIMENT_PROTOCOL.md) | Detailed experimental methodology and metrics definitions. |
| [docs/PROOF_OF_WORK_TEMPLATE.md](docs/PROOF_OF_WORK_TEMPLATE.md) | Template for the final proof-of-work report. |

---

## Quick Start

### Prerequisites

- Android Studio Iguana or newer
- JDK 17
- NDK 26.1.x
- ARM64 Android device (physical)

### Build & Install

```bash
# Clone
git clone https://github.com/aksoyh/edgeq-slm.git
cd EdgeQ-SLM

# Build
./gradlew composeApp:assembleDebug

# Install
adb install composeApp/build/outputs/apk/debug/composeApp-debug.apk
```

### Model Deployment

```bash
# LLM Chat Model (~1.8 GB) - download in-app or push manually
adb push qwen-q8_0.gguf /sdcard/Android/data/com.aksoyapps.edgeqslm/files/

# CLIP Models (~660 MB) for ML-based visual search
adb push clip-vit-b32-image.onnx /sdcard/Android/data/com.aksoyapps.edgeqslm/files/models/
adb push clip-vit-b32-text.onnx /sdcard/Android/data/com.aksoyapps.edgeqslm/files/models/

# SLM Planner Model (~700 MB) for structured query planning
adb push edgeq_planner_tinyllama_q4_k_m.gguf /sdcard/Android/data/com.aksoyapps.edgeqslm/files/
```

The SLM planner model is optional. If the file is not present, the SLM button in the photo search screen stays disabled. The app will fall back to ML or VLM mode without any errors.

---

## Running Benchmarks

1. Launch the app and load the model on the **LLM** tab.
2. Navigate to the **Benchmark** tab.
3. Tap **Full Benchmark** to run the complete experiment suite.
4. Results are automatically exported to CSV/JSON upon completion.
5. Pull data to your computer:

```bash
adb pull /sdcard/Android/data/com.aksoyapps.edgeqslm/files/results/ ./collected_data/
```

---

## Benchmark Configuration

Default configuration (optimized for thesis timeline):

| Parameter | Value |
|-----------|-------|
| Temperatures | 0.1, 1.1, 2.0 |
| Max Tokens | 128, 512, 1024 |
| Repeats | 3 per condition |
| Total Runs | ~270 (10 prompts × 3 temps × 3 tokens × 3 repeats) |
| Estimated Time | ~45-60 minutes |

---

## Key Metrics Collected

| Metric | Unit | Description |
|--------|------|-------------|
| E2E Latency | ms | Total inference time |
| TTFT | ms | Time to first token (prefill) |
| Tokens/sec | t/s | Generation speed |
| Peak Memory | MB | Maximum RAM usage (PSS) |
| CPU Utilization | % | Process CPU usage |
| Repetition Score | 0-1 | Output quality indicator |

---

## Application Screenshots

The `/screenshots_of_the_app/` directory contains screenshots demonstrating:

- **General UI:** LLM chat interface, photo search, resource monitoring
- **Benchmark Results:** In-app charts showing latency and token speed distributions

---

## Implementation Notes

**On-demand model loading**

The VLM and SLM models are not loaded at app startup. They are loaded on demand when the user taps the corresponding button in the photo search screen. This is by design: the JNI bridge for llama.cpp uses a single set of global native pointers (`g_model`, `g_context`), so loading two models concurrently causes the second load to free the first model's memory, resulting in a SIGSEGV crash. Loading lazily means only one model is ever in the native layer at a time.

The buttons reflect this with a `↓` indicator when the model file is present but not yet loaded, a spinner while loading, and no indicator when ready. After loading completes, any pending query runs automatically.

See [docs/README_FOR_SUPERVISOR.md](docs/README_FOR_SUPERVISOR.md) (Phase 4b) for the full analysis.

---

## Known Limitations

1. **Single Device Testing:** Results are device-specific.
2. **Quantized Models Only:** No unquantized baseline due to memory constraints.
3. **Process CPU Only:** Android restrictions prevent system-wide CPU measurement.
4. **Sanity Checks ≠ Full Benchmarks:** Output quality is approximated, not formally evaluated.
5. **One llama.cpp Model at a Time:** The native JNI bridge uses global state, so VLM and SLM cannot be loaded simultaneously. Switching between them requires unloading one first — this is handled automatically but it means the first switch after install always has a loading delay.

---

## Future Work

- INT4 quantization comparison
- Multi-device benchmark study
- NPU acceleration testing
- iOS implementation
- Fine-tuning the TinyLlama planner on a photo-specific query dataset to improve JSON output reliability at Q4 quantization

---

## License

MIT License

---

---

**Author:** Hasan Aksoy (140130)  
**Institution:** SGH Warsaw School of Economics  
**Contact:** ha140130@student.sgh.waw.pl | aksoy.android@gmail.com  
**Repository:** [github.com/aksoyh/edgeq-slm](https://github.com/aksoyh/edgeq-slm)  
**Date:** June 2026
