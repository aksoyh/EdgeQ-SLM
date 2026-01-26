# EdgeQ-SLM Session Notes

## Session: 2026-01-21 (Latest)

### Work Done
- Updated all documentation to reflect current state
- Created `PROJECT_VISION_AND_MILESTONES.md` explaining project goals

### Current State
- All features working on Android
- CLIP tokenizer needs BPE improvement for better accuracy
- System CPU monitoring shows 0% due to Android restrictions

---

## Session: 2026-01-19

### Work Done
1. **CLIP Visual Search**
   - Integrated CLIP ViT-B/32 models (image + text)
   - Added image embedding generation during indexing
   - Implemented cosine similarity search
   - Created hybrid search (OCR + CLIP combined)

2. **Match Type Badges**
   - OCR matches: Blue badge
   - CLIP matches: Purple badge
   - Hybrid matches: Orange badge
   - Match reason shown below each result

3. **Force Index Feature**
   - Long-press (5 seconds) triggers force re-indexing
   - Progress bar fills during hold
   - Clears database and re-indexes all photos

4. **Resource Monitoring Bar**
   - Added above bottom navigation
   - Shows: Device CPU, App CPU, Device RAM, App RAM
   - Updates every 2 seconds

5. **UI Improvements**
   - Made Photo Search page fully scrollable
   - Aligned Index and Refresh button heights
   - Added CLIP loading indicator with spinner

### Issues Encountered
- CLIP tokenizer uses simple word-level tokenization instead of BPE
- System CPU shows 0% due to Android SELinux restrictions on /proc
- Some photos not matching CLIP search despite visual similarity

### Technical Notes
- Updated ONNX Runtime from 1.16.3 to 1.18.0 for IR version 10 support
- CLIP models stored in `/sdcard/Android/data/com.aksoyapps.edgeqslm/files/models/`
- Vocab.json uses `</w>` suffix for complete words

---

## Session: 2026-01-18

### Work Done
1. **Photo Search with OCR**
   - Added ML Kit OCR integration
   - Created PhotoIndexer for folder scanning
   - Implemented SQLite storage in PhotoVectorStore
   - Built PhotoSearchScreen with search UI
   - Added fullscreen photo viewer with pinch-to-zoom

2. **Model Download Feature**
   - Switched from Google Drive to HuggingFace
   - Added progress bar with speed/size info
   - System notification during download
   - Model selection dropdown

3. **Package Rename**
   - Changed from com.example to com.aksoyapps

### Issues Encountered
- Google Drive download blocked due to virus scan page
- Ktor memory issues with large file download
- Scoped storage restrictions on Downloads folder

---

## Session: 2026-01-17

### Work Done
1. **LLM Integration**
   - Created JNI bridge (llama_jni.cpp)
   - Configured CMake for ARM64 cross-compilation
   - Implemented AndroidLlamaCppEngine
   - Added ChatML template support
   - Created metrics dashboard (TTFT, tokens/s, memory)

2. **Model Preparation**
   - Downloaded Qwen 1.5 1.8B
   - Converted to GGUF format
   - Quantized to INT8 (Q8_0)

### Issues Encountered
- Java 25 incompatible with AGP
- Makefile deprecated, switched to CMake
- -ffast-math error in GGML

---

## Session: 2025-11-19

### Work Done
- Initial project setup
- Created Kotlin Multiplatform project structure
- Set up Compose Multiplatform for UI
- Configured Gradle build system

---

## Pending Tasks

### High Priority
- [ ] Implement proper BPE tokenizer for CLIP
- [ ] Merge feature/clip-visual-search to main
- [ ] Collect performance metrics on device

### Medium Priority
- [ ] INT4 quantization testing
- [ ] GPU/NPU acceleration exploration
- [ ] Multiple device benchmarks

### Low Priority
- [ ] iOS implementation
- [ ] RAG integration (LLM + Photo context)
- [ ] Voice input support

---

*Last updated: 2026-01-21*
