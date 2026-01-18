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
 * Model info for listing available models
 */
data class ModelInfo(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val isDownloadable: Boolean = false // true for HuggingFace models not yet downloaded
)

/**
 * Cross-platform model repository for checking and downloading model files.
 * Implemented separately for Android and iOS.
 */
expect class ModelRepository() {
    fun getModelPath(): String
    suspend fun isModelDownloaded(): Boolean
    fun downloadModel(): Flow<DownloadStatus>
    suspend fun getAvailableModels(): List<ModelInfo>
    fun setSelectedModel(path: String)
}
