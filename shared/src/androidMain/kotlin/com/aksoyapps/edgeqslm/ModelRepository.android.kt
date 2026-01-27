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
    
    // ========== TEXT-ONLY MODEL: Qwen 1.5 1.8B Chat ==========
    private val textModelUrl = "https://huggingface.co/Qwen/Qwen1.5-1.8B-Chat-GGUF/resolve/main/qwen1_5-1_8b-chat-q8_0.gguf"
    private val textModelFileName = "qwen1_5-1_8b-chat-q8_0.gguf"
    private val textModelSize = 1958304128L // ~1.82GB
    
    // ========== VISION MODEL: Qwen2.5-VL-3B ==========
    private val visionModelUrl = "https://huggingface.co/Mungert/Qwen2.5-VL-3B-Instruct-GGUF/resolve/main/Qwen2.5-VL-3B-Instruct-q4_k_m.gguf"
    private val visionModelFileName = "Qwen2.5-VL-3B-Instruct-q4_k_m.gguf"
    private val visionModelSize = 1929902656L // ~1.93GB
    private val visionProjectorUrl = "https://huggingface.co/Mungert/Qwen2.5-VL-3B-Instruct-GGUF/resolve/main/Qwen2.5-VL-3B-Instruct-mmproj-f16.gguf"
    private val visionProjectorFileName = "Qwen2.5-VL-3B-Instruct-mmproj-f16.gguf"
    private val visionProjectorSize = 1338428640L // ~1.34GB
    
    // Legacy reference for backwards compatibility
    private val huggingFaceUrl = textModelUrl
    private val huggingFaceFileName = textModelFileName
    private val expectedFileSize = textModelSize
    
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
        val appDir = appFilesDir
        
        // Scan app files directory for .gguf files (already downloaded)
        if (appDir != null && appDir.exists()) {
            val ggufFiles = appDir.listFiles { file -> file.name.endsWith(".gguf") }
            ggufFiles?.forEach { file ->
                // Determine model type based on filename
                val isVisionModel = file.name.contains("VL", ignoreCase = true) || 
                                   file.name.contains("vision", ignoreCase = true)
                val isProjector = file.name.contains("mmproj", ignoreCase = true)
                
                // Skip projector files from main list
                if (!isProjector) {
                    val typeEmoji = if (isVisionModel) "👁️" else "📝"
                    models.add(ModelInfo(
                        name = "$typeEmoji ${file.name}",
                        path = file.absolutePath,
                        sizeBytes = file.length(),
                        isDownloadable = false,
                        modelType = if (isVisionModel) ModelType.VISION_TEXT else ModelType.TEXT_ONLY,
                        description = if (isVisionModel) "Vision + Text" else "Text Only"
                    ))
                }
            }
        }
        
        // Add downloadable models if not already present
        val baseDir = appDir ?: File("/sdcard")
        
        // 1. Text-only model (Qwen 1.5 1.8B)
        val textFile = File(baseDir, textModelFileName)
        if (!textFile.exists()) {
            models.add(ModelInfo(
                name = "📥 📝 Qwen 1.5 1.8B Chat (Text Only)",
                path = textFile.absolutePath,
                sizeBytes = textModelSize,
                isDownloadable = true,
                modelType = ModelType.TEXT_ONLY,
                description = "Text generation only • Q8_0 • 1.82GB",
                downloadUrl = textModelUrl
            ))
        }
        
        // 2. Vision model (Qwen2.5-VL-3B)
        val visionFile = File(baseDir, visionModelFileName)
        if (!visionFile.exists()) {
            models.add(ModelInfo(
                name = "📥 👁️ Qwen2.5-VL-3B (Vision + Text)",
                path = visionFile.absolutePath,
                sizeBytes = visionModelSize + visionProjectorSize, // Total size with projector
                isDownloadable = true,
                modelType = ModelType.VISION_TEXT,
                description = "Vision + Text • Q4_K_M • 2.5GB total",
                downloadUrl = visionModelUrl,
                visionProjectorUrl = visionProjectorUrl
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
    
    /**
     * Download a specific model by its ModelInfo.
     * Uses the downloadUrl from ModelInfo instead of default URL.
     */
    actual fun downloadModelByInfo(modelInfo: ModelInfo): Flow<DownloadStatus> = flow {
        val context = androidContext ?: run {
            emit(DownloadStatus.Error("Context not initialized. Call init() first."))
            return@flow
        }
        
        val appDir = appFilesDir ?: run {
            emit(DownloadStatus.Error("Cannot determine storage path"))
            return@flow
        }
        
        if (modelInfo.downloadUrl.isEmpty()) {
            emit(DownloadStatus.Error("No download URL for this model"))
            return@flow
        }
        
        // Extract filename from path
        val fileName = modelInfo.path.substringAfterLast("/")
        val targetFile = File(appDir, fileName)
        
        android.util.Log.d("ModelRepository", "Downloading: ${modelInfo.name}")
        android.util.Log.d("ModelRepository", "URL: ${modelInfo.downloadUrl}")
        android.util.Log.d("ModelRepository", "Target: ${targetFile.absolutePath}")
        
        // Download main model file
        downloadFile(context, modelInfo.downloadUrl, targetFile, modelInfo.name).collect { status ->
            emit(status)
        }
        
        // For vision models, also download the projector file
        if (modelInfo.modelType == ModelType.VISION_TEXT && modelInfo.visionProjectorUrl.isNotEmpty()) {
            val projectorFileName = modelInfo.visionProjectorUrl.substringAfterLast("/")
            val projectorFile = File(appDir, projectorFileName)
            
            android.util.Log.d("ModelRepository", "Downloading vision projector: $projectorFileName")
            
            downloadFile(context, modelInfo.visionProjectorUrl, projectorFile, "Vision Encoder").collect { status ->
                // Only emit if error (main model already completed)
                if (status is DownloadStatus.Error) {
                    emit(status)
                }
            }
        }
    }.flowOn(Dispatchers.IO)
    
    /**
     * Generic file download helper
     */
    private fun downloadFile(
        context: Context,
        url: String,
        targetFile: File,
        displayName: String
    ): Flow<DownloadStatus> = flow {
        var connection: HttpURLConnection? = null
        
        try {
            createNotificationChannel(context)
            val notificationManager = NotificationManagerCompat.from(context)
            val notificationId = 1001 + targetFile.name.hashCode().rem(1000)
            val builder = NotificationCompat.Builder(context, "model_download")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Downloading: $displayName")
                .setContentText("Connecting...")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setProgress(100, 0, true)

            try {
                notificationManager.notify(notificationId, builder.build())
            } catch (e: SecurityException) {
                android.util.Log.w("ModelRepository", "Notification permission not granted")
            }

            val urlObj = URL(url)
            connection = urlObj.openConnection() as HttpURLConnection
            connection.connectTimeout = 30000
            connection.readTimeout = 60000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "EdgeQ-SLM/1.0 Android")
            connection.connect()
            
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("Server returned HTTP $responseCode")
            }
            
            val totalBytes = connection.contentLengthLong.takeIf { it > 0 } ?: targetFile.length()
            
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
                    val elapsedSec = (now - lastUpdate) / 1000.0
                    val bytesInInterval = bytesCopied - lastBytesCopied
                    val speedBytesPerSec = if (elapsedSec > 0) (bytesInInterval / elapsedSec).toLong() else 0L
                    
                    emit(DownloadStatus.Progress(
                        progress = progress,
                        downloadedBytes = bytesCopied,
                        totalBytes = totalBytes,
                        speedBytesPerSec = speedBytesPerSec
                    ))
                    
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
            
            if (targetFile.exists()) targetFile.delete()
            tempFile.renameTo(targetFile)
            
            android.util.Log.d("ModelRepository", "Download complete: ${targetFile.absolutePath}")
            
            builder.setContentText("Download completed")
                .setProgress(0, 0, false)
                .setOngoing(false)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
            try { notificationManager.notify(notificationId, builder.build()) } catch (e: SecurityException) {}
            
            emit(DownloadStatus.Completed)
            
        } catch (e: Exception) {
            android.util.Log.e("ModelRepository", "Download error: ${e.message}", e)
            emit(DownloadStatus.Error(e.message ?: "Unknown download error"))
        } finally {
            connection?.disconnect()
        }
    }
    
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
