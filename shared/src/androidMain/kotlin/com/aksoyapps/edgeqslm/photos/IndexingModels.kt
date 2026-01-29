package com.aksoyapps.edgeqslm.photos

/**
 * Indexing state shared between Service and ViewModel
 */
data class IndexingState(
    val isRunning: Boolean = false,
    val type: IndexingType = IndexingType.NONE,
    val current: Int = 0,
    val total: Int = 0,
    val progress: Float = 0f,
    val message: String = "",
    val error: String? = null
)

enum class IndexingType {
    NONE,
    ML,
    VLM
}

/**
 * Log entry for a full indexing session
 */
data class IndexingSessionLog(
    val timestamp: Long,
    val type: IndexingType,
    val durationMs: Long,
    val totalItems: Int,
    val successItems: Int,
    val failedItems: Int,
    val folderPath: String
)

/**
 * Log entry for a single item (photo) indexing
 */
data class ItemIndexingLog(
    val timestamp: Long,
    val type: IndexingType,
    val fileName: String,
    val latencyMs: Long,
    val imageSize: Long = 0,
    val success: Boolean,
    val error: String? = null
)
