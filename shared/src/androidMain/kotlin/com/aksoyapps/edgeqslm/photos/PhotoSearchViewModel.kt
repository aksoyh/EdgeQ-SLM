package com.aksoyapps.edgeqslm.photos

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * ViewModel for Photo Search functionality
 * Supports both OCR text search and CLIP visual search
 */
class PhotoSearchViewModel(private val context: Context) {
    
    private val _uiState = MutableStateFlow(PhotoSearchUiState())
    val uiState: StateFlow<PhotoSearchUiState> = _uiState.asStateFlow()
    
    private val scope = CoroutineScope(Dispatchers.Main)
    private var photoIndexer: PhotoIndexer? = null
    private var indexingJob: Job? = null
    
    // CLIP model paths
    private val modelsDir: File
        get() = File(context.getExternalFilesDir(null), "models")
    
    private val clipImageModelPath: String
        get() = File(modelsDir, "clip-image.onnx").absolutePath
    
    private val clipTextModelPath: String
        get() = File(modelsDir, "clip-text.onnx").absolutePath
    
    private val clipVocabPath: String
        get() = File(modelsDir, "clip_tokenizer").absolutePath
    
    init {
        initialize()
    }
    
    private fun initialize() {
        scope.launch(Dispatchers.IO) {
            photoIndexer = PhotoIndexer(context)
            
            // Get default folder
            val defaultFolder = photoIndexer?.getDefaultFolder() ?: ""
            File(defaultFolder).mkdirs()
            
            // Log model paths for debugging
            android.util.Log.d("PhotoSearchVM", "Models dir: ${modelsDir.absolutePath}")
            android.util.Log.d("PhotoSearchVM", "CLIP image path: $clipImageModelPath, exists: ${File(clipImageModelPath).exists()}")
            android.util.Log.d("PhotoSearchVM", "CLIP text path: $clipTextModelPath, exists: ${File(clipTextModelPath).exists()}")
            
            // Check if CLIP models exist
            val clipModelsExist = File(clipImageModelPath).exists() && File(clipTextModelPath).exists()
            
            val (modelLoaded, modeMessage) = if (clipModelsExist) {
                // Update UI to show loading state
                _uiState.value = _uiState.value.copy(
                    isModelLoading = true,
                    modelLoadingMessage = "⏳ Loading CLIP models (~660MB)..."
                )
                
                // Try to initialize CLIP models
                android.util.Log.d("PhotoSearchVM", "Initializing CLIP models...")
                val success = photoIndexer?.initialize(clipImageModelPath, clipTextModelPath, clipVocabPath) ?: false
                android.util.Log.d("PhotoSearchVM", "CLIP init result: $success")
                if (success) {
                    Pair(true, "✅ CLIP + OCR mode")
                } else {
                    Pair(true, "⚠️ OCR only (CLIP failed)")
                }
            } else {
                android.util.Log.d("PhotoSearchVM", "CLIP models not found")
                Pair(true, "📝 OCR only (no CLIP)")
            }
            
            _uiState.value = _uiState.value.copy(
                scanFolderPath = defaultFolder,
                isModelLoading = false,
                isModelLoaded = modelLoaded,
                modelLoadingMessage = modeMessage
            )
            
            android.util.Log.d("PhotoSearchVM", "Mode: $modeMessage, CLIP exists: $clipModelsExist")
            
            // Refresh photo list
            refreshPhotoList()
        }
    }
    
    /**
     * Refresh the list of photos in folder
     */
    fun refreshPhotoList() {
        scope.launch(Dispatchers.IO) {
            val photos = photoIndexer?.getPhotosInFolder() ?: emptyList()
            val stats = photoIndexer?.getStats()
            
            _uiState.value = _uiState.value.copy(
                photoFiles = photos.map { 
                    PhotoFileUi(
                        path = it.path,
                        name = it.name,
                        sizeKb = it.size / 1024,
                        isIndexed = it.isIndexed
                    )
                },
                totalPhotos = stats?.totalPhotos ?: 0,
                indexedCount = stats?.indexedPaths ?: 0
            )
        }
    }
    
    /**
     * Update search query and search immediately
     */
    fun updateQueryAndSearch(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        if (query.length >= 2) {
            search()
        } else if (query.isEmpty()) {
            _uiState.value = _uiState.value.copy(searchResults = emptyList())
        }
    }
    
    /**
     * Perform search - uses CLIP embedding if available, otherwise OCR text search
     */
    fun search() {
        val query = _uiState.value.searchQuery
        if (query.isBlank()) return
        
        scope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isSearching = true)
            
            try {
                val results = photoIndexer?.search(query) ?: emptyList()
                
                _uiState.value = _uiState.value.copy(
                    isSearching = false,
                    searchResults = results.map { result ->
                        PhotoSearchResultUi(
                            id = result.id,
                            filePath = result.filePath,
                            fileName = result.fileName,
                            ocrText = result.ocrText,
                            score = result.score,
                            thumbnailUri = "file://${result.filePath}",
                            matchType = when (result.matchType) {
                                "CLIP" -> MatchType.CLIP
                                "HYBRID" -> MatchType.HYBRID
                                else -> MatchType.OCR
                            },
                            matchReason = result.matchReason
                        )
                    }
                )
            } catch (e: Exception) {
                android.util.Log.e("PhotoSearchVM", "Search error: ${e.message}", e)
                _uiState.value = _uiState.value.copy(
                    isSearching = false,
                    error = "Search error: ${e.message}"
                )
            }
        }
    }
    
    /**
     * Start indexing photos in folder
     */
    fun startIndexing() {
        if (_uiState.value.isIndexing) return
        
        indexingJob = scope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(
                isIndexing = true,
                indexingProgress = 0f,
                indexingMessage = "Starting..."
            )
            
            try {
                photoIndexer?.indexFolder()?.collect { progress ->
                    _uiState.value = _uiState.value.copy(
                        indexingProgress = progress.progress,
                        indexingMessage = progress.message,
                        totalPhotos = progress.total
                    )
                    
                    if (progress.isComplete) {
                        _uiState.value = _uiState.value.copy(isIndexing = false)
                        refreshPhotoList()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("PhotoSearchVM", "Indexing error: ${e.message}", e)
                _uiState.value = _uiState.value.copy(
                    isIndexing = false,
                    error = "Indexing error: ${e.message}"
                )
            }
        }
    }
    
    /**
     * Force re-index all photos - deletes database and re-indexes with CLIP
     */
    fun forceIndex() {
        if (_uiState.value.isIndexing) return
        
        indexingJob = scope.launch(Dispatchers.IO) {
            android.util.Log.d("PhotoSearchVM", "FORCE INDEXING: Clearing database and re-indexing all photos")
            
            _uiState.value = _uiState.value.copy(
                isIndexing = true,
                indexingProgress = 0f,
                indexingMessage = "Force indexing: clearing database..."
            )
            
            try {
                // Clear the database
                photoIndexer?.clearDatabase()
                
                // Re-index all photos
                photoIndexer?.indexFolder()?.collect { progress ->
                    _uiState.value = _uiState.value.copy(
                        indexingProgress = progress.progress,
                        indexingMessage = "Force: ${progress.message}",
                        totalPhotos = progress.total
                    )
                    
                    if (progress.isComplete) {
                        _uiState.value = _uiState.value.copy(isIndexing = false)
                        refreshPhotoList()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("PhotoSearchVM", "Force indexing error: ${e.message}", e)
                _uiState.value = _uiState.value.copy(
                    isIndexing = false,
                    error = "Force indexing error: ${e.message}"
                )
            }
        }
    }
    
    /**
     * Set scan folder from user selection
     */
    fun setScanFolder(path: String) {
        scope.launch(Dispatchers.IO) {
            photoIndexer?.setScanFolder(path)
            _uiState.value = _uiState.value.copy(scanFolderPath = path)
            refreshPhotoList()
        }
    }
    
    /**
     * Select tab
     */
    fun selectTab(index: Int) {
        _uiState.value = _uiState.value.copy(selectedTab = index)
        if (index == 0) {
            refreshPhotoList()
        }
    }
    
    /**
     * Clear error
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
    
    /**
     * Cleanup
     */
    fun close() {
        indexingJob?.cancel()
        photoIndexer?.close()
    }
}
