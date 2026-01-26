package com.aksoyapps.edgeqslm.benchmark

import com.aksoyapps.edgeqslm.LlmEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * ViewModel for the Benchmark & Results screen.
 * Manages benchmark execution, results, and export.
 */
class BenchmarkViewModel(
    private val engine: LlmEngine,
    private val metricsProvider: SystemMetricsProvider,
    private val exportService: ExportService,
    private val appVersion: String = "1.0.0",
    private val modelPath: String = ""
) {
    private val _uiState = MutableStateFlow(BenchmarkUiState())
    val uiState: StateFlow<BenchmarkUiState> = _uiState.asStateFlow()
    
    private val scope = CoroutineScope(Dispatchers.Main)
    private var benchmarkJob: Job? = null
    private var runner: BenchmarkRunner? = null
    
    private val allResults = mutableListOf<RunResult>()
    
    /**
     * Start the benchmark with given configuration.
     */
    fun startBenchmark(config: BenchmarkConfig = BenchmarkConfig()) {
        if (_uiState.value.isRunning) return
        
        allResults.clear()
        
        runner = BenchmarkRunner(
            engine = engine,
            metricsProvider = metricsProvider,
            onResultSaved = { result ->
                // Incremental save would happen here
            }
        )
        
        _uiState.value = _uiState.value.copy(
            isRunning = true,
            isPaused = false,
            isCompleted = false,
            error = null,
            config = config,
            results = emptyList(),
            aggregatedStats = emptyList(),
            exportPath = null
        )
        
        benchmarkJob = scope.launch {
            runner?.runBenchmark(config)?.collect { event ->
                handleBenchmarkEvent(event)
            }
        }
    }
    
    /**
     * Start a quick test with subset of prompts.
     */
    fun startQuickTest() {
        val quickConfig = BenchmarkConfig(
            temperatures = listOf(0.7f),
            repeatsPerCondition = 1,
            warmupRuns = 0
        )
        startBenchmarkWithPrompts(quickConfig, PromptSet.getQuickTestSet())
    }
    
    /**
     * Start benchmark with custom prompt set.
     */
    fun startBenchmarkWithPrompts(
        config: BenchmarkConfig,
        prompts: List<BenchmarkPrompt>
    ) {
        if (_uiState.value.isRunning) return
        
        allResults.clear()
        
        runner = BenchmarkRunner(
            engine = engine,
            metricsProvider = metricsProvider
        )
        
        _uiState.value = _uiState.value.copy(
            isRunning = true,
            isPaused = false,
            isCompleted = false,
            error = null,
            config = config,
            results = emptyList(),
            aggregatedStats = emptyList()
        )
        
        benchmarkJob = scope.launch {
            runner?.runBenchmark(config, prompts)?.collect { event ->
                handleBenchmarkEvent(event)
            }
        }
    }
    
    private fun handleBenchmarkEvent(event: BenchmarkEvent) {
        when (event) {
            is BenchmarkEvent.Started -> {
                _uiState.value = _uiState.value.copy(isRunning = true)
            }
            
            is BenchmarkEvent.WarmupStarted -> {
                // Warmup indication could be added to UI
            }
            
            is BenchmarkEvent.WarmupCompleted -> {
                // Warmup complete
            }
            
            is BenchmarkEvent.Progress -> {
                _uiState.value = _uiState.value.copy(progress = event.progress)
            }
            
            is BenchmarkEvent.RunCompleted -> {
                allResults.add(event.result)
                _uiState.value = _uiState.value.copy(results = allResults.toList())
            }
            
            is BenchmarkEvent.Paused -> {
                _uiState.value = _uiState.value.copy(isPaused = true)
            }
            
            is BenchmarkEvent.Resumed -> {
                _uiState.value = _uiState.value.copy(isPaused = false)
            }
            
            is BenchmarkEvent.Completed -> {
                _uiState.value = _uiState.value.copy(
                    isRunning = false,
                    isCompleted = true,
                    results = event.results,
                    aggregatedStats = event.aggregatedStats
                )
            }
            
            is BenchmarkEvent.Cancelled -> {
                _uiState.value = _uiState.value.copy(
                    isRunning = false,
                    results = event.partialResults
                )
            }
            
            is BenchmarkEvent.Error -> {
                _uiState.value = _uiState.value.copy(
                    isRunning = false,
                    error = event.message,
                    results = event.partialResults
                )
            }
        }
    }
    
    /**
     * Pause the running benchmark.
     */
    fun pauseBenchmark() {
        runner?.pause()
    }
    
    /**
     * Resume the paused benchmark.
     */
    fun resumeBenchmark() {
        runner?.resume()
    }
    
    /**
     * Cancel the running benchmark.
     */
    fun cancelBenchmark() {
        benchmarkJob?.cancel()
        runner?.cancel()
        _uiState.value = _uiState.value.copy(
            isRunning = false,
            isPaused = false
        )
    }
    
    /**
     * Export results to files.
     */
    fun exportResults() {
        val results = _uiState.value.results
        val stats = _uiState.value.aggregatedStats
        
        if (results.isEmpty()) {
            _uiState.value = _uiState.value.copy(error = "No results to export")
            return
        }
        
        scope.launch {
            try {
                val deviceInfo = metricsProvider.getDeviceInfo()
                val config = _uiState.value.config
                
                val metadata = BenchmarkMetadata(
                    sessionId = UUID.randomUUID().toString(),
                    startTimestamp = results.minOfOrNull { it.timestamp } ?: 0L,
                    endTimestamp = results.maxOfOrNull { it.timestamp } ?: 0L,
                    appVersion = appVersion,
                    modelId = modelPath.substringAfterLast("/"),
                    modelPath = modelPath,
                    quantizationVariant = config.engineVariant,
                    deviceInfo = deviceInfo,
                    temperatureSweep = config.temperatures,
                    repeatsPerCondition = config.repeatsPerCondition,
                    warmupRuns = config.warmupRuns,
                    promptCount = results.map { it.config.promptId }.distinct().size,
                    totalRuns = results.size,
                    successfulRuns = results.count { it.success },
                    failedRuns = results.count { !it.success }
                )
                
                val exportService = exportService as? AndroidExportService
                val paths = exportService?.exportAll(results, stats, metadata) ?: emptyList()
                
                _uiState.value = _uiState.value.copy(
                    exportPath = exportService?.getExportDirectory()
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Export failed: ${e.message}"
                )
            }
        }
    }
    
    /**
     * Share exported results via Android share sheet.
     * Exports files first if not already exported.
     */
    fun shareResults() {
        val results = _uiState.value.results
        val stats = _uiState.value.aggregatedStats
        
        if (results.isEmpty()) {
            _uiState.value = _uiState.value.copy(error = "No results to share. Run a benchmark first.")
            return
        }
        
        scope.launch {
            try {
                val androidExportService = exportService as? AndroidExportService
                if (androidExportService == null) {
                    _uiState.value = _uiState.value.copy(error = "Export service not available")
                    return@launch
                }
                
                // Always export fresh files before sharing
                val deviceInfo = metricsProvider.getDeviceInfo()
                val config = _uiState.value.config
                
                val metadata = BenchmarkMetadata(
                    sessionId = UUID.randomUUID().toString(),
                    startTimestamp = results.minOfOrNull { it.timestamp } ?: 0L,
                    endTimestamp = results.maxOfOrNull { it.timestamp } ?: 0L,
                    appVersion = appVersion,
                    modelId = modelPath.substringAfterLast("/"),
                    modelPath = modelPath,
                    quantizationVariant = config.engineVariant,
                    deviceInfo = deviceInfo,
                    temperatureSweep = config.temperatures,
                    repeatsPerCondition = config.repeatsPerCondition,
                    warmupRuns = config.warmupRuns,
                    promptCount = results.map { it.config.promptId }.distinct().size,
                    totalRuns = results.size,
                    successfulRuns = results.count { it.success },
                    failedRuns = results.count { !it.success }
                )
                
                // Export all files
                val exportedPaths = androidExportService.exportAll(results, stats, metadata)
                
                _uiState.value = _uiState.value.copy(
                    exportPath = androidExportService.getExportDirectory()
                )
                
                // Now share the exported files
                if (exportedPaths.isNotEmpty()) {
                    androidExportService.shareExports(exportedPaths)
                } else {
                    _uiState.value = _uiState.value.copy(error = "No files to share")
                }
                
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Share failed: ${e.message}"
                )
            }
        }
    }
    
    /**
     * Update benchmark configuration.
     */
    fun updateConfig(config: BenchmarkConfig) {
        if (!_uiState.value.isRunning) {
            _uiState.value = _uiState.value.copy(config = config)
        }
    }
    
    /**
     * Clear error message.
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
    
    /**
     * Get summary statistics for display.
     */
    fun getSummaryStats(): BenchmarkSummary? {
        val results = _uiState.value.results.filter { it.success && !it.config.isWarmup }
        if (results.isEmpty()) return null
        
        return BenchmarkSummary(
            totalRuns = results.size,
            successRate = results.count { it.sanityCheck.isCoherent }.toFloat() / results.size,
            avgLatencyMs = results.map { it.totalLatencyMs }.average().toFloat(),
            avgTtftMs = results.map { it.prefillTimeMs }.average().toFloat(),
            avgTokensPerSec = results.map { it.tokensPerSecond }.average().toFloat(),
            avgMemoryMb = results.map { it.peakMemoryMb }.average().toFloat(),
            avgCpuPercent = results.map { it.cpuUtilizationPercent }.average().toFloat()
        )
    }
    
    /**
     * Load list of previous export sessions.
     */
    fun loadPreviousExports() {
        val androidExportService = exportService as? AndroidExportService ?: return
        val sessions = androidExportService.getPreviousExports()
        _uiState.value = _uiState.value.copy(previousExports = sessions)
    }
    
    /**
     * Share a previous export session.
     */
    fun sharePreviousExport(session: ExportSession) {
        scope.launch {
            try {
                val androidExportService = exportService as? AndroidExportService
                if (androidExportService != null && session.files.isNotEmpty()) {
                    androidExportService.shareExports(session.files)
                } else {
                    _uiState.value = _uiState.value.copy(error = "No files to share")
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = "Share failed: ${e.message}")
            }
        }
    }
    
    /**
     * Delete a previous export session.
     */
    fun deletePreviousExport(session: ExportSession) {
        val androidExportService = exportService as? AndroidExportService ?: return
        androidExportService.deleteExportSession(session.timestamp)
        loadPreviousExports()  // Refresh list
    }
}

/**
 * Summary statistics for quick display.
 */
data class BenchmarkSummary(
    val totalRuns: Int,
    val successRate: Float,
    val avgLatencyMs: Float,
    val avgTtftMs: Float,
    val avgTokensPerSec: Float,
    val avgMemoryMb: Float,
    val avgCpuPercent: Float
)

