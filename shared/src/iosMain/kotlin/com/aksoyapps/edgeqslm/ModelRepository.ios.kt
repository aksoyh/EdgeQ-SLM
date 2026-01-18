package com.aksoyapps.edgeqslm

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import platform.Foundation.*

/**
 * iOS implementation of ModelRepository.
 * Downloads model from HuggingFace with progress tracking.
 */
actual class ModelRepository {
    
    // HuggingFace direct download URL
    private val huggingFaceUrl = "https://huggingface.co/Qwen/Qwen1.5-1.8B-Chat-GGUF/resolve/main/qwen1_5-1_8b-chat-q8_0.gguf"
    private val huggingFaceFileName = "qwen1_5-1_8b-chat-q8_0.gguf"
    private val expectedFileSize = 1958304128L // ~1.82GB
    
    // Selected model path
    private var selectedModelPath: String? = null
    
    // Debug flag
    var forceModelNotFound: Boolean = false
    
    // iOS Documents directory path
    private val documentsPath: String get() {
        val paths = NSSearchPathForDirectoriesInDomains(
            NSDocumentDirectory,
            NSUserDomainMask,
            true
        )
        return (paths.firstOrNull() as? String) ?: ""
    }
    
    /**
     * Get the path to the currently selected model file
     */
    actual fun getModelPath(): String {
        selectedModelPath?.let { return it }
        return "$documentsPath/$huggingFaceFileName"
    }
    
    /**
     * Set the selected model path
     */
    actual fun setSelectedModel(path: String) {
        selectedModelPath = path
    }
    
    /**
     * Get list of available models
     */
    actual suspend fun getAvailableModels(): List<ModelInfo> {
        val models = mutableListOf<ModelInfo>()
        
        val fileManager = NSFileManager.defaultManager
        val contents = fileManager.contentsOfDirectoryAtPath(documentsPath, null)
        
        contents?.forEach { item ->
            val fileName = item as? String
            if (fileName != null && fileName.endsWith(".gguf")) {
                val filePath = "$documentsPath/$fileName"
                val attrs = fileManager.attributesOfItemAtPath(filePath, null)
                val size = (attrs?.get(NSFileSize) as? NSNumber)?.longValue ?: 0L
                
                models.add(ModelInfo(
                    name = fileName,
                    path = filePath,
                    sizeBytes = size,
                    isDownloadable = false
                ))
            }
        }
        
        // Add HuggingFace model as downloadable if not present
        val huggingFacePath = "$documentsPath/$huggingFaceFileName"
        if (!fileManager.fileExistsAtPath(huggingFacePath)) {
            models.add(ModelInfo(
                name = "📥 $huggingFaceFileName (HuggingFace)",
                path = huggingFacePath,
                sizeBytes = expectedFileSize,
                isDownloadable = true
            ))
        }
        
        return models
    }
    
    /**
     * Check if model is already downloaded
     */
    actual suspend fun isModelDownloaded(): Boolean {
        if (forceModelNotFound) return false
        
        val fileManager = NSFileManager.defaultManager
        val contents = fileManager.contentsOfDirectoryAtPath(documentsPath, null)
        
        contents?.forEach { item ->
            val fileName = item as? String
            if (fileName != null && fileName.endsWith(".gguf")) {
                val filePath = "$documentsPath/$fileName"
                val attrs = fileManager.attributesOfItemAtPath(filePath, null)
                val size = (attrs?.get(NSFileSize) as? NSNumber)?.longValue ?: 0L
                if (size > expectedFileSize / 2) {
                    return true
                }
            }
        }
        return false
    }
    
    /**
     * Download model from HuggingFace
     */
    actual fun downloadModel(): Flow<DownloadStatus> = flow {
        val client = HttpClient {
            install(HttpTimeout) {
                requestTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
                socketTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
            }
        }

        try {
            val response = client.get(huggingFaceUrl)

            if (response.status != HttpStatusCode.OK) {
                throw Exception("Server returned ${response.status}")
            }

            val contentType = response.contentType()?.toString() ?: ""
            if (contentType.contains("text/html")) {
                throw Exception("Expected binary file but received HTML.")
            }

            val channel: ByteReadChannel = response.bodyAsChannel()
            val totalBytes = response.contentLength() ?: expectedFileSize
            
            val targetPath = "$documentsPath/$huggingFaceFileName"
            val tempPath = "$documentsPath/${huggingFaceFileName}.tmp"
            
            val data = NSMutableData()
            var bytesCopied = 0L
            val buffer = ByteArray(8192)
            var lastUpdate = 0L
            var lastBytesCopied = 0L
            
            while (!channel.isClosedForRead) {
                val read = channel.readAvailable(buffer, 0, buffer.size)
                if (read <= 0) break
                
                buffer.toNSData(read).let { data.appendData(it) }
                bytesCopied += read
                
                val now = NSDate().timeIntervalSince1970.toLong() * 1000
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
                    
                    lastUpdate = now
                    lastBytesCopied = bytesCopied
                }
            }
            
            data.writeToFile(tempPath, true)
            
            val fileManager = NSFileManager.defaultManager
            if (fileManager.fileExistsAtPath(targetPath)) {
                fileManager.removeItemAtPath(targetPath, null)
            }
            fileManager.moveItemAtPath(tempPath, targetPath, null)
            
            emit(DownloadStatus.Completed)
            
        } catch (e: Exception) {
            emit(DownloadStatus.Error(e.message ?: "Unknown download error"))
        } finally {
            client.close()
        }
    }.flowOn(Dispatchers.Default)
    
    private fun ByteArray.toNSData(length: Int): NSData {
        return NSData.create(bytes = this.toUByteArray().refTo(0), length = length.toULong())
    }
}
