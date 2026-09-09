package com.aksoyapps.edgeqslm.photos

import android.content.Context
import com.aksoyapps.edgeqslm.AndroidLlamaCppEngine
import com.aksoyapps.edgeqslm.diagnostics.ThesisDiagnostics
import com.aksoyapps.edgeqslm.photos.models.ModelDelivery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ViewModel for Photo Search functionality
 * Supports both OCR text search and CLIP visual search
 */
class PhotoSearchViewModel(private val context: Context) {
    
    private val _uiState = MutableStateFlow(PhotoSearchUiState())
    val uiState: StateFlow<PhotoSearchUiState> = _uiState.asStateFlow()

    @Volatile var lastSearchDiagnostics: ProductionSearchResponse? = null
        private set
    
    private val scope = CoroutineScope(Dispatchers.Main)
    private var photoIndexer: PhotoIndexer? = null
    private var indexingJob: Job? = null
    private val selectionStore = PhotoSelectionStore(context)
    private var readinessJob: Job? = null
    @Volatile private var retainedVlmDiagnostics: Map<String, VlmSourceDiagnostic> = emptyMap()
    val selection: ThesisPhotoSelection? get() = selectionStore.snapshot()
    
    // VLM analyzer for Vision LLM search
    private var vlmAnalyzer: VlmImageAnalyzer? = null
    private var vlmIndexer: VlmIndexer? = null
    private var vlmIndexingJob: Job? = null

    // SLM planner engine (TinyLlama, text-only) — loaded lazily on first mode switch
    private var slmEngine: AndroidLlamaCppEngine? = null
    private var slmLoader: (() -> Unit)? = null  // set by MainActivity

    // VLM loader — set by MainActivity, triggered lazily on first VLM mode switch
    private var vlmLoader: (() -> Unit)? = null

    // CLIP loading state — loaded lazily on first ML search or ML mode switch
    private var clipInitialized = false
    private var clipLoading = false

    // Guard against concurrent generate() calls — llama.cpp uses a single global context
    @Volatile private var isGenerating = false
    @Volatile private var lastSearchedQuery = ""
    
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
        scope.launch {
            ModelDelivery(context).busyState.collect { busy ->
                if (busy) _uiState.value = _uiState.value.copy(indexingBlockReason = "Wait for the active model operation to finish before indexing.")
                else refreshModeAvailability()
            }
        }
        scope.launch(Dispatchers.IO) {
            photoIndexer = PhotoIndexer(context.applicationContext)
            refreshPhotoList()
            refreshModeAvailability()
        }
    }

    fun refreshModeAvailability() {
        readinessJob?.cancel()
        readinessJob = scope.launch(Dispatchers.IO) {
            val store = photoIndexer?.getVectorStore() ?: return@launch
            try {
                if (ModelDelivery(context).isBusy) {
                    _uiState.value = _uiState.value.copy(indexingBlockReason = "Wait for the active model operation to finish before indexing.")
                    return@launch
                }
                val modes = ThesisSearchEngine(context.applicationContext, store).availability()
                val selected = selectionStore.snapshot()
                val session = ThesisIndexingStateStore(context).snapshot()
                val readiness = ThesisIndexingReadiness.inspect(context, selected?.photos.orEmpty().map { it.path }, true)
                if (ModelDelivery(context).isBusy) return@launch
                val sameSelection = selected != null && session != null && session.sessionId == selected.sessionId &&
                    session.selectedPaths == selected.photos.map { it.path }
                _uiState.value = _uiState.value.copy(
                    enabledThesisModes = modes.filter { it.enabled }.map { it.mode }.toSet(),
                    modeReasons = modes.associate { it.mode to it.reason },
                    canCompleteMissingChannels = sameSelection && session?.canRequestCompletion == true && readiness.canComplete,
                    missingChannelSummary = readiness.summary,
                    indexingBlockReason = "",
                )
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) {
                _uiState.value = _uiState.value.copy(indexingBlockReason = "Index/model status could not be checked (${failure.javaClass.simpleName}). Refresh or inspect Diagnostics.")
            }
        }
    }

    fun prepareIndexingStart(completeMissing: Boolean, onReady: (ThesisIndexingSession) -> Unit) {
        val current = _uiState.value
        if (current.isIndexing || current.isVlmIndexing || current.isSelectingPhotos || current.isIndexingPreflight) return
        val selected = selectionStore.snapshot()
        if (selected == null || selected.photos.isEmpty()) { reportError("Choose photos before indexing"); return }
        _uiState.value = current.copy(isIndexingPreflight = true, indexingBlockReason = "", error = null)
        scope.launch {
            try {
                val session = withContext(Dispatchers.IO) {
                    check(!ModelDelivery(context).isBusy) { "Wait for the active model operation to finish before indexing." }
                    check(selected.photos.all { File(it.path).let { file -> file.isFile && file.canRead() } }) { "Some selected photos are unavailable. Choose a readable source before indexing." }
                    val existing = ThesisIndexingStateStore(context).snapshot()
                    val readiness = ThesisIndexingReadiness.inspect(context, selected.photos.map { it.path }, true)
                    if (completeMissing) {
                        check(existing?.canRequestCompletion == true && existing.sessionId == selected.sessionId &&
                            existing.selectedPaths == selected.photos.map { it.path }) { "The completed session no longer matches the selected photos." }
                        check(readiness.canComplete) { readiness.summary }
                    }
                    check(!ModelDelivery(context).isBusy) { "A model operation started. Finish it before indexing." }
                    if (completeMissing) requireNotNull(existing).copy(completeMissingChannels = true) else
                        ThesisIndexingSession(sessionId = selected.sessionId, selectedPaths = selected.photos.map { it.path }, includeSemantic = true)
                }
                onReady(session)
                _uiState.value = _uiState.value.copy(isIndexing = true, indexingMessage = "Starting selected photos", canCompleteMissingChannels = false)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) {
                _uiState.value = _uiState.value.copy(indexingBlockReason = failure.message ?: "Indexing preflight failed.")
            } finally { _uiState.value = _uiState.value.copy(isIndexingPreflight = false) }
        }
    }

    fun setThesisMode(mode: ThesisSearchMode) {
        if (mode !in _uiState.value.enabledThesisModes) return
        if (_uiState.value.isSearching) return
        _uiState.value = _uiState.value.copy(thesisMode = mode, searchResults = emptyList(), searchNote = "",
            lastSubmittedQuery = null, searchError = null, lastQueryMs = null)
    }

    fun chooseFolder(uri: android.net.Uri) = selectPhotos { selectionStore.chooseFolder(uri) }
    fun chooseRandomSample(count: Int) = selectPhotos { selectionStore.randomSample(count) }

    private fun selectPhotos(action: suspend () -> ThesisPhotoSelection) {
        if (_uiState.value.isIndexing || _uiState.value.isVlmIndexing || _uiState.value.isSelectingPhotos || _uiState.value.isIndexingPreflight) return
        scope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isSelectingPhotos = true, error = null)
            try {
                val selected = action()
                com.aksoyapps.edgeqslm.diagnostics.ThesisDiagnostics.get(context).event("photo_selection",
                    mapOf("selected_count" to selected.photos.size), selected.sessionId)
                refreshPhotoList()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _uiState.value = _uiState.value.copy(error = failure.message ?: "Photo selection failed")
            } finally {
                _uiState.value = _uiState.value.copy(isSelectingPhotos = false)
            }
        }
    }

    fun resetSelection() {
        if (_uiState.value.isIndexing || _uiState.value.isVlmIndexing || _uiState.value.isIndexingPreflight) return
        selectionStore.reset()
        com.aksoyapps.edgeqslm.diagnostics.ThesisDiagnostics.get(context).event("photo_selection_reset")
        refreshPhotoList()
    }

    /**
     * Load CLIP models on demand (first ML search or explicit ML mode switch).
     * Safe to call multiple times — subsequent calls are no-ops.
     */
    private fun loadClipIfNeeded() {
        if (clipInitialized || clipLoading) return
        clipLoading = true

        scope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(
                isModelLoading = true,
                modelLoadingMessage = "⏳ Loading CLIP models (~660MB)..."
            )

            android.util.Log.d("PhotoSearchVM", "CLIP image: $clipImageModelPath exists=${File(clipImageModelPath).exists()}")
            android.util.Log.d("PhotoSearchVM", "CLIP text:  $clipTextModelPath  exists=${File(clipTextModelPath).exists()}")

            val clipModelsExist = File(clipImageModelPath).exists() && File(clipTextModelPath).exists()
            val modeMessage = if (clipModelsExist) {
                val success = photoIndexer?.initialize(clipImageModelPath, clipTextModelPath, clipVocabPath) ?: false
                android.util.Log.d("PhotoSearchVM", "CLIP init: $success")
                if (success) "✅ CLIP + OCR mode" else "⚠️ OCR only (CLIP failed)"
            } else {
                android.util.Log.d("PhotoSearchVM", "CLIP models not found — OCR only")
                "📝 OCR only (no CLIP)"
            }

            clipInitialized = true
            clipLoading = false

            _uiState.value = _uiState.value.copy(
                isModelLoading = false,
                isModelLoaded = true,
                modelLoadingMessage = modeMessage
            )

            // CLIP now contributes a visual-similarity channel to every search — refresh results
            if (_uiState.value.searchQuery.isNotBlank()) {
                search()
            }
        }
    }
    
    /**
     * Refresh the list of photos in folder
     */
    fun refreshPhotoList() {
        scope.launch(Dispatchers.IO) {
            val selected = selectionStore.snapshot()
            val indexedPaths = photoIndexer?.getVectorStore()?.getAllIndexedPaths().orEmpty()
            val photos = selected?.photos.orEmpty()
            retainedVlmDiagnostics = VlmDiagnosticReadView(context)
                .enrich(ThesisIndexingStateStore(context).snapshot()?.photos.orEmpty())
                .mapNotNull { photo -> photo.vlmDiagnostic?.let { photo.path to it } }.toMap()
            _uiState.value = _uiState.value.copy(
                sourceLabel = selected?.source ?: "No photos selected",
                selectionSessionId = selected?.sessionId.orEmpty(),
                scanFolderPath = photos.firstOrNull()?.path?.let { File(it).parent }.orEmpty(),
                photoFiles = photos.map {
                    PhotoFileUi(path = it.path, name = it.name, sizeKb = File(it.path).length() / 1024,
                        isIndexed = it.path in indexedPaths)
                },
                totalPhotos = photos.size,
                indexedCount = photos.count { it.path in indexedPaths },
                existingIndexCount = indexedPaths.size,
            )
            refreshIndexingState()
            refreshModeAvailability()
        }
    }

    fun refreshIndexingState() {
        val session = ThesisIndexingStateStore(context.applicationContext).snapshot()
        val selected = selectionStore.snapshot()?.photos.orEmpty()
        val states = session?.photos.orEmpty().associateBy { it.path }
        val selectedStates = selected.map { states[it.path] ?: PhotoChannelState(it.path, "unindexed") }
        val coverage = IndexCoverage.from(selectedStates)
        val state = _uiState.value
        val memory = runCatching { ThesisDiagnostics.get(context).snapshot() }.getOrDefault(emptyMap())
        _uiState.value = state.copy(
            isIndexing = session?.isRunning == true,
            isVlmIndexing = false,
            canResumeIndexing = session?.canResume == true,
            indexingProgress = if (session != null && session.total > 0) session.processed.toFloat() / session.total else 0f,
            indexingMessage = session?.let { "${it.status} · ${it.stage} · ${it.processed}/${it.total}" }.orEmpty(),
            indexingElapsedMs = session?.let { (it.finishedAt ?: System.currentTimeMillis()) - (it.activeStartedAt ?: it.startedAt) },
            indexingStage = session?.stage.orEmpty(),
            indexingProcessed = session?.processed ?: 0,
            indexingTotal = session?.total ?: 0,
            indexingRssMiB = (memory["rss_bytes"] as? Number)?.toDouble()?.div(1024 * 1024) ?: session?.sampledRssMiB,
            indexingPeakRssMiB = session?.peakSampledRssMiB,
            indexingFailedPhotos = selectedStates.count { photo -> listOf(photo.ocr, photo.clip, photo.vlmSource,
                photo.semanticProjection, photo.miniLmVector).any { it == ChannelIndexStatus.FAILED } },
            indexingUnavailablePhotos = selectedStates.count { photo -> listOf(photo.ocr, photo.clip, photo.vlmSource,
                photo.semanticProjection, photo.miniLmVector).any { it == ChannelIndexStatus.UNAVAILABLE } },
            searchableCount = coverage.searchable,
            fullyIndexedCount = coverage.fullyIndexed,
            partiallyIndexedCount = coverage.partiallyIndexed,
            failedUnavailableCount = coverage.failedUnavailable,
            coverageSummary = "OCR ${coverage.ocr}/${coverage.selected} · CLIP ${coverage.clip}/${coverage.selected} · VLM ${coverage.vlmSource}/${coverage.selected} · Semantic ${coverage.semanticProjection}/${coverage.selected} · MiniLM ${coverage.miniLmVector}/${coverage.selected}",
            photoFiles = state.photoFiles.map { photo ->
                val channels = states[photo.path]?.let { channel ->
                    channel.copy(vlmDiagnostic = channel.vlmDiagnostic ?: retainedVlmDiagnostics[photo.path])
                }
                val statuses = if (channels == null) emptyMap() else linkedMapOf(
                    "OCR" to channels.ocr.name, "CLIP" to channels.clip.name,
                    "VLM source" to channels.vlmSource.name,
                    "Semantic projection" to channels.semanticProjection.name,
                    "MiniLM vector" to channels.miniLmVector.name,
                )
                photo.copy(channelStates = statuses,
                    channelDisplayStatuses = statuses.keys.associateWith { channels!!.presentation(it).status },
                    channelDetails = statuses.keys.mapNotNull { key -> channels!!.presentation(key).detail?.let { key to it } }.toMap())
            },
        )
        if (state.isIndexing && session?.isRunning != true) refreshPhotoList()
    }

    fun updateQuery(query: String) {
        if (_uiState.value.isSearching) return
        _uiState.value = PhotoSearchPresentation.editQuery(_uiState.value, query)
    }

    fun search() {
        val state = _uiState.value
        if (state.searchQuery.isBlank() || isGenerating) return
        if (state.thesisMode !in state.enabledThesisModes) {
            _uiState.value = state.copy(searchError = state.modeReasons[state.thesisMode]
                ?: "This mode needs compatible models and an existing index")
            return
        }
        val query = state.searchQuery
        val mode = state.thesisMode
        isGenerating = true
        _uiState.value = state.copy(isSearching = true, searchError = null,
            lastSubmittedQuery = query, searchResults = emptyList(), searchNote = "")
        scope.launch(Dispatchers.IO) {
            try {
                val store = photoIndexer?.getVectorStore() ?: error("Index is unavailable")
                val result = ThesisSearchEngine(context.applicationContext, store).search(query, mode)
                _uiState.value = _uiState.value.copy(
                    isSearching = false, searchResults = result.results, searchNote = result.note,
                    lastQueryMs = result.timingsMs["total_ms"], slmDebugPlan = "",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _uiState.value = _uiState.value.copy(isSearching = false,
                    searchResults = emptyList(), searchError = failure.message ?: "Search failed")
            } finally {
                isGenerating = false
                _uiState.value = _uiState.value.copy(isSearching = false)
            }
        }
    }

    /**
     * Ask TinyLlama to decompose the query into a structured plan; falls back to
     * deterministic tokenization when the planner isn't loaded or generation fails.
     */
    private suspend fun buildRawPlan(query: String): SearchPlan {
        val engine = slmEngine ?: return SearchPlan.fallback(query)
        val systemPrompt = "You are an on-device image search planner. Convert the user's Turkish or English query into a compact JSON search plan. Return only valid JSON. Do not explain."
        val userMessage = "Query: $query\nReturn JSON with this schema:\n{\"intent\":\"SEARCH_IMAGES\",\"query_terms\":[],\"search_channels\":[\"ocr_text\",\"vlm_description\",\"vlm_tags\",\"file_name\",\"file_path\"],\"ocr_required\":false,\"prefer_screenshot\":false,\"top_k\":10}"
        val prompt = "<|system|>\n$systemPrompt\n<|user|>\n$userMessage\n<|assistant|>\n"
        return try {
            val result = engine.generate(
                com.aksoyapps.edgeqslm.GenerationRequest(
                    prompt = prompt,
                    maxTokens = 150,
                    temperature = 0.1f,
                    useChatTemplate = false
                )
            )
            android.util.Log.d("PhotoSearchVM", "SLM raw output: ${result.text}")
            SearchPlan.fromJson(result.text) ?: SearchPlan.fallback(query)
        } catch (e: Exception) {
            android.util.Log.w("PhotoSearchVM", "SLM inference failed: ${e.message}")
            SearchPlan.fallback(query)
        }
    }

    /**
     * Set SLM planner engine availability
     * Called from MainActivity when edgeq_planner_tinyllama_q4_k_m.gguf is loaded
     */
    fun setSlmAvailable(available: Boolean, engine: AndroidLlamaCppEngine? = null) {
        slmEngine = if (available) engine else null
        _uiState.value = _uiState.value.copy(isSlmAvailable = available)
        android.util.Log.d("PhotoSearchVM", "SLM planner availability: $available")

        // TinyLlama upgrades the keyword channel's term decomposition — refresh results now
        if (available && _uiState.value.searchQuery.isNotBlank()) {
            search()
        }
    }

    /**
     * Start VLM indexing - analyzes photos with Vision LLM in background
     */
    fun startVlmIndexing() {
        if (_uiState.value.isVlmIndexing) {
            android.util.Log.w("PhotoSearchVM", "VLM indexing already in progress")
            return
        }
        
        val indexer = vlmIndexer
        if (indexer == null || !indexer.isAvailable()) {
            _uiState.value = _uiState.value.copy(
                error = "VLM not available. Please load a vision model first."
            )
            return
        }
        
        vlmIndexingJob = scope.launch {
            _uiState.value = _uiState.value.copy(
                isVlmIndexing = true,
                vlmIndexProgress = 0f,
                vlmIndexMessage = "Starting VLM indexing..."
            )
            
            try {
                // Get current folder path and pass to indexer
                val currentFolderPath = photoIndexer?.getScanFolderPath()
                android.util.Log.d("PhotoSearchVM", "VLM indexing folder: $currentFolderPath")
                
                indexer.indexAllPhotos(folderPath = currentFolderPath).collect { progress ->
                    val progressPercent = if (progress.total > 0) 
                        progress.current.toFloat() / progress.total 
                    else 0f
                    
                    _uiState.value = _uiState.value.copy(
                        vlmIndexProgress = progressPercent,
                        vlmIndexMessage = progress.message,
                        vlmIndexedCount = progress.current
                    )
                    
                    if (progress.status == VlmIndexStatus.COMPLETED || 
                        progress.status == VlmIndexStatus.CANCELLED) {
                        _uiState.value = _uiState.value.copy(
                            isVlmIndexing = false,
                            vlmIndexMessage = progress.message
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("PhotoSearchVM", "VLM indexing error: ${e.message}", e)
                _uiState.value = _uiState.value.copy(
                    isVlmIndexing = false,
                    error = "VLM indexing error: ${e.message}"
                )
            }
        }
    }
    
    /**
     * Cancel VLM indexing
     */
    fun cancelVlmIndexing() {
        vlmIndexingJob?.cancel()
        _uiState.value = _uiState.value.copy(
            isVlmIndexing = false,
            vlmIndexMessage = "VLM indexing cancelled"
        )
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
     * Register loaders that MainActivity calls to start model loading on demand.
     * Callbacks keep the ViewModel free of Context references.
     */
    fun setVlmLoader(loader: () -> Unit) { vlmLoader = loader }
    fun setSlmLoader(loader: () -> Unit) { slmLoader = loader }

    /** Called by MainActivity at the start and end of VLM model loading. */
    fun setVlmLoading(loading: Boolean) {
        _uiState.value = _uiState.value.copy(isVlmLoading = loading)
    }

    /** Called by MainActivity at the start and end of SLM model loading. */
    fun setSlmLoading(loading: Boolean) {
        _uiState.value = _uiState.value.copy(isSlmLoading = loading)
    }

    /**
     * Load the engine backing one search channel on demand. `mode` no longer selects an
     * exclusive search backend — every loaded channel contributes to the same unified
     * ranking — it only identifies which badge was tapped and which engine to lazy-load.
     * VLM loads the vision model used for indexing (background captioning), not search:
     * VLM tags/description are already stored in the DB and searched for free once indexed.
     */
    fun setSearchMode(mode: SearchMode) {
        val state = _uiState.value

        when (mode) {
            SearchMode.ML_BASED -> if (!clipInitialized && !clipLoading) {
                android.util.Log.d("PhotoSearchVM", "CLIP not loaded — triggering lazy load")
                loadClipIfNeeded()
            }
            SearchMode.VLM_BASED -> if (!state.isVlmAvailable && !state.isVlmLoading) {
                android.util.Log.d("PhotoSearchVM", "VLM not loaded — triggering lazy load")
                vlmLoader?.invoke()
            }
            SearchMode.SLM_BASED -> if (!state.isSlmAvailable && !state.isSlmLoading) {
                android.util.Log.d("PhotoSearchVM", "SLM not loaded — triggering lazy load")
                slmLoader?.invoke()
            }
        }

        _uiState.value = state.copy(searchMode = mode)
        android.util.Log.d("PhotoSearchVM", "Active channel badge → $mode")

        if (_uiState.value.searchQuery.isNotBlank()) {
            search()
        }
    }
    
    /**
     * Update VLM availability status and initialize analyzer
     * Called from MainActivity when VLM is loaded
     */
    fun setVlmAvailable(available: Boolean) {
        _uiState.value = _uiState.value.copy(isVlmAvailable = available)
        android.util.Log.d("PhotoSearchVM", "VLM availability: $available")
        
        if (available) {
            vlmAnalyzer = VlmImageAnalyzer(context.applicationContext)
            android.util.Log.d("PhotoSearchVM", "VlmImageAnalyzer initialized")
            
            // Initialize VlmIndexer for pre-indexing
            val vectorStore = photoIndexer?.getVectorStore()
            if (vectorStore != null && vlmAnalyzer != null) {
                vlmIndexer = VlmIndexer(vlmAnalyzer!!, vectorStore)
                android.util.Log.d("PhotoSearchVM", "VlmIndexer initialized")
                
                // Update VLM indexed count in UI
                val vlmIndexedCount = vectorStore.getVlmIndexedCount()
                _uiState.value = _uiState.value.copy(vlmIndexedCount = vlmIndexedCount)
            }
        } else {
            vlmAnalyzer = null
            vlmIndexer = null
        }
        // Note: loading the VLM model only enables VLM *indexing* (background captioning).
        // Search already reads vlm_description/vlm_tags from the DB regardless of whether
        // this model is loaded, so there's no search result to refresh here.
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
    fun reportError(message: String) {
        _uiState.value = _uiState.value.copy(error = message)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
    
    /**
     * Cleanup
     */
    fun close() {
        val ownerJob = scope.coroutineContext[Job]
        ownerJob?.cancel()
        CoroutineScope(Dispatchers.IO).launch {
            ownerJob?.join()
            photoIndexer?.close()
            slmEngine?.unload()
            slmEngine = null
        }
    }
    
    /**
     * Get PhotoIndexer for background service
     */
    fun getPhotoIndexer(): PhotoIndexer? = photoIndexer
    
    /**
     * Get VlmIndexer for background service
     */
    fun getVlmIndexer(): VlmIndexer? = vlmIndexer
    
    /**
     * Update UI state from background service progress
     */
    fun updateFromServiceState(state: IndexingState) {
        when (state.type) {
            IndexingType.ML -> {
                _uiState.value = _uiState.value.copy(
                    isIndexing = state.isRunning,
                    indexingProgress = state.progress,
                    indexingMessage = state.message,
                    totalPhotos = if (state.total > 0) state.total else _uiState.value.totalPhotos
                )
                // If finished, refresh list
                if (!state.isRunning && state.progress >= 1.0f) {
                    refreshPhotoList()
                }
            }
            IndexingType.VLM -> {
                _uiState.value = _uiState.value.copy(
                    isVlmIndexing = state.isRunning,
                    vlmIndexProgress = state.progress,
                    vlmIndexMessage = state.message,
                    vlmIndexedCount = state.current
                )
            }
            IndexingType.NONE -> {
                // Service idle, ensure UI reflects that
                if (_uiState.value.isIndexing) {
                    _uiState.value = _uiState.value.copy(isIndexing = false)
                }
                if (_uiState.value.isVlmIndexing) {
                    _uiState.value = _uiState.value.copy(isVlmIndexing = false)
                }
            }
        }
    }
}
