package com.aksoyapps.edgeqslm.photos

import android.content.Context
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.aksoyapps.edgeqslm.diagnostics.ThesisDiagnostics
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * Photo Indexer - scans a specific folder and creates embeddings
 */
class PhotoIndexer(private val context: Context) {
    
    private val vectorStore = PhotoVectorStore(context)
    private val diagnostics = ThesisDiagnostics.get(context)
    private var imageEncoder: ClipImageEncoder? = null
    private var textEncoder: ClipTextEncoder? = null
    private var textRecognizer: TextRecognizer? = null
    private var encoderPaths: Triple<String, String, String?>? = null

    init {
        ModelResourceCoordinator.registerClipOwner(this) { releaseEncoders() }
    }
    
    // Current scan folder
    private var scanFolder: File? = null
    
    /**
     * Initialize encoders
     */
    fun initialize(imageModelPath: String, textModelPath: String, vocabPath: String? = null): Boolean {
        encoderPaths = Triple(imageModelPath, textModelPath, vocabPath)
        return try {
            ModelResourceCoordinator.withClipResources {
                releaseEncoders()
                loadEncoders()
            }
        } catch (_: IllegalStateException) {
            false
        }
    }

    private fun loadEncoders(): Boolean {
        val (imageModelPath, textModelPath, vocabPath) = encoderPaths ?: return false
        ModelResourceCoordinator.prepareClipLoad(this)
        imageEncoder = ClipImageEncoder(context).also {
            if (!it.initialize(imageModelPath)) {
                it.close()
                return false
            }
        }
        textEncoder = ClipTextEncoder(context).also {
            if (!it.initialize(textModelPath, vocabPath)) {
                it.close()
                releaseEncoders()
                return false
            }
        }
        android.util.Log.d("PhotoIndexer", "Encoders initialized successfully")
        return true
    }
    
    /**
     * Set the folder to scan
     */
    fun setScanFolder(folderPath: String) {
        scanFolder = File(folderPath)
    }
    
    /**
     * Get current scan folder path
     */
    fun getScanFolderPath(): String {
        return scanFolder?.absolutePath ?: getDefaultFolder()
    }
    
    /**
     * Clear all indexed photos from database
     */
    fun clearDatabase() {
        android.util.Log.d("PhotoIndexer", "Clearing database for force re-indexing")
        vectorStore.clearAll()
    }
    
    /**
     * Get default test folder path - try user's test folder first, then Screenshots
     */
    fun getDefaultFolder(): String {
        // Try user's test folder first, then common screenshot locations
        val paths = listOf(
            "/sdcard/DCIM/for_edgeQ_testing",  // User's test folder
            "/storage/emulated/0/DCIM/for_edgeQ_testing",
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
        emitAll(indexSelected(getPhotosInFolder()))
    }.flowOn(Dispatchers.IO)

    fun completionPlan(photo: PhotoFile, clipAvailable: Boolean): PhotoChannelWorkPlan {
        val existing = vectorStore.getPhotoForVlmPath(photo.path)
        return PhotoChannelWorkPlan.create(
            currentFile = existing?.fileModified == photo.lastModified,
            currentOcrOutcome = OcrIndexProvenanceStore(context).hasCurrentOutcome(vectorStore, photo.path, photo.lastModified),
            compatibleClip = ClipIndexProvenanceStore(context).isCompatible(vectorStore, photo.path),
            clipAvailable = clipAvailable,
        )
    }

    fun indexSelected(
        selected: List<PhotoFile>,
        completeMissingChannels: Boolean = false,
        clipAvailable: Boolean = true,
        onPhotoStart: (PhotoFile) -> Unit = {},
        onOutcome: (PhotoIndexOutcome) -> Unit = {},
    ): Flow<IndexingProgress> = flow {
        ModelResourceCoordinator.withClipSession {
        android.util.Log.d("PhotoIndexer", "indexFolder STARTED")
        val photos = selected.distinctBy { it.path }
        val total = photos.size
        var indexed = 0
        var skipped = 0
        var failed = 0
        
        android.util.Log.d("PhotoIndexer", "indexFolder: $total photos to process")
        emit(IndexingProgress(0, total, "Starting indexing..."))
        
        if (total == 0) {
            emit(IndexingProgress(0, 0, "No photos found in folder", isComplete = true))
            return@withClipSession
        }
        
        for (photo in photos) {
            currentCoroutineContext().ensureActive()
            onPhotoStart(photo)
            var channelPlan: PhotoChannelWorkPlan? = null
            try {
                // Check if already indexed and up-to-date
                val needs = vectorStore.needsIndexing(photo.path, photo.lastModified)

                if (completeMissingChannels) {
                    channelPlan = completionPlan(photo, clipAvailable)
                }
                
                if (channelPlan?.hasWork == false || (channelPlan == null && !needs)) {
                    skipped++
                    onOutcome(PhotoIndexOutcome(photo.path, skipped = true))
                    emit(IndexingProgress(indexed + skipped + failed, total, "Reused existing index", skipped = skipped, failed = failed))
                    continue
                }
                
                // Index the photo
                val startTime = System.currentTimeMillis()
                val outcome = indexPhoto(photo, channelPlan)
                onOutcome(outcome)
                val latency = System.currentTimeMillis() - startTime
                indexed++
                
                emit(IndexingProgress(
                    current = indexed + skipped + failed,
                    total = total,
                    message = "Indexed: $indexed, Skipped: $skipped",
                    currentFile = photo.name,
                    latencyMs = latency,
                    imageSize = photo.size,
                    success = true
                ))
                
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
                onOutcome(PhotoIndexOutcome(photo.path, failureCode = e.javaClass.simpleName,
                    ocrAttempted = channelPlan?.runOcr ?: true, clipAttempted = channelPlan?.runClip ?: true))
                emit(IndexingProgress(
                    current = indexed + skipped + failed, // Update progress even on failure
                    total = total,
                    message = "Failed: ${photo.name}",
                    currentFile = photo.name,
                    latencyMs = 0,
                    imageSize = photo.size,
                    success = false
                ))
            }
        }
        
        emit(IndexingProgress(
            current = total,
            total = total,
            message = "Complete! Indexed: $indexed, Skipped: $skipped, Failed: $failed",
            isComplete = true,
            indexed = indexed, skipped = skipped, failed = failed
        ))
        }
    }.flowOn(Dispatchers.IO)
    
    /**
     * Index a single photo
     */
    private suspend fun indexPhoto(photo: PhotoFile, plan: PhotoChannelWorkPlan? = null): PhotoIndexOutcome {
        val existing = if (plan != null) vectorStore.getPhotoForVlmPath(photo.path) else null
        val bitmap = BitmapFactory.decodeFile(photo.path) ?: error("image_decode_failed")
        try {
        
        // Generate image embedding
        val imageEmbedding = if (plan?.runClip != false) ModelResourceCoordinator.withClipResources {
                if (imageEncoder == null) loadEncoders()
                imageEncoder?.encode(bitmap)
        } else null
        android.util.Log.d("PhotoIndexer", "Image embedding: ${imageEmbedding?.size ?: "null"}")
        
        // Run OCR
        val ocr = if (plan?.runOcr != false) runOcr(photo.path) else
            existing?.ocrText to true
        val ocrText = ocr.first
        diagnostics.event(if (plan?.reuseOcr == true) "ocr_reused" else "ocr_end", mapOf("photo_id" to diagnostics.safeId(photo.path), "success" to ocr.second, "nonempty" to !ocrText.isNullOrBlank()))
        
        // Generate text embedding for OCR text
        val textEmbedding = if (plan?.runClip != false && !ocrText.isNullOrBlank()) {
            ModelResourceCoordinator.withClipResources {
                    if (textEncoder == null) loadEncoders()
                    textEncoder?.encode(ocrText)
            }
        } else null
        
        // Store in database
        currentCoroutineContext().ensureActive()
        check(File(photo.path).let { it.lastModified() == photo.lastModified && it.length() == photo.size }) {
            "photo_changed_during_indexing"
        }
        vectorStore.upsertPhoto(PhotoRecord(
            folderId = existing?.folderId ?: 1,
            filePath = photo.path,
            fileName = photo.name,
            dateTaken = existing?.takeIf { it.fileModified == photo.lastModified }?.dateTaken ?: photo.lastModified,
            ocrText = when {
                plan?.reuseOcr == true -> null
                plan != null && ocr.second -> ocrText.orEmpty()
                else -> ocrText
            },
            imageEmbedding = imageEmbedding,
            textEmbedding = textEmbedding,
            fileModified = photo.lastModified
        ))
        diagnostics.event("db_write", mapOf("photo_id" to diagnostics.safeId(photo.path), "stage" to "ml"))
        return PhotoIndexOutcome(photo.path, clipSucceeded = imageEmbedding != null || plan?.reuseClip == true,
            ocrSucceeded = ocr.second, ocrNonempty = !ocrText.isNullOrBlank(),
            ocrAttempted = plan?.runOcr ?: true, clipAttempted = plan?.runClip ?: true,
            ocrTextSha256 = if (ocr.second && plan?.reuseOcr != true) OcrIndexProvenanceStore.textHash(ocrText.orEmpty()) else null)
        } finally {
            bitmap.recycle()
        }
    }
    
    /**
     * Run OCR on an image
     */
    private suspend fun runOcr(imagePath: String): Pair<String?, Boolean> {
        diagnostics.event("ocr_start", mapOf("photo_id" to diagnostics.safeId(imagePath)))
        return suspendCancellableCoroutine { continuation ->
            try {
                val bitmap = BitmapFactory.decodeFile(imagePath)
                if (bitmap == null) {
                    continuation.resume(null to false)
                    return@suspendCancellableCoroutine
                }
                
                val image = InputImage.fromBitmap(bitmap, 0)
                
                val recognizer = textRecognizer ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    .also { textRecognizer = it }
                recognizer.process(image)
                    .addOnSuccessListener { result ->
                        val text = result.text.takeIf { it.isNotBlank() }
                        bitmap.recycle()
                        continuation.resume(text to true)
                    }
                    .addOnFailureListener { 
                        bitmap.recycle()
                        continuation.resume(null to false)
                    }
            } catch (e: Exception) {
                continuation.resume(null to false)
            }
        }
    }
    
    /**
     * Search photos by text query
     */
    fun search(query: String): List<PhotoSearchResult> {
        // Generate query embedding for image search
        val queryEmbedding = try {
            ModelResourceCoordinator.withClipResources {
                if (textEncoder == null) loadEncoders()
                textEncoder?.encode(query)
            }
        } catch (_: IllegalStateException) { null }
        
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
    
    /**
     * Get vector store for direct access (VLM indexing)
     */
    fun getVectorStore(): PhotoVectorStore = vectorStore
    
    private fun releaseEncoders() {
        imageEncoder?.close()
        textEncoder?.close()
        imageEncoder = null
        textEncoder = null
    }

    fun close() {
        ModelResourceCoordinator.releaseClipOwner(this)
        textRecognizer?.close()
        textRecognizer = null
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
    val isComplete: Boolean = false,
    val currentFile: String = "",
    val latencyMs: Long = 0,
    val imageSize: Long = 0,
    val success: Boolean = true,
    val indexed: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0
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

/** Outcome metadata never changes encoded inputs or ranking values. */
data class PhotoIndexOutcome(
    val path: String,
    val skipped: Boolean = false,
    val clipSucceeded: Boolean = false,
    val ocrSucceeded: Boolean = false,
    val ocrNonempty: Boolean = false,
    val ocrTextSha256: String? = null,
    val failureCode: String? = null,
    val ocrAttempted: Boolean = false,
    val clipAttempted: Boolean = false,
)
