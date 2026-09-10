package com.aksoyapps.edgeqslm

import android.os.Debug
import com.aksoyapps.edgeqslm.photos.ModelResourceCoordinator
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android implementation of LlmEngine using llama.cpp via JNI.
 *
 * This implementation provides real on-device inference using quantized GGUF models.
 * Features:
 * - ChatML template support for instruction-tuned models
 * - Configurable sampling parameters (temperature, top_p, repeat_penalty)
 * - Stop token detection for clean outputs
 * - Performance timing metrics (TTFT, decode speed, memory)
 */
class AndroidLlamaCppEngine : LlmEngine {

    companion object {
        private const val TAG = "AndroidLlamaCppEngine"

        init {
            try {
                System.loadLibrary("llama_jni")
                println("$TAG: Native library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                System.err.println("$TAG: Failed to load native library: ${e.message}")
                throw RuntimeException("Failed to load llama_jni native library", e)
            }
        }
    }

    override val isSimulation: Boolean = false

    // JNI native method declarations
    private external fun loadModelNative(path: String): Boolean
    
    private external fun generateNative(
        prompt: String, 
        maxTokens: Int, 
        temperature: Float,
        topP: Float,
        repeatPenalty: Float,
        useChatTemplate: Boolean
    ): String
    
    private external fun unloadNative()
    private external fun getPrefillTimeNative(): Long
    private external fun getDecodeTimeNative(): Long
    private external fun getTokensGeneratedNative(): Int
    private external fun isModelLoadedNative(): Boolean
    
    // Vision model native methods
    private external fun loadVisionProjectorNative(projectorPath: String): Boolean
    private external fun isVisionModelLoadedNative(): Boolean
    private external fun createVisionCancellationNative(): Long
    private external fun cancelVisionRequestNative(request: Long)
    private external fun releaseVisionCancellationNative(request: Long)
    private external fun analyzeImageNative(
        imageData: ByteArray,
        width: Int,
        height: Int,
        prompt: String,
        maxTokens: Int,
        cancellationRequest: Long,
    ): String

    override suspend fun loadModel(modelPath: String): Boolean = withContext(Dispatchers.IO) {
        ModelResourceCoordinator.claimNative(this@AndroidLlamaCppEngine) {
            println("$TAG: Loading model from $modelPath")

            var isLoaded: Boolean
            val memoryBefore = Debug.getNativeHeapAllocatedSize()
            val loadTime = measureTimeMillis {
                isLoaded = loadModelNative(modelPath)
            }
            val memoryAfter = Debug.getNativeHeapAllocatedSize()

            if (isLoaded) {
                val memoryUsedMB = (memoryAfter - memoryBefore) / (1024.0 * 1024.0)
                println("$TAG: Model loaded in ${loadTime}ms, memory: ${String.format("%.2f", memoryUsedMB)} MB")
            } else {
                println("$TAG: Failed to load model from $modelPath")
            }

            isLoaded
        }
    }

    override suspend fun generate(request: GenerationRequest): GenerationResult = withContext(Dispatchers.IO) {
        ModelResourceCoordinator.withNativeOperation(this@AndroidLlamaCppEngine) {

            println("$TAG: Generate: prompt='${request.prompt.take(50)}...', maxTokens=${request.maxTokens}, temp=${request.temperature}, topP=${request.topP}, repPen=${request.repeatPenalty}, chat=${request.useChatTemplate}")

            val memoryBefore = Debug.getNativeHeapAllocatedSize()

            var generatedText: String
            val totalLatency = measureTimeMillis {
                generatedText = generateNative(
                    request.prompt,
                    request.maxTokens,
                    request.temperature,
                    request.topP,
                    request.repeatPenalty,
                    request.useChatTemplate
                )
            }

            val memoryAfter = Debug.getNativeHeapAllocatedSize()
            val peakMemory = maxOf(memoryBefore, memoryAfter)

            // Get detailed timing from native side
            val prefillTime = getPrefillTimeNative()
            val decodeTime = getDecodeTimeNative()
            val tokensGenerated = getTokensGeneratedNative()

            // Calculate tokens per second
            val tokensPerSec = if (decodeTime > 0) {
                (tokensGenerated * 1000f) / decodeTime
            } else {
                0f
            }

            println("$TAG: Complete - TTFT: ${prefillTime}ms, Decode: ${decodeTime}ms, Tokens: $tokensGenerated, Speed: ${String.format("%.2f", tokensPerSec)} tok/s")

            GenerationResult(
                text = generatedText,
                latencyMs = totalLatency,
                tokensPerSecond = tokensPerSec,
                memoryUsageBytes = peakMemory,
                prefillTimeMs = prefillTime,
                decodeTimeMs = decodeTime,
                tokensGenerated = tokensGenerated
            )
        }
    }

    override fun unload() {
        ModelResourceCoordinator.releaseNative(this) {
            unloadNative()
            println("$TAG: Model unloaded successfully")
        }
    }

    /**
     * Check if a model is currently loaded (queries native side)
     */
    fun isModelLoaded(): Boolean {
        return ModelResourceCoordinator.inspectNative(this, false) {
            isModelLoadedNative()
        }
    }
    
    // ============= VISION MODEL SUPPORT =============
    
    /**
     * Load the vision projector (mmproj file) for Vision LLM support
     * The base model must be loaded first via loadModel()
     */
    suspend fun loadVisionProjector(projectorPath: String): Boolean = withContext(Dispatchers.IO) {
        ModelResourceCoordinator.withNativeOperation(this@AndroidLlamaCppEngine) {

            println("$TAG: Loading vision projector from $projectorPath")

            var isVisionProjectorLoaded: Boolean
            val loadTime = measureTimeMillis {
                isVisionProjectorLoaded = loadVisionProjectorNative(projectorPath)
            }

            if (isVisionProjectorLoaded) {
                println("$TAG: Vision projector loaded in ${loadTime}ms")
            } else {
                println("$TAG: Failed to load vision projector")
            }

            isVisionProjectorLoaded
        }
    }
    
    /**
     * Check if vision model (base + projector) is loaded
     */
    fun isVisionLoaded(): Boolean {
        return ModelResourceCoordinator.inspectNative(this, false) {
            isVisionModelLoadedNative()
        }
    }

    suspend fun <T> withExclusiveSession(block: suspend () -> T): T =
        ModelResourceCoordinator.withExclusiveSession(this) {
            try {
                block()
            } finally {
                unload()
            }
        }
    
    /**
     * Analyze an image with the Vision LLM
     * @param imageData RGB byte array (3 bytes per pixel, RGBRGBRGB format)
     * @param width Image width in pixels
     * @param height Image height in pixels
     * @param prompt Text prompt for image analysis
     * @param maxTokens Maximum tokens to generate
     * @return Analysis result with generated text and timing
     */
    suspend fun analyzeImage(
        imageData: ByteArray,
        width: Int,
        height: Int,
        prompt: String,
        maxTokens: Int = 320,
    ): GenerationResult = withContext(Dispatchers.IO) {
        cancellableNativeCall(
            ::createVisionCancellationNative,
            ::cancelVisionRequestNative,
            ::releaseVisionCancellationNative,
        ) { cancellationRequest ->
            ModelResourceCoordinator.withNativeOperation(this@AndroidLlamaCppEngine) {
                if (!isVisionLoaded()) {
                    throw IllegalStateException("Vision model not loaded. Load base model and vision projector first.")
                }
                require(width > 0 && height > 0 && imageData.size.toLong() == width.toLong() * height * 3) {
                    "Invalid RGB image dimensions"
                }
                require(maxTokens == 320) { "The accepted VLM contract requires 320 maximum tokens" }

                println("$TAG: Analyzing image ${width}x${height}, prompt='${prompt.take(50)}...', maxTokens=$maxTokens")

                val memoryBefore = Debug.getNativeHeapAllocatedSize()

                var generatedText: String
                val totalLatency = measureTimeMillis {
                    generatedText = analyzeImageNative(
                        imageData,
                        width,
                        height,
                        prompt,
                        maxTokens,
                        cancellationRequest,
                    )
                }
                check(!generatedText.startsWith("[Error:")) { generatedText }

                val memoryAfter = Debug.getNativeHeapAllocatedSize()
                val peakMemory = maxOf(memoryBefore, memoryAfter)

                // Get timing from native side
                val prefillTime = getPrefillTimeNative()
                val decodeTime = getDecodeTimeNative()
                val tokensGenerated = getTokensGeneratedNative()

                val tokensPerSec = if (decodeTime > 0) {
                    (tokensGenerated * 1000f) / decodeTime
                } else {
                    0f
                }

                println("$TAG: Vision analysis complete - TTFT: ${prefillTime}ms, Decode: ${decodeTime}ms, Tokens: $tokensGenerated, Speed: ${String.format("%.2f", tokensPerSec)} tok/s")

                GenerationResult(
                    text = generatedText,
                    latencyMs = totalLatency,
                    tokensPerSecond = tokensPerSec,
                    memoryUsageBytes = peakMemory,
                    prefillTimeMs = prefillTime,
                    decodeTimeMs = decodeTime,
                    tokensGenerated = tokensGenerated
                )
            }
        }
    }
}
