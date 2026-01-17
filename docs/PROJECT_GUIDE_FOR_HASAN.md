# PROJECT_GUIDE_FOR_HASAN.md

## Project Structure (Proje Yapısı)
Bu proje Kotlin Multiplatform (KMP) kullanılarak hazırlanmıştır. Kodun büyük bir kısmı Android ve iOS arasında ortaktır.

-   **`shared` module**:
    -   `commonMain`: Tüm mantık burada (ViewModel, Data classes).
    -   `androidMain`: Android'e özel kodlar. `AndroidLlamaCppEngine` burada bulunur. JNI çağrıları buradan yapılır.
    -   `androidMain/cpp`: **llama.cpp JNI bridge** - Native C++ kodu ve CMake konfigürasyonu.
    -   `iosMain`: iOS için stub (taslak) kodlar.
-   **`composeApp` module**:
    -   `commonMain`: UI kodları (`App.kt`). Ekran tasarımı buradadır.
    -   `androidMain`: `MainActivity.kt`. Uygulamanın Android giriş noktası.
    -   `iosMain`: `MainViewController.kt`. iOS tarafına Compose UI'ı bağlayan köprü.

## ✅ llama.cpp Entegrasyonu TAMAMLANDI

### Native Build Yapısı
```
shared/src/androidMain/cpp/
├── CMakeLists.txt        # llama.cpp cross-compile konfigürasyonu
└── llama_jni.cpp          # JNI bridge implementasyonu
```

### JNI Fonksiyonları
- `loadModelNative(path: String): Boolean` - GGUF model yükleme
- `generateNative(prompt, maxTokens, temperature): String` - Text generation
- `unloadNative()` - Model unload
- `getPrefillTimeNative(): Long` - TTFT (Time To First Token) ms
- `getDecodeTimeNative(): Long` - Decode süresi ms
- `getTokensGeneratedNative(): Int` - Üretilen token sayısı
- `isModelLoadedNative(): Boolean` - Model yüklü mü kontrolü

### Ölçüm Metrikleri
- **Prefill Time (TTFT)**: İlk token'a kadar geçen süre
- **Decode Time**: Token üretim süresi
- **Tokens/sec**: Saniyede üretilen token
- **Peak Memory**: `Debug.getNativeHeapAllocatedSize()` ile native heap ölçümü

## Model Setup (Model Kurulumu)
1.  **Model**: `qwen-q8_0.gguf` veya benzer bir GGUF modeli.
2.  **Location (Konum)**: `/sdcard/Download/qwen-q8_0.gguf`
    - ADB ile: `adb push qwen-q8_0.gguf /sdcard/Download/`
    - Ya da cihazdan Files uygulamasıyla Downloads klasörüne kopyala

## How to Build (Nasıl Derlenir)
```bash
# Shared module (native library dahil)
./gradlew :shared:assembleDebug

# Tam APK
./gradlew :composeApp:assembleDebug

# APK konumu
composeApp/build/outputs/apk/debug/composeApp-debug.apk
```

## How to Run (Nasıl Çalıştırılır)
1.  Model dosyasını cihaza kopyala: `/sdcard/Download/qwen-q8_0.gguf`
2.  Android Studio'yu aç.
3.  `composeApp` konfigürasyonunu seç ve "Run" tuşuna bas.
4.  Uygulama açıldığında "Load Model" butonuna bas.
5.  "Generate" butonuna basarak inference işlemini başlat.
6.  Metrikleri (TTFT, tok/s, memory) ekranda gör.

## Build Configuration Details

### NDK Version
```
ndkVersion = "26.1.10909125"
```

### ABI Filter (Desteklenen Mimariler)
```
abiFilters = ["arm64-v8a"]
```
Sadece 64-bit ARM cihazları destekleniyor. x86 emulator'da çalışmaz!

### llama.cpp CMake Options
```cmake
BUILD_SHARED_LIBS = OFF    # Static linkage
LLAMA_BUILD_TESTS = OFF   
LLAMA_BUILD_TOOLS = OFF   
LLAMA_BUILD_EXAMPLES = OFF
LLAMA_BUILD_COMMON = ON    # common/ utilities gerekli
GGML_OPENMP = OFF          # OpenMP disabled for simpler Android build
GGML_CPU = ON              # CPU backend aktif
```

## Extending the Project (Projeyi Genişletme)

### 1. NPU/GPU Integration (NPU/GPU Entegrasyonu)
`llama.cpp` kütüphanesi GPU/NPU desteği sunar:
-   **OpenCL**: `GGML_OPENCL=ON` ile aktif edilir (Adreno GPU)
-   **Vulkan**: `GGML_VULKAN=ON` ile aktif edilir
-   CMakeLists.txt'de `model_params.n_gpu_layers` değerini artırarak layer'ları GPU'ya offload et

### 2. INT4 Quantization Test
-   Farklı quantization seviyelerini test etmek için:
    -   `qwen-q4_0.gguf` (INT4)
    -   `qwen-q8_0.gguf` (INT8)
-   Sonuçları karşılaştır: speed vs accuracy tradeoff

### 3. Adding More Metrics (Daha Fazla Metrik Ekleme)
-   **Energy**: Android'de `BatteryManager` API'sini kullanarak anlık akım çekimini ölçebilirsin
-   **Detailed Memory**: `Debug.getMemoryInfo()` ile PSS değeri

### 4. Streaming Output
JNI tarafında callback mekanizması ekleyerek token-by-token streaming yapılabilir.

## Troubleshooting (Sorun Giderme)

### "Model not loaded" hatası
- Model yolunu kontrol et
- Dosya izinlerini kontrol et: `READ_EXTERNAL_STORAGE` permission gerekli
- Logcat'te `LlamaJNI` tag'ini filtrele

### Build hatası: "ffast-math"
- CMakeLists.txt'de `-fno-finite-math-only` flag'i ekli olmalı
- llama.cpp non-finite math arithmetic gerektiriyor

### Emulator'da çalışmıyor
- x86/x86_64 emulator desteklenmiyor, sadece arm64-v8a
- Gerçek ARM cihaz kullan

## Tips (İpuçları)
-   **Logs**: Logcat'te `LlamaJNI` ve `AndroidLlamaCppEngine` tag'lerini filtrele
-   **Performance**: Native taraftaki timer'lar JNI ile döndürülüyor, çok hassas ölçüm mümkün
-   **Memory**: Model yüklendiğinde ~2-4GB native heap kullanımı bekleniyor (INT8 için)
