package com.aksoyapps.edgeqslm.photos

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.nio.FloatBuffer
import java.io.File

/**
 * CLIP Image Encoder using ONNX Runtime
 * Converts images to 512-dimensional embeddings for similarity search
 */
class ClipImageEncoder(private val context: Context) {
    
    private var ortEnv: OrtEnvironment? = null
    private var session: OrtSession? = null
    
    private val imageSize = 224
    private val embeddingDim = 512
    
    // CLIP ImageNet normalization values
    private val mean = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
    private val std = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)
    
    /**
     * Initialize the ONNX session with the CLIP image model
     * @param modelPath Path to clip-vit-b32-image.onnx file
     */
    fun initialize(modelPath: String): Boolean {
        return try {
            ortEnv = OrtEnvironment.getEnvironment()
            
            val sessionOptions = OrtSession.SessionOptions()
            sessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            
            session = ortEnv?.createSession(modelPath, sessionOptions)
            
            android.util.Log.d("ClipImageEncoder", "Model loaded successfully from: $modelPath")
            android.util.Log.d("ClipImageEncoder", "Input: ${session?.inputNames}")
            android.util.Log.d("ClipImageEncoder", "Output: ${session?.outputNames}")
            
            true
        } catch (e: Exception) {
            android.util.Log.e("ClipImageEncoder", "Failed to load model: ${e.message}", e)
            false
        }
    }
    
    /**
     * Generate embedding for an image file
     */
    fun encode(imagePath: String): FloatArray? {
        val bitmap = BitmapFactory.decodeFile(imagePath) ?: return null
        return encode(bitmap)
    }
    
    /**
     * Generate embedding for a Bitmap
     */
    fun encode(bitmap: Bitmap): FloatArray? {
        val env = ortEnv ?: return null
        val sess = session ?: return null
        
        try {
            // Preprocess image
            val inputTensor = preprocessImage(bitmap, env)
            
            // Run inference
            val inputs = mapOf("pixel_values" to inputTensor)
            val results = sess.run(inputs)
            
            // Get output
            val output = results[0].value as Array<FloatArray>
            val embedding = output[0]
            
            inputTensor.close()
            results.close()
            
            return embedding
            
        } catch (e: Exception) {
            android.util.Log.e("ClipImageEncoder", "Inference error: ${e.message}", e)
            return null
        }
    }
    
    /**
     * Preprocess image for CLIP:
     * 1. Resize to 224x224
     * 2. Convert to RGB float tensor
     * 3. Normalize with CLIP mean/std
     */
    private fun preprocessImage(bitmap: Bitmap, env: OrtEnvironment): OnnxTensor {
        // Resize
        val resized = Bitmap.createScaledBitmap(bitmap, imageSize, imageSize, true)
        
        // Create float buffer for CHW format (batch=1, channels=3, height=224, width=224)
        val buffer = FloatBuffer.allocate(1 * 3 * imageSize * imageSize)
        
        // Extract pixels and normalize
        val pixels = IntArray(imageSize * imageSize)
        resized.getPixels(pixels, 0, imageSize, 0, 0, imageSize, imageSize)
        
        // Convert to CHW format with normalization
        for (c in 0 until 3) {
            for (y in 0 until imageSize) {
                for (x in 0 until imageSize) {
                    val pixel = pixels[y * imageSize + x]
                    val value = when (c) {
                        0 -> ((pixel shr 16) and 0xFF) / 255.0f  // R
                        1 -> ((pixel shr 8) and 0xFF) / 255.0f   // G
                        2 -> (pixel and 0xFF) / 255.0f           // B
                        else -> 0f
                    }
                    // Normalize
                    buffer.put((value - mean[c]) / std[c])
                }
            }
        }
        buffer.rewind()
        
        // Create tensor
        val shape = longArrayOf(1, 3, imageSize.toLong(), imageSize.toLong())
        return OnnxTensor.createTensor(env, buffer, shape)
    }
    
    /**
     * Calculate cosine similarity between two embeddings
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Embeddings must have same dimension" }
        
        var dot = 0f
        var normA = 0f
        var normB = 0f
        
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        
        return dot / (kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB))
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
