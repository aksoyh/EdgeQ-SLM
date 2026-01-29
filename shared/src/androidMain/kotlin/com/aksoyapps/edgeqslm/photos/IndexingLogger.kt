package com.aksoyapps.edgeqslm.photos

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Handles logging of indexing activities to CSV files for thesis data collection.
 */
class IndexingLogger(private val context: Context) {
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val logsDir: File
        get() = File(context.getExternalFilesDir(null), "results").also { it.mkdirs() }
        
    private val sessionCsvFile: File
        get() = File(logsDir, "indexing_sessions.csv")
        
    private val itemCsvFile: File
        get() = File(logsDir, "indexing_item_details.csv")

    init {
        initSessionLog()
        initItemLog()
    }

    private fun initSessionLog() {
        if (!sessionCsvFile.exists()) {
            FileWriter(sessionCsvFile, true).use { writer ->
                writer.append("Timestamp,Type,DurationMs,TotalItems,Success,Failed,FolderPath,AvgLatencyPerItem\n")
            }
        }
    }

    private fun initItemLog() {
        if (!itemCsvFile.exists()) {
            FileWriter(itemCsvFile, true).use { writer ->
                writer.append("Timestamp,Type,FileName,LatencyMs,ImageSizeBytes,Success,Error\n")
            }
        }
    }

    fun logSession(log: IndexingSessionLog) {
        try {
            val dateStr = dateFormat.format(Date(log.timestamp))
            val avgLatency = if (log.totalItems > 0 && log.durationMs > 0) 
                log.durationMs / log.totalItems 
            else 0
            
            val line = "$dateStr,${log.type},${log.durationMs},${log.totalItems},${log.successItems},${log.failedItems},\"${log.folderPath}\",$avgLatency\n"
            
            FileWriter(sessionCsvFile, true).use { writer ->
                writer.append(line)
            }
            android.util.Log.i("IndexingLogger", "Session logged: $line")
        } catch (e: Exception) {
            android.util.Log.e("IndexingLogger", "Error logging session: ${e.message}")
        }
    }

    fun logItem(log: ItemIndexingLog) {
        try {
            val dateStr = dateFormat.format(Date(log.timestamp))
            val errorStr = log.error?.replace(",", ";")?.replace("\n", " ") ?: ""
            
            val line = "$dateStr,${log.type},\"${log.fileName}\",${log.latencyMs},${log.imageSize},${log.success},\"$errorStr\"\n"
            
            FileWriter(itemCsvFile, true).use { writer ->
                writer.append(line)
            }
        } catch (e: Exception) {
            android.util.Log.e("IndexingLogger", "Error logging item: ${e.message}")
        }
    }
}
