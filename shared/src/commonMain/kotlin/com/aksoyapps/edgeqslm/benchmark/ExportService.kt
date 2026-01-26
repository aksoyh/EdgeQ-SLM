package com.aksoyapps.edgeqslm.benchmark

/**
 * Service for exporting benchmark results to CSV/JSON.
 * Platform-specific implementations handle file I/O.
 */
interface ExportService {
    /**
     * Export results to CSV format.
     * Returns the file path where results were saved.
     */
    suspend fun exportToCsv(
        results: List<RunResult>,
        filename: String = "latency_memory_cpu.csv"
    ): String
    
    /**
     * Export aggregated stats to CSV format.
     */
    suspend fun exportAggregatedToCsv(
        stats: List<AggregatedStats>,
        filename: String = "aggregated_stats.csv"
    ): String
    
    /**
     * Export sanity check results to CSV.
     */
    suspend fun exportSanityChecksToCsv(
        results: List<RunResult>,
        filename: String = "sanity_checks.csv"
    ): String
    
    /**
     * Export sample outputs for manual review.
     */
    suspend fun exportSampleOutputs(
        results: List<RunResult>,
        maxSamplesPerCondition: Int = 3,
        filename: String = "sample_outputs.json"
    ): String
    
    /**
     * Export run metadata for reproducibility.
     */
    suspend fun exportMetadata(
        metadata: BenchmarkMetadata,
        filename: String = "run_metadata.json"
    ): String
    
    /**
     * Export device info.
     */
    suspend fun exportDeviceInfo(
        deviceInfo: DeviceInfo,
        filename: String = "device_info.txt"
    ): String
    
    /**
     * Get the export directory path.
     */
    fun getExportDirectory(): String
    
    /**
     * Share exported files (platform-specific).
     */
    suspend fun shareExports(filePaths: List<String>)
}

/**
 * Common export utilities.
 */
object ExportUtils {
    
    /**
     * Generate CSV header for RunResult.
     */
    fun getRunResultCsvHeader(): String {
        return listOf(
            "timestamp",
            "prompt_id",
            "prompt_category",
            "temperature",
            "max_tokens",
            "engine_variant",
            "repeat_index",
            "is_warmup",
            "total_latency_ms",
            "ttft_ms",
            "decode_ms",
            "tokens_generated",
            "tokens_per_sec",
            "peak_memory_mb",
            "avg_memory_mb",
            "memory_method",
            "cpu_percent",
            "cpu_method",
            "output_chars",
            "output_tokens",
            "sanity_is_empty",
            "sanity_repetition",
            "sanity_is_coherent",
            "success",
            "error"
        ).joinToString(",")
    }
    
    /**
     * Convert RunResult to CSV row.
     */
    fun runResultToCsvRow(result: RunResult): String {
        return listOf(
            result.timestamp.toString(),
            result.config.promptId,
            result.config.promptCategory.name,
            result.config.temperature.toString(),
            result.config.maxTokens.toString(),
            result.config.engineVariant.name,
            result.config.repeatIndex.toString(),
            result.config.isWarmup.toString(),
            result.totalLatencyMs.toString(),
            result.prefillTimeMs.toString(),
            result.decodeTimeMs.toString(),
            result.tokensGenerated.toString(),
            "%.2f".format(result.tokensPerSecond),
            "%.2f".format(result.peakMemoryMb),
            "%.2f".format(result.avgMemoryMb),
            result.memoryMethod,
            "%.2f".format(result.cpuUtilizationPercent),
            result.cpuMethod,
            result.outputCharCount.toString(),
            result.outputTokenCount.toString(),
            result.sanityCheck.isEmpty.toString(),
            "%.3f".format(result.sanityCheck.repetitionScore),
            result.sanityCheck.isCoherent.toString(),
            result.success.toString(),
            (result.error ?: "").replace(",", ";").replace("\n", " ")
        ).joinToString(",")
    }
    
    /**
     * Generate CSV header for AggregatedStats.
     */
    fun getAggregatedStatsCsvHeader(): String {
        return listOf(
            "prompt_id",
            "prompt_category",
            "temperature",
            "max_tokens",
            "engine_variant",
            "run_count",
            "latency_mean_ms",
            "latency_std_ms",
            "ttft_mean_ms",
            "ttft_std_ms",
            "decode_mean_ms",
            "decode_std_ms",
            "tok_per_sec_mean",
            "tok_per_sec_std",
            "memory_mean_mb",
            "memory_std_mb",
            "cpu_mean_percent",
            "cpu_std_percent",
            "tokens_mean",
            "tokens_std",
            "empty_outputs",
            "high_repetition",
            "success_rate"
        ).joinToString(",")
    }
    
    /**
     * Convert AggregatedStats to CSV row.
     */
    fun aggregatedStatsToCsvRow(stats: AggregatedStats): String {
        return listOf(
            stats.promptId,
            stats.promptCategory.name,
            stats.temperature.toString(),
            stats.maxTokens.toString(),
            stats.engineVariant.name,
            stats.runCount.toString(),
            "%.2f".format(stats.latencyMsMean),
            "%.2f".format(stats.latencyMsStd),
            "%.2f".format(stats.ttftMsMean),
            "%.2f".format(stats.ttftMsStd),
            "%.2f".format(stats.decodeMsMean),
            "%.2f".format(stats.decodeMsStd),
            "%.2f".format(stats.tokensPerSecMean),
            "%.2f".format(stats.tokensPerSecStd),
            "%.2f".format(stats.memoryMbMean),
            "%.2f".format(stats.memoryMbStd),
            "%.2f".format(stats.cpuPercentMean),
            "%.2f".format(stats.cpuPercentStd),
            "%.2f".format(stats.tokensGeneratedMean),
            "%.2f".format(stats.tokensGeneratedStd),
            stats.emptyOutputCount.toString(),
            stats.highRepetitionCount.toString(),
            "%.3f".format(stats.successRate)
        ).joinToString(",")
    }
    
    /**
     * Generate CSV header for sanity checks.
     */
    fun getSanityCheckCsvHeader(): String {
        return listOf(
            "prompt_id",
            "temperature",
            "repeat_index",
            "is_empty",
            "char_count",
            "estimated_tokens",
            "repetition_score",
            "repeated_ngrams",
            "is_coherent",
            "coherence_reason"
        ).joinToString(",")
    }
    
    /**
     * Convert sanity check to CSV row.
     */
    fun sanityCheckToCsvRow(result: RunResult): String {
        return listOf(
            result.config.promptId,
            result.config.temperature.toString(),
            result.config.repeatIndex.toString(),
            result.sanityCheck.isEmpty.toString(),
            result.sanityCheck.charCount.toString(),
            result.sanityCheck.estimatedTokenCount.toString(),
            "%.3f".format(result.sanityCheck.repetitionScore),
            result.sanityCheck.repeatedNGramCount.toString(),
            result.sanityCheck.isCoherent.toString(),
            result.sanityCheck.coherenceReason.replace(",", ";")
        ).joinToString(",")
    }
    
    /**
     * Generate device info text.
     */
    fun deviceInfoToText(info: DeviceInfo): String {
        return """
            |Device Information
            |==================
            |Model: ${info.model}
            |Manufacturer: ${info.manufacturer}
            |OS Version: Android ${info.osVersion} (SDK ${info.sdkVersion})
            |ABI: ${info.abi}
            |CPU Cores: ${info.cpuCores}
            |Total RAM: ${info.totalRamMb} MB
            |Available RAM: ${info.availableRamMb} MB
        """.trimMargin()
    }
    
    /**
     * Select sample outputs for export (diverse selection).
     */
    fun selectSampleOutputs(
        results: List<RunResult>,
        maxSamplesPerCondition: Int = 3
    ): List<SampleOutput> {
        return results
            .filter { it.success && !it.config.isWarmup }
            .groupBy { it.config.promptId to it.config.temperature }
            .flatMap { (key, runs) ->
                runs.take(maxSamplesPerCondition).map { result ->
                    SampleOutput(
                        promptId = result.config.promptId,
                        promptText = result.config.promptText.take(200),
                        temperature = result.config.temperature,
                        outputText = result.outputText,
                        tokensGenerated = result.tokensGenerated,
                        latencyMs = result.totalLatencyMs,
                        isCoherent = result.sanityCheck.isCoherent
                    )
                }
            }
    }
}

/**
 * Sample output for JSON export.
 */
data class SampleOutput(
    val promptId: String,
    val promptText: String,
    val temperature: Float,
    val outputText: String,
    val tokensGenerated: Int,
    val latencyMs: Long,
    val isCoherent: Boolean
)
