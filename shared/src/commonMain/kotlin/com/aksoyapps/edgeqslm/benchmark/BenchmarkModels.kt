package com.aksoyapps.edgeqslm.benchmark

import kotlinx.serialization.Serializable

/**
 * Configuration for a single benchmark run.
 */
@Serializable
data class RunConfig(
    val promptId: String,
    val promptText: String,
    val promptCategory: PromptCategory,
    val temperature: Float,
    val maxTokens: Int = 2048,
    val topP: Float = 0.9f,
    val repeatPenalty: Float = 1.1f,
    val repeatIndex: Int = 0,         // Which repeat this is (0-based)
    val isWarmup: Boolean = false,
    val engineVariant: EngineVariant = EngineVariant.QUANTIZED_INT8
)

/**
 * Result of a single benchmark run.
 */
@Serializable
data class RunResult(
    val config: RunConfig,
    val timestamp: Long,              // Unix timestamp of run
    
    // Timing metrics (ms)
    val totalLatencyMs: Long,
    val prefillTimeMs: Long,          // TTFT - Time To First Token
    val decodeTimeMs: Long,
    
    // Token metrics
    val tokensGenerated: Int,
    val tokensPerSecond: Float,
    
    // Memory metrics
    val peakMemoryMb: Float,
    val avgMemoryMb: Float,
    val memoryMethod: String,         // "PSS", "RSS", "NativeHeap", etc.
    
    // CPU metrics
    val cpuUtilizationPercent: Float,
    val cpuMethod: String,            // "/proc/stat", "estimated", etc.
    
    // Output
    val outputText: String,
    val outputCharCount: Int,
    val outputTokenCount: Int,
    
    // Sanity check results
    val sanityCheck: SanityCheckResult,
    
    // Error info (if any)
    val error: String? = null,
    val success: Boolean = error == null
)

/**
 * Aggregated statistics for a (promptId, temperature, maxTokens, variant) combination.
 */
@Serializable
data class AggregatedStats(
    val promptId: String,
    val promptCategory: PromptCategory,
    val temperature: Float,
    val maxTokens: Int,
    val engineVariant: EngineVariant,
    val runCount: Int,
    
    // Timing stats
    val latencyMsMean: Float,
    val latencyMsStd: Float,
    val ttftMsMean: Float,
    val ttftMsStd: Float,
    val decodeMsMean: Float,
    val decodeMsStd: Float,
    val tokensPerSecMean: Float,
    val tokensPerSecStd: Float,
    
    // Memory stats
    val memoryMbMean: Float,
    val memoryMbStd: Float,
    
    // CPU stats
    val cpuPercentMean: Float,
    val cpuPercentStd: Float,
    
    // Token stats
    val tokensGeneratedMean: Float,
    val tokensGeneratedStd: Float,
    
    // Sanity check summary
    val emptyOutputCount: Int,
    val highRepetitionCount: Int,      // outputs with repetition > threshold
    val successRate: Float
)

/**
 * Categories for prompts.
 */
@Serializable
enum class PromptCategory {
    FACTUAL_SHORT,
    FACTUAL_MEDIUM,
    FACTUAL_LONG,
    INSTRUCTION_SHORT,
    INSTRUCTION_MEDIUM,
    INSTRUCTION_LONG,
    CREATIVE_SHORT,
    CREATIVE_MEDIUM,
    CREATIVE_LONG,
    EDGE_VERY_SHORT,
    EDGE_VERY_LONG
}

/**
 * Engine variants for comparison.
 */
@Serializable
enum class EngineVariant {
    QUANTIZED_INT8,         // Current implementation
    QUANTIZED_INT4,         // Future: INT4 quantization
    UNQUANTIZED_FP16,       // Future: Baseline unquantized
    UNQUANTIZED_FP32        // Future: Full precision baseline
}

/**
 * Sanity check results for output quality.
 */
@Serializable
data class SanityCheckResult(
    val isEmpty: Boolean,
    val charCount: Int,
    val estimatedTokenCount: Int,
    val repetitionScore: Float,        // 0.0 = no repetition, 1.0 = all repeated
    val repeatedNGramCount: Int,       // Number of repeated n-grams
    val isCoherent: Boolean,           // Passes basic coherence check
    val coherenceReason: String
)

/**
 * Device information for reproducibility.
 */
@Serializable
data class DeviceInfo(
    val model: String,
    val manufacturer: String,
    val osVersion: String,
    val sdkVersion: Int,
    val abi: String,
    val cpuCores: Int,
    val totalRamMb: Long,
    val availableRamMb: Long
)

/**
 * Metadata about the benchmark run session.
 */
@Serializable
data class BenchmarkMetadata(
    val sessionId: String,
    val startTimestamp: Long,
    val endTimestamp: Long,
    val appVersion: String,
    val modelId: String,
    val modelPath: String,
    val quantizationVariant: EngineVariant,
    val gitCommitHash: String? = null,
    val deviceInfo: DeviceInfo,
    val temperatureSweep: List<Float>,
    val repeatsPerCondition: Int,
    val warmupRuns: Int,
    val promptCount: Int,
    val totalRuns: Int,
    val successfulRuns: Int,
    val failedRuns: Int
)

/**
 * Configuration for the entire benchmark session.
 */
data class BenchmarkConfig(
    val temperatures: List<Float> = listOf(0.1f, 1.1f, 2.0f),
    val maxTokensSweep: List<Int> = listOf(128, 512, 1024),  // Token count sweep
    val repeatsPerCondition: Int = 3,
    val warmupRuns: Int = 1,
    val topP: Float = 0.9f,
    val repeatPenalty: Float = 1.1f,
    val engineVariant: EngineVariant = EngineVariant.QUANTIZED_INT8
)

/**
 * Progress state during benchmark execution.
 */
data class BenchmarkProgress(
    val currentPromptIndex: Int,
    val totalPrompts: Int,
    val currentTemperatureIndex: Int,
    val totalTemperatures: Int,
    val currentTokenIndex: Int,
    val totalTokens: Int,
    val currentRepeat: Int,
    val totalRepeats: Int,
    val currentPromptId: String,
    val currentTemperature: Float,
    val currentMaxTokens: Int,
    val isWarmup: Boolean,
    val completedRuns: Int,
    val totalRuns: Int,
    val elapsedTimeMs: Long,
    val estimatedRemainingMs: Long
) {
    val progressPercent: Float get() = if (totalRuns > 0) completedRuns.toFloat() / totalRuns else 0f
}

/**
 * State of the benchmark screen.
 */
data class BenchmarkUiState(
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val isCompleted: Boolean = false,
    val progress: BenchmarkProgress? = null,
    val results: List<RunResult> = emptyList(),
    val aggregatedStats: List<AggregatedStats> = emptyList(),
    val error: String? = null,
    val exportPath: String? = null,
    val config: BenchmarkConfig = BenchmarkConfig(),
    val previousExports: List<Any> = emptyList(),  // List<ExportSession> on Android
    val legacyExports: List<Any> = emptyList()     // List<LegacyExport> on Android
)

