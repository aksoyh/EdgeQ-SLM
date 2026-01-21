# EdgeQ-SLM Projesi: Vizyon ve Ara Hedefler

## 🎯 Nihai Hedef

**Mobil cihazda (Android) çalışan Small Language Model (SLM)** - Bulut bağımlılığı olmadan, cihaz üzerinde (on-device) metin üretimi yapabilen, fotoğrafları anlayabilen ve kişisel verilerde arama yapabilen akıllı bir asistan uygulaması.

### Hedefin Önemi
- **Gizlilik**: Veriler cihazdan çıkmaz
- **Çevrimdışı Çalışma**: İnternet bağlantısı gerekmez
- **Düşük Gecikme**: Sunucu round-trip yok
- **Maliyet**: API ücreti yok

---

## 📊 Ara Hedefler ve Gerekçeleri

### 1️⃣ LLM Quantization (Kuantizasyon)

#### Ne Yaptık
| Görev | Açıklama |
|-------|----------|
| Model Seçimi | Qwen 1.5 1.8B - Küçük ama yetenekli |
| Format Dönüşümü | GGUF Q8 formatına dönüştürme |
| Runtime Entegrasyonu | llama.cpp (JNI bridge ile) |
| Quantization Seviyesi | INT8 - Kalite/boyut dengesi |

#### Neden Gerekli
- **Bellek Kısıtı**: Orijinal model ~3.6GB, mobil RAM'e sığmaz
- **Performans**: INT8 ile ~1.8GB'a düşüş, kalite kaybı minimal (%1-2)
- **CPU Optimizasyonu**: llama.cpp SIMD/NEON desteği ile hızlı inference
- **Batarya Verimliliği**: Optimize edilmiş hesaplama = düşük güç tüketimi

#### Teknik Detaylar
```
Orijinal Model (FP16): ~3.6 GB
Quantized Model (Q8):  ~1.8 GB
Bellek Tasarrufu:      ~%50
Inference Hızı:        ~15-25 token/saniye (cihaza bağlı)
```

---

### 2️⃣ OCR Tabanlı Fotoğraf Arama

#### Ne Yaptık
| Görev | Açıklama |
|-------|----------|
| OCR Engine | Google ML Kit (on-device) |
| Veritabanı | SQLite ile metin index'leme |
| Arama | Full-text search |
| UI | Fullscreen viewer + arama arayüzü |

#### Neden Gerekli
- **Multimodal Temel**: Görsel + metin birlikte işleme pratiği
- **On-Device ML**: Model yükleme, inference, sonuç işleme deneyimi
- **RAG Hazırlığı**: Retrieval Augmented Generation için veri tabanı
- **Pratik Fayda**: Fotoğraflarda metin arama (ekran görüntüleri, belgeler)

#### Kullanım Senaryoları
- Ekran görüntülerinde telefon numarası bulma
- Fotoğraflanan belgelerde arama
- Tarif fotoğraflarından malzeme listesi çıkarma

---

### 3️⃣ CLIP Visual Search (Görsel Benzerlik)

#### Ne Yaptık
| Görev | Açıklama |
|-------|----------|
| Model | CLIP ViT-B/32 (ONNX format) |
| Image Encoder | Fotoğraf → 512-dim vektör |
| Text Encoder | Arama sorgusu → 512-dim vektör |
| Benzerlik | Cosine similarity hesaplama |
| Hybrid Search | OCR + CLIP birleşik arama |

#### Neden Gerekli
- **Vision-Language Modeli**: Görsel anlama kapasitesi kazandırma
- **Semantic Search**: OCR olmayan fotoğraflarda bile arama
- **VLM Hazırlığı**: LLM + Vision entegrasyonu için altyapı

#### Teknik Detaylar
```
Image Model:  clip-vit-b32-image.onnx (~330 MB)
Text Model:   clip-vit-b32-text.onnx (~330 MB)
Embedding:    512 boyutlu vektör
Tokenizer:    BPE (Byte-Pair Encoding)
```

#### Bilinen Sınırlamalar
- CLIP İngilizce eğitimli, Türkçe aramalar çalışmaz
- BPE tokenizer tam implementasyon gerektirir
- Model boyutu (~660MB) bellek kullanımını artırır

---

## 🔗 Ara Hedeflerin Nihai Hedefe Bağlantısı

```
┌────────────────────────────────────────────────────────────┐
│                    NİHAİ HEDEF                              │
│     Mobil Cihazda Çalışan Akıllı Asistan                   │
│   (Chat + Fotoğraf Anlama + Kişisel Veri Arama)            │
└────────────────────────────────────────────────────────────┘
                           ▲
                           │
         ┌─────────────────┼─────────────────┐
         │                 │                 │
         ▼                 ▼                 ▼
┌─────────────┐   ┌─────────────┐   ┌─────────────┐
│   LLM Q8    │   │    OCR      │   │    CLIP     │
│  (Qwen 1.5) │   │  (ML Kit)   │   │  (ViT-B/32) │
├─────────────┤   ├─────────────┤   ├─────────────┤
│ Metin üret  │   │ Metin çıkar │   │ Görsel anla │
│ Soru cevap  │   │ PDF/Fotoğraf│   │ Benzerlik   │
│ Kod yaz     │   │ index'le    │   │ bulma       │
└─────────────┘   └─────────────┘   └─────────────┘
       │                 │                 │
       └─────────────────┴─────────────────┘
                         │
                         ▼
              ┌─────────────────────┐
              │   Gelecek: RAG +    │
              │   Multimodal LLM    │
              │   (LLaVA, Qwen-VL)  │
              └─────────────────────┘
```

### Bağlantı Açıklaması

| Ara Hedef | Nihai Hedefteki Rolü |
|-----------|----------------------|
| **LLM (Qwen Q8)** | Doğal dil anlama ve üretme, kullanıcı ile sohbet |
| **OCR (ML Kit)** | Fotoğraflardaki metni LLM'e context olarak verme |
| **CLIP (ViT-B/32)** | Görsel içeriği anlama, "Bu fotoğrafta ne var?" sorusuna cevap |

---

## 📈 Gelecek Potansiyeli

### Kısa Vadeli Entegrasyonlar

| Mevcut Durum | Hedef Entegrasyon |
|--------------|-------------------|
| LLM ayrı çalışıyor | LLM + CLIP = "Bu fotoğrafı anlat" |
| OCR text döndürüyor | OCR + LLM = "Bu belgede ne yazıyor, özetle" |
| CLIP benzerlik buluyor | CLIP + LLM = "Benzer fotoğraflar hakkında yorum yap" |

### Orta Vadeli Hedefler

1. **RAG (Retrieval Augmented Generation)**
   - Fotoğraf veritabanından ilgili içerik çekme
   - LLM'e context olarak verme
   - Daha doğru ve kişiselleştirilmiş cevaplar

2. **Multimodal LLM**
   - LLaVA veya Qwen-VL entegrasyonu
   - Doğrudan görüntü girişi
   - "Bu fotoğrafı analiz et" komutu

3. **Sesli Asistan**
   - Speech-to-Text (Whisper)
   - Text-to-Speech
   - Tam hands-free deneyim

---

## 🧪 Tez/Araştırma Değeri

### Ölçülebilir Metrikler

1. **On-Device Inference Latency**
   - İlk token süresi (Time to First Token - TTFT)
   - Token/saniye throughput
   - Quantization seviyesine göre karşılaştırma

2. **Memory Footprint Analizi**
   - Model boyutu vs RAM kullanımı
   - Peak memory during inference
   - Çoklu model yükleme senaryoları

3. **Accuracy Trade-off**
   - Q8 vs Q4 vs FP16 kalite karşılaştırması
   - Perplexity ölçümleri
   - Kullanıcı değerlendirmesi

4. **Enerji Tüketimi**
   - mAh/inference hesaplaması
   - Batarya ömrü etkisi
   - Termal throttling analizi

### Karşılaştırma Noktaları

| Metrik | Cloud API | On-Device (Q8) |
|--------|-----------|----------------|
| Latency | 200-500ms | 50-100ms (ilk token) |
| Gizlilik | ❌ Veri sunucuya gidiyor | ✅ Veri cihazda kalıyor |
| Maliyet | $0.002/1K token | $0 (cihaz maliyeti hariç) |
| Çevrimdışı | ❌ | ✅ |

---

## 📁 Proje Dosya Yapısı

```
EdgeQ-SLM/
├── composeApp/                 # Kotlin Multiplatform UI
│   └── src/
│       ├── androidMain/        # Android-specific UI
│       └── commonMain/         # Shared Compose UI
├── shared/                     # Business Logic
│   └── src/
│       ├── androidMain/        # Android implementations
│       │   ├── AndroidLlamaCppEngine.kt  # JNI bridge
│       │   └── photos/                   # CLIP + OCR
│       └── commonMain/         # Shared interfaces
├── docs/                       # Documentation
└── models/                     # Model files (gitignored)
```

---

## 🏁 Sonuç

EdgeQ-SLM projesi, mobil cihazlarda çalışan yapay zeka uygulamalarının geleceğine bir bakış sunuyor. Her ara hedef (LLM Quantization, OCR, CLIP), tek başına değerli olmanın ötesinde, birlikte çalışarak gerçek anlamda akıllı bir mobil asistan oluşturma potansiyeli taşıyor.

**Anahtar Öğrenimler:**
- Quantization ile büyük modeller mobilde çalışabilir
- Multimodal yaklaşım (metin + görsel) daha zengin deneyim sunar
- On-device ML gizlilik ve performans avantajı sağlar
- Modüler mimari gelecek entegrasyonları kolaylaştırır

---

*Son güncelleme: Ocak 2026*
