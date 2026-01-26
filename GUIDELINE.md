# EdgeQ-SLM – Measurement, Logging, and Proof-of-Work Guidelines

This project supports a Master's thesis proof-of-work for **on-device SLM inference** using **llama.cpp**.
The goal is to provide **reproducible experiments**, **exportable logs**, and **clear documentation**.

---

## What we must deliver (Winter proof-of-work)

1. **Working mobile PoC** (Android baseline; iOS optional)
2. **Reproducible build** (no hardcoded local paths)
3. **Measurement discipline**:
   - Latency (E2E + TTFT/prefill + decode if available)
   - Memory (document method)
   - CPU utilization (document method)
4. **RQ2: Lightweight sanity checks** (not full benchmarks)
5. **Exports**: CSV/JSON + minimal sample outputs
6. **Docs**: Experiment protocol + proof-of-work writeup template

---

## Folder Structure

```
/docs
├── EXPERIMENT_PROTOCOL.md     # Academic methodology description
├── PROOF_OF_WORK_TEMPLATE.md  # Report template for thesis
├── NOTES_TR.md                # Turkish explanations (for Hasan)
├── PROJECT_GUIDE.md           # Technical overview
└── README_FOR_SUPERVISOR.md   # Supervisor-facing summary

/results                       # Benchmark outputs (gitignored except samples)
├── latency_memory_cpu.csv
├── sanity_checks.csv
├── run_metadata.json
├── sample_outputs.json
└── device_info.txt

/assets
└── prompts.json               # Fixed prompt set

/shared/src/commonMain/.../benchmark/
├── BenchmarkModels.kt         # Data classes
├── PromptSet.kt               # Fixed prompts
├── SanityChecker.kt           # Output quality checks
├── SystemMetricsProvider.kt   # Platform interface
├── BenchmarkRunner.kt         # Experiment orchestrator
└── ExportService.kt           # Export utilities

/shared/src/androidMain/.../benchmark/
├── AndroidSystemMetricsProvider.kt
├── AndroidExportService.kt
└── BenchmarkViewModel.kt

/composeApp/src/androidMain/.../benchmark/
└── BenchmarkScreen.kt         # Benchmark UI
```

---

## Experiment Protocol (Minimum)

### Prompt Set
- **18 prompts** across categories:
  - Factual Q&A (short/medium/long)
  - Instruction following (short/medium/long)
  - Creative (short/medium/long)
  - Edge cases (very short / very long)

### Temperature Sweep
```
[0.1, 0.3, 0.7, 1.0, 1.3, 1.7, 2.0]
```

### Repeats
- Default: **5 runs per condition**
- Total runs: 18 prompts × 7 temps × 5 repeats = **630 runs**

### Warmup
- 1 warmup run (excluded from averages)

### Aggregation
- Mean + std for: latency, TTFT, decode, tok/s, memory, CPU

---

## Metrics Definitions

| Metric | Definition | Source |
|--------|------------|--------|
| **E2E latency (ms)** | Time from "run" start to final output | `measureTimeMillis` |
| **TTFT / Prefill (ms)** | Time to first token | `llama.cpp getPrefillTimeNative()` |
| **Decode (ms)** | Generation time excluding prefill | `llama.cpp getDecodeTimeNative()` |
| **Tokens/sec** | `tokens_generated / decode_time_seconds` | Calculated |
| **Memory (MB)** | PSS from `Debug.MemoryInfo` | Android |
| **CPU (%)** | Process CPU utilization | `/proc/stat` |

---

## RQ2 Sanity Checks (Winter)

**NOT full benchmark suites.** Minimum checks:

| Check | Pass Criterion |
|-------|----------------|
| Empty output | `output.length > 0` |
| Minimum tokens | `≥ 3 tokens` |
| Repetition score | `repeated_3grams / total < 0.3` |
| Coherence | All checks pass |

Store a small set of sample outputs for manual review.

---

## Baseline Comparison (Future-Ready)

| Variant | Status |
|---------|--------|
| **QUANTIZED_INT8** | ✅ Current implementation |
| **QUANTIZED_INT4** | 🔜 Future |
| **UNQUANTIZED_FP16** | 🔜 Future (optional baseline) |

Architecture supports multiple engine variants without refactor:
```kotlin
enum class EngineVariant {
    QUANTIZED_INT8,
    QUANTIZED_INT4,
    UNQUANTIZED_FP16,
    UNQUANTIZED_FP32
}
```

---

## Reproducibility Rules

### ✅ Do
- Use Gradle properties or environment variables for configuration
- Record git commit hash in metadata
- Include device info in exports
- Document measurement methods

### ❌ Don't
- Hardcode paths like `/Users/hasanaksoy/...`
- Use undocumented magic numbers
- Skip warmup runs
- Ignore measurement limitations

---

## How to Run Experiments

### 1. Build and Install
```bash
./gradlew composeApp:assembleDebug
adb install composeApp/build/outputs/apk/debug/composeApp-debug.apk
```

### 2. Download/Deploy Model
In-app download or:
```bash
adb push qwen-q8_0.gguf /sdcard/Android/data/com.aksoyapps.edgeqslm/files/
```

### 3. Run Benchmark
1. Launch app
2. Load model
3. Navigate to "Benchmark & Results" screen
4. Tap "Full Benchmark" or "Quick Test"
5. Wait for completion

### 4. Export Results
1. Tap "Export CSV/JSON"
2. Tap "Share" to send files
3. Or pull via ADB:
```bash
adb pull /data/data/com.aksoyapps.edgeqslm/files/results/ ./local_results/
```

---

## Supervisor-Facing Documents (English)

| Document | Purpose |
|----------|---------|
| `docs/EXPERIMENT_PROTOCOL.md` | Academic-style method description |
| `docs/PROOF_OF_WORK_TEMPLATE.md` | 2-4 page report structure |
| `docs/README_FOR_SUPERVISOR.md` | Project overview |

## Personal Notes (TR Explanations)

| Document | Purpose |
|----------|---------|
| `docs/NOTES_TR.md` | English terms, Turkish explanations |

---

## Quality Checklist

Before submitting proof-of-work:

- [ ] App builds without errors
- [ ] Full benchmark runs without crashes
- [ ] CSV/JSON exports contain all fields
- [ ] Device info is recorded
- [ ] Sample outputs are saved
- [ ] No hardcoded paths in code
- [ ] Documentation is up-to-date
- [ ] Git commit hash is available

---

## Limitations to Document

1. **Single device testing** - Results may not generalize
2. **Approximate memory** - PSS is not exact
3. **Approximate CPU** - `/proc/stat` is coarse
4. **Sanity ≠ Quality** - Not a substitute for LLM benchmarks
5. **Thermal effects** - Long runs may trigger throttling

---

*This document defines the measurement, logging, and documentation standards for the EdgeQ-SLM thesis proof-of-work.*
