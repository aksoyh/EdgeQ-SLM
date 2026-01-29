package com.aksoyapps.edgeqslm.photos

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Photo metadata and embeddings storage using SQLite
 * Uses simple LIKE query for text search (FTS5 not available on all devices)
 */
class PhotoVectorStore(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
    
    override fun onCreate(db: SQLiteDatabase) {
        // Scan folders table - each folder has its own set of indexed photos
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS scan_folders (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                folder_path TEXT UNIQUE NOT NULL,
                folder_name TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                last_indexed_at INTEGER
            )
        """)
        
        // Main photos table with folder_id foreign key
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS photos (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                folder_id INTEGER NOT NULL,
                file_path TEXT NOT NULL,
                file_name TEXT NOT NULL,
                date_taken INTEGER,
                width INTEGER,
                height INTEGER,
                ocr_text TEXT,
                image_embedding BLOB,
                text_embedding BLOB,
                indexed_at INTEGER NOT NULL,
                file_modified INTEGER NOT NULL,
                vlm_description TEXT,
                vlm_tags TEXT,
                vlm_indexed_at INTEGER,
                UNIQUE(folder_id, file_path),
                FOREIGN KEY (folder_id) REFERENCES scan_folders(id)
            )
        """)
        
        // Indexes
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_photos_folder ON photos(folder_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_photos_path ON photos(file_path)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_photos_modified ON photos(file_modified)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_photos_ocr ON photos(ocr_text)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_photos_vlm_tags ON photos(vlm_tags)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_folders_path ON scan_folders(folder_path)")
    }
    
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Migration from version 3 to 4: Add scan_folders table
        if (oldVersion < 4) {
            try {
                // Create scan_folders table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS scan_folders (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        folder_path TEXT UNIQUE NOT NULL,
                        folder_name TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        last_indexed_at INTEGER
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_folders_path ON scan_folders(folder_path)")
                
                // Add folder_id column (default to 1 for existing data)
                db.execSQL("ALTER TABLE photos ADD COLUMN folder_id INTEGER DEFAULT 1")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_photos_folder ON photos(folder_id)")
                
                android.util.Log.i("PhotoVectorStore", "Migrated to version 4 with scan_folders")
            } catch (e: Exception) {
                android.util.Log.w("PhotoVectorStore", "Migration v4: ${e.message}")
            }
        }
        
        // Handle VLM columns (from v2 to v3)
        if (oldVersion < 3) {
            try {
                db.execSQL("ALTER TABLE photos ADD COLUMN vlm_description TEXT")
                db.execSQL("ALTER TABLE photos ADD COLUMN vlm_tags TEXT")
                db.execSQL("ALTER TABLE photos ADD COLUMN vlm_indexed_at INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_photos_vlm_tags ON photos(vlm_tags)")
            } catch (e: Exception) {
                android.util.Log.w("PhotoVectorStore", "Migration v3: ${e.message}")
            }
        }
    }
    
    /**
     * Insert or update a photo record
     */
    fun upsertPhoto(photo: PhotoRecord): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("folder_id", photo.folderId)
            put("file_path", photo.filePath)
            put("file_name", photo.fileName)
            put("date_taken", photo.dateTaken)
            put("width", photo.width)
            put("height", photo.height)
            put("ocr_text", photo.ocrText)
            put("image_embedding", photo.imageEmbedding?.toByteArray())
            put("text_embedding", photo.textEmbedding?.toByteArray())
            put("indexed_at", System.currentTimeMillis())
            put("file_modified", photo.fileModified)
            if (photo.vlmDescription != null) {
                put("vlm_description", photo.vlmDescription)
                put("vlm_tags", photo.vlmTags)
                put("vlm_indexed_at", System.currentTimeMillis())
            }
        }
        
        return db.insertWithOnConflict("photos", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }
    
    /**
     * Update only VLM description for a photo
     */
    fun updateVlmDescription(filePath: String, description: String, tags: String): Int {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("vlm_description", description)
            put("vlm_tags", tags)
            put("vlm_indexed_at", System.currentTimeMillis())
        }
        return db.update("photos", values, "file_path = ?", arrayOf(filePath))
    }
    
    /**
     * Check if a photo needs re-indexing
     */
    fun needsIndexing(filePath: String, fileModified: Long): Boolean {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT file_modified FROM photos WHERE file_path = ?",
            arrayOf(filePath)
        )
        
        return cursor.use {
            if (it.moveToFirst()) {
                val storedModified = it.getLong(0)
                storedModified < fileModified
            } else {
                true // Not indexed yet
            }
        }
    }
    
    /**
     * Simple text search using LIKE (works on all devices)
     */
    fun searchByText(query: String, limit: Int = 20): List<PhotoSearchResult> {
        val db = readableDatabase
        val searchTerms = query.split(" ").filter { it.isNotBlank() }
        
        if (searchTerms.isEmpty()) return emptyList()
        
        // Build LIKE conditions for each search term
        val conditions = searchTerms.joinToString(" AND ") { 
            "(ocr_text LIKE ? OR file_name LIKE ?)" 
        }
        val args = searchTerms.flatMap { 
            listOf("%$it%", "%$it%") 
        }.toTypedArray()
        
        val cursor = db.rawQuery("""
            SELECT id, file_path, file_name, ocr_text
            FROM photos 
            WHERE $conditions
            LIMIT ?
        """, args + limit.toString())
        
        val results = mutableListOf<PhotoSearchResult>()
        cursor.use {
            while (it.moveToNext()) {
                // Calculate simple relevance score based on match count
                val ocrText = it.getString(3) ?: ""
                val fileName = it.getString(2)
                
                // Find matching terms in OCR text
                val matchedTerms = searchTerms.filter { term -> 
                    ocrText.contains(term, ignoreCase = true) || 
                    fileName.contains(term, ignoreCase = true)
                }
                val score = matchedTerms.size.toFloat() / searchTerms.size
                
                // Build match reason - show matching snippet from OCR
                val matchReason = if (matchedTerms.isNotEmpty()) {
                    val term = matchedTerms.first()
                    val ocrLower = ocrText.lowercase()
                    val termLower = term.lowercase()
                    val idx = ocrLower.indexOf(termLower)
                    if (idx >= 0) {
                        val start = maxOf(0, idx - 15)
                        val end = minOf(ocrText.length, idx + term.length + 15)
                        val snippet = ocrText.substring(start, end).trim()
                        "OCR: '...$snippet...'"
                    } else {
                        "Filename: '$fileName'"
                    }
                } else ""
                
                results.add(PhotoSearchResult(
                    id = it.getLong(0),
                    filePath = it.getString(1),
                    fileName = fileName,
                    ocrText = ocrText,
                    score = score,
                    matchType = "OCR",
                    matchReason = matchReason
                ))
            }
        }
        return results.sortedByDescending { it.score }
    }
    
    /**
     * Vector similarity search using image embeddings
     */
    fun searchByImageEmbedding(queryEmbedding: FloatArray, queryText: String = "", limit: Int = 20): List<PhotoSearchResult> {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT id, file_path, file_name, ocr_text, image_embedding FROM photos WHERE image_embedding IS NOT NULL",
            null
        )
        
        val results = mutableListOf<Pair<PhotoSearchResult, Float>>()
        cursor.use {
            while (it.moveToNext()) {
                val embeddingBlob = it.getBlob(4)
                val embedding = embeddingBlob.toFloatArray()
                val similarity = cosineSimilarity(queryEmbedding, embedding)
                
                results.add(Pair(
                    PhotoSearchResult(
                        id = it.getLong(0),
                        filePath = it.getString(1),
                        fileName = it.getString(2),
                        ocrText = it.getString(3),
                        score = similarity,
                        matchType = "CLIP",
                        matchReason = "CLIP: görsel benzerlik (query: '$queryText')"
                    ),
                    similarity
                ))
            }
        }
        
        return results
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }
    
    /**
     * Hybrid search combining text and image similarity
     */
    fun hybridSearch(
        textQuery: String,
        queryEmbedding: FloatArray?,
        textWeight: Float = 0.3f,
        imageWeight: Float = 0.7f,
        limit: Int = 20
    ): List<PhotoSearchResult> {
        val textResults = if (textQuery.isNotBlank()) {
            searchByText(textQuery, limit * 2).associateBy { it.filePath }
        } else emptyMap()
        
        val imageResults = if (queryEmbedding != null) {
            searchByImageEmbedding(queryEmbedding, textQuery, limit * 2).associateBy { it.filePath }
        } else emptyMap()
        
        // If no image embeddings, just return text results
        if (imageResults.isEmpty() && textResults.isNotEmpty()) {
            return textResults.values.toList().sortedByDescending { it.score }.take(limit)
        }
        
        // If only image results
        if (textResults.isEmpty() && imageResults.isNotEmpty()) {
            return imageResults.values.toList().sortedByDescending { it.score }.take(limit)
        }
        
        // Combine scores
        val allPaths = textResults.keys + imageResults.keys
        val combined = allPaths.map { path ->
            val textResult = textResults[path]
            val imageResult = imageResults[path]
            val textScore = textResult?.score ?: 0f
            val imageScore = imageResult?.score ?: 0f
            val combinedScore = textWeight * textScore + imageWeight * imageScore
            
            // Determine match type and reason
            val (matchType, matchReason) = when {
                textResult != null && imageResult != null -> 
                    "HYBRID" to "${textResult.matchReason} + ${imageResult.matchReason}"
                textResult != null -> 
                    "OCR" to textResult.matchReason
                else -> 
                    "CLIP" to (imageResult?.matchReason ?: "")
            }
            
            (textResult ?: imageResult)!!.copy(
                score = combinedScore,
                matchType = matchType,
                matchReason = matchReason
            )
        }
        
        return combined.sortedByDescending { it.score }.take(limit)
    }
    
    /**
     * Get all indexed photo paths
     */
    fun getAllIndexedPaths(): Set<String> {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT file_path FROM photos", null)
        val paths = mutableSetOf<String>()
        cursor.use {
            while (it.moveToNext()) {
                paths.add(it.getString(0))
            }
        }
        return paths
    }
    
    /**
     * Get photo count
     */
    fun getPhotoCount(): Int {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM photos", null)
        return cursor.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }
    
    /**
     * Delete a photo by path
     */
    fun deleteByPath(filePath: String): Int {
        return writableDatabase.delete("photos", "file_path = ?", arrayOf(filePath))
    }
    
    /**
     * Clear all data
     */
    fun clearAll() {
        writableDatabase.execSQL("DELETE FROM photos")
    }
    
    // Helper functions
    private fun FloatArray.toByteArray(): ByteArray {
        val buffer = ByteBuffer.allocate(this.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        this.forEach { buffer.putFloat(it) }
        return buffer.array()
    }
    
    private fun ByteArray.toFloatArray(): FloatArray {
        val buffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
        val result = FloatArray(this.size / 4)
        for (i in result.indices) {
            result[i] = buffer.getFloat()
        }
        return result
    }
    
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        
        var dot = 0f
        var normA = 0f
        var normB = 0f
        
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        
        val denom = kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB)
        return if (denom > 0) dot / denom else 0f
    }
    
    companion object {
        private const val DATABASE_NAME = "photo_index.db"
        private const val DATABASE_VERSION = 4  // Bumped for scan_folders table
    }
    
    /**
     * Search VLM descriptions for matching photos
     */
    fun searchVlmDescriptions(query: String, limit: Int = 50): List<PhotoSearchResult> {
        val db = readableDatabase
        val searchTerms = query.lowercase().split(" ").filter { it.isNotBlank() && it.length > 1 }
        
        if (searchTerms.isEmpty()) return emptyList()
        
        // Build LIKE conditions for each search term in vlm_description and vlm_tags
        val conditions = searchTerms.joinToString(" OR ") { 
            "(LOWER(vlm_description) LIKE ? OR LOWER(vlm_tags) LIKE ? OR LOWER(file_name) LIKE ?)" 
        }
        val args = searchTerms.flatMap { 
            listOf("%$it%", "%$it%", "%$it%") 
        }.toTypedArray()
        
        val cursor = db.rawQuery("""
            SELECT id, file_path, file_name, vlm_description, vlm_tags
            FROM photos 
            WHERE vlm_description IS NOT NULL AND ($conditions)
            LIMIT ?
        """, args + limit.toString())
        
        val results = mutableListOf<PhotoSearchResult>()
        cursor.use {
            while (it.moveToNext()) {
                val description = it.getString(3) ?: ""
                val tags = it.getString(4) ?: ""
                val fileName = it.getString(2)
                
                // Calculate relevance score
                val matchedInDesc = searchTerms.count { term -> 
                    description.lowercase().contains(term) 
                }
                val matchedInTags = searchTerms.count { term -> 
                    tags.lowercase().contains(term) 
                }
                val score = (matchedInDesc * 0.6f + matchedInTags * 0.4f) / searchTerms.size
                
                // Build match reason
                val matchReason = buildString {
                    if (matchedInTags > 0) append("Tags: $tags")
                    else if (description.length > 80) append(description.take(80) + "...")
                    else append(description)
                }
                
                results.add(PhotoSearchResult(
                    id = it.getLong(0),
                    filePath = it.getString(1),
                    fileName = fileName,
                    ocrText = null,  // OCR text is separate
                    score = score,
                    matchType = "VLM",
                    matchReason = matchReason,
                    vlmDescription = description,
                    vlmTags = tags
                ))
            }
        }
        return results.sortedByDescending { it.score }
    }
    
    /**
     * Get photos that don't have VLM descriptions yet, filtered by folder path
     */
    fun getPhotosWithoutVlmDescription(folderPath: String? = null, limit: Int = 1000): List<PhotoRecord> {
        val db = readableDatabase
        
        val query = if (folderPath != null) {
            """
                SELECT id, file_path, file_name, date_taken, width, height, file_modified
                FROM photos 
                WHERE vlm_description IS NULL AND file_path LIKE ?
                LIMIT ?
            """
        } else {
            """
                SELECT id, file_path, file_name, date_taken, width, height, file_modified
                FROM photos 
                WHERE vlm_description IS NULL
                LIMIT ?
            """
        }
        
        val args = if (folderPath != null) {
            arrayOf("$folderPath%", limit.toString())
        } else {
            arrayOf(limit.toString())
        }
        
        val cursor = db.rawQuery(query, args)
        
        val photos = mutableListOf<PhotoRecord>()
        cursor.use {
            while (it.moveToNext()) {
                photos.add(PhotoRecord(
                    id = it.getLong(0),
                    filePath = it.getString(1),
                    fileName = it.getString(2),
                    dateTaken = if (it.isNull(3)) null else it.getLong(3),
                    width = if (it.isNull(4)) null else it.getInt(4),
                    height = if (it.isNull(5)) null else it.getInt(5),
                    fileModified = it.getLong(6)
                ))
            }
        }
        return photos
    }
    
    /**
     * Get count of VLM indexed photos
     */
    fun getVlmIndexedCount(): Int {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM photos WHERE vlm_description IS NOT NULL", null)
        return cursor.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }
    
    /**
     * Get count of VLM indexed photos for a specific folder
     */
    fun getVlmIndexedCount(folderId: Long): Int {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT COUNT(*) FROM photos WHERE folder_id = ? AND vlm_description IS NOT NULL", 
            arrayOf(folderId.toString())
        )
        return cursor.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }
    
    /**
     * Get or create a folder entry, returns folder ID
     */
    fun getOrCreateFolder(folderPath: String): Long {
        val db = writableDatabase
        
        // Check if folder exists
        val cursor = db.rawQuery(
            "SELECT id FROM scan_folders WHERE folder_path = ?",
            arrayOf(folderPath)
        )
        
        return cursor.use {
            if (it.moveToFirst()) {
                it.getLong(0)
            } else {
                // Create new folder entry
                val folderName = folderPath.substringAfterLast("/")
                val values = ContentValues().apply {
                    put("folder_path", folderPath)
                    put("folder_name", folderName)
                    put("created_at", System.currentTimeMillis())
                }
                db.insert("scan_folders", null, values)
            }
        }
    }
    
    /**
     * Get all scan folders
     */
    fun getScanFolders(): List<ScanFolder> {
        val db = readableDatabase
        val cursor = db.rawQuery("""
            SELECT f.id, f.folder_path, f.folder_name, f.created_at, f.last_indexed_at,
                   COUNT(p.id) as photo_count,
                   SUM(CASE WHEN p.vlm_description IS NOT NULL THEN 1 ELSE 0 END) as vlm_count
            FROM scan_folders f
            LEFT JOIN photos p ON f.id = p.folder_id
            GROUP BY f.id
            ORDER BY f.created_at DESC
        """, null)
        
        val folders = mutableListOf<ScanFolder>()
        cursor.use {
            while (it.moveToNext()) {
                folders.add(ScanFolder(
                    id = it.getLong(0),
                    folderPath = it.getString(1),
                    folderName = it.getString(2),
                    createdAt = it.getLong(3),
                    lastIndexedAt = if (it.isNull(4)) null else it.getLong(4),
                    photoCount = it.getInt(5),
                    vlmIndexedCount = it.getInt(6)
                ))
            }
        }
        return folders
    }
    
    /**
     * Update last indexed timestamp for a folder
     */
    fun updateFolderLastIndexed(folderId: Long) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("last_indexed_at", System.currentTimeMillis())
        }
        db.update("scan_folders", values, "id = ?", arrayOf(folderId.toString()))
    }
    
    /**
     * Clear VLM descriptions and tags for all photos or specific folder
     */
    fun clearVlmDescriptions(folderPath: String? = null) {
        val db = writableDatabase
        val values = ContentValues().apply {
            putNull("vlm_description")
            putNull("vlm_tags")
            putNull("vlm_indexed_at")
        }
        
        val count = if (folderPath != null) {
            // Use LIKE query on file_path to be consistent with getPhotosWithoutVlmDescription
            // and robust against scan_folders mismatch
            val likePath = "$folderPath%"
            db.update("photos", values, "file_path LIKE ?", arrayOf(likePath))
        } else {
            db.update("photos", values, null, null)
        }
        android.util.Log.i("PhotoVectorStore", "Cleared VLM descriptions for $count photos (folder=$folderPath)")
    }
}

/**
 * Scan folder entry
 */
data class ScanFolder(
    val id: Long,
    val folderPath: String,
    val folderName: String,
    val createdAt: Long,
    val lastIndexedAt: Long?,
    val photoCount: Int = 0,
    val vlmIndexedCount: Int = 0
)

/**
 * Photo record for storage
 */
data class PhotoRecord(
    val id: Long = 0,
    val folderId: Long = 1,  // Default folder ID for backward compatibility
    val filePath: String,
    val fileName: String,
    val dateTaken: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val ocrText: String? = null,
    val imageEmbedding: FloatArray? = null,
    val textEmbedding: FloatArray? = null,
    val fileModified: Long = 0,
    val vlmDescription: String? = null,
    val vlmTags: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PhotoRecord) return false
        return filePath == other.filePath
    }
    
    override fun hashCode(): Int = filePath.hashCode()
}

/**
 * Search result
 */
data class PhotoSearchResult(
    val id: Long,
    val filePath: String,
    val fileName: String,
    val ocrText: String?,
    val score: Float,
    val matchType: String = "OCR",  // OCR, CLIP, or HYBRID
    val matchReason: String = "",    // What matched
    val vlmDescription: String? = null,
    val vlmTags: String? = null
)
