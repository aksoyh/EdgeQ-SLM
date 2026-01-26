# Experiment Protocol

**Project:** EdgeQ-SLM – On-Device Small Language Model Inference  
**Version:** 1.0  
**Last Updated:** January 2026

---

## 1. Overview

This document describes the experimental methodology for evaluating the performance of quantized Small Language Models (SLMs) on mobile devices. The experiments are designed to provide reproducible measurements for thesis proof-of-work requirements.

### 1.1 Research Questions

- **RQ1:** What are the latency, memory, and CPU characteristics of INT8-quantized SLM inference on mobile ARM64 devices?
- **RQ2:** Does the quantized model produce coherent, non-degenerate outputs across various prompt types and sampling parameters?

### 1.2 Scope

This protocol covers:
- Performance measurement methodology (latency, memory, CPU)
- Output quality sanity checks (not full benchmark suites)
- Reproducibility requirements

Out of scope for Winter milestone:
- Full benchmark suites (MMLU, HellaSwag, etc.)
- NPU/GPU acceleration
- Cross-device comparison

---

## 2. Experimental Design

### 2.1 Independent Variables

| Variable | Values | Rationale |
|----------|--------|-----------|
| **Temperature** | 0.1, 0.3, 0.7, 1.0, 1.3, 1.7, 2.0 | Covers deterministic (low) to creative (high) sampling |
| **Prompt Category** | Factual, Instruction, Creative, Edge Cases | Tests different use-case scenarios |
| **Prompt Length** | Short, Medium, Long, Very Long | Tests prefill/decode performance scaling |

### 2.2 Dependent Variables

| Metric | Unit | Measurement Method |
|--------|------|-------------------|
| End-to-end latency | ms | System clock (Kotlin measureTimeMillis) |
| Time to First Token (TTFT) | ms | llama.cpp internal timing |
| Decode time | ms | llama.cpp internal timing |
| Tokens per second | tok/s | tokens_generated / decode_time_seconds |
| Peak memory | MB | Android Debug.MemoryInfo (PSS) |
| CPU utilization | % | /proc/[pid]/stat deltas |

### 2.3 Controlled Variables

- **Model:** Qwen 1.5 1.8B (INT8 Q8_0 quantization)
- **Inference engine:** llama.cpp (CPU)
- **Max tokens:** 256
- **Top-P:** 0.9
- **Repeat penalty:** 1.1
- **Chat template:** ChatML format enabled

---

## 3. Prompt Set

The experiment uses a fixed set of 18 prompts across categories:

### 3.1 Factual Q&A (5 prompts)
- **Short:** Simple factual questions (1-2 sentences)
  - Example: "What is the capital of France?"
- **Medium:** Concept explanations (3-5 sentences expected)
  - Example: "Explain what photosynthesis is and why it is important."
- **Long:** Complex multi-part questions

### 3.2 Instruction Following (5 prompts)
- **Short:** Simple lists, translations
- **Medium:** Code generation, structured outputs
- **Long:** Step-by-step tutorials

### 3.3 Creative (5 prompts)
- **Short:** Haiku, slogans
- **Medium:** Dialogue, world-building
- **Long:** Story openings

### 3.4 Edge Cases (3 prompts)
- **Very Short:** Single word/character inputs ("Hi", "?")
- **Very Long:** Multi-paragraph complex prompts (~500 tokens)

---

## 4. Procedure

### 4.1 Pre-Experiment Setup

1. **Device Preparation**
   - Close all background applications
   - Set device to airplane mode (optional, reduces variance)
   - Ensure battery > 50% or connected to power
   - Allow device to reach thermal equilibrium (2-3 minutes idle)

2. **Model Loading**
   - Load model once per session
   - Record model load time and memory

3. **Environment Recording**
   - Record device model, OS version, ABI
   - Record available RAM at start
   - Record ambient conditions if relevant

### 4.2 Warmup Phase

- Execute 1 warmup run with the first prompt at default temperature
- **Purpose:** Ensure JIT compilation, cache warming, memory allocation
- Warmup results are logged but **excluded from statistical analysis**

### 4.3 Main Experiment Phase

For each prompt in the prompt set:
  For each temperature in [0.1, 0.3, 0.7, 1.0, 1.3, 1.7, 2.0]:
    For each repeat in [1..5]:
      1. Record start timestamp
      2. Start CPU monitoring
      3. Record pre-inference memory
      4. Execute inference with current (prompt, temperature)
      5. Record post-inference memory
      6. Stop CPU monitoring
      7. Perform sanity checks on output
      8. Save result incrementally to file

### 4.4 Post-Experiment

1. Calculate aggregated statistics (mean, std)
2. Export results to CSV/JSON
3. Export sample outputs for manual review
4. Record session metadata

---

## 5. Metrics Definitions

### 5.1 Timing Metrics

| Metric | Definition | Source |
|--------|------------|--------|
| **E2E Latency** | Wall-clock time from generate() call to return | Kotlin measureTimeMillis |
| **TTFT (Prefill)** | Time from prompt submission to first token generation | llama.cpp getPrefillTimeNative() |
| **Decode Time** | Time spent generating tokens after first token | llama.cpp getDecodeTimeNative() |
| **Tokens/sec** | tokens_generated / (decode_time / 1000) | Calculated |

### 5.2 Memory Metrics

| Metric | Definition | Source |
|--------|------------|--------|
| **Peak Memory (PSS)** | Maximum Proportional Set Size during inference | Debug.MemoryInfo.totalPss |
| **Avg Memory** | Average of pre and post inference readings | Calculated |

**Note:** PSS includes the app's share of shared memory, providing a more accurate picture of actual memory impact than private memory alone.

### 5.3 CPU Metrics

| Metric | Definition | Source |
|--------|------------|--------|
| **CPU Utilization** | Process CPU time / Total CPU time during inference | /proc/[pid]/stat, /proc/stat |

**Calculation:**
```
cpu_percent = (process_user + process_system) / (total_cpu_delta) * 100 * num_cores
```

### 5.4 Sanity Check Metrics (RQ2)

| Check | Pass Criteria | Purpose |
|-------|---------------|---------|
| **Empty Output** | output.length > 0 | Detect generation failures |
| **Minimum Tokens** | estimated_tokens >= 3 | Detect truncated outputs |
| **Repetition Score** | repeated_3grams / total_3grams < 0.3 | Detect degenerate repetition |
| **Coherence** | Passes all above checks | Overall quality indicator |

---

## 6. Data Aggregation

### 6.1 Grouping

Results are aggregated by: **(prompt_id, temperature, engine_variant)**

### 6.2 Statistics Computed

For each group:
- **Mean** and **Standard Deviation** for:
  - Total latency (ms)
  - TTFT (ms)
  - Decode time (ms)
  - Tokens per second
  - Peak memory (MB)
  - CPU utilization (%)
  - Tokens generated

- **Counts:**
  - Empty outputs
  - High repetition outputs (score > 0.3)
  - Success rate (coherent outputs / total outputs)

---

## 7. Output Files

All outputs are saved to the app's internal storage under `/results/`:

| File | Format | Contents |
|------|--------|----------|
| `latency_memory_cpu_[timestamp].csv` | CSV | All raw run results |
| `aggregated_stats_[timestamp].csv` | CSV | Aggregated statistics |
| `sanity_checks_[timestamp].csv` | CSV | Sanity check results |
| `sample_outputs_[timestamp].json` | JSON | Sample outputs for review |
| `run_metadata_[timestamp].json` | JSON | Session configuration and device info |
| `device_info_[timestamp].txt` | Text | Human-readable device information |

---

## 8. Reproducibility Requirements

### 8.1 Code Requirements

- No hardcoded local paths (e.g., `/Users/...`)
- Configuration via Gradle properties or environment variables
- Git commit hash recorded in metadata (when available)

### 8.2 Reporting Requirements

Each experiment report must include:
1. Device specifications (model, OS, RAM)
2. Model specifications (name, quantization type, size)
3. Exact configuration parameters used
4. Number of runs, warmup runs
5. Date and time of experiment

### 8.3 Data Availability

- Raw CSV files must be retained
- Sample outputs must be available for manual inspection
- Metadata JSON provides full experiment configuration

---

## 9. Limitations

### 9.1 Measurement Limitations

1. **Memory measurement:** PSS is an approximation; exact memory usage is difficult to isolate for native processes.
2. **CPU measurement:** /proc/stat provides approximate utilization; does not capture per-core distribution.
3. **Token counting:** Estimated from word count; actual tokenization depends on model vocabulary.

### 9.2 Experimental Limitations

1. **Single device:** Results may not generalize to other devices.
2. **Single model:** Results specific to Qwen 1.5 1.8B with Q8_0 quantization.
3. **No quality benchmarks:** Sanity checks are not substitutes for LLM evaluation benchmarks.
4. **Environmental factors:** Battery level, thermal throttling, background processes may introduce variance.

### 9.3 Mitigation Strategies

- Use warmup runs to reduce cold-start effects
- Multiple repeats (n=5) to capture variance
- Record device state in metadata
- Report standard deviations alongside means

---

## 10. Future Extensions

### 10.1 Winter+ (Planned)

- INT4 quantization comparison
- Unquantized baseline comparison (when available)
- Multi-device comparison

### 10.2 Out of Scope

- NPU acceleration (requires hardware-specific implementation)
- Full LLM benchmarks (MMLU, HellaSwag) - significant additional effort
- Real-time output quality evaluation

---

## Appendix A: Prompt List

See `assets/prompts.json` for the complete prompt set.

## Appendix B: Temperature Effects

| Temperature | Expected Behavior |
|-------------|-------------------|
| 0.1 | Near-deterministic, highly focused outputs |
| 0.3 | Slightly varied but still focused |
| 0.7 | Balanced creativity and coherence |
| 1.0 | Default, moderate variation |
| 1.3 | More creative, may show minor coherence issues |
| 1.7 | High creativity, potential quality degradation |
| 2.0 | Maximum randomness, expect higher repetition/incoherence |

---

*Document prepared for thesis proof-of-work requirements. For questions, contact the thesis author.*
