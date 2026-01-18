---
description: Continue EdgeQ-SLM project development from previous session
---

# EdgeQ-SLM Project Continuation Workflow

When starting a new session on this project, follow these steps:

## 1. Read Context Files
Read the following files to understand the current project state:

```bash
# Primary context
view docs/SESSION_NOTES.md
view docs/PROGRESS_REPORT.md
```

## 2. Check Git Status
```bash
cd /Users/hasanaksoy/AntigravityProjects/EdgeQ-SLM
git status
git log -n 5 --oneline
```

## 3. Key Information Summary

### Project Details
- **Package Name:** com.aksoyapps.edgeqslm
- **Model:** Qwen 1.5 1.8B Chat (Q8_0, ~1.86GB)
- **Model Storage:** `/sdcard/Android/data/com.aksoyapps.edgeqslm/files/`
- **Download URL:** `https://huggingface.co/Qwen/Qwen1.5-1.8B-Chat-GGUF/resolve/main/qwen1_5-1_8b-chat-q8_0.gguf`

### Key Files
| File | Purpose |
|------|---------|
| `shared/src/commonMain/kotlin/.../ModelRepository.kt` | Cross-platform model management |
| `shared/src/androidMain/kotlin/.../ModelRepository.android.kt` | Android download implementation |
| `shared/src/commonMain/kotlin/.../LlmViewModel.kt` | State management |
| `composeApp/src/commonMain/kotlin/.../App.kt` | Main UI |
| `shared/src/androidMain/cpp/llama_jni.cpp` | JNI bridge |

### Build Commands
// turbo
```bash
./gradlew :composeApp:assembleDebug
```

// turbo
```bash
adb install -r composeApp/build/outputs/apk/debug/composeApp-debug.apk
```

### Completed Features (as of 2026-01-18)
- [x] Model loading via JNI/llama.cpp
- [x] Text generation with ChatML template
- [x] Performance metrics (TTFT, tokens/sec, memory)
- [x] In-app model download from HuggingFace
- [x] Download progress UI with speed/size info
- [x] Model selection dropdown
- [x] Debug checkbox for testing
- [x] Package rename to com.aksoyapps

### Next Steps
- [ ] Collect Android performance benchmarks
- [ ] INT4 quantization comparison
- [ ] iOS implementation completion
- [ ] GPU/NPU acceleration testing

## 4. After Reading Context
Confirm understanding and ask user what to work on next.
