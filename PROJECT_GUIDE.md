# PROJECT_GUIDE_FOR_HASAN.md

Bu doküman, mobil PoC projesinin yapısını ve nasıl geliştirileceğini sana anlatmak için hazırlandı.  
Teknik terimler İngilizce, açıklamalar ise Türkçe olacak.

---

## 1. Overall Goal

Amaç:  
- Quantized bir **Small Language Model (SLM)** (Qwen 1.5 1.8B INT8 GGUF)  
- `llama.cpp` tabanlı bir **LlmEngine** ile  
- Android (ve mümkün oldukça iOS) üzerinde **on-device inference** çalıştırmak  
- ve **latency + memory** metriklerini ölçmek.

Bu PoC, tezinin Winter dönemindeki **“proof-of-work”** kısmını desteklemek için tasarlandı.

---

## 2. Project Structure (Modules & Folders)

Örnek yapı:

- `build.gradle.kts`, `settings.gradle.kts`
- `shared/` (veya `core/`)
  - `commonMain/`
    - `LlmEngine.kt`
    - `GenerationRequest.kt`
    - `GenerationResult.kt`
    - `LlmController.kt` (veya `ViewModel`)
    - `UiState.kt`
  - `androidMain/`
    - `AndroidLlmEngine.kt`
    - JNI / llamacpp binding stub’ları
  - `iosMain/`
    - `IosLlmEngine.kt` (gerekirse TODO ile stub)
- `androidApp/`
  - `MainActivity.kt`
  - Compose UI ekranları
- (Opsiyonel) `iosApp/` veya Xcode projesi

**LlmEngine interface:**  
Bu interface, tüm platformlar için ortak abstraction.  
```kotlin
interface LlmEngine {
    suspend fun generate(request: GenerationRequest): GenerationResult
}