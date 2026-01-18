package com.aksoyapps.edgeqslm.photos

import android.content.Context
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * Photo Indexer - scans a specific folder and creates embeddings
 */
class PhotoIndexer(private val context: Context) {
    
    private val vectorStore = PhotoVectorStore(context)
    private var imageEncoder: ClipImageEncoder? = null
    private var textEncoder: ClipTextEncoder? = null
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    
    // Current scan folder
    private var scanFolder: File? = null
    
    /**
     * Initialize encoders
     */
    fun initialize(imageModelPath: String, textModelPath: String, vocabPath: String? = null): Boolean {
        imageEncoder = ClipImageEncoder(context).also {
            if (!it.initialize(imageModelPath)) return false
        }
        textEncoder = ClipTextEncoder(context).also {
            if (!it.initialize(textModelPath, vocabPath)) return false
        }
        android.util.Log.d("PhotoIndexer", "Encoders initialized successfully")
        return true
    }
    
    /**
     * Set the folder to scan
     */
    fun setScanFolder(folderPath: String) {
        scanFolder = File(folderPath)
        android.util.Log.d("PhotoIndexer", "Scan folder set to: $folderPath, exists: ${scanFolder?.exists()}")
    }
    
    /**
     * Get default test folder path - use Screenshots folder
     */
    fun getDefaultFolder(): String {
        // Try common screenshot locations
        val paths = listOf(
            "/sdcard/Pictures/Screenshots",
            "/sdcard/DCIM/Screenshots", 
            "/storage/emulated/0/Pictures/Screenshots",
            "/storage/emulated/0/DCIM/Screenshots"
        )
        
        for (path in paths) {
            val folder = File(path)
            if (folder.exists() && folder.isDirectory) {
                android.util.Log.d("PhotoIndexer", "Found screenshots folder: $path")
                return path
            }
        }
        
        // Fallback to app directory
        val appDir = context.getExternalFilesDir(null)
        return File(appDir, "test_photos").absolutePath
    }
    
    /**
     * Get all photos in the scan folder
     */
    fun getPhotosInFolder(): List<PhotoFile> {
        val folder = scanFolder ?: File(getDefaultFolder())
        
        android.util.Log.d("PhotoIndexer", "Getting photos from: ${folder.absolutePath}")
        android.util.Log.d("PhotoIndexer", "Folder exists: ${folder.exists()}, isDirectory: ${folder.isDirectory}")
        
        if (!folder.exists()) {
            android.util.Log.w("PhotoIndexer", "Folder does not exist, creating...")
            folder.mkdirs()
            return emptyList()
        }
        
        val files = folder.listFiles()
        android.util.Log.d("PhotoIndexer", "Total files in folder: ${files?.size ?: 0}")
        
        val indexed = vectorStore.getAllIndexedPaths()
        
        val photos = files
            ?.filter { it.isFile && it.extension.lowercase() in listOf("jpg", "jpeg", "png", "webp") }
            ?.map { file ->
                PhotoFile(
                    path = file.absolutePath,
                    name = file.name,
                    size = file.length(),
                    isIndexed = indexed.contains(file.absolutePath),
                    lastModified = file.lastModified()
                )
            }
            ?.sortedBy { it.name }
            ?: emptyList()
        
        android.util.Log.d("PhotoIndexer", "Image files found: ${photos.size}")
        return photos
    }
    
    /**
     * Index all photos in the folder
     */
    fun indexFolder(): Flow<IndexingProgress> = flow {
        val photos = getPhotosInFolder()
        val total = photos.size
        var indexed = 0
        var skipped = 0
        var failed = 0
        
        emit(IndexingProgress(0, total, "Starting indexing..."))
        
        if (total == 0) {
            emit(IndexingProgress(0, 0, "No photos found in folder", isComplete = true))
            return@flow
        }
        
        for (photo in photos) {
            try {
                // Check if already indexed and up-to-date
                if (!vectorStore.needsIndexing(photo.path, photo.lastModified)) {
                    skipped++
                    continue
                }
                
                // Index the photo
                indexPhoto(photo)
                indexed++
                
                emit(IndexingProgress(
                    current = indexed + skipped,
                    total = total,
                    message = "Indexed: $indexed, Skipped: $skipped"
                ))
                
            } catch (e: Exception) {
                android.util.Log.e("PhotoIndexer", "Failed to index ${photo.path}: ${e.message}")
                failed++
            }
        }
        
        emit(IndexingProgress(
            current = total,
            total = total,
            message = "Complete! Indexed: $indexed, Skipped: $skipped, Failed: $failed",
            isComplete = true
        ))
    }.flowOn(Dispatchers.IO)
    
    /**
     * Index a single photo
     */
    private suspend fun indexPhoto(photo: PhotoFile) {
        val bitmap = BitmapFactory.decodeFile(photo.path) ?: return
        
        // Generate image embedding
        val imageEmbedding = imageEncoder?.encode(bitmap)
        
        // Run OCR
        val ocrText = runOcr(photo.path)
        
        // Generate text embedding for OCR text
        val textEmbedding = if (!ocrText.isNullOrBlank()) {
            textEncoder?.encode(ocrText)
        } else null
        
        // Store in database
        vectorStore.upsertPhoto(PhotoRecord(
            filePath = photo.path,
            fileName = photo.name,
            dateTaken = photo.lastModified,
            ocrText = ocrText,
            imageEmbedding = imageEmbedding,
            textEmbedding = textEmbedding,
            fileModified = photo.lastModified
        ))
        
        bitmap.recycle()
    }
    
    /**
     * Run OCR on an image
     */
    private suspend fun runOcr(imagePath: String): String? {
        return suspendCancellableCoroutine { continuation ->
            try {
                val bitmap = BitmapFactory.decodeFile(imagePath)
                if (bitmap == null) {
                    continuation.resume(null)
                    return@suspendCancellableCoroutine
                }
                
                val image = InputImage.fromBitmap(bitmap, 0)
                
                textRecognizer.process(image)
                    .addOnSuccessListener { result ->
                        val text = result.text.takeIf { it.isNotBlank() }
                        bitmap.recycle()
                        continuation.resume(text)
                    }
                    .addOnFailureListener { 
                        bitmap.recycle()
                        continuation.resume(null)
                    }
            } catch (e: Exception) {
                continuation.resume(null)
            }
        }
    }
    
    /**
     * Search photos by text query
     */
    fun search(query: String): List<PhotoSearchResult> {
        // Generate query embedding for image search
        val queryEmbedding = textEncoder?.encode(query)
        
        // Hybrid search
        return vectorStore.hybridSearch(
            textQuery = query,
            queryEmbedding = queryEmbedding,
            textWeight = 0.3f,
            imageWeight = 0.7f,
            limit = 50
        )
    }
    
    /**
     * Get indexing stats
     */
    fun getStats(): IndexingStats {
        val photos = getPhotosInFolder()
        val indexedCount = photos.count { it.isIndexed }
        return IndexingStats(
            totalPhotos = photos.size,
            indexedPaths = indexedCount
        )
    }
    
    fun close() {
        imageEncoder?.close()
        textEncoder?.close()
        textRecognizer.close()
        vectorStore.close()
    }
}

/**
 * Photo file info
 */
data class PhotoFile(
    val path: String,
    val name: String,
    val size: Long,
    val isIndexed: Boolean,
    val lastModified: Long
)

/**
 * Indexing progress
 */
data class IndexingProgress(
    val current: Int,
    val total: Int,
    val message: String,
    val isComplete: Boolean = false
) {
    val progress: Float get() = if (total > 0) current.toFloat() / total else 0f
}

/**
 * Indexing stats
 */
data class IndexingStats(
    val totalPhotos: Int,
    val indexedPaths: Int
)
