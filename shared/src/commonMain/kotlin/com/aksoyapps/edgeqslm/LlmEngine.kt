package com.aksoyapps.edgeqslm

/**
 * Request parameters for text generation.
 */
data class GenerationRequest(
    val prompt: String,
    val maxTokens: Int = 2048,                   // Maximum tokens for longer responses
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,                      // Nucleus sampling
    val repeatPenalty: Float = 1.1f,             // Prevent repetition
    val useChatTemplate: Boolean = true          // Wrap prompt in chatml format
)

/**
 * Result of text generation with performance metrics.
 */
data class GenerationResult(
    val text: String,
    val latencyMs: Long,
    val tokensPerSecond: Float,
    val memoryUsageBytes: Long,
    // Extended timing metrics (for thesis measurements)
    val prefillTimeMs: Long = 0,      // Time To First Token (TTFT)
    val decodeTimeMs: Long = 0,        // Decode phase duration
    val tokensGenerated: Int = 0       // Number of tokens generated
)

/**
 * Interface for LLM inference engines.
 * Implemented by AndroidLlamaCppEngine (real) and can have iOS/simulation implementations.
 */
interface LlmEngine {
    val isSimulation: Boolean

    /**
     * Loads the model from the specified path.
     * @param modelPath Absolute path to the GGUF model file.
     * @return true if loaded successfully, false otherwise.
     */
    suspend fun loadModel(modelPath: String): Boolean

    /** Generates text based on the request. */
    suspend fun generate(request: GenerationRequest): GenerationResult

    /** Unloads the model to free memory. */
    fun unload()
}
