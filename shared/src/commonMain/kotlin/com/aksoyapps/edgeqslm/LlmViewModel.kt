package com.aksoyapps.edgeqslm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI State for the LLM inference screen.
 * Contains all metrics needed for thesis performance analysis.
 */
data class UiState(
    // Input
    val prompt: String = "What is the capital of Poland?",
    val maxTokens: Int = 128,            // Default tokens for UI
    val temperature: Float = 0.3f,
    val topP: Float = 0.9f,              // Nucleus sampling
    val repeatPenalty: Float = 1.1f,     // Prevent repetition
    val useChatTemplate: Boolean = true,  // Use ChatML format
    
    // Status
    val isLoading: Boolean = false,
    val isModelLoaded: Boolean = false,
    val loadingMessage: String = "",
    val error: String? = null,
    val isSimulation: Boolean = false,
    
    // Download Status
    val isDownloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val downloadedBytes: Long = 0,
    val downloadTotalBytes: Long = 0,
    val downloadSpeedBytesPerSec: Long = 0,
    val isModelCheckCompleted: Boolean = false,
    val modelExists: Boolean = false,
    
    // Model Selection
    val availableModels: List<ModelInfo> = emptyList(),
    val selectedModelIndex: Int = 0,
    
    // Debug flag for testing download UI
    val debugForceNoModel: Boolean = false,
    
    // Generation Output
    val resultText: String = "",
    
    // Performance Metrics (Tez için önemli!)
    val totalLatencyMs: Long = 0,        // Toplam süre
    val prefillTimeMs: Long = 0,          // TTFT - Time To First Token
    val decodeTimeMs: Long = 0,           // Decode phase süresi
    val tokensGenerated: Int = 0,         // Üretilen token sayısı
    val tokensPerSecond: Float = 0f,      // Decode hızı
    val memoryUsageMb: Float = 0f,        // Peak memory (MB)
    
    // Model info
    val modelPath: String = ""
)

/**
 * Preset prompts for quick testing
 */
data class PresetPrompt(
    val label: String,
    val prompt: String,
    val category: String
)

val PRESET_PROMPTS = listOf(
    PresetPrompt("🇵🇱 Capital", "What is the capital of Poland?", "Short"),
    PresetPrompt("🧮 Math", "What is 25 multiplied by 17?", "Short"),
    PresetPrompt("💻 Quantum", "Explain quantum computing in simple terms.", "Medium"),
    PresetPrompt("🐍 Python", "Write a Python function to calculate factorial.", "Code"),
    PresetPrompt("📖 Story", "Write a short story about a robot learning to feel emotions.", "Creative"),
    PresetPrompt("🔬 Science", "Explain the theory of relativity in 100 words.", "Medium")
)

/**
 * ViewModel for LLM inference operations.
 * Manages model loading, generation, and UI state.
 */
class LlmViewModel(
    private val engine: LlmEngine, 
    initialModelPath: String,
    private val repository: ModelRepository? = null
) {
    private val _uiState = MutableStateFlow(
        UiState(
            isSimulation = engine.isSimulation,
            modelPath = initialModelPath
        )
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main)
    
    init {
        loadAvailableModels()
        checkModelExistence()
    }
    
    /**
     * Load available models from repository
     */
    fun loadAvailableModels() {
        repository?.let { repo ->
            scope.launch {
                val models = repo.getAvailableModels()
                _uiState.value = _uiState.value.copy(
                    availableModels = models,
                    selectedModelIndex = 0
                )
            }
        }
    }
    
    /**
     * Select a model from the available list
     */
    fun selectModel(index: Int) {
        val models = _uiState.value.availableModels
        if (index >= 0 && index < models.size) {
            val selected = models[index]
            repository?.setSelectedModel(selected.path)
            _uiState.value = _uiState.value.copy(
                selectedModelIndex = index,
                modelPath = selected.path
            )
        }
    }
    
    /**
     * Toggle debug flag for forcing "no model found"
     */
    fun setDebugForceNoModel(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(debugForceNoModel = enabled)
        // Note: This flag is checked in checkModelExistence
        checkModelExistence()
    }
    
    /**
     * Check if model file exists on device
     */
    fun checkModelExistence() {
        repository?.let { repo ->
            scope.launch {
                // Set forceModelNotFound on Android repository
                try {
                    val repoField = repo::class.java.getDeclaredField("forceModelNotFound")
                    repoField.isAccessible = true
                    repoField.setBoolean(repo, _uiState.value.debugForceNoModel)
                } catch (e: Exception) {
                    // Ignore on iOS or if field doesn't exist
                }
                
                val exists = repo.isModelDownloaded()
                _uiState.value = _uiState.value.copy(
                    modelExists = exists,
                    isModelCheckCompleted = true,
                    modelPath = if (exists) repo.getModelPath() else _uiState.value.modelPath
                )
            }
        }
    }
    
    /**
     * Download the currently selected model from dropdown.
     * Uses downloadModelByInfo to download based on ModelInfo.
     */
    fun downloadModel() {
        val repo = repository ?: run {
            _uiState.value = _uiState.value.copy(error = "Repository not initialized")
            return
        }
        
        // Get the selected model from the list
        val selectedModel = _uiState.value.availableModels.getOrNull(_uiState.value.selectedModelIndex)
        
        if (selectedModel == null) {
            _uiState.value = _uiState.value.copy(error = "No model selected")
            return
        }
        
        if (!selectedModel.isDownloadable) {
            _uiState.value = _uiState.value.copy(error = "This model is already downloaded")
            return
        }
        
        scope.launch {
            _uiState.value = _uiState.value.copy(
                isDownloading = true,
                downloadProgress = 0f,
                error = null
            )
            
            // Use the new downloadModelByInfo function
            repo.downloadModelByInfo(selectedModel).collect { status ->
                when (status) {
                    is DownloadStatus.Progress -> {
                        _uiState.value = _uiState.value.copy(
                            downloadProgress = status.progress,
                            downloadedBytes = status.downloadedBytes,
                            downloadTotalBytes = status.totalBytes,
                            downloadSpeedBytesPerSec = status.speedBytesPerSec
                        )
                    }
                    is DownloadStatus.Completed -> {
                        _uiState.value = _uiState.value.copy(
                            isDownloading = false,
                            downloadProgress = 1f,
                            modelExists = true,
                            modelPath = repo.getModelPath()
                        )
                        // Refresh model list after download
                        loadAvailableModels()
                    }
                    is DownloadStatus.Error -> {
                        _uiState.value = _uiState.value.copy(
                            isDownloading = false,
                            downloadProgress = 0f,
                            error = "Download failed: ${status.message}"
                        )
                    }
                }
            }
        }
    }

    // --- Input Handlers ---
    
    fun onPromptChanged(newPrompt: String) {
        _uiState.value = _uiState.value.copy(prompt = newPrompt)
    }
    
    fun onMaxTokensChanged(tokens: Int) {
        _uiState.value = _uiState.value.copy(maxTokens = tokens.coerceIn(16, 512))
    }
    
    fun onTemperatureChanged(temp: Float) {
        _uiState.value = _uiState.value.copy(temperature = temp.coerceIn(0.1f, 2.0f))
    }
    
    fun onTopPChanged(topP: Float) {
        _uiState.value = _uiState.value.copy(topP = topP.coerceIn(0.1f, 1.0f))
    }
    
    fun onRepeatPenaltyChanged(penalty: Float) {
        _uiState.value = _uiState.value.copy(repeatPenalty = penalty.coerceIn(1.0f, 2.0f))
    }
    
    fun onChatTemplateChanged(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(useChatTemplate = enabled)
    }
    
    fun onModelPathChanged(path: String) {
        _uiState.value = _uiState.value.copy(modelPath = path)
    }
    
    fun selectPresetPrompt(preset: PresetPrompt) {
        _uiState.value = _uiState.value.copy(prompt = preset.prompt)
    }
    
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
    
    fun clearResults() {
        _uiState.value = _uiState.value.copy(
            resultText = "",
            totalLatencyMs = 0,
            prefillTimeMs = 0,
            decodeTimeMs = 0,
            tokensGenerated = 0,
            tokensPerSecond = 0f,
            memoryUsageMb = 0f
        )
    }

    // --- Model Operations ---
    
    fun loadModel() {
        val path = _uiState.value.modelPath
        if (path.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "Model path cannot be empty")
            return
        }
        
        scope.launch {
            try {
                _uiState.value = _uiState.value.copy(
                    isLoading = true, 
                    error = null,
                    loadingMessage = "Loading model..."
                )
                
                val success = engine.loadModel(path)
                
                if (success) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false, 
                        isModelLoaded = true,
                        loadingMessage = ""
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        loadingMessage = "",
                        error = "Failed to load model.\nPath: $path\n\nCheck if file exists and has read permissions."
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false, 
                    loadingMessage = "",
                    error = "Exception: ${e.message}"
                )
            }
        }
    }
    
    fun unloadModel() {
        try {
            engine.unload()
            _uiState.value = _uiState.value.copy(
                isModelLoaded = false,
                resultText = "",
                totalLatencyMs = 0,
                prefillTimeMs = 0,
                decodeTimeMs = 0,
                tokensGenerated = 0,
                tokensPerSecond = 0f,
                memoryUsageMb = 0f
            )
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(error = "Unload failed: ${e.message}")
        }
    }

    // --- Generation ---
    
    fun generate() {
        if (!_uiState.value.isModelLoaded) {
            _uiState.value = _uiState.value.copy(error = "Model not loaded. Tap 'Load Model' first.")
            return
        }
        
        val prompt = _uiState.value.prompt
        if (prompt.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "Prompt cannot be empty")
            return
        }

        scope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true, 
                error = null, 
                resultText = "",
                loadingMessage = "Generating..."
            )
            
            try {
                val request = GenerationRequest(
                    prompt = prompt,
                    maxTokens = _uiState.value.maxTokens,
                    temperature = _uiState.value.temperature,
                    topP = _uiState.value.topP,
                    repeatPenalty = _uiState.value.repeatPenalty,
                    useChatTemplate = _uiState.value.useChatTemplate
                )
                
                val result = engine.generate(request)

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    loadingMessage = "",
                    resultText = result.text,
                    totalLatencyMs = result.latencyMs,
                    prefillTimeMs = result.prefillTimeMs,
                    decodeTimeMs = result.decodeTimeMs,
                    tokensGenerated = result.tokensGenerated,
                    tokensPerSecond = result.tokensPerSecond,
                    memoryUsageMb = result.memoryUsageBytes / (1024f * 1024f)
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false, 
                    loadingMessage = "",
                    error = "Generation failed: ${e.message}"
                )
            }
        }
    }
}
