package com.example.edgeqslm

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
    val maxTokens: Int = 256,           // Increased for longer responses
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,              // Nucleus sampling
    val repeatPenalty: Float = 1.1f,     // Prevent repetition
    val useChatTemplate: Boolean = true,  // Use ChatML format
    
    // Status
    val isLoading: Boolean = false,
    val isModelLoaded: Boolean = false,
    val loadingMessage: String = "",
    val error: String? = null,
    val isSimulation: Boolean = false,
    
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
    initialModelPath: String
) {
    private val _uiState = MutableStateFlow(
        UiState(
            isSimulation = engine.isSimulation,
            modelPath = initialModelPath
        )
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main)

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
