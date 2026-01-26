# EdgeQ-SLM Notlar (Türkçe Açıklamalar)

Bu dosya, projedeki teknik terimleri ve kavramları Türkçe açıklamalarla içerir.
**Technical terms** kullanılır ama açıklamalar Türkçe'dir.

---

## 1. Proje Özeti

**EdgeQ-SLM**, mobil cihazlarda **Small Language Model (SLM)** çalıştırmak için geliştirilmiş bir Proof of Concept (PoC) uygulamasıdır.

**Amaç:** Quantize edilmiş (INT8) modellerin mobil cihazlarda ne kadar hızlı ve verimli çalıştığını ölçmek ve belgelemek.

---

## 2. Temel Kavramlar

### 2.1 Quantization (Kuantizasyon)

- **Nedir:** Model ağırlıklarını daha düşük hassasiyetli sayı formatına dönüştürme.
- **FP32 → INT8:** 32-bit float'tan 8-bit integer'a.
- **Avantaj:** Model boyutu küçülür, inference hızlanır.
- **Dezavantaj:** Küçük kalite kaybı olabilir.

### 2.2 Inference (Çıkarsama)

- **Nedir:** Modele prompt verip cevap üretme işlemi.
- **Prefill:** Prompt'un işlenmesi (Time To First Token - TTFT).
- **Decode:** Token token cevap üretme.

### 2.3 TTFT (Time To First Token)

- **Nedir:** Prompt gönderildikten sonra ilk token'ın üretilmesine kadar geçen süre.
- **Önemi:** Kullanıcının bekleme süresi hissi.

### 2.4 Tokens/sec

- **Nedir:** Saniyede üretilen token sayısı.
- **Hesaplama:** `tokens_generated / decode_time_seconds`
- **İyi değer:** Mobilde 5-15 tok/sec kabul edilebilir.

---

## 3. Ölçüm Metodları

### 3.1 Latency (Gecikme)

```kotlin
val totalLatency = measureTimeMillis {
    generatedText = generateNative(...)
}
```

- **E2E Latency:** Toplam süre (prefill + decode).
- **TTFT:** llama.cpp'den `getPrefillTimeNative()` ile alınır.
- **Decode Time:** llama.cpp'den `getDecodeTimeNative()` ile alınır.

### 3.2 Memory (Bellek)

Android'de `Debug.MemoryInfo` kullanılır:

```kotlin
val memoryInfo = Debug.MemoryInfo()
Debug.getMemoryInfo(memoryInfo)
val totalPss = memoryInfo.totalPss * 1024L  // KB -> Bytes
```

- **PSS (Proportional Set Size):** Shared memory'nin uygulama payı dahil.
- **Native Heap:** C++ tarafında (llama.cpp) ayrılan bellek.

### 3.3 CPU Utilization

`/proc/stat` ve `/proc/[pid]/stat` dosyaları okunur:

```kotlin
// İşlem CPU zamanı
val processTime = (endUserTime - startUserTime) + (endSystemTime - startSystemTime)
// Toplam CPU zamanı
val totalTime = endTotalTime - startTotalTime
// Yüzde hesabı
val cpuPercent = (processTime / totalTime) * 100 * numCores
```

---

## 4. Benchmark Sistemi

### 4.1 Prompt Set (Prompt Seti)

18 test prompt'u, 4 kategoride:

| Kategori | Açıklama | Örnek |
|----------|----------|-------|
| **Factual** | Bilgi soruları | "What is the capital of France?" |
| **Instruction** | Komut takibi | "Write a Python function..." |
| **Creative** | Yaratıcı içerik | "Write a haiku about autumn." |
| **Edge Cases** | Uç durumlar | "Hi" (çok kısa), uzun paragraf |

### 4.2 Temperature Sweep

Temperature değerleri: `[0.1, 0.3, 0.7, 1.0, 1.3, 1.7, 2.0]`

- **Düşük (0.1-0.3):** Deterministik, tutarlı cevaplar.
- **Orta (0.7-1.0):** Dengeli yaratıcılık.
- **Yüksek (1.3-2.0):** Yaratıcı ama tutarsız olabilir.

### 4.3 Warmup (Isınma)

- **Nedir:** İlk çalıştırmada JIT, cache vs. etkilerini azaltmak için yapılan deneme.
- **Sayı:** 1 warmup run.
- **Not:** Warmup sonuçları istatistiklere dahil edilmez.

### 4.4 Repeats (Tekrar)

- Her (prompt, temperature) kombinasyonu için 5 tekrar.
- **Amaç:** Varyasyonu ölçmek, ortalama ve standart sapma hesaplamak.

---

## 5. Sanity Checks (Sağlık Kontrolleri)

Çıktı kalitesini kontrol eden basit testler (**full benchmark değil**):

| Kontrol | Açıklama | Geçme Kriteri |
|---------|----------|---------------|
| **Empty Check** | Boş çıktı kontrolü | output.length > 0 |
| **Token Count** | Minimum token sayısı | ≥ 3 token |
| **Repetition** | Tekrar eden n-gram oranı | < 30% tekrar |
| **Coherence** | Tutarlılık (tüm kontroller) | Hepsi geçti |

### 5.1 Repetition Score Hesabı

```kotlin
// 3-gram'lar oluştur
val ngrams = words.windowed(3).map { it.joinToString(" ") }
// Tekrar edenleri say
val repeated = ngrams.groupBy { it }.filter { it.value.size > 1 }
// Skor: tekrar sayısı / toplam
val score = repeatedCount / totalNgrams
```

---

## 6. Export (Dışa Aktarma)

### 6.1 CSV Dosyaları

| Dosya | İçerik |
|-------|--------|
| `latency_memory_cpu.csv` | Tüm ham sonuçlar |
| `aggregated_stats.csv` | Gruplandırılmış istatistikler |
| `sanity_checks.csv` | Kalite kontrol sonuçları |

### 6.2 JSON Dosyaları

| Dosya | İçerik |
|-------|--------|
| `sample_outputs.json` | Örnek çıktılar (manuel inceleme için) |
| `run_metadata.json` | Deney konfigürasyonu, cihaz bilgisi |

### 6.3 Dosya Konumu

Android'de: `/data/data/com.aksoyapps.edgeqslm/files/results/`

---

## 7. Kod Yapısı

### 7.1 Benchmark Modülü

```
shared/src/commonMain/kotlin/.../benchmark/
├── BenchmarkModels.kt      # Data class'ları
├── PromptSet.kt            # Sabit prompt listesi
├── SanityChecker.kt        # Kalite kontrolleri
├── SystemMetricsProvider.kt # Platform arayüzü
├── BenchmarkRunner.kt      # Deney orkestratörü
└── ExportService.kt        # CSV/JSON export

shared/src/androidMain/kotlin/.../benchmark/
├── AndroidSystemMetricsProvider.kt  # Android ölçüm
├── AndroidExportService.kt          # Android dosya I/O
└── BenchmarkViewModel.kt            # UI state yönetimi

composeApp/src/androidMain/kotlin/.../benchmark/
└── BenchmarkScreen.kt      # Benchmark UI
```

### 7.2 Akış Diyagramı

```
1. Kullanıcı "Full Benchmark" butonuna basar
2. BenchmarkViewModel.startBenchmark() çağrılır
3. BenchmarkRunner prompt × temperature × repeat döngüsü başlatır
4. Her run için:
   a. CPU monitoring başlar
   b. Memory ölçülür
   c. LlmEngine.generate() çağrılır
   d. Metrics toplanır
   e. SanityChecker çıktıyı kontrol eder
   f. Sonuç kaydedilir
5. Tüm sonuçlar aggregate edilir
6. Export butonuyla CSV/JSON yazılır
```

---

## 8. Tez İçin Önemli Notlar

### 8.1 Reproducibility (Tekrarlanabilirlik)

- **Hardcoded path yok:** `/Users/...` gibi yollar kaldırıldı.
- **Git commit hash:** Metadata'da kaydedilir.
- **Tam konfigürasyon:** JSON'da saklanır.

### 8.2 Limitations (Sınırlamalar)

Tezde şunları belirtmek gerekir:

1. **Tek cihaz:** Sonuçlar başka cihazlara genellenemeyebilir.
2. **Tek model:** Sadece Qwen 1.5 1.8B test edildi.
3. **Yaklaşık ölçümler:** PSS, CPU tam değil yaklaşık.
4. **Sanity check ≠ Benchmark:** MMLU gibi gerçek kalite testleri yapılmadı.

### 8.3 Future Work (Gelecek Çalışmalar)

- INT4 quantization karşılaştırması
- Unquantized baseline eklenmesi
- iOS implementasyonu
- NPU desteği (cihaz bazlı)

---

## 9. Hızlı Referans

### Sık Kullanılan Komutlar

```bash
# Build
./gradlew composeApp:assembleDebug

# Install
adb install composeApp/build/outputs/apk/debug/composeApp-debug.apk

# Model yükleme
adb push model.gguf /sdcard/Android/data/com.aksoyapps.edgeqslm/files/

# Log takibi
adb logcat | grep -E "(BenchmarkRunner|AndroidLlamaCppEngine)"
```

### Dosya Boyutu Referansları

| Dosya | Boyut |
|-------|-------|
| qwen-q8_0.gguf | ~1.8 GB |
| APK | ~15-20 MB |
| CSV (full run) | ~100-500 KB |

---

*Bu dosya Hasan için hazırlanmıştır. Teknik terimler İngilizce, açıklamalar Türkçe'dir.*
