/**
 * llama_jni.cpp - JNI Bridge for llama.cpp Android Integration
 * EdgeQ-SLM Project
 *
 * Features:
 * - ChatML template support
 * - Stop token detection
 * - Configurable sampling parameters
 * - Performance timing metrics
 * - Vision model support (mtmd/multi-modal)
 */

#include <android/log.h>
#include <chrono>
#include <jni.h>
#include <memory>
#include <string>
#include <vector>

#include "common.h"
#include "llama.h"
#include "mtmd.h"
#include "sampling.h"

#define LOG_TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

static llama_model *g_model = nullptr;
static llama_context *g_context = nullptr;
static const llama_vocab *g_vocab = nullptr;

// Vision support (mtmd)
static mtmd_context *g_mtmd_ctx = nullptr;
static bool g_is_vision_model = false;

static long g_prefill_time_ms = 0;
static long g_decode_time_ms = 0;
static int g_tokens_generated = 0;

// ChatML template helper
static std::string apply_chat_template(const std::string &prompt) {
  return "<|im_start|>user\n" + prompt + "<|im_end|>\n<|im_start|>assistant\n";
}

// Check for stop tokens
static bool has_stop_token(const std::string &text) {
  if (text.find("<|im_end|>") != std::string::npos)
    return true;
  if (text.find("<|endoftext|>") != std::string::npos)
    return true;
  if (text.find("<|im_start|>") != std::string::npos)
    return true;
  return false;
}

// Trim stop tokens from output
static std::string trim_stop_tokens(const std::string &text) {
  std::string result = text;
  size_t pos;
  pos = result.find("<|im_end|>");
  if (pos != std::string::npos)
    result = result.substr(0, pos);
  pos = result.find("<|endoftext|>");
  if (pos != std::string::npos)
    result = result.substr(0, pos);
  pos = result.find("<|im_start|>");
  if (pos != std::string::npos)
    result = result.substr(0, pos);
  // Trim trailing whitespace
  while (!result.empty() && (result.back() == ' ' || result.back() == '\n')) {
    result.pop_back();
  }
  return result;
}

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
  LOGI("JNI_OnLoad: Initializing llama backend");
  llama_backend_init();
  return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *vm, void *reserved) {
  LOGI("JNI_OnUnload: Cleaning up");
  if (g_context) {
    llama_free(g_context);
    g_context = nullptr;
  }
  if (g_model) {
    llama_model_free(g_model);
    g_model = nullptr;
  }
  llama_backend_free();
}

JNIEXPORT jboolean JNICALL
Java_com_aksoyapps_edgeqslm_AndroidLlamaCppEngine_loadModelNative(
    JNIEnv *env, jobject thiz, jstring modelPath) {

  const char *path = env->GetStringUTFChars(modelPath, nullptr);
  if (!path) {
    LOGE("loadModelNative: Failed to get path");
    return JNI_FALSE;
  }

  LOGI("loadModelNative: Loading model from %s", path);

  if (g_context) {
    llama_free(g_context);
    g_context = nullptr;
  }
  if (g_model) {
    llama_model_free(g_model);
    g_model = nullptr;
  }

  llama_model_params model_params = llama_model_default_params();
  model_params.n_gpu_layers = 0;
  model_params.use_mmap = true;
  model_params.use_mlock = false;

  auto start = std::chrono::high_resolution_clock::now();
  g_model = llama_model_load_from_file(path, model_params);
  auto end = std::chrono::high_resolution_clock::now();

  env->ReleaseStringUTFChars(modelPath, path);

  if (!g_model) {
    LOGE("loadModelNative: Failed to load");
    return JNI_FALSE;
  }

  long load_time =
      std::chrono::duration_cast<std::chrono::milliseconds>(end - start)
          .count();
  LOGI("loadModelNative: Loaded in %ld ms", load_time);

  g_vocab = llama_model_get_vocab(g_model);

  llama_context_params ctx_params = llama_context_default_params();
  ctx_params.n_ctx = 4096;
  ctx_params.n_batch = 512;
  ctx_params.n_ubatch = 512;
  ctx_params.n_threads = 4;
  ctx_params.n_threads_batch = 4;
  ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;

  g_context = llama_init_from_model(g_model, ctx_params);
  if (!g_context) {
    LOGE("loadModelNative: Failed to create context");
    llama_model_free(g_model);
    g_model = nullptr;
    return JNI_FALSE;
  }

  LOGI("loadModelNative: Context created (n_ctx=%d)", ctx_params.n_ctx);
  return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_aksoyapps_edgeqslm_AndroidLlamaCppEngine_generateNative(
    JNIEnv *env, jobject thiz, jstring prompt, jint maxTokens,
    jfloat temperature, jfloat topP, jfloat repeatPenalty,
    jboolean useChatTemplate) {

  if (!g_model || !g_context || !g_vocab) {
    LOGE("generateNative: Model not loaded");
    return env->NewStringUTF("[Error: Model not loaded]");
  }

  const char *prompt_cstr = env->GetStringUTFChars(prompt, nullptr);
  if (!prompt_cstr)
    return env->NewStringUTF("[Error: Invalid prompt]");

  std::string prompt_str(prompt_cstr);
  env->ReleaseStringUTFChars(prompt, prompt_cstr);

  std::string final_prompt =
      useChatTemplate ? apply_chat_template(prompt_str) : prompt_str;

  LOGI("generateNative: prompt_len=%zu, max=%d, temp=%.2f, top_p=%.2f, "
       "rep_pen=%.2f, chat=%d",
       final_prompt.length(), maxTokens, temperature, topP, repeatPenalty,
       useChatTemplate);

  g_prefill_time_ms = 0;
  g_decode_time_ms = 0;
  g_tokens_generated = 0;

  std::vector<llama_token> tokens(final_prompt.length() + 256);
  int n_tokens =
      llama_tokenize(g_vocab, final_prompt.c_str(), final_prompt.length(),
                     tokens.data(), tokens.size(), true, true);

  if (n_tokens < 0) {
    tokens.resize(-n_tokens);
    n_tokens =
        llama_tokenize(g_vocab, final_prompt.c_str(), final_prompt.length(),
                       tokens.data(), tokens.size(), true, true);
  }

  if (n_tokens <= 0) {
    LOGE("generateNative: Tokenization failed");
    return env->NewStringUTF("[Error: Tokenization failed]");
  }
  tokens.resize(n_tokens);
  LOGD("generateNative: Tokenized to %d tokens", n_tokens);

  llama_memory_clear(llama_get_memory(g_context), true);

  auto prefill_start = std::chrono::high_resolution_clock::now();
  llama_batch batch = llama_batch_get_one(tokens.data(), n_tokens);
  if (llama_decode(g_context, batch) != 0) {
    LOGE("generateNative: Prefill decode failed");
    return env->NewStringUTF("[Error: Decode failed]");
  }
  auto prefill_end = std::chrono::high_resolution_clock::now();
  g_prefill_time_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                          prefill_end - prefill_start)
                          .count();
  LOGI("generateNative: Prefill in %ld ms (TTFT)", g_prefill_time_ms);

  auto decode_start = std::chrono::high_resolution_clock::now();

  llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
  llama_sampler *sampler = llama_sampler_chain_init(sparams);

  llama_sampler_chain_add(
      sampler, llama_sampler_init_penalties(64, repeatPenalty, 0.0f, 0.0f));
  llama_sampler_chain_add(sampler, llama_sampler_init_top_p(topP, 1));
  llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
  llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

  std::string output;
  output.reserve(maxTokens * 8);
  llama_token eos_token = llama_vocab_eos(g_vocab);
  bool stop_detected = false;

  for (int i = 0; i < maxTokens && !stop_detected; i++) {
    llama_token new_token = llama_sampler_sample(sampler, g_context, -1);

    if (new_token == eos_token || llama_vocab_is_eog(g_vocab, new_token)) {
      LOGD("generateNative: EOS at %d", i);
      break;
    }

    char buf[256];
    int len =
        llama_token_to_piece(g_vocab, new_token, buf, sizeof(buf), 0, true);
    if (len > 0) {
      output.append(buf, len);
      if (has_stop_token(output)) {
        LOGD("generateNative: Stop token at %d", i);
        stop_detected = true;
        break;
      }
    }

    llama_batch next_batch = llama_batch_get_one(&new_token, 1);
    if (llama_decode(g_context, next_batch) != 0) {
      LOGE("generateNative: Decode failed at %d", i);
      break;
    }
    g_tokens_generated++;
  }

  llama_sampler_free(sampler);

  auto decode_end = std::chrono::high_resolution_clock::now();
  g_decode_time_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                         decode_end - decode_start)
                         .count();

  std::string clean_output = trim_stop_tokens(output);

  float tokens_per_sec = g_decode_time_ms > 0
                             ? (g_tokens_generated * 1000.0f / g_decode_time_ms)
                             : 0.0f;
  LOGI("generateNative: %d tokens in %ld ms (%.2f tok/s)", g_tokens_generated,
       g_decode_time_ms, tokens_per_sec);

  return env->NewStringUTF(clean_output.c_str());
}

JNIEXPORT void JNICALL
Java_com_aksoyapps_edgeqslm_AndroidLlamaCppEngine_unloadNative(JNIEnv *env,
                                                               jobject thiz) {
  LOGI("unloadNative: Unloading");

  // Cleanup vision context first
  if (g_mtmd_ctx) {
    mtmd_free(g_mtmd_ctx);
    g_mtmd_ctx = nullptr;
  }
  g_is_vision_model = false;

  if (g_context) {
    llama_free(g_context);
    g_context = nullptr;
  }
  if (g_model) {
    llama_model_free(g_model);
    g_model = nullptr;
  }
  g_vocab = nullptr;
  g_prefill_time_ms = 0;
  g_decode_time_ms = 0;
  g_tokens_generated = 0;
  LOGI("unloadNative: Done");
}

JNIEXPORT jlong JNICALL
Java_com_aksoyapps_edgeqslm_AndroidLlamaCppEngine_getPrefillTimeNative(
    JNIEnv *env, jobject thiz) {
  return g_prefill_time_ms;
}

JNIEXPORT jlong JNICALL
Java_com_aksoyapps_edgeqslm_AndroidLlamaCppEngine_getDecodeTimeNative(
    JNIEnv *env, jobject thiz) {
  return g_decode_time_ms;
}

JNIEXPORT jint JNICALL
Java_com_aksoyapps_edgeqslm_AndroidLlamaCppEngine_getTokensGeneratedNative(
    JNIEnv *env, jobject thiz) {
  return g_tokens_generated;
}

JNIEXPORT jboolean JNICALL
Java_com_aksoyapps_edgeqslm_AndroidLlamaCppEngine_isModelLoadedNative(
    JNIEnv *env, jobject thiz) {
  return (g_model != nullptr && g_context != nullptr) ? JNI_TRUE : JNI_FALSE;
}

// ============= VISION MODEL SUPPORT (mtmd) =============

JNIEXPORT jboolean JNICALL
Java_com_aksoyapps_edgeqslm_AndroidLlamaCppEngine_loadVisionProjectorNative(
    JNIEnv *env, jobject thiz, jstring projectorPath) {

  if (!g_model) {
    LOGE("loadVisionProjectorNative: Model not loaded first");
    return JNI_FALSE;
  }

  const char *path = env->GetStringUTFChars(projectorPath, nullptr);
  if (!path) {
    LOGE("loadVisionProjectorNative: Failed to get path");
    return JNI_FALSE;
  }

  LOGI("loadVisionProjectorNative: Loading mmproj from %s", path);

  // Cleanup existing mtmd context
  if (g_mtmd_ctx) {
    mtmd_free(g_mtmd_ctx);
    g_mtmd_ctx = nullptr;
  }

  // Initialize mtmd context
  mtmd_context_params mtmd_params = mtmd_context_params_default();
  mtmd_params.use_gpu = false; // CPU only for now
  mtmd_params.n_threads = 4;
  mtmd_params.print_timings = true;

  auto start = std::chrono::high_resolution_clock::now();
  g_mtmd_ctx = mtmd_init_from_file(path, g_model, mtmd_params);
  auto end = std::chrono::high_resolution_clock::now();

  env->ReleaseStringUTFChars(projectorPath, path);

  if (!g_mtmd_ctx) {
    LOGE("loadVisionProjectorNative: Failed to load mmproj");
    g_is_vision_model = false;
    return JNI_FALSE;
  }

  long load_time =
      std::chrono::duration_cast<std::chrono::milliseconds>(end - start)
          .count();
  LOGI("loadVisionProjectorNative: Loaded in %ld ms", load_time);

  // Check if vision is supported
  g_is_vision_model = mtmd_support_vision(g_mtmd_ctx);
  LOGI("loadVisionProjectorNative: Vision support: %s",
       g_is_vision_model ? "YES" : "NO");

  return g_is_vision_model ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_aksoyapps_edgeqslm_AndroidLlamaCppEngine_isVisionModelLoadedNative(
    JNIEnv *env, jobject thiz) {
  return (g_mtmd_ctx != nullptr && g_is_vision_model) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_aksoyapps_edgeqslm_AndroidLlamaCppEngine_analyzeImageNative(
    JNIEnv *env, jobject thiz, jbyteArray imageData, jint width, jint height,
    jstring prompt, jint maxTokens, jfloat temperature) {

  if (!g_model || !g_context || !g_vocab || !g_mtmd_ctx || !g_is_vision_model) {
    LOGE("analyzeImageNative: Vision model not loaded");
    return env->NewStringUTF("[Error: Vision model not loaded]");
  }

  // Get image data (RGB format, 3 bytes per pixel)
  jsize dataLen = env->GetArrayLength(imageData);
  jbyte *imgBytes = env->GetByteArrayElements(imageData, nullptr);
  if (!imgBytes || dataLen != width * height * 3) {
    LOGE("analyzeImageNative: Invalid image data (len=%d, expected=%d)",
         dataLen, width * height * 3);
    if (imgBytes)
      env->ReleaseByteArrayElements(imageData, imgBytes, 0);
    return env->NewStringUTF("[Error: Invalid image data]");
  }

  const char *prompt_cstr = env->GetStringUTFChars(prompt, nullptr);
  if (!prompt_cstr) {
    env->ReleaseByteArrayElements(imageData, imgBytes, 0);
    return env->NewStringUTF("[Error: Invalid prompt]");
  }

  LOGI("analyzeImageNative: Image %dx%d, prompt='%s', maxTokens=%d", width,
       height, prompt_cstr, maxTokens);

  auto start = std::chrono::high_resolution_clock::now();

  // Create bitmap from image data
  mtmd_bitmap *bitmap = mtmd_bitmap_init((uint32_t)width, (uint32_t)height,
                                         (const unsigned char *)imgBytes);

  if (!bitmap) {
    LOGE("analyzeImageNative: Failed to create bitmap");
    env->ReleaseByteArrayElements(imageData, imgBytes, 0);
    env->ReleaseStringUTFChars(prompt, prompt_cstr);
    return env->NewStringUTF("[Error: Failed to create bitmap]");
  }

  // Create input text with image marker and ChatML template
  // Qwen2.5-VL expects ChatML format for proper response generation
  std::string prompt_with_marker =
      "<|im_start|>user\n" + std::string(mtmd_default_marker()) + "\n" +
      prompt_cstr + "<|im_end|>\n<|im_start|>assistant\n";
  mtmd_input_text input_text = {.text = prompt_with_marker.c_str(),
                                .add_special =
                                    false, // We're adding template manually
                                .parse_special = true};

  // Tokenize with image
  mtmd_input_chunks *chunks = mtmd_input_chunks_init();
  const mtmd_bitmap *bitmaps[] = {bitmap};

  int32_t tokenize_result =
      mtmd_tokenize(g_mtmd_ctx, chunks, &input_text, bitmaps, 1);

  env->ReleaseByteArrayElements(imageData, imgBytes, 0);
  env->ReleaseStringUTFChars(prompt, prompt_cstr);

  if (tokenize_result != 0) {
    LOGE("analyzeImageNative: Tokenization failed (err=%d)", tokenize_result);
    mtmd_bitmap_free(bitmap);
    mtmd_input_chunks_free(chunks);
    return env->NewStringUTF("[Error: Image tokenization failed]");
  }

  // Clear context and encode chunks
  llama_memory_clear(llama_get_memory(g_context), true);

  size_t n_chunks = mtmd_input_chunks_size(chunks);
  LOGD("analyzeImageNative: Processing %zu chunks", n_chunks);

  for (size_t i = 0; i < n_chunks; i++) {
    const mtmd_input_chunk *chunk = mtmd_input_chunks_get(chunks, i);
    mtmd_input_chunk_type chunk_type = mtmd_input_chunk_get_type(chunk);

    if (chunk_type == MTMD_INPUT_CHUNK_TYPE_TEXT) {
      size_t n_tokens = 0;
      const llama_token *tokens =
          mtmd_input_chunk_get_tokens_text(chunk, &n_tokens);

      llama_batch batch = llama_batch_get_one((llama_token *)tokens, n_tokens);
      if (llama_decode(g_context, batch) != 0) {
        LOGE("analyzeImageNative: Text decode failed");
        mtmd_bitmap_free(bitmap);
        mtmd_input_chunks_free(chunks);
        return env->NewStringUTF("[Error: Text decode failed]");
      }
    } else if (chunk_type == MTMD_INPUT_CHUNK_TYPE_IMAGE) {
      // Encode image chunk
      if (mtmd_encode_chunk(g_mtmd_ctx, chunk) != 0) {
        LOGE("analyzeImageNative: Image encode failed");
        mtmd_bitmap_free(bitmap);
        mtmd_input_chunks_free(chunks);
        return env->NewStringUTF("[Error: Image encode failed]");
      }

      size_t chunk_n_tokens = mtmd_input_chunk_get_n_tokens(chunk);
      LOGD("analyzeImageNative: Image chunk encoded (%zu tokens)",
           chunk_n_tokens);
    }
  }

  // Generate response
  g_prefill_time_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                          std::chrono::high_resolution_clock::now() - start)
                          .count();
  g_decode_time_ms = 0;
  g_tokens_generated = 0;

  auto decode_start = std::chrono::high_resolution_clock::now();

  llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
  llama_sampler *sampler = llama_sampler_chain_init(sparams);
  llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
  llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

  std::string output;
  output.reserve(maxTokens * 8);
  llama_token eos_token = llama_vocab_eos(g_vocab);

  for (int i = 0; i < maxTokens; i++) {
    llama_token new_token = llama_sampler_sample(sampler, g_context, -1);

    if (new_token == eos_token || llama_vocab_is_eog(g_vocab, new_token)) {
      LOGD("analyzeImageNative: EOS at %d", i);
      break;
    }

    char buf[256];
    int len =
        llama_token_to_piece(g_vocab, new_token, buf, sizeof(buf), 0, true);
    if (len > 0) {
      output.append(buf, len);
      if (has_stop_token(output)) {
        LOGD("analyzeImageNative: Stop token at %d", i);
        break;
      }
    }

    llama_batch next_batch = llama_batch_get_one(&new_token, 1);
    if (llama_decode(g_context, next_batch) != 0) {
      LOGE("analyzeImageNative: Decode failed at %d", i);
      break;
    }
    g_tokens_generated++;
  }

  llama_sampler_free(sampler);
  mtmd_bitmap_free(bitmap);
  mtmd_input_chunks_free(chunks);

  auto decode_end = std::chrono::high_resolution_clock::now();
  g_decode_time_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                         decode_end - decode_start)
                         .count();

  std::string clean_output = trim_stop_tokens(output);

  float tokens_per_sec = g_decode_time_ms > 0
                             ? (g_tokens_generated * 1000.0f / g_decode_time_ms)
                             : 0.0f;
  LOGI("analyzeImageNative: %d tokens in %ld ms (%.2f tok/s)",
       g_tokens_generated, g_decode_time_ms, tokens_per_sec);

  return env->NewStringUTF(clean_output.c_str());
}

} // extern "C"
