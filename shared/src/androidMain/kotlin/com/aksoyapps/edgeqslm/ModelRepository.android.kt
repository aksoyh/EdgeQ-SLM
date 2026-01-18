package com.aksoyapps.edgeqslm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Android implementation of ModelRepository.
 * Downloads model from Google Drive with progress tracking and notifications.
 */
actual class ModelRepository {
    
    private var androidContext: Context? = null
    
    /**
     * Initialize with Android context. Must be called before using repository.
     */
    fun init(context: Context) {
        this.androidContext = context
    }
    
    // HuggingFace direct download URL for Qwen 1.5 1.8B Chat Q8_0 model
    private val huggingFaceUrl = "https://huggingface.co/Qwen/Qwen1.5-1.8B-Chat-GGUF/resolve/main/qwen1_5-1_8b-chat-q8_0.gguf"
    private val huggingFaceFileName = "qwen1_5-1_8b-chat-q8_0.gguf"
    private val expectedFileSize = 1958304128L // ~1.82GB
    
    // Selected model path (can be changed by user)
    private var selectedModelPath: String? = null
    
    // Debug flag: force model not found
    var forceModelNotFound: Boolean = false
    
    // App's external files directory (no permissions needed)
    private val appFilesDir: File? get() = androidContext?.getExternalFilesDir(null)
    
    /**
     * Get the path to the currently selected model file
     */
    actual fun getModelPath(): String {
        // Return user-selected model if set
        selectedModelPath?.let { return it }
        
        // Otherwise find the first available model
        val appDir = appFilesDir
        if (appDir != null && appDir.exists()) {
            val ggufFiles = appDir.listFiles { file -> file.name.endsWith(".gguf") }
            if (!ggufFiles.isNullOrEmpty()) {
                val largest = ggufFiles.maxByOrNull { it.length() }
                if (largest != null && largest.length() > expectedFileSize / 2) {
                    return largest.absolutePath
                }
            }
        }
        
        // Default path for new downloads
        return File(appFilesDir ?: File("/sdcard"), huggingFaceFileName).absolutePath
    }
    
    /**
     * Set the selected model path
     */
    actual fun setSelectedModel(path: String) {
        selectedModelPath = path
        android.util.Log.d("ModelRepository", "Selected model: $path")
    }
    
    /**
     * Get list of available models in app directory
     */
    actual suspend fun getAvailableModels(): List<ModelInfo> {
        val models = mutableListOf<ModelInfo>()
        
        // Scan app files directory for .gguf files
        val appDir = appFilesDir
        if (appDir != null && appDir.exists()) {
            val ggufFiles = appDir.listFiles { file -> file.name.endsWith(".gguf") }
            ggufFiles?.forEach { file ->
                models.add(ModelInfo(
                    name = file.name,
                    path = file.absolutePath,
                    sizeBytes = file.length(),
                    isDownloadable = false
                ))
            }
        }
        
        // Add HuggingFace model as downloadable option if not already present
        val huggingFaceFile = File(appDir ?: File("/sdcard"), huggingFaceFileName)
        if (!huggingFaceFile.exists()) {
            models.add(ModelInfo(
                name = "📥 $huggingFaceFileName (HuggingFace)",
                path = huggingFaceFile.absolutePath,
                sizeBytes = expectedFileSize,
                isDownloadable = true
            ))
        }
        
        android.util.Log.d("ModelRepository", "Found ${models.size} models")
        return models
    }
    
    /**
     * Check if any model is available for loading
     */
    actual suspend fun isModelDownloaded(): Boolean {
        // Debug flag for testing download UI
        if (forceModelNotFound) {
            android.util.Log.d("ModelRepository", "⚠️ DEBUG: Forcing model not found")
            return false
        }
        
        // Check if any .gguf file exists in app directory
        val appDir = appFilesDir
        if (appDir != null && appDir.exists()) {
            val ggufFiles = appDir.listFiles { file -> 
                file.name.endsWith(".gguf") && file.length() > expectedFileSize / 2
            }
            if (!ggufFiles.isNullOrEmpty()) {
                android.util.Log.d("ModelRepository", "Model found: ${ggufFiles.first().name}")
                return true
            }
        }
        
        android.util.Log.d("ModelRepository", "No model found")
        return false
    }
    
    /**
     * Download model from HuggingFace with progress tracking using native HttpURLConnection
     */
    actual fun downloadModel(): Flow<DownloadStatus> = flow {
        val context = androidContext ?: run {
            emit(DownloadStatus.Error("Context not initialized. Call init() first."))
            return@flow
        }
        
        val appDir = appFilesDir ?: run {
            emit(DownloadStatus.Error("Cannot determine storage path"))
            return@flow
        }
        
        val targetFile = File(appDir, huggingFaceFileName)
        var connection: HttpURLConnection? = null

        try {
            // Setup Notification Channel
            createNotificationChannel(context)
            val notificationManager = NotificationManagerCompat.from(context)
            val notificationId = 1001
            val builder = NotificationCompat.Builder(context, "model_download")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Downloading AI Model")
                .setContentText("Connecting...")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setProgress(100, 0, true)

            try {
                notificationManager.notify(notificationId, builder.build())
            } catch (e: SecurityException) {
                android.util.Log.w("ModelRepository", "Notification permission not granted")
            }

            android.util.Log.d("ModelRepository", "Starting download from: $huggingFaceUrl")
            
            // Use native HttpURLConnection for reliable downloads
            val url = URL(huggingFaceUrl)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 30000
            connection.readTimeout = 60000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "EdgeQ-SLM/1.0 Android")
            connection.connect()
            
            val responseCode = connection.responseCode
            android.util.Log.d("ModelRepository", "Response code: $responseCode")
            
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("Server returned HTTP $responseCode")
            }
            
            val totalBytes = connection.contentLengthLong.takeIf { it > 0 } ?: expectedFileSize
            android.util.Log.d("ModelRepository", "Total bytes: $totalBytes")
            
            val tempFile = File(targetFile.parent, "${targetFile.name}.tmp")
            val inputStream = BufferedInputStream(connection.inputStream, 8192)
            val outputStream = FileOutputStream(tempFile)
            
            var bytesCopied = 0L
            val buffer = ByteArray(8192)
            var lastUpdate = 0L
            var lastBytesCopied = 0L
            
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                bytesCopied += bytesRead
                
                val now = System.currentTimeMillis()
                if (now - lastUpdate > 500) {
                    val progress = (bytesCopied.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                    
                    // Calculate speed (bytes per second)
                    val elapsedSec = (now - lastUpdate) / 1000.0
                    val bytesInInterval = bytesCopied - lastBytesCopied
                    val speedBytesPerSec = if (elapsedSec > 0) (bytesInInterval / elapsedSec).toLong() else 0L
                    
                    emit(DownloadStatus.Progress(
                        progress = progress,
                        downloadedBytes = bytesCopied,
                        totalBytes = totalBytes,
                        speedBytesPerSec = speedBytesPerSec
                    ))
                    
                    // Update notification with speed
                    val downloadedMB = bytesCopied / (1024 * 1024)
                    val totalMB = totalBytes / (1024 * 1024)
                    val speedMbps = (speedBytesPerSec * 8) / (1024 * 1024.0)
                    builder.setProgress(100, (progress * 100).toInt(), false)
                    builder.setContentText("${(progress * 100).toInt()}% - ${downloadedMB}MB / ${totalMB}MB - ${"%.1f".format(speedMbps)} Mbps")
                    try { notificationManager.notify(notificationId, builder.build()) } catch (e: SecurityException) {}
                    
                    lastUpdate = now
                    lastBytesCopied = bytesCopied
                }
            }
            
            outputStream.flush()
            outputStream.close()
            inputStream.close()
            
            // Rename temp file to final
            if (targetFile.exists()) targetFile.delete()
            val renamed = tempFile.renameTo(targetFile)
            
            android.util.Log.d("ModelRepository", "Download complete: ${targetFile.absolutePath}, renamed: $renamed")
            
            // Success notification
            builder.setContentText("Download completed")
                .setProgress(0, 0, false)
                .setOngoing(false)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
            try { notificationManager.notify(notificationId, builder.build()) } catch (e: SecurityException) {}
            
            // Refresh available models
            emit(DownloadStatus.Completed)
            
        } catch (e: Exception) {
            android.util.Log.e("ModelRepository", "Download error: ${e.message}", e)
            emit(DownloadStatus.Error(e.message ?: "Unknown download error"))
        } finally {
            connection?.disconnect()
        }
    }.flowOn(Dispatchers.IO)
    
    private fun extractConfirmUrl(html: String): String? {
        // Try to find the confirm download URL in Google Drive's virus scan page
        val regex = """href="(/uc\?export=download[^"]+)"""".toRegex()
        val match = regex.find(html)
        return match?.groupValues?.get(1)?.let { "https://drive.google.com$it".replace("&amp;", "&") }
    }
    
    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "model_download",
                "Model Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifications for AI model downloads"
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
