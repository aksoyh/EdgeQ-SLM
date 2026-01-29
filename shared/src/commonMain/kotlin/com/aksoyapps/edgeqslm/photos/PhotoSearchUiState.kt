package com.aksoyapps.edgeqslm.photos

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Search mode - ML-based (CLIP+OCR) vs Vision LLM
 */
enum class SearchMode {
    ML_BASED,   // CLIP embedding + OCR text search
    VLM_BASED   // Vision Language Model analysis
}

/**
 * Photo Search UI State
 */
data class PhotoSearchUiState(
    // Search
    val searchQuery: String = "",
    val searchResults: List<PhotoSearchResultUi> = emptyList(),
    val isSearching: Boolean = false,
    val searchMode: SearchMode = SearchMode.ML_BASED,  // Current search backend
    
    // Photo List (taranan/taranmayan)
    val photoFiles: List<PhotoFileUi> = emptyList(),
    
    // Indexing
    val isIndexing: Boolean = false,
    val indexingProgress: Float = 0f,
    val indexingMessage: String = "",
    val indexedCount: Int = 0,
    val totalPhotos: Int = 0,
    
    // Model status
    val isModelLoading: Boolean = true,  // True while CLIP models are loading
    val isModelLoaded: Boolean = false,
    val modelLoadingMessage: String = "Loading CLIP models...",
    val isVlmAvailable: Boolean = false,  // True if Vision LLM is loaded
    
    // VLM Indexing
    val isVlmIndexing: Boolean = false,
    val vlmIndexProgress: Float = 0f,
    val vlmIndexMessage: String = "",
    val vlmIndexedCount: Int = 0,
    
    // Folder
    val scanFolderPath: String = "",
    
    // Tab
    val selectedTab: Int = 0, // 0=Photos, 1=Search
    
    // Errors
    val error: String? = null
)

/**
 * Photo file for UI (with indexed status)
 */
data class PhotoFileUi(
    val path: String,
    val name: String,
    val sizeKb: Long,
    val isIndexed: Boolean
)

/**
 * Search result for UI
 */
data class PhotoSearchResultUi(
    val id: Long,
    val filePath: String,
    val fileName: String,
    val ocrText: String?,
    val score: Float,
    val thumbnailUri: String? = null,
    val matchType: MatchType = MatchType.OCR,
    val matchReason: String = "",
    val latencyMs: Long = 0,  // For benchmark comparison
    val vlmDescription: String? = null,  // VLM generated description
    val vlmTags: String? = null  // VLM generated tags
)

/**
 * Type of match
 */
enum class MatchType {
    OCR,         // Text match from OCR
    CLIP,        // Visual similarity from CLIP
    HYBRID,      // Both OCR and CLIP
    VISION_LLM   // Vision Language Model analysis
}
