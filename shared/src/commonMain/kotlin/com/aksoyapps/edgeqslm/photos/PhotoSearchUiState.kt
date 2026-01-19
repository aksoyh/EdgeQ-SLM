package com.aksoyapps.edgeqslm.photos

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Photo Search UI State
 */
data class PhotoSearchUiState(
    // Search
    val searchQuery: String = "",
    val searchResults: List<PhotoSearchResultUi> = emptyList(),
    val isSearching: Boolean = false,
    
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
    val matchType: MatchType = MatchType.OCR,  // OCR or CLIP
    val matchReason: String = ""  // e.g., "OCR: 'uçak bileti'" or "CLIP: visual similarity"
)

/**
 * Type of match
 */
enum class MatchType {
    OCR,    // Text match from OCR
    CLIP,   // Visual similarity from CLIP
    HYBRID  // Both OCR and CLIP
}
