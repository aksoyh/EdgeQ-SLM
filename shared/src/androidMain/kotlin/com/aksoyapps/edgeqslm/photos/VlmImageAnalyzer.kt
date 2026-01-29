package com.aksoyapps.edgeqslm.photos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.aksoyapps.edgeqslm.AndroidLlamaCppEngine
import com.aksoyapps.edgeqslm.GenerationResult
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

/**
 * VlmImageAnalyzer - Uses Vision LLM (e.g., Qwen2.5-VL) to analyze images
 * 
 * This class provides image preprocessing and VLM-based analysis as an
 * alternative to CLIP+OCR approach for photo search.
 */
class VlmImageAnalyzer(private val context: Context) {
    
    companion object {
        private const val TAG = "VlmImageAnalyzer"
        
        // VLM input size - Qwen2.5-VL typically works with 448x448 or 896x896
        private const val VLM_INPUT_SIZE = 448
        
        // Default prompts for image description
        const val PROMPT_DESCRIBE = "Describe this image in detail, including text, objects, colors, and any notable features."
        const val PROMPT_SEARCH_TAGS = "List all searchable keywords and tags for this image, separated by commas."
        const val PROMPT_OCR_FOCUS = "Read and transcribe all text visible in this image."
    }
    
    private var engine: AndroidLlamaCppEngine? = null
    private var isInitialized = false
    
    /**
     * Initialize with an existing AndroidLlamaCppEngine instance
     * The engine should already have the vision projector loaded
     */
    fun initialize(engine: AndroidLlamaCppEngine): Boolean {
        this.engine = engine
        isInitialized = engine.isVisionLoaded()
        
        if (isInitialized) {
            Log.i(TAG, "VlmImageAnalyzer initialized with vision support")
        } else {
            Log.w(TAG, "VlmImageAnalyzer: Vision model not available")
        }
        
        return isInitialized
    }
    
    /**
     * Check if VLM analysis is available
     */
    fun isAvailable(): Boolean = isInitialized && (engine?.isVisionLoaded() == true)
    
    /**
     * Analyze an image file with the VLM
     * 
     * @param imagePath Path to the image file
     * @param prompt Custom prompt for analysis (default: describe image)
     * @param maxTokens Maximum tokens to generate
     * @return VlmAnalysisResult with description and metadata
     */
    suspend fun analyzeImage(
        imagePath: String,
        prompt: String = PROMPT_DESCRIBE,
        maxTokens: Int = 256
    ): VlmAnalysisResult {
        if (!isAvailable()) {
            return VlmAnalysisResult(
                success = false,
                error = "VLM not available",
                imagePath = imagePath
            )
        }
        
        val file = File(imagePath)
        if (!file.exists()) {
            return VlmAnalysisResult(
                success = false,
                error = "Image file not found",
                imagePath = imagePath
            )
        }
        
        return try {
            Log.d(TAG, "Analyzing image: $imagePath")
            
            // Load and preprocess image
            val (imageData, width, height) = preprocessImage(imagePath)
            
            // Run VLM analysis
            val result = engine!!.analyzeImage(
                imageData = imageData,
                width = width,
                height = height,
                prompt = prompt,
                maxTokens = maxTokens,
                temperature = 0.7f
            )
            
            VlmAnalysisResult(
                success = true,
                description = result.text,
                imagePath = imagePath,
                latencyMs = result.latencyMs,
                tokensGenerated = result.tokensGenerated,
                tokensPerSecond = result.tokensPerSecond
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "VLM analysis failed: ${e.message}", e)
            VlmAnalysisResult(
                success = false,
                error = e.message ?: "Unknown error",
                imagePath = imagePath
            )
        }
    }
    
    /**
     * Generate search tags for an image
     */
    suspend fun generateSearchTags(imagePath: String): List<String> {
        val result = analyzeImage(imagePath, PROMPT_SEARCH_TAGS, maxTokens = 128)
        
        return if (result.success && result.description != null) {
            // Parse comma-separated tags
            result.description
                .split(",", "\n")
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() && it.length > 1 }
                .distinct()
        } else {
            emptyList()
        }
    }
    
    /**
     * Check if an image matches a search query using VLM
     * 
     * @param imagePath Path to the image
     * @param query Search query
     * @return Pair of (matches, confidence/relevance description)
     */
    suspend fun matchesQuery(imagePath: String, query: String): VlmMatchResult {
        val prompt = """
            Does this image match the search query: "$query"?
            Answer with:
            1. YES or NO
            2. A confidence score from 0 to 100
            3. Brief explanation why
            
            Format: [YES/NO] [SCORE] [REASON]
        """.trimIndent()
        
        val result = analyzeImage(imagePath, prompt, maxTokens = 64)
        
        return if (result.success && result.description != null) {
            parseMatchResult(result.description, result.latencyMs)
        } else {
            VlmMatchResult(
                matches = false,
                confidence = 0f,
                reason = result.error ?: "Analysis failed",
                latencyMs = result.latencyMs
            )
        }
    }
    
    /**
     * Preprocess image for VLM input
     * - Load image
     * - Resize to VLM input size
     * - Convert to RGB byte array
     */
    private fun preprocessImage(imagePath: String): Triple<ByteArray, Int, Int> {
        // Load bitmap with sampling to avoid OOM on large images
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(imagePath, options)
        
        // Calculate sample size
        val inSampleSize = calculateInSampleSize(
            options.outWidth, options.outHeight,
            VLM_INPUT_SIZE, VLM_INPUT_SIZE
        )
        
        options.apply {
            inJustDecodeBounds = false
            this.inSampleSize = inSampleSize
        }
        
        var bitmap = BitmapFactory.decodeFile(imagePath, options)
            ?: throw IllegalStateException("Failed to decode image: $imagePath")
        
        // Resize to exact VLM input size
        bitmap = Bitmap.createScaledBitmap(bitmap, VLM_INPUT_SIZE, VLM_INPUT_SIZE, true)
        
        // Convert to RGB byte array
        val rgbData = bitmapToRgbByteArray(bitmap)
        
        Log.d(TAG, "Preprocessed image: ${bitmap.width}x${bitmap.height}, ${rgbData.size} bytes")
        
        return Triple(rgbData, bitmap.width, bitmap.height)
    }
    
    /**
     * Calculate inSampleSize for memory-efficient loading
     */
    private fun calculateInSampleSize(
        width: Int, height: Int,
        reqWidth: Int, reqHeight: Int
    ): Int {
        var inSampleSize = 1
        
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            
            while ((halfHeight / inSampleSize) >= reqHeight &&
                   (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        
        return inSampleSize
    }
    
    /**
     * Convert Bitmap to RGB byte array (no alpha channel)
     */
    private fun bitmapToRgbByteArray(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        val rgbData = ByteArray(width * height * 3)
        var idx = 0
        
        for (pixel in pixels) {
            rgbData[idx++] = ((pixel shr 16) and 0xFF).toByte()  // R
            rgbData[idx++] = ((pixel shr 8) and 0xFF).toByte()   // G
            rgbData[idx++] = (pixel and 0xFF).toByte()           // B
        }
        
        return rgbData
    }
    
    /**
     * Parse VLM match result from response text
     */
    private fun parseMatchResult(response: String, latencyMs: Long): VlmMatchResult {
        val upper = response.uppercase().trim()
        
        val matches = upper.startsWith("YES")
        
        // Try to extract confidence score
        val scoreRegex = Regex("""(\d+)""")
        val scoreMatch = scoreRegex.find(response)
        val confidence = scoreMatch?.groupValues?.get(1)?.toFloatOrNull()?.div(100f) ?: 0.5f
        
        // Get the reason (everything after score or after YES/NO)
        val reason = response
            .replace(Regex("""^(YES|NO)\s*\d*""", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifBlank { if (matches) "Content matches query" else "No match found" }
        
        return VlmMatchResult(
            matches = matches,
            confidence = confidence.coerceIn(0f, 1f),
            reason = reason,
            latencyMs = latencyMs
        )
    }
}

/**
 * Result of VLM image analysis
 */
data class VlmAnalysisResult(
    val success: Boolean,
    val description: String? = null,
    val error: String? = null,
    val imagePath: String,
    val latencyMs: Long = 0,
    val tokensGenerated: Int = 0,
    val tokensPerSecond: Float = 0f
)

/**
 * Result of VLM-based query matching
 */
data class VlmMatchResult(
    val matches: Boolean,
    val confidence: Float,
    val reason: String,
    val latencyMs: Long
)
