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
| 2026-01-18 | Model Download | Implemented HuggingFace download with progress UI |
| 2026-01-18 | Package Rename | Changed package from com.example to com.aksoyapps |

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
│   ├── src/commonMain/kotlin/    # LlmEngine interface, ViewModel, ModelRepository
│   ├── src/androidMain/kotlin/   # AndroidLlamaCppEngine (JNI wrapper), ModelRepository.android
│   ├── src/iosMain/kotlin/       # IosLlamaCppEngine, ModelRepository.ios
│   └── src/androidMain/cpp/      # JNI bridge (llama_jni.cpp, CMakeLists.txt)
├── composeApp/
│   ├── src/commonMain/kotlin/    # App.kt (UI with download progress)
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

**Attempt 3: App's External Files Directory** ✅
```bash
adb push model.gguf /sdcard/Android/data/com.aksoyapps.edgeqslm/files/
```
Status: **WORKING** - No permission issues

### 6.2 Permission Fixes Applied

- Added `READ_EXTERNAL_STORAGE` permission
- Added `INTERNET` and `ACCESS_NETWORK_STATE` permissions
- Added `POST_NOTIFICATIONS` permission (Android 13+)
- Set `android:requestLegacyExternalStorage="true"`
- Set `android:largeHeap="true"` for 2GB model
- Changed default path to app's own directory

---

## 7. Model Download Feature (2026-01-18)

### 7.1 Feature Overview

Implemented in-app model download from HuggingFace with:
- Progress bar with percentage
- Download speed (Mbps)
- Downloaded/Total size (MB)
- System notification with progress
- Model selection dropdown
- Debug checkbox for testing

### 7.2 Implementation Details

| Component | File | Description |
|-----------|------|-------------|
| Common Interface | `ModelRepository.kt` | expect class with DownloadStatus, ModelInfo |
| Android Impl | `ModelRepository.android.kt` | HttpURLConnection download |
| iOS Impl | `ModelRepository.ios.kt` | Ktor client download (placeholder) |
| ViewModel | `LlmViewModel.kt` | Download state management |
| UI | `App.kt` | Progress bar, model selector, debug checkbox |

### 7.3 Download Issues and Resolutions

| Issue | Cause | Resolution |
|-------|-------|------------|
| Google Drive warning page | File >100MB triggers virus scan | Switched to HuggingFace direct URL |
| Ktor "connection abort" | Ktor memory issues with large files | Replaced with native HttpURLConnection |
| Read permission error | Scoped storage on Downloads folder | Prioritize app's external files directory |
| Duplicate model files | No model selection | Added dropdown to select from available models |

### 7.4 Model Sources

| Source | URL | Status |
|--------|-----|--------|
| HuggingFace (Official) | `https://huggingface.co/Qwen/Qwen1.5-1.8B-Chat-GGUF/resolve/main/qwen1_5-1_8b-chat-q8_0.gguf` | ✅ Working |
| Google Drive (Custom) | `https://drive.google.com/uc?export=download&id=...` | ❌ Warning page blocks download |

---

## 8. Package Rename (2026-01-18)

Changed package name from `com.example.edgeqslm` to `com.aksoyapps.edgeqslm`.

### Files Updated

| File | Change |
|------|--------|
| `composeApp/build.gradle.kts` | namespace and applicationId |
| `shared/build.gradle.kts` | namespace |
| `AndroidManifest.xml` | Package references |
| All Kotlin files | Package declarations |
| `llama_jni.cpp` | JNI function names |

---

## 9. Challenges and Learnings

| Challenge | Root Cause | Solution |
|-----------|------------|----------|
| Java 25 incompatible | AGP requires Java 17 | Set org.gradle.java.home |
| Makefile deprecated | llama.cpp migrated to CMake | Use cmake instead of make |
| -ffast-math error | GGML needs non-finite math | Add -fno-finite-math-only |
| Truncated responses | n_predict=50 too low | Increased to 256, made configurable |
| Poor formatting | Missing ChatML template | Added template wrapper |
| Model load fails | Android scoped storage | Use app's files directory |
| ADB unauthorized | USB debugging not approved | Accept prompt on device |
| Google Drive block | Virus scan warning page | Use HuggingFace instead |
| Ktor download fails | Memory issues with large files | Use native HttpURLConnection |

---

## 10. Current Status

### Completed ✅
- [x] llama.cpp desktop build
- [x] Model download and INT8 quantization
- [x] Desktop baseline measurements
- [x] JNI bridge implementation
- [x] CMake cross-compilation for ARM64
- [x] Kotlin/Compose UI with metrics dashboard
- [x] ChatML template support
- [x] Stop token detection
- [x] APK build and installation on device
- [x] Model loading on Android device
- [x] In-app model download from HuggingFace
- [x] Download progress UI with speed/size info
- [x] Model selection dropdown
- [x] Package rename to com.aksoyapps.edgeqslm

### In Progress 🔄
- [ ] Android performance measurements

### Pending 📋
- [ ] INT4 quantization comparison
- [ ] GPU/NPU acceleration tests
- [ ] Multiple device benchmarks
- [ ] iOS implementation testing

---

## 11. Metrics Summary Table

| Metric | Desktop (M-series) | Android (Expected) |
|--------|-------------------|-------------------|
| Model Size | 1.86 GB | 1.86 GB |
| Prefill Speed | ~300 t/s | TBD |
| Decode Speed | ~50 t/s | TBD |
| TTFT | ~50 ms | TBD |
| Memory Usage | ~2.5 GB | TBD |

---

## 12. Next Steps

1. **Collect Metrics**: Measure TTFT, tokens/sec, memory on device
2. **INT4 Testing**: Quantize to Q4_0, compare speed vs accuracy
3. **GPU Offloading**: Test with GGML_OPENCL for Adreno GPUs
4. **Multi-Device Benchmark**: Test on various Android devices
5. **iOS Testing**: Verify iOS build and functionality
6. **Documentation**: Complete thesis measurements appendix

---

## Appendix A: Key Files Modified

| File | Purpose |
|------|---------|
| `shared/src/androidMain/cpp/CMakeLists.txt` | llama.cpp cross-compile config |
| `shared/src/androidMain/cpp/llama_jni.cpp` | JNI bridge with ChatML support |
| `shared/src/androidMain/kotlin/.../AndroidLlamaCppEngine.kt` | Kotlin JNI wrapper |
| `shared/src/androidMain/kotlin/.../ModelRepository.android.kt` | Android download implementation |
| `shared/src/commonMain/kotlin/.../LlmEngine.kt` | Interface with metrics |
| `shared/src/commonMain/kotlin/.../LlmViewModel.kt` | State management with download |
| `shared/src/commonMain/kotlin/.../ModelRepository.kt` | Cross-platform download interface |
| `composeApp/src/commonMain/kotlin/.../App.kt` | UI with metrics and download progress |
| `gradle.properties` | Java 17 path configuration |

## Appendix B: Git Branch Structure

| Branch | Description |
|--------|-------------|
| `main` | Stable base project |
| `feature/model-download` | Model download feature with progress UI |

---

*Report generated: 2026-01-18*
