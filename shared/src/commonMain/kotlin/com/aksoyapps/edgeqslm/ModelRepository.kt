package com.aksoyapps.edgeqslm

import kotlinx.coroutines.flow.Flow

/**
 * Download status sealed class for tracking download progress
 */
sealed class DownloadStatus {
    data class Progress(
        val progress: Float,
        val downloadedBytes: Long = 0,
        val totalBytes: Long = 0,
        val speedBytesPerSec: Long = 0
    ) : DownloadStatus()
    object Completed : DownloadStatus()
    data class Error(val message: String) : DownloadStatus()
}

/**
 * Model capability type
 */
enum class ModelType {
    TEXT_ONLY,      // Pure text generation (e.g., Qwen 1.8B)
    VISION_TEXT     // Vision + Text generation (e.g., Qwen2.5-VL)
}

/**
 * Model info for listing available models
 */
data class ModelInfo(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val isDownloadable: Boolean = false,    // true for HuggingFace models not yet downloaded
    val modelType: ModelType = ModelType.TEXT_ONLY,
    val description: String = "",           // Human-readable description
    val downloadUrl: String = "",           // URL for downloading
    val visionProjectorUrl: String = ""     // For vision models: mmproj file URL
)

/**
 * Cross-platform model repository for checking and downloading model files.
 * Implemented separately for Android and iOS.
 */
expect class ModelRepository() {
    fun getModelPath(): String
    suspend fun isModelDownloaded(): Boolean
    fun downloadModel(): Flow<DownloadStatus>
    fun downloadModelByInfo(modelInfo: ModelInfo): Flow<DownloadStatus>  // Download specific model
    suspend fun getAvailableModels(): List<ModelInfo>
    fun setSelectedModel(path: String)
}
