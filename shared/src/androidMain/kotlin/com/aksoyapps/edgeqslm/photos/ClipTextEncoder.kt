package com.aksoyapps.edgeqslm.photos

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.LongBuffer

/**
 * CLIP Text Encoder using ONNX Runtime
 * Converts text queries to 512-dimensional embeddings for image search
 */
class ClipTextEncoder(private val context: Context) {
    
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
        return try {
            ortEnv = OrtEnvironment.getEnvironment()
            
            val sessionOptions = OrtSession.SessionOptions()
            sessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            
            session = ortEnv?.createSession(modelPath, sessionOptions)
            
            vocabPath?.let { loadVocabulary(it) }
            
            android.util.Log.d("ClipTextEncoder", "Model loaded from: $modelPath")
            true
        } catch (e: Exception) {
            android.util.Log.e("ClipTextEncoder", "Failed to load model: ${e.message}", e)
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
        
        try {
            val (inputIds, attentionMask) = tokenize(text)
            
            val inputIdsBuffer = LongBuffer.wrap(inputIds.map { it.toLong() }.toLongArray())
            val attentionMaskBuffer = LongBuffer.wrap(attentionMask.map { it.toLong() }.toLongArray())
            
            val shape = longArrayOf(1, maxTokens.toLong())
            val inputIdsTensor = OnnxTensor.createTensor(env, inputIdsBuffer, shape)
            val attentionMaskTensor = OnnxTensor.createTensor(env, attentionMaskBuffer, shape)
            
            val inputs = mapOf(
                "input_ids" to inputIdsTensor,
                "attention_mask" to attentionMaskTensor
            )
            val results = sess.run(inputs)
            
            val output = results[0].value as Array<FloatArray>
            val embedding = output[0]
            
            inputIdsTensor.close()
            attentionMaskTensor.close()
            results.close()
            
            return embedding
        } catch (e: Exception) {
            android.util.Log.e("ClipTextEncoder", "Inference error: ${e.message}", e)
            return null
        }
    }
    
    /**
     * Simple tokenization for CLIP
     */
    private fun tokenize(text: String): Pair<IntArray, IntArray> {
        val inputIds = IntArray(maxTokens) { 0 }
        val attentionMask = IntArray(maxTokens) { 0 }
        
        val startToken = 49406  // startoftext
        val endToken = 49407    // endoftext
        val padToken = 0
        
        inputIds[0] = startToken
        attentionMask[0] = 1
        
        // Simple word-level tokenization
        val words = text.lowercase().split(Regex("\\s+"))
        var pos = 1
        
        for (word in words) {
            if (pos >= maxTokens - 1) break
            
            // Look up in vocabulary or use unknown token
            val tokenId = vocab?.get(word) ?: vocab?.get(word + "</w>") ?: 0
            inputIds[pos] = tokenId
            attentionMask[pos] = 1
            pos++
        }
        
        // Add end token
        if (pos < maxTokens) {
            inputIds[pos] = endToken
            attentionMask[pos] = 1
        }
        
        return Pair(inputIds, attentionMask)
    }
    
    fun close() {
        session?.close()
        ortEnv?.close()
        session = null
        ortEnv = null
    }
    
    companion object {
        const val EMBEDDING_DIM = 512
    }
}
