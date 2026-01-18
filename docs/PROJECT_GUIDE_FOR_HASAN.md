# PROJECT_GUIDE_FOR_HASAN.md

## Project Structure (Proje Yapısı)
Bu proje Kotlin Multiplatform (KMP) kullanılarak hazırlanmıştır. Kodun büyük bir kısmı Android ve iOS arasında ortaktır.

### Module Yapısı
```
EdgeQ-SLM/
├── shared/                          # Paylaşılan iş mantığı
│   ├── commonMain/                  # Cross-platform kod
│   │   ├── LlmEngine.kt             # Inference engine interface
│   │   ├── LlmViewModel.kt          # State management + download
│   │   └── ModelRepository.kt       # Model yönetimi (expect)
│   ├── androidMain/                 # Android implementasyonları
│   │   ├── AndroidLlamaCppEngine.kt # JNI wrapper
│   │   ├── ModelRepository.android.kt # HuggingFace download
│   │   └── cpp/                     # Native C++ kodu
│   │       ├── llama_jni.cpp        # JNI bridge
│   │       └── CMakeLists.txt       # Cross-compile config
│   └── iosMain/                     # iOS implementasyonları
│       └── ModelRepository.ios.kt   # iOS download (placeholder)
├── composeApp/                      # UI modülü
│   ├── commonMain/                  # Shared UI
│   │   └── App.kt                   # Ana UI + download progress
│   ├── androidMain/                 # Android UI
│   │   └── MainActivity.kt          # Entry point
│   └── iosMain/                     # iOS UI
│       └── MainViewController.kt    # iOS entry point
```

---

## ✅ Tamamlanan Özellikler

### 1. llama.cpp Entegrasyonu
- **JNI Bridge**: `llama_jni.cpp` ile native llama.cpp çağrıları
- **ChatML Template**: Prompt'ları `<|im_start|>user...` formatında sarmalama
- **Stop Token Detection**: `<|im_end|>` görünce üretimi durdurma
- **Sampling Parameters**: temperature, top_p, repeat_penalty

### 2. Model İndirme (YENİ - 2026-01-18)
- **HuggingFace Download**: Uygulama içinden model indirme
- **Progress UI**: %, MB/Toplam MB, Mbps hız göstergesi
- **Notification**: Sistem bildiriminde indirme durumu
- **Model Seçimi**: Dropdown ile mevcut modellerden seçim
- **Debug Mode**: "Force No Model" checkbox'ı ile test

### 3. Performans Metrikleri
- **TTFT**: Time To First Token (Prefill süresi)
- **Decode Speed**: Saniyede üretilen token
- **Memory**: Native heap kullanımı

---

## JNI Fonksiyonları

```kotlin
// Model yükleme
private external fun loadModelNative(path: String): Boolean

// Text üretme
private external fun generateNative(
    prompt: String,
    maxTokens: Int,        // 256 default
    temperature: Float,    // 0.7 default
    topP: Float,           // 0.9 default
    repeatPenalty: Float,  // 1.1 default
    useChatTemplate: Boolean
): String

// Metrikleri al
private external fun getPrefillTimeNative(): Long
private external fun getDecodeTimeNative(): Long
private external fun getTokensGeneratedNative(): Int
private external fun getMemoryUsageNative(): Long

// Cleanup
private external fun unloadNative()
private external fun isModelLoadedNative(): Boolean
```

---

## Model Download Sistemi

### Akış
```
1. Uygulama açılır → ModelRepository.isModelDownloaded() kontrol
2. Model yoksa → "Download" butonu göster
3. Download başlat → HuggingFace'ten indir
4. Progress update → UI ve notification güncelle
5. Tamamlandı → "Load Model" butonu göster
```

### Download URL
```
https://huggingface.co/Qwen/Qwen1.5-1.8B-Chat-GGUF/resolve/main/qwen1_5-1_8b-chat-q8_0.gguf
```

### Dosya Konumu
```
/sdcard/Android/data/com.aksoyapps.edgeqslm/files/qwen1_5-1_8b-chat-q8_0.gguf
```

---

## Build ve Çalıştırma

### Build
```bash
# Debug APK oluştur
./gradlew :composeApp:assembleDebug

# APK konumu
composeApp/build/outputs/apk/debug/composeApp-debug.apk
```

### Cihaza Yükle
```bash
# ADB ile yükle
adb install -r composeApp/build/outputs/apk/debug/composeApp-debug.apk

# Uygulamayı başlat
adb shell am start -n com.aksoyapps.edgeqslm/.MainActivity
```

### Manuel Model Yükleme (Opsiyonel)
```bash
# Dizini oluştur
adb shell mkdir -p /sdcard/Android/data/com.aksoyapps.edgeqslm/files/

# Modeli kopyala
adb push qwen-q8_0.gguf /sdcard/Android/data/com.aksoyapps.edgeqslm/files/
```

### Logcat
```bash
# Model repository logları
adb logcat -s ModelRepository:D

# JNI logları
adb logcat -s LlamaJNI:V AndroidLlamaCppEngine:V
```

---

## Troubleshooting (Sorun Giderme)

### "Model not found" hatası
- Model yolunu kontrol et
- `adb shell ls /sdcard/Android/data/com.aksoyapps.edgeqslm/files/`
- Debug checkbox'ı aktif mi kontrol et

### "Download failed: connection abort"
- İnternet bağlantısını kontrol et
- WiFi kullan (mobil veri yavaş olabilir)
- Uygulamayı yeniden başlat

### "Read permission" hatası
- Model `/sdcard/Android/data/com.aksoyapps.edgeqslm/files/` içinde olmalı
- Downloads klasöründen çalışmaz (scoped storage)

### Build hatası: "ffast-math"
- CMakeLists.txt'de `-fno-finite-math-only` flag'i var mı kontrol et

### Emulator'da çalışmıyor
- Sadece arm64-v8a ABI destekleniyor
- Gerçek ARM64 cihaz kullan

---

## Gelecek Çalışmalar

1. **INT4 Quantization**: Q4_0 modeli ile hız karşılaştırması
2. **GPU Offload**: `GGML_OPENCL` veya `GGML_VULKAN` ile GPU kullanımı
3. **iOS Tamamlama**: iOS download ve inference
4. **Streaming Output**: Token-by-token çıktı
5. **Energy Profiling**: Batarya tüketimi ölçümü

---

## Paket Bilgileri

| Özellik | Değer |
|---------|-------|
| Package Name | com.aksoyapps.edgeqslm |
| Min SDK | 24 (Android 7.0) |
| Target SDK | 34 (Android 14) |
| NDK Version | 26.1.10909125 |
| Architecture | arm64-v8a |
| Model Size | ~1.86 GB |

---

*Son Güncelleme: 2026-01-18*
