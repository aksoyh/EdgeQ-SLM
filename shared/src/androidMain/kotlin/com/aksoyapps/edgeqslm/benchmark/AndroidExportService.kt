package com.aksoyapps.edgeqslm.benchmark

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Android implementation of ExportService.
 * Saves files to app-specific storage and supports sharing via Intent.
 */
class AndroidExportService(
    private val context: Context
) : ExportService {
    
    companion object {
        private const val RESULTS_DIR = "results"
    }
    
    private val resultsDir: File by lazy {
        File(context.filesDir, RESULTS_DIR).also { it.mkdirs() }
    }
    
    override suspend fun exportToCsv(
        results: List<RunResult>,
        filename: String
    ): String = withContext(Dispatchers.IO) {
        val file = File(resultsDir, filename)
        
        file.bufferedWriter().use { writer ->
            writer.write(ExportUtils.getRunResultCsvHeader())
            writer.newLine()
            
            results.forEach { result ->
                writer.write(ExportUtils.runResultToCsvRow(result))
                writer.newLine()
            }
        }
        
        file.absolutePath
    }
    
    override suspend fun exportAggregatedToCsv(
        stats: List<AggregatedStats>,
        filename: String
    ): String = withContext(Dispatchers.IO) {
        val file = File(resultsDir, filename)
        
        file.bufferedWriter().use { writer ->
            writer.write(ExportUtils.getAggregatedStatsCsvHeader())
            writer.newLine()
            
            stats.forEach { stat ->
                writer.write(ExportUtils.aggregatedStatsToCsvRow(stat))
                writer.newLine()
            }
        }
        
        file.absolutePath
    }
    
    override suspend fun exportSanityChecksToCsv(
        results: List<RunResult>,
        filename: String
    ): String = withContext(Dispatchers.IO) {
        val file = File(resultsDir, filename)
        
        file.bufferedWriter().use { writer ->
            writer.write(ExportUtils.getSanityCheckCsvHeader())
            writer.newLine()
            
            results.filter { !it.config.isWarmup }.forEach { result ->
                writer.write(ExportUtils.sanityCheckToCsvRow(result))
                writer.newLine()
            }
        }
        
        file.absolutePath
    }
    
    override suspend fun exportSampleOutputs(
        results: List<RunResult>,
        maxSamplesPerCondition: Int,
        filename: String
    ): String = withContext(Dispatchers.IO) {
        val file = File(resultsDir, filename)
        
        val samples = ExportUtils.selectSampleOutputs(results, maxSamplesPerCondition)
        
        val jsonArray = JSONArray()
        samples.forEach { sample ->
            val obj = JSONObject().apply {
                put("prompt_id", sample.promptId)
                put("prompt_text", sample.promptText)
                put("temperature", sample.temperature)
                put("output_text", sample.outputText)
                put("tokens_generated", sample.tokensGenerated)
                put("latency_ms", sample.latencyMs)
                put("is_coherent", sample.isCoherent)
            }
            jsonArray.put(obj)
        }
        
        file.writeText(jsonArray.toString(2))
        file.absolutePath
    }
    
    override suspend fun exportMetadata(
        metadata: BenchmarkMetadata,
        filename: String
    ): String = withContext(Dispatchers.IO) {
        val file = File(resultsDir, filename)
        
        val json = JSONObject().apply {
            put("session_id", metadata.sessionId)
            put("start_timestamp", metadata.startTimestamp)
            put("end_timestamp", metadata.endTimestamp)
            put("app_version", metadata.appVersion)
            put("model_id", metadata.modelId)
            put("model_path", metadata.modelPath)
            put("quantization_variant", metadata.quantizationVariant.name)
            metadata.gitCommitHash?.let { put("git_commit_hash", it) }
            
            put("device_info", JSONObject().apply {
                put("model", metadata.deviceInfo.model)
                put("manufacturer", metadata.deviceInfo.manufacturer)
                put("os_version", metadata.deviceInfo.osVersion)
                put("sdk_version", metadata.deviceInfo.sdkVersion)
                put("abi", metadata.deviceInfo.abi)
                put("cpu_cores", metadata.deviceInfo.cpuCores)
                put("total_ram_mb", metadata.deviceInfo.totalRamMb)
                put("available_ram_mb", metadata.deviceInfo.availableRamMb)
            })
            
            put("temperature_sweep", JSONArray(metadata.temperatureSweep))
            put("repeats_per_condition", metadata.repeatsPerCondition)
            put("warmup_runs", metadata.warmupRuns)
            put("prompt_count", metadata.promptCount)
            put("total_runs", metadata.totalRuns)
            put("successful_runs", metadata.successfulRuns)
            put("failed_runs", metadata.failedRuns)
        }
        
        file.writeText(json.toString(2))
        file.absolutePath
    }
    
    override suspend fun exportDeviceInfo(
        deviceInfo: DeviceInfo,
        filename: String
    ): String = withContext(Dispatchers.IO) {
        val file = File(resultsDir, filename)
        file.writeText(ExportUtils.deviceInfoToText(deviceInfo))
        file.absolutePath
    }
    
    override fun getExportDirectory(): String {
        return resultsDir.absolutePath
    }
    
    override suspend fun shareExports(filePaths: List<String>) = withContext(Dispatchers.Main) {
        val uris = filePaths.mapNotNull { path ->
            try {
                val file = File(path)
                if (file.exists()) {
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                } else null
            } catch (e: Exception) {
                null
            }
        }
        
        if (uris.isNotEmpty()) {
            val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            val chooser = Intent.createChooser(shareIntent, "Export Benchmark Results")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        }
    }
    
    /**
     * Export all benchmark data to files.
     * Returns a list of created file paths.
     */
    suspend fun exportAll(
        results: List<RunResult>,
        aggregatedStats: List<AggregatedStats>,
        metadata: BenchmarkMetadata
    ): List<String> {
        // Format: yyyyMMdd_HHmmss_SSS (date_time_milliseconds)
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val paths = mutableListOf<String>()
        
        paths.add(exportToCsv(results, "latency_memory_cpu_$timestamp.csv"))
        paths.add(exportAggregatedToCsv(aggregatedStats, "aggregated_stats_$timestamp.csv"))
        paths.add(exportSanityChecksToCsv(results, "sanity_checks_$timestamp.csv"))
        paths.add(exportSampleOutputs(results, filename = "sample_outputs_$timestamp.json"))
        paths.add(exportMetadata(metadata, "run_metadata_$timestamp.json"))
        paths.add(exportDeviceInfo(metadata.deviceInfo, "device_info_$timestamp.txt"))
        
        return paths
    }
    
    /**
     * Get list of previous export sessions.
     * Returns list of session info sorted by date (newest first).
     */
    fun getPreviousExports(): List<ExportSession> {
        val sessions = mutableMapOf<String, MutableList<File>>()
        
        resultsDir.listFiles()?.forEach { file ->
            // Extract timestamp from filename (e.g., "latency_memory_cpu_20260126_175030_123.csv")
            val nameWithoutExt = file.nameWithoutExtension
            val parts = nameWithoutExt.split("_")
            
            // Find the timestamp pattern (yyyyMMdd_HHmmss_SSS)
            if (parts.size >= 3) {
                // Look for date pattern at the end
                val timestampParts = parts.takeLast(3)
                if (timestampParts[0].length == 8 && timestampParts[1].length == 6) {
                    val timestamp = "${timestampParts[0]}_${timestampParts[1]}_${timestampParts[2]}"
                    sessions.getOrPut(timestamp) { mutableListOf() }.add(file)
                }
            }
        }
        
        return sessions.map { (timestamp, files) ->
            // Parse timestamp to readable format
            val readableDate = try {
                val inputFormat = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
                val outputFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                val date = inputFormat.parse(timestamp)
                outputFormat.format(date!!)
            } catch (e: Exception) {
                timestamp
            }
            
            ExportSession(
                timestamp = timestamp,
                displayDate = readableDate,
                files = files.map { it.absolutePath },
                fileCount = files.size,
                totalSizeBytes = files.sumOf { it.length() }
            )
        }.sortedByDescending { it.timestamp }
    }
    
    /**
     * Delete an export session.
     */
    fun deleteExportSession(timestamp: String) {
        resultsDir.listFiles()?.forEach { file ->
            if (file.name.contains(timestamp)) {
                file.delete()
            }
        }
    }
}

/**
 * Represents a previous export session.
 */
data class ExportSession(
    val timestamp: String,
    val displayDate: String,
    val files: List<String>,
    val fileCount: Int,
    val totalSizeBytes: Long
) {
    val totalSizeKb: Float get() = totalSizeBytes / 1024f
}
