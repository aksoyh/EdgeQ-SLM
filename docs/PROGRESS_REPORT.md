# EdgeQ-SLM Progress Report

## Thesis: Efficient Quantization and Low-Latency Inference of Small Language Models on Mobile Devices

**Author:** Hasan Aksoy  
**Last Updated:** 2026-01-18  
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

**Issue 3: Python Dependencies Missing**
```
ModuleNotFoundError: No module named 'transformers'
```
Resolution: `pip3 install transformers torch sentencepiece huggingface_hub`

---

## 3. Model Preparation

### 3.1 Quantization Results

| Metric | Value |
|--------|-------|
| Original Size (FP16) | 3.67 GB |
| Quantized Size (Q8_0) | 1.86 GB |
| Compression Ratio | 1.97x |

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
│   ├── src/commonMain/kotlin/    # LlmEngine interface, ViewModel
│   ├── src/androidMain/kotlin/   # AndroidLlamaCppEngine (JNI wrapper)
│   └── src/androidMain/cpp/      # JNI bridge (llama_jni.cpp, CMakeLists.txt)
├── composeApp/
│   ├── src/commonMain/kotlin/    # App.kt (UI)
│   └── src/androidMain/kotlin/   # MainActivity
```

### 5.2 CMake Cross-Compilation Issues

**Issue: -ffast-math Incompatibility**
```
error: "some routines in ggml.c require non-finite math arithmetics"
```
Resolution: Added to CMakeLists.txt:
```cmake
add_compile_options(-fno-finite-math-only)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
```

### 5.3 JNI Signature

```kotlin
private external fun generateNative(
    prompt: String,
    maxTokens: Int,        // 256 default
    temperature: Float,    // 0.7 default
    topP: Float,           // 0.9 default
    repeatPenalty: Float,  // 1.1 default
    useChatTemplate: Boolean
): String
```

---

## 6. Device Deployment Challenges

### 6.1 Model Loading Failures

**Attempt 1: /sdcard/Download/**
```
E LlamaJNI: loadModelNative: Failed to load
```
Cause: Scoped storage restrictions on Android 11+

**Attempt 2: /data/local/tmp/**
```
E LlamaJNI: loadModelNative: Failed to load
```
Cause: File owned by shell user, app cannot access

**Attempt 3: App's External Files Directory**
```bash
adb push model.gguf /sdcard/Android/data/com.example.edgeqslm/files/
```
Status: Testing in progress

### 6.2 Permission Fixes Applied

- Added `READ_EXTERNAL_STORAGE` permission
- Set `android:requestLegacyExternalStorage="true"`
- Set `android:largeHeap="true"` for 2GB model
- Changed default path to app's own directory

---

## 7. Challenges and Learnings

| Challenge | Root Cause | Solution |
|-----------|------------|----------|
| Java 25 incompatible | AGP requires Java 17 | Set org.gradle.java.home |
| Makefile deprecated | llama.cpp migrated to CMake | Use cmake instead of make |
| -ffast-math error | GGML needs non-finite math | Add -fno-finite-math-only |
| Truncated responses | n_predict=50 too low | Increased to 256, made configurable |
| Poor formatting | Missing ChatML template | Added template wrapper |
| Model load fails | Android scoped storage | Use app's files directory |
| ADB unauthorized | USB debugging not approved | Accept prompt on device |

---

## 8. Current Status

### Completed
- [x] llama.cpp desktop build
- [x] Model download and INT8 quantization
- [x] Desktop baseline measurements
- [x] JNI bridge implementation
- [x] CMake cross-compilation for ARM64
- [x] Kotlin/Compose UI with metrics dashboard
- [x] ChatML template support
- [x] Stop token detection
- [x] APK build and installation on device

### In Progress
- [ ] Model loading on Android device (permission debugging)

### Pending
- [ ] Android performance measurements
- [ ] INT4 quantization comparison
- [ ] GPU/NPU acceleration tests
- [ ] Multiple device benchmarks

---

## 9. Metrics Summary Table

| Metric | Desktop (M-series) | Android (Expected) |
|--------|-------------------|-------------------|
| Model Size | 1.86 GB | 1.86 GB |
| Prefill Speed | ~300 t/s | TBD |
| Decode Speed | ~50 t/s | TBD |
| TTFT | ~50 ms | TBD |
| Memory Usage | ~2.5 GB | TBD |

---

## 10. Next Steps

1. **Resolve Model Loading**: Fix permission issues on Android device
2. **Collect Metrics**: Measure TTFT, tokens/sec, memory on device
3. **INT4 Testing**: Quantize to Q4_0, compare speed vs accuracy
4. **GPU Offloading**: Test with GGML_OPENCL for Adreno GPUs
5. **Multi-Device Benchmark**: Test on various Android devices
6. **Documentation**: Complete thesis measurements appendix

---

## Appendix: Key Files Modified

| File | Purpose |
|------|---------|
| `shared/src/androidMain/cpp/CMakeLists.txt` | llama.cpp cross-compile config |
| `shared/src/androidMain/cpp/llama_jni.cpp` | JNI bridge with ChatML support |
| `shared/src/androidMain/kotlin/.../AndroidLlamaCppEngine.kt` | Kotlin JNI wrapper |
| `shared/src/commonMain/kotlin/.../LlmEngine.kt` | Interface with metrics |
| `shared/src/commonMain/kotlin/.../LlmViewModel.kt` | State management |
| `composeApp/src/commonMain/kotlin/.../App.kt` | UI with metrics dashboard |
| `gradle.properties` | Java 17 path configuration |

---

*Report generated: 2026-01-18*
