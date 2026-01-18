package com.aksoyapps.edgeqslm

import android.os.Debug
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

    private var isLoaded = false

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

    override suspend fun loadModel(modelPath: String): Boolean = withContext(Dispatchers.IO) {
        println("$TAG: Loading model from $modelPath")

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

    override suspend fun generate(request: GenerationRequest): GenerationResult = withContext(Dispatchers.IO) {
        if (!isLoaded) {
            throw IllegalStateException("Model not loaded. Call loadModel() first.")
        }

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

    override fun unload() {
        println("$TAG: Unloading model")
        if (isLoaded) {
            unloadNative()
            isLoaded = false
            println("$TAG: Model unloaded successfully")
        } else {
            println("$TAG: No model loaded to unload")
        }
    }

    /**
     * Check if a model is currently loaded (queries native side)
     */
    fun isModelLoaded(): Boolean {
        return try {
            isModelLoadedNative()
        } catch (e: Exception) {
            isLoaded
        }
    }
}
