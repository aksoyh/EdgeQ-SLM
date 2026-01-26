package com.aksoyapps.edgeqslm.benchmark

import com.aksoyapps.edgeqslm.GenerationRequest
import com.aksoyapps.edgeqslm.LlmEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Orchestrates benchmark experiment runs.
 * Manages the execution of prompts × temperatures × repeats matrix.
 */
class BenchmarkRunner(
    private val engine: LlmEngine,
    private val metricsProvider: SystemMetricsProvider,
    private val onResultSaved: suspend (RunResult) -> Unit = {}
) {
    
    companion object {
        private const val TAG = "BenchmarkRunner"
    }
    
    private var currentJob: Job? = null
    private var isPaused = false
    
    /**
     * Run the complete benchmark suite.
     * Emits progress updates and final results.
     */
    fun runBenchmark(
        config: BenchmarkConfig,
        prompts: List<BenchmarkPrompt> = PromptSet.ALL_PROMPTS
    ): Flow<BenchmarkEvent> = flow {
        val startTime = System.currentTimeMillis()
        val results = mutableListOf<RunResult>()
        
        // Calculate total runs: prompts × temps × tokens × repeats
        val totalRuns = prompts.size * config.temperatures.size * config.maxTokensSweep.size * config.repeatsPerCondition
        val totalWithWarmup = totalRuns + config.warmupRuns
        var completedRuns = 0
        
        emit(BenchmarkEvent.Started(totalWithWarmup))
        
        try {
            // Warmup runs (excluded from results)
            if (config.warmupRuns > 0) {
                emit(BenchmarkEvent.WarmupStarted)
                
                val warmupPrompt = prompts.first()
                repeat(config.warmupRuns) { warmupIndex ->
                    if (isPaused) {
                        emit(BenchmarkEvent.Paused)
                        while (isPaused) {
                            delay(100)
                        }
                        emit(BenchmarkEvent.Resumed)
                    }
                    
                    val warmupConfig = RunConfig(
                        promptId = warmupPrompt.id,
                        promptText = warmupPrompt.text,
                        promptCategory = warmupPrompt.category,
                        temperature = config.temperatures.first(),
                        maxTokens = config.maxTokensSweep.first(),
                        topP = config.topP,
                        repeatPenalty = config.repeatPenalty,
                        repeatIndex = warmupIndex,
                        isWarmup = true,
                        engineVariant = config.engineVariant
                    )
                    
                    runSingleBenchmark(warmupConfig, warmupPrompt.expectedMinTokens)
                    completedRuns++
                    
                    emitProgress(
                        emit = { emit(it) },
                        promptIndex = 0,
                        totalPrompts = prompts.size,
                        tempIndex = 0,
                        totalTemps = config.temperatures.size,
                        tokenIndex = 0,
                        totalTokens = config.maxTokensSweep.size,
                        repeat = warmupIndex,
                        totalRepeats = config.warmupRuns,
                        promptId = warmupPrompt.id,
                        temperature = config.temperatures.first(),
                        maxTokens = config.maxTokensSweep.first(),
                        isWarmup = true,
                        completed = completedRuns,
                        total = totalWithWarmup,
                        startTime = startTime
                    )
                }
                
                emit(BenchmarkEvent.WarmupCompleted)
            }
            
            // Main benchmark runs: prompts × temps × tokens × repeats
            prompts.forEachIndexed { promptIndex, prompt ->
                config.temperatures.forEachIndexed { tempIndex, temperature ->
                    config.maxTokensSweep.forEachIndexed { tokenIndex, maxTokens ->
                        repeat(config.repeatsPerCondition) { repeatIndex ->
                            // Check for pause
                            if (isPaused) {
                                emit(BenchmarkEvent.Paused)
                                while (isPaused) {
                                    delay(100)
                                }
                                emit(BenchmarkEvent.Resumed)
                            }
                            
                            val runConfig = RunConfig(
                                promptId = prompt.id,
                                promptText = prompt.text,
                                promptCategory = prompt.category,
                                temperature = temperature,
                                maxTokens = maxTokens,
                                topP = config.topP,
                                repeatPenalty = config.repeatPenalty,
                                repeatIndex = repeatIndex,
                                isWarmup = false,
                                engineVariant = config.engineVariant
                            )
                            
                            val result = runSingleBenchmark(runConfig, prompt.expectedMinTokens)
                            results.add(result)
                            completedRuns++
                            
                            // Save incrementally
                            onResultSaved(result)
                            
                            emit(BenchmarkEvent.RunCompleted(result))
                            
                            emitProgress(
                                emit = { emit(it) },
                                promptIndex = promptIndex,
                                totalPrompts = prompts.size,
                                tempIndex = tempIndex,
                                totalTemps = config.temperatures.size,
                                tokenIndex = tokenIndex,
                                totalTokens = config.maxTokensSweep.size,
                                repeat = repeatIndex,
                                totalRepeats = config.repeatsPerCondition,
                                promptId = prompt.id,
                                temperature = temperature,
                                maxTokens = maxTokens,
                                isWarmup = false,
                                completed = completedRuns,
                                total = totalWithWarmup,
                                startTime = startTime
                            )
                        }
                    }
                }
            }
            
            // Calculate aggregated stats
            val aggregatedStats = aggregateResults(results, config)
            
            val endTime = System.currentTimeMillis()
            emit(BenchmarkEvent.Completed(results, aggregatedStats, endTime - startTime))
            
        } catch (e: CancellationException) {
            emit(BenchmarkEvent.Cancelled(results))
            throw e
        } catch (e: Exception) {
            emit(BenchmarkEvent.Error(e.message ?: "Unknown error", results))
        }
    }.flowOn(Dispatchers.Default)
    
    /**
     * Run a single benchmark with metrics collection.
     */
    private suspend fun runSingleBenchmark(
        config: RunConfig,
        expectedMinTokens: Int
    ): RunResult {
        val timestamp = System.currentTimeMillis()
        
        // Start monitoring
        metricsProvider.startCpuMonitoring()
        val memoryBefore = metricsProvider.getMemoryUsage()
        
        return try {
            val request = GenerationRequest(
                prompt = config.promptText,
                maxTokens = config.maxTokens,
                temperature = config.temperature,
                topP = config.topP,
                repeatPenalty = config.repeatPenalty,
                useChatTemplate = true
            )
            
            val result = engine.generate(request)
            
            // Stop monitoring
            val cpuReading = metricsProvider.stopCpuMonitoring()
            val memoryAfter = metricsProvider.getMemoryUsage()
            
            // Sanity check
            val sanityCheck = SanityChecker.check(result.text, expectedMinTokens)
            
            RunResult(
                config = config,
                timestamp = timestamp,
                totalLatencyMs = result.latencyMs,
                prefillTimeMs = result.prefillTimeMs,
                decodeTimeMs = result.decodeTimeMs,
                tokensGenerated = result.tokensGenerated,
                tokensPerSecond = result.tokensPerSecond,
                peakMemoryMb = maxOf(memoryBefore.peakBytes, memoryAfter.peakBytes) / (1024f * 1024f),
                avgMemoryMb = (memoryBefore.usedBytes + memoryAfter.usedBytes) / 2f / (1024f * 1024f),
                memoryMethod = memoryAfter.method,
                cpuUtilizationPercent = cpuReading.utilizationPercent,
                cpuMethod = cpuReading.method,
                outputText = result.text,
                outputCharCount = result.text.length,
                outputTokenCount = result.tokensGenerated,
                sanityCheck = sanityCheck,
                success = true
            )
        } catch (e: Exception) {
            // Stop monitoring even on error
            val cpuReading = metricsProvider.stopCpuMonitoring()
            val memoryAfter = metricsProvider.getMemoryUsage()
            
            RunResult(
                config = config,
                timestamp = timestamp,
                totalLatencyMs = 0,
                prefillTimeMs = 0,
                decodeTimeMs = 0,
                tokensGenerated = 0,
                tokensPerSecond = 0f,
                peakMemoryMb = memoryAfter.peakBytes / (1024f * 1024f),
                avgMemoryMb = memoryAfter.usedBytes / (1024f * 1024f),
                memoryMethod = memoryAfter.method,
                cpuUtilizationPercent = cpuReading.utilizationPercent,
                cpuMethod = cpuReading.method,
                outputText = "",
                outputCharCount = 0,
                outputTokenCount = 0,
                sanityCheck = SanityCheckResult(
                    isEmpty = true,
                    charCount = 0,
                    estimatedTokenCount = 0,
                    repetitionScore = 0f,
                    repeatedNGramCount = 0,
                    isCoherent = false,
                    coherenceReason = "Error: ${e.message}"
                ),
                error = e.message,
                success = false
            )
        }
    }
    
    /**
     * Aggregate results by (promptId, temperature, maxTokens, variant).
     */
    private fun aggregateResults(
        results: List<RunResult>,
        config: BenchmarkConfig
    ): List<AggregatedStats> {
        // Custom data class for 4-tuple key
        data class AggKey(val promptId: String, val temp: Float, val tokens: Int, val variant: EngineVariant)
        
        return results
            .filter { !it.config.isWarmup && it.success }
            .groupBy { AggKey(it.config.promptId, it.config.temperature, it.config.maxTokens, it.config.engineVariant) }
            .map { (key, runs) ->
                val category = runs.first().config.promptCategory
                
                AggregatedStats(
                    promptId = key.promptId,
                    promptCategory = category,
                    temperature = key.temp,
                    maxTokens = key.tokens,
                    engineVariant = key.variant,
                    runCount = runs.size,
                    latencyMsMean = runs.map { it.totalLatencyMs.toFloat() }.average().toFloat(),
                    latencyMsStd = runs.map { it.totalLatencyMs.toFloat() }.standardDeviation(),
                    ttftMsMean = runs.map { it.prefillTimeMs.toFloat() }.average().toFloat(),
                    ttftMsStd = runs.map { it.prefillTimeMs.toFloat() }.standardDeviation(),
                    decodeMsMean = runs.map { it.decodeTimeMs.toFloat() }.average().toFloat(),
                    decodeMsStd = runs.map { it.decodeTimeMs.toFloat() }.standardDeviation(),
                    tokensPerSecMean = runs.map { it.tokensPerSecond }.average().toFloat(),
                    tokensPerSecStd = runs.map { it.tokensPerSecond }.standardDeviation(),
                    memoryMbMean = runs.map { it.peakMemoryMb }.average().toFloat(),
                    memoryMbStd = runs.map { it.peakMemoryMb }.standardDeviation(),
                    cpuPercentMean = runs.map { it.cpuUtilizationPercent }.average().toFloat(),
                    cpuPercentStd = runs.map { it.cpuUtilizationPercent }.standardDeviation(),
                    tokensGeneratedMean = runs.map { it.tokensGenerated.toFloat() }.average().toFloat(),
                    tokensGeneratedStd = runs.map { it.tokensGenerated.toFloat() }.standardDeviation(),
                    emptyOutputCount = runs.count { it.sanityCheck.isEmpty },
                    highRepetitionCount = runs.count { it.sanityCheck.repetitionScore > 0.3f },
                    successRate = runs.count { it.sanityCheck.isCoherent }.toFloat() / runs.size
                )
            }
    }
    
    private suspend fun emitProgress(
        emit: suspend (BenchmarkEvent) -> Unit,
        promptIndex: Int,
        totalPrompts: Int,
        tempIndex: Int,
        totalTemps: Int,
        tokenIndex: Int,
        totalTokens: Int,
        repeat: Int,
        totalRepeats: Int,
        promptId: String,
        temperature: Float,
        maxTokens: Int,
        isWarmup: Boolean,
        completed: Int,
        total: Int,
        startTime: Long
    ) {
        val elapsed = System.currentTimeMillis() - startTime
        val estimatedRemaining = if (completed > 0) {
            (elapsed.toFloat() / completed * (total - completed)).toLong()
        } else {
            0L
        }
        
        emit(BenchmarkEvent.Progress(
            BenchmarkProgress(
                currentPromptIndex = promptIndex,
                totalPrompts = totalPrompts,
                currentTemperatureIndex = tempIndex,
                totalTemperatures = totalTemps,
                currentTokenIndex = tokenIndex,
                totalTokens = totalTokens,
                currentRepeat = repeat,
                totalRepeats = totalRepeats,
                currentPromptId = promptId,
                currentTemperature = temperature,
                currentMaxTokens = maxTokens,
                isWarmup = isWarmup,
                completedRuns = completed,
                totalRuns = total,
                elapsedTimeMs = elapsed,
                estimatedRemainingMs = estimatedRemaining
            )
        ))
    }
    
    fun pause() {
        isPaused = true
    }
    
    fun resume() {
        isPaused = false
    }
    
    fun cancel() {
        currentJob?.cancel()
    }
}

/**
 * Events emitted by the benchmark runner.
 */
sealed class BenchmarkEvent {
    data class Started(val totalRuns: Int) : BenchmarkEvent()
    data object WarmupStarted : BenchmarkEvent()
    data object WarmupCompleted : BenchmarkEvent()
    data class Progress(val progress: BenchmarkProgress) : BenchmarkEvent()
    data class RunCompleted(val result: RunResult) : BenchmarkEvent()
    data object Paused : BenchmarkEvent()
    data object Resumed : BenchmarkEvent()
    data class Completed(
        val results: List<RunResult>,
        val aggregatedStats: List<AggregatedStats>,
        val totalTimeMs: Long
    ) : BenchmarkEvent()
    data class Cancelled(val partialResults: List<RunResult>) : BenchmarkEvent()
    data class Error(val message: String, val partialResults: List<RunResult>) : BenchmarkEvent()
}

/**
 * Extension to calculate standard deviation.
 */
private fun List<Float>.standardDeviation(): Float {
    if (size < 2) return 0f
    val mean = average().toFloat()
    val variance = map { (it - mean) * (it - mean) }.average().toFloat()
    return kotlin.math.sqrt(variance)
}
