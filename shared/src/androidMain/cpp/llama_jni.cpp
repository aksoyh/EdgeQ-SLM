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

#include "chat.h"
#include "common.h"
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"
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

static void release_model() {
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
}

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
  release_model();
  llama_backend_free();
}

JNIEXPORT jboolean JNICALL
Java_com_aksoyapps_edgeqslm_AndroidLlamaCppEngine_loadModelNative(
    JNIEnv *env, jobject thiz, jstring modelPath) {

  release_model();
  const char *path = env->GetStringUTFChars(modelPath, nullptr);
  if (!path) {
    LOGE("loadModelNative: Failed to get path");
    return JNI_FALSE;
  }

  LOGI("loadModelNative: Loading model from %s", path);

  llama_model_params model_params = llama_model_default_params();
  model_params.n_gpu_layers = 0;
  model_params.load_mode = LLAMA_LOAD_MODE_MMAP;

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
    release_model();
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
      sampler, llama_sampler_init_penalties(llama_vocab_n_tokens(g_vocab), 64,
                                            repeatPenalty, 0.0f, 0.0f));
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
  release_model();
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
  g_is_vision_model = false;

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

  if (!g_is_vision_model) {
    mtmd_free(g_mtmd_ctx);
    g_mtmd_ctx = nullptr;
  }

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
    jstring prompt, jint maxTokens) {

  if (!g_model || !g_context || !g_vocab || !g_mtmd_ctx || !g_is_vision_model) {
    LOGE("analyzeImageNative: Vision model not loaded");
    return env->NewStringUTF("[Error: Vision model not loaded]");
  }

  const jsize data_len = env->GetArrayLength(imageData);
  if (width <= 0 || height <= 0 ||
      static_cast<int64_t>(width) * height * 3 != data_len || maxTokens != 320) {
    return env->NewStringUTF("[Error: Invalid image data or token budget]");
  }

  try {
    auto release_bytes = [env, imageData](jbyte *bytes) {
      env->ReleaseByteArrayElements(imageData, bytes, JNI_ABORT);
    };
    std::unique_ptr<jbyte, decltype(release_bytes)> image_bytes(
        env->GetByteArrayElements(imageData, nullptr), release_bytes);
    if (!image_bytes) return nullptr;

    auto release_prompt = [env, prompt](const char *chars) {
      env->ReleaseStringUTFChars(prompt, chars);
    };
    std::unique_ptr<const char, decltype(release_prompt)> prompt_chars(
        env->GetStringUTFChars(prompt, nullptr), release_prompt);
    if (!prompt_chars) return nullptr;

    std::string prompt_content(prompt_chars.get());
    prompt_chars.reset();
    LOGI("analyzeImageNative: invocation contract=android_q5_vlm_candidate_contract_v4 "
         "image=%dx%d raw_prompt_bytes=%zu maxTokens=%d",
         width, height, prompt_content.size(), maxTokens);

    auto start = std::chrono::high_resolution_clock::now();
    mtmd::bitmap_ptr bitmap(mtmd_bitmap_init(
        static_cast<uint32_t>(width), static_cast<uint32_t>(height),
        reinterpret_cast<const unsigned char *>(image_bytes.get())));
    image_bytes.reset();
    if (!bitmap) {
      return env->NewStringUTF("[Error: Failed to create bitmap]");
    }

    if (prompt_content.size() != 917 || prompt_content.back() != '\n') {
      LOGE("analyzeImageNative: V4 prompt contract failed (bytes=%zu)",
           prompt_content.size());
      return env->NewStringUTF("[Error: V4 prompt contract failed]");
    }
    prompt_content.pop_back();

    const char *native_template = llama_model_chat_template(g_model, nullptr);
    if (!native_template) {
      return env->NewStringUTF("[Error: Missing native chat template]");
    }

    const std::string marker = mtmd_default_marker();
    const std::string user_content = marker + prompt_content;
    const std::string bos_piece =
        common_token_to_piece(g_vocab, llama_vocab_bos(g_vocab), true);
    const std::string eos_piece =
        common_token_to_piece(g_vocab, llama_vocab_eos(g_vocab), true);
    auto templates = common_chat_templates_init(
        nullptr, native_template, bos_piece, eos_piece);
    common_chat_msg user_message;
    user_message.role = "user";
    user_message.content = user_content;
    common_chat_templates_inputs template_inputs;
    template_inputs.messages = {user_message};
    template_inputs.add_generation_prompt = true;
    template_inputs.use_jinja = true;
    const std::string prompt_with_marker =
        common_chat_templates_apply(templates.get(), template_inputs).prompt;
    LOGI("analyzeImageNative: V4 prompt contract raw_bytes=917 "
         "user_content_bytes=%zu terminal_lf_excluded=1 native_template=1 "
         "media_prompt_separator_bytes=0 add_special=1 rendered_bytes=%zu",
         prompt_content.size(), prompt_with_marker.size());

    mtmd_input_text input_text = {.text = prompt_with_marker.c_str(),
                                 .text_len = prompt_with_marker.size(),
                                 .add_special = true,
                                 .parse_special = true};
    mtmd::input_chunks_ptr chunks(mtmd_input_chunks_init());
    const mtmd_bitmap *bitmaps[] = {bitmap.get()};
    const int32_t tokenize_result =
        mtmd_tokenize(g_mtmd_ctx, chunks.get(), &input_text, bitmaps, 1);
    if (tokenize_result != 0) {
      LOGE("analyzeImageNative: Tokenization failed (err=%d)", tokenize_result);
      return env->NewStringUTF("[Error: Image tokenization failed]");
    }

    llama_memory_clear(llama_get_memory(g_context), true);
    const size_t n_chunks = mtmd_input_chunks_size(chunks.get());
    std::vector<llama_token> prefill_text_tokens;
    size_t image_chunks = 0;
    size_t leading_bos_tokens = 0;
    const llama_token bos_token = llama_vocab_bos(g_vocab);
    for (size_t i = 0; i < n_chunks; i++) {
      const mtmd_input_chunk *chunk = mtmd_input_chunks_get(chunks.get(), i);
      const mtmd_input_chunk_type type = mtmd_input_chunk_get_type(chunk);
      if (type == MTMD_INPUT_CHUNK_TYPE_TEXT) {
        size_t n_tokens = 0;
        const llama_token *tokens =
            mtmd_input_chunk_get_tokens_text(chunk, &n_tokens);
        if (n_tokens > 0) {
          prefill_text_tokens.insert(prefill_text_tokens.end(), tokens,
                                     tokens + n_tokens);
        }
        if (i == 0) {
          while (leading_bos_tokens < n_tokens &&
                 tokens[leading_bos_tokens] == bos_token) {
            leading_bos_tokens++;
          }
        }
        LOGI("analyzeImageNative: V4 chunk[%zu]=text tokens=%zu", i, n_tokens);
      } else if (type == MTMD_INPUT_CHUNK_TYPE_IMAGE) {
        image_chunks++;
        LOGI("analyzeImageNative: V4 chunk[%zu]=image tokens=%zu", i,
             mtmd_input_chunk_get_n_tokens(chunk));
      } else {
        LOGI("analyzeImageNative: V4 chunk[%zu]=audio tokens=%zu", i,
             mtmd_input_chunk_get_n_tokens(chunk));
      }
    }
    LOGI("analyzeImageNative: V4 chunks=%zu image_chunks=%zu "
         "leading_bos_tokens=%zu prefill_text_tokens=%zu",
         n_chunks, image_chunks, leading_bos_tokens, prefill_text_tokens.size());

    llama_pos n_past = 0;
    LOGI("analyzeImageNative: Calling mtmd_helper_eval_chunks for V4 multimodal prefill");
    const int32_t eval_result = mtmd_helper_eval_chunks(
        g_mtmd_ctx, g_context, chunks.get(), n_past, 0,
        static_cast<int32_t>(llama_n_batch(g_context)), true, &n_past);
    if (eval_result != 0) {
      LOGE("analyzeImageNative: Multimodal prefill failed (err=%d)", eval_result);
      return env->NewStringUTF("[Error: Multimodal prefill failed]");
    }
    LOGI("analyzeImageNative: V4 multimodal prefill success "
         "final_n_past=%d; generation may begin", n_past);

    g_prefill_time_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                            std::chrono::high_resolution_clock::now() - start)
                            .count();
    g_decode_time_ms = 0;
    g_tokens_generated = 0;
    const auto decode_start = std::chrono::high_resolution_clock::now();

    common_params_sampling sampling_params;
    sampling_params.seed = 42;
    sampling_params.temp = 0.0f;
    sampling_params.n_prev = 64;
    sampling_params.penalty_last_n = 64;
    sampling_params.penalty_repeat = 1.05f;
    std::unique_ptr<common_sampler, decltype(&common_sampler_free)> sampler(
        common_sampler_init(g_model, sampling_params), common_sampler_free);
    if (!sampler) {
      return env->NewStringUTF("[Error: Sampler initialization failed]");
    }
    for (llama_token token : prefill_text_tokens) {
      common_sampler_accept(sampler.get(), token, false);
    }
    LOGI("analyzeImageNative: V4 sampler seed=42 temp=0 "
         "repeat_penalty=1.05 last_n=64 prompt_text_tokens_accepted=%zu "
         "image_positions_accepted=0 chain='%s'",
         prefill_text_tokens.size(), common_sampler_print(sampler.get()).c_str());

    std::string output;
    output.reserve(maxTokens * 8);
    const llama_token eos_token = llama_vocab_eos(g_vocab);
    for (int i = 0; i < maxTokens; i++) {
      const llama_token new_token =
          common_sampler_sample(sampler.get(), g_context, -1);
      common_sampler_accept(sampler.get(), new_token, true);
      if (new_token == eos_token || llama_vocab_is_eog(g_vocab, new_token)) {
        LOGD("analyzeImageNative: EOS at %d", i);
        break;
      }

      char buf[256];
      const int len =
          llama_token_to_piece(g_vocab, new_token, buf, sizeof(buf), 0, true);
      if (len < 0) {
        return env->NewStringUTF("[Error: Token piece exceeds output buffer]");
      }
      if (len > 0) {
        output.append(buf, len);
        if (has_stop_token(output)) {
          LOGD("analyzeImageNative: Stop token at %d", i);
          break;
        }
      }

      llama_token decode_token = new_token;
      llama_batch next_batch = llama_batch_get_one(&decode_token, 1);
      if (llama_decode(g_context, next_batch) != 0) {
        LOGE("analyzeImageNative: Decode failed at %d", i);
        return env->NewStringUTF("[Error: Vision generation decode failed]");
      }
      g_tokens_generated++;
    }

    g_decode_time_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                           std::chrono::high_resolution_clock::now() - decode_start)
                           .count();
    const std::string clean_output = trim_stop_tokens(output);
    const float tokens_per_sec = g_decode_time_ms > 0
                                     ? (g_tokens_generated * 1000.0f / g_decode_time_ms)
                                     : 0.0f;
    LOGI("analyzeImageNative: %d tokens in %ld ms (%.2f tok/s)",
         g_tokens_generated, g_decode_time_ms, tokens_per_sec);
    return env->NewStringUTF(clean_output.c_str());
  } catch (const std::exception &error) {
    LOGE("analyzeImageNative: V4 inference failed: %s", error.what());
    return env->NewStringUTF("[Error: V4 inference failed]");
  }
}

} // extern "C"
