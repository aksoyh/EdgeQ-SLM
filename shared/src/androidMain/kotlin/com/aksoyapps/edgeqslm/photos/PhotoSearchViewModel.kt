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
 * Simple demo mode - no ML models needed
 */
class PhotoSearchViewModel(private val context: Context) {
    
    private val _uiState = MutableStateFlow(PhotoSearchUiState())
    val uiState: StateFlow<PhotoSearchUiState> = _uiState.asStateFlow()
    
    private val scope = CoroutineScope(Dispatchers.Main)
    private var photoIndexer: PhotoIndexer? = null
    private var indexingJob: Job? = null
    
    // Demo mode - skip ML models
    private var demoMode = true
    
    init {
        initialize()
    }
    
    private fun initialize() {
        scope.launch(Dispatchers.IO) {
            photoIndexer = PhotoIndexer(context)
            
            // Get default folder
            val defaultFolder = photoIndexer?.getDefaultFolder() ?: ""
            
            // Ensure folder exists
            File(defaultFolder).mkdirs()
            
            _uiState.value = _uiState.value.copy(
                scanFolderPath = defaultFolder,
                isModelLoaded = demoMode, // Demo mode enabled
                modelLoadingMessage = if (demoMode) "Demo Mode (OCR only)" else "Models not loaded"
            )
            
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
     * Perform search
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
                            thumbnailUri = "file://${result.filePath}"
                        )
                    }
                )
            } catch (e: Exception) {
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
                _uiState.value = _uiState.value.copy(
                    isIndexing = false,
                    error = "Indexing error: ${e.message}"
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
