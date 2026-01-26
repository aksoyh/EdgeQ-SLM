# EdgeQ-SLM Proje Rehberi (Hasan için)

## 🎯 Proje Özeti

Bu proje, mobil cihazlarda çalışan bir AI asistanı oluşturmayı hedefliyor:
- **LLM Chat**: Qwen 1.5 1.8B modeli ile metin üretimi
- **Photo Search**: OCR + CLIP ile fotoğraflarda arama
- **On-Device**: Tüm AI işlemleri cihaz üzerinde çalışıyor

---

## 📱 Uygulama Yapısı

### Tab 1: LLM (🤖)
- Qwen 1.5 1.8B Q8 modeli ile chat
- ChatML format desteği
- TTFT, token/s, bellek metrikleri
- HuggingFace'den model indirme

### Tab 2: Photos (📷)
- **Files**: Fotoğraf listesi (index durumu ile)
- **Search**: OCR + CLIP ile arama
- Match type badge'leri: OCR, CLIP, HYBRID
- Fullscreen viewer (pinch-to-zoom)

### Resource Bar
- CPU kullanımı (sistem + uygulama)
- RAM kullanımı (cihaz + uygulama)

---

## 🛠️ Kurulum

### 1. Gereksinimler
- Android Studio Iguana+
- JDK 17
- NDK 26.1.x
- ARM64 Android telefon

### 2. Build ve Yükleme
```bash
cd /Users/hasanaksoy/AntigravityProjects/EdgeQ-SLM

# Build
./gradlew :composeApp:assembleDebug

# Yükle
adb install -r composeApp/build/outputs/apk/debug/composeApp-debug.apk
```

### 3. Model Yükleme
```bash
# LLM modeli (~1.8GB)
adb push qwen-q8_0.gguf /sdcard/Android/data/com.aksoyapps.edgeqslm/files/

# CLIP modelleri (~660MB)
adb push clip-vit-b32-image.onnx /sdcard/Android/data/com.aksoyapps.edgeqslm/files/models/
adb push clip-vit-b32-text.onnx /sdcard/Android/data/com.aksoyapps.edgeqslm/files/models/
adb push -r clip_tokenizer/ /sdcard/Android/data/com.aksoyapps.edgeqslm/files/models/
```

### 4. İzinler
- **All Files Access**: Ayarlar → Uygulamalar → EdgeQ-SLM → İzinler → Tüm dosyalara erişim

---

## 📂 Dosya Yapısı

```
EdgeQ-SLM/
├── composeApp/
│   └── src/androidMain/kotlin/
│       ├── MainActivity.kt         # Ana ekran, navigation, resource bar
│       └── photos/
│           └── PhotoSearchScreen.kt # Fotoğraf arama UI
├── shared/
│   └── src/androidMain/kotlin/
│       ├── AndroidLlamaCppEngine.kt # LLM JNI bridge
│       └── photos/
│           ├── PhotoIndexer.kt      # OCR + CLIP indexing
│           ├── PhotoVectorStore.kt  # SQLite veritabanı
│           ├── ClipImageEncoder.kt  # CLIP görsel encoder
│           ├── ClipTextEncoder.kt   # CLIP metin encoder
│           └── PhotoSearchViewModel.kt # State yönetimi
└── docs/                            # Dokümantasyon
```

---

## 🔧 Önemli Özellikler

### Force Index (Zorla Yeniden Index)
- Index butonuna **5 saniye basılı tut**
- 2. saniyeden sonra progress bar dolmaya başlar
- 5. saniyede veritabanı silinir ve tüm fotoğraflar yeniden indexlenir

### Match Type Badge'leri
| Badge | Renk | Anlam |
|-------|------|-------|
| OCR | Mavi | Metin içeriği ile eşleşti |
| CLIP | Mor | Görsel benzerlik ile eşleşti |
| HYBRID | Turuncu | Hem OCR hem CLIP eşleşti |

### CLIP Sınırlamaları
- **Sadece İngilizce**: "airplane" çalışır, "uçak" çalışmaz
- **BPE Tokenizer eksik**: Basit kelime bazlı tokenization

---

## 🌿 Git Yapısı

### Branch'ler
| Branch | Açıklama |
|--------|----------|
| `main` | Eski, merge gerekiyor |
| `feature/clip-visual-search` | **Güncel** - Tüm özellikler |
| `feature/photo-search-ocr` | OCR özelliği |
| `feature/model-download` | Model indirme |

### Commit Komutları
```bash
# Değişiklikleri stage'e ekle
git add .

# Commit
git commit -m "Açıklama"

# Push
git push origin feature/clip-visual-search

# Main'e merge (opsiyonel)
git checkout main
git merge feature/clip-visual-search
git push origin main
```

---

## 🐛 Bilinen Sorunlar

| Sorun | Neden | Çözüm |
|-------|-------|-------|
| Sistem CPU 0% | Android SELinux /proc/loadavg erişimi engelliyor | Düzeltilmedi |
| CLIP "airplane" çalışmıyor | BPE tokenizer tam değil | İyileştirme bekliyor |
| Türkçe arama çalışmıyor | CLIP İngilizce eğitimli | İngilizce kullan |

---

## 📊 Performans Metrikleri

| Metrik | Değer |
|--------|-------|
| LLM Model Boyutu | 1.86 GB |
| CLIP Model Boyutu | ~660 MB |
| LLM Yükleme Süresi | ~5 saniye |
| CLIP Yükleme Süresi | ~3 saniye |
| Inference Hızı | 15-25 token/s |
| Toplam RAM Kullanımı | ~2-3 GB |

---

## 🚀 Sonraki Adımlar

1. ✅ ~~OCR entegrasyonu~~
2. ✅ ~~CLIP visual search~~
3. ✅ ~~Resource monitoring~~
4. ⏳ CLIP BPE tokenizer iyileştirme
5. ⏳ RAG entegrasyonu (LLM + Photo context)
6. ⏳ Performans ölçümleri dokümante et
7. ⏳ main branch'e merge

---

## 📝 Logcat Komutları

```bash
# Genel uygulama logları
adb logcat -s "PhotoIndexer" "ClipTextEncoder" "PhotoSearchVM"

# CLIP tokenizer debug
adb logcat | grep "ClipTextEncoder"

# Tüm hataları gör
adb logcat *:E | grep edgeqslm
```

---

*Son güncelleme: 2026-01-21*
