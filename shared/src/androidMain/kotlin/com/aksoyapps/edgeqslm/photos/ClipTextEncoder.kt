package com.aksoyapps.edgeqslm.photos

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.aksoyapps.edgeqslm.diagnostics.ThesisDiagnostics
import java.nio.LongBuffer

/**
 * CLIP Text Encoder using ONNX Runtime
 * Converts text queries to 512-dimensional embeddings for image search
 */
class ClipTextEncoder(private val context: Context) : AutoCloseable {
    var lastTokenizeMs: Double = 0.0
        private set
    
    private val diagnostics by lazy { ThesisDiagnostics.get(context) }
    private var ortEnv: OrtEnvironment? = null
    private var session: OrtSession? = null
    
    private val maxTokens = 77  // CLIP max sequence length
    private val embeddingDim = 512
    
    // Simple vocabulary for basic tokenization
    private var vocab: Map<String, Int>? = null
    
    /**
     * Initialize the ONNX session with the CLIP text model
     */
    fun initialize(modelPath: String, vocabPath: String? = null): Boolean {
        val started = System.nanoTime()
        diagnostics.modelState("clip_text", "loading")
        return try {
            ortEnv = OrtEnvironment.getEnvironment()
            
            OrtSession.SessionOptions().use { options ->
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                session = ortEnv?.createSession(modelPath, options)
            }
            
            vocabPath?.let { loadVocabulary(it) }
            
            diagnostics.event("clip_text_load", mapOf("duration_ms" to (System.nanoTime() - started) / 1e6))
            diagnostics.modelState("clip_text", "loaded")
            true
        } catch (e: Exception) {
            android.util.Log.e("ClipTextEncoder", "Failed to load model: ${e.message}", e)
            diagnostics.modelState("clip_text", "load_failed")
            false
        }
    }
    
    private fun loadVocabulary(vocabPath: String) {
        try {
            val vocabFile = java.io.File(vocabPath, "vocab.json")
            if (vocabFile.exists()) {
                val content = vocabFile.readText()
                vocab = parseVocabJson(content)
                android.util.Log.d("ClipTextEncoder", "Vocabulary loaded: ${vocab?.size} tokens")
            }
        } catch (e: Exception) {
            android.util.Log.w("ClipTextEncoder", "Could not load vocabulary: ${e.message}")
        }
    }
    
    private fun parseVocabJson(json: String): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        val regex = """"([^"]+)":\s*(\d+)""".toRegex()
        regex.findAll(json).forEach { match ->
            val word = match.groupValues[1]
            val id = match.groupValues[2].toIntOrNull() ?: return@forEach
            result[word] = id
        }
        return result
    }
    
    /**
     * Generate embedding for a text query
     */
    fun encode(text: String): FloatArray? {
        val env = ortEnv ?: return null
        val sess = session ?: return null
        val encodeStarted = System.nanoTime()
        
        try {
            val tokenStarted = System.nanoTime()
            val (inputIds, attentionMask) = tokenize(text)
            lastTokenizeMs = (System.nanoTime() - tokenStarted) / 1e6
            
            val inputIdsBuffer = LongBuffer.wrap(inputIds.map { it.toLong() }.toLongArray())
            val attentionMaskBuffer = LongBuffer.wrap(attentionMask.map { it.toLong() }.toLongArray())
            
            val shape = longArrayOf(1, maxTokens.toLong())
            return OnnxTensor.createTensor(env, inputIdsBuffer, shape).use { inputIdsTensor ->
                OnnxTensor.createTensor(env, attentionMaskBuffer, shape).use { attentionMaskTensor ->
                    sess.run(mapOf("input_ids" to inputIdsTensor,
                        "attention_mask" to attentionMaskTensor)).use { results ->
                        val output = results[0].value as Array<FloatArray>
                        output[0]
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ClipTextEncoder", "Inference error: ${e.message}", e)
            return null
        } finally {
            diagnostics.event("clip_text_encode", mapOf("duration_ms" to (System.nanoTime() - encodeStarted) / 1e6))
        }
    }
    
    /**
     * Tokenization for CLIP - vocabulary uses word</w> format
     */
    private fun tokenize(text: String): Pair<IntArray, IntArray> {
        val inputIds = IntArray(maxTokens) { 0 }
        val attentionMask = IntArray(maxTokens) { 0 }
        
        val startToken = 49406  // startoftext
        val endToken = 49407    // endoftext
        
        inputIds[0] = startToken
        attentionMask[0] = 1
        
        // Clean and split text
        val cleanText = text.lowercase().replace(Regex("[^a-z0-9\\s]"), " ")
        val words = cleanText.split(Regex("\\s+")).filter { it.isNotBlank() }
        var pos = 1
        
        
        for (word in words) {
            if (pos >= maxTokens - 1) break
            
            // CLIP vocab uses word</w> format for complete words
            // Try with </w> suffix first (complete word), then without (subword)
            val wordWithSuffix = "$word</w>"
            val tokenId = vocab?.get(wordWithSuffix) ?: vocab?.get(word) ?: 0
            
            
            if (tokenId > 0) {
                inputIds[pos] = tokenId
                attentionMask[pos] = 1
                pos++
            }
        }
        
        // Add end token
        if (pos < maxTokens) {
            inputIds[pos] = endToken
            attentionMask[pos] = 1
        }
        
        
        return Pair(inputIds, attentionMask)
    }
    
    override fun close() {
        session?.close()
        session = null
        vocab = null
        ortEnv = null
        diagnostics.modelState("clip_text", "closed")
    }
    
    companion object {
        const val EMBEDDING_DIM = 512
    }
}
