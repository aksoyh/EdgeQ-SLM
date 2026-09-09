package com.aksoyapps.edgeqslm.photos

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Search mode - ML-based (CLIP+OCR) vs Vision LLM
 */
enum class SearchMode {
    ML_BASED,   // CLIP embedding + OCR text search
    VLM_BASED,  // Vision Language Model analysis
    SLM_BASED   // TinyLlama planner + weighted keyword scoring
}

/**
 * Photo Search UI State
 */
data class PhotoSearchUiState(
    val thesisMode: ThesisSearchMode = ThesisSearchMode.RECOMMENDED,
    val enabledThesisModes: Set<ThesisSearchMode> = emptySet(),
    val modeReasons: Map<ThesisSearchMode, String> = emptyMap(),
    val sourceLabel: String = "No photos selected",
    val selectionSessionId: String = "",
    val isSelectingPhotos: Boolean = false,
    val canResumeIndexing: Boolean = false,
    val canCompleteMissingChannels: Boolean = false,
    val isIndexingPreflight: Boolean = false,
    val indexingBlockReason: String = "",
    val missingChannelSummary: String = "",
    val indexingElapsedMs: Long? = null,
    val indexingStage: String = "",
    val indexingProcessed: Int = 0,
    val indexingTotal: Int = 0,
    val indexingRssMiB: Double? = null,
    val indexingPeakRssMiB: Double? = null,
    val indexingFailedPhotos: Int = 0,
    val indexingUnavailablePhotos: Int = 0,
    val existingIndexCount: Int = 0,
    val searchableCount: Int = 0,
    val fullyIndexedCount: Int = 0,
    val partiallyIndexedCount: Int = 0,
    val failedUnavailableCount: Int = 0,
    val coverageSummary: String = "No indexing session",
    val searchNote: String = "",
    val lastQueryMs: Double? = null,
    // Search
    val searchQuery: String = "",
    val searchResults: List<PhotoSearchResultUi> = emptyList(),
    val isSearching: Boolean = false,
    val lastSubmittedQuery: String? = null,
    val searchError: String? = null,
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
    val isModelLoading: Boolean = false,  // True while CLIP models are loading on demand
    val isModelLoaded: Boolean = false,
    val modelLoadingMessage: String = "Loading CLIP models...",
    val isVlmAvailable: Boolean = false,  // True if Vision LLM is loaded and ready
    val isVlmLoading: Boolean = false,   // True while VLM model is being loaded
    val isSlmAvailable: Boolean = false, // True if TinyLlama planner is loaded and ready
    val isSlmLoading: Boolean = false,   // True while SLM model is being loaded
    val slmDebugPlan: String = "",       // JSON plan from planner for debug display
    
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
    val isIndexed: Boolean,
    val channelStates: Map<String, String> = emptyMap(),
    val channelDisplayStatuses: Map<String, String> = emptyMap(),
    val channelDetails: Map<String, String> = emptyMap(),
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
    VISION_LLM,  // Vision Language Model analysis
    SEMANTIC,    // MiniLM semantic retrieval over verified safe projections
    SLM_PLANNER  // TinyLlama planner + keyword scoring
}
