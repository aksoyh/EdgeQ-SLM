package com.aksoyapps.edgeqslm.photos

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

/**
 * VLM Indexer - Pre-indexes photos with Vision LLM descriptions
 * 
 * This runs on IO dispatcher and can be paused/resumed.
 * If app is closed, indexing resumes from where it left off
 * (photos without vlm_description are re-processed).
 */
class VlmIndexer(
    private val vlmAnalyzer: VlmImageAnalyzer,
    private val vectorStore: PhotoVectorStore
) {
    companion object {
        private const val TAG = "VlmIndexer"
        
        // Updated prompt as requested (English)
        private const val PROMPT_COMBINED = "Describe the objects in the image, extract the text written in the image."
    }
    
    /**
     * Index all photos that don't have VLM descriptions yet in the specified folder
     * Returns a Flow of progress updates
     * @param force If true, clear existing VLM descriptions and re-index all photos
     */
    fun indexAllPhotos(folderPath: String? = null, force: Boolean = false): Flow<VlmIndexProgress> = flow {
        // Clear existing data if force is true
        if (force) {
            Log.i(TAG, "Force indexing enabled, clearing VLM descriptions for folder: $folderPath")
            emit(VlmIndexProgress(
                current = 0,
                total = 0,
                failed = 0,
                currentFile = "",
                status = VlmIndexStatus.RUNNING,
                message = "Clearing existing VLM index..."
            ))
            vectorStore.clearVlmDescriptions(folderPath)
        }

        val photosToIndex = vectorStore.getPhotosWithoutVlmDescription(folderPath = folderPath, limit = 1000)
        val total = photosToIndex.size
        Log.i(TAG, "Found $total photos to index (folder=$folderPath)")
        
        if (total == 0) {
            emit(VlmIndexProgress(
                current = 0,
                total = 0,
                failed = 0,
                currentFile = "",
                status = VlmIndexStatus.COMPLETED,
                message = "All photos already indexed with VLM"
            ))
            return@flow
        }
        
        Log.i(TAG, "Starting VLM indexing for $total photos")
        
        emit(VlmIndexProgress(
            current = 0,
            total = total,
            failed = 0,
            currentFile = "",
            status = VlmIndexStatus.RUNNING,
            message = "Starting VLM indexing..."
        ))
        
        var indexed = 0
        var failed = 0
        
        for (photo in photosToIndex) {
            // Check if coroutine is still active
            if (!coroutineContext.isActive) {
                Log.i(TAG, "Indexing cancelled at $indexed/$total")
                emit(VlmIndexProgress(
                    current = indexed,
                    total = total,
                    failed = failed,
                    currentFile = photo.fileName,
                    status = VlmIndexStatus.CANCELLED,
                    message = "Indexing cancelled"
                ))
                return@flow
            }
            
            // Emit progress BEFORE processing
            emit(VlmIndexProgress(
                current = indexed,
                total = total,
                failed = failed,
                currentFile = photo.fileName,
                status = VlmIndexStatus.RUNNING,
                message = "Analyzing ${indexed+1}/$total: ${photo.fileName}"
            ))
            
            var latency: Long = 0
            
            try {
                val result = indexSinglePhoto(photo.filePath)
                latency = result.latencyMs
                if (result.success) {
                    indexed++
                    Log.i(TAG, "Indexed: ${photo.fileName} | Desc: ${result.description.take(100)} | Tags: ${result.tags}")
                } else {
                    failed++
                    Log.w(TAG, "Failed: ${photo.fileName} - ${result.error}")
                }
            } catch (e: Exception) {
                failed++
                Log.e(TAG, "Error indexing ${photo.fileName}: ${e.message}")
            }
            
            // Emit progress AFTER processing
            emit(VlmIndexProgress(
                current = indexed,
                total = total,
                failed = failed,
                currentFile = photo.fileName,
                status = VlmIndexStatus.RUNNING,
                message = "Done $indexed/$total (${failed} failed)",
                latencyMs = latency,
                imageSize = java.io.File(photo.filePath).length()
            ))
        }
        
        Log.i(TAG, "VLM indexing completed: $indexed indexed, $failed failed")
        
        emit(VlmIndexProgress(
            current = indexed,
            total = total,
            failed = failed,
            currentFile = "",
            status = VlmIndexStatus.COMPLETED,
            message = "Completed: $indexed indexed, $failed failed"
        ))
    }.flowOn(Dispatchers.IO)
    
    /**
     * Index a single photo with VLM - uses single combined prompt for speed
     */
    suspend fun indexSinglePhoto(photoPath: String): VlmIndexResult {
        val startTime = System.currentTimeMillis()
        
        try {
            // First attempt: 128 tokens
            var result = vlmAnalyzer.analyzeImage(photoPath, PROMPT_COMBINED, maxTokens = 128)
            var response = result.description ?: ""
            
            // Retry logic: If response is too short or empty, try with 256 tokens
            if (response.trim().length < 15) {
                 Log.w(TAG, "Short/Empty response, retrying with 256 tokens: '$response'")
                 result = vlmAnalyzer.analyzeImage(photoPath, PROMPT_COMBINED, maxTokens = 256)
                 response = result.description ?: ""
            }
            
            if (response.isBlank()) {
                return VlmIndexResult(
                    success = false,
                    description = "",
                    tags = "",
                    latencyMs = System.currentTimeMillis() - startTime,
                    error = "Empty VLM response"
                )
            }
            
            // Simple parsing - use response as description, extract keywords for tags
            val description = response.trim().take(500)
            
            // Extract simple keywords from response for tags
            val tags = response
                .lowercase()
                .replace(Regex("[^a-z0-9şçğüöı\\s]"), " ")
                .split(Regex("\\s+"))
                .filter { it.length > 2 }
                .distinct()
                .take(10)
                .joinToString(",")
            
            // Save to database
            vectorStore.updateVlmDescription(photoPath, description, tags)
            
            val latency = System.currentTimeMillis() - startTime
            
            return VlmIndexResult(
                success = true,
                description = description,
                tags = tags,
                latencyMs = latency,
                error = null
            )
            
        } catch (e: Exception) {
            return VlmIndexResult(
                success = false,
                description = "",
                tags = "",
                latencyMs = System.currentTimeMillis() - startTime,
                error = e.message
            )
        }
    }
    
    /**
     * Check if VLM analyzer is available
     */
    fun isAvailable(): Boolean = vlmAnalyzer.isAvailable()
    
    /**
     * Get indexing stats
     */
    fun getStats(): VlmIndexStats {
        val total = vectorStore.getPhotoCount()
        val indexed = vectorStore.getVlmIndexedCount()
        return VlmIndexStats(
            totalPhotos = total,
            vlmIndexed = indexed,
            remaining = total - indexed
        )
    }
}

/**
 * Progress update during VLM indexing
 */
data class VlmIndexProgress(
    val current: Int,
    val total: Int,
    val failed: Int = 0,
    val currentFile: String,
    val status: VlmIndexStatus,
    val message: String,
    val latencyMs: Long = 0,
    val imageSize: Long = 0
)

enum class VlmIndexStatus {
    IDLE,
    RUNNING,
    COMPLETED,
    CANCELLED,
    ERROR
}

/**
 * Result of indexing a single photo
 */
data class VlmIndexResult(
    val success: Boolean,
    val description: String,
    val tags: String,
    val latencyMs: Long,
    val error: String? = null
)

/**
 * VLM indexing statistics
 */
data class VlmIndexStats(
    val totalPhotos: Int,
    val vlmIndexed: Int,
    val remaining: Int
) {
    val percentComplete: Float = if (totalPhotos > 0) vlmIndexed.toFloat() / totalPhotos else 0f
}
