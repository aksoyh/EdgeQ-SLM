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
        // Main photos table
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS photos (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                file_path TEXT UNIQUE NOT NULL,
                file_name TEXT NOT NULL,
                date_taken INTEGER,
                width INTEGER,
                height INTEGER,
                ocr_text TEXT,
                image_embedding BLOB,
                text_embedding BLOB,
                indexed_at INTEGER NOT NULL,
                file_modified INTEGER NOT NULL
            )
        """)
        
        // Index on file_path and ocr_text for quick lookups
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_photos_path ON photos(file_path)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_photos_modified ON photos(file_modified)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_photos_ocr ON photos(ocr_text)")
    }
    
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS photos")
        onCreate(db)
    }
    
    /**
     * Insert or update a photo record
     */
    fun upsertPhoto(photo: PhotoRecord): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
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
        }
        
        return db.insertWithOnConflict("photos", null, values, SQLiteDatabase.CONFLICT_REPLACE)
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
                val matchCount = searchTerms.count { term -> 
                    ocrText.contains(term, ignoreCase = true) || 
                    fileName.contains(term, ignoreCase = true)
                }
                val score = matchCount.toFloat() / searchTerms.size
                
                results.add(PhotoSearchResult(
                    id = it.getLong(0),
                    filePath = it.getString(1),
                    fileName = fileName,
                    ocrText = ocrText,
                    score = score
                ))
            }
        }
        return results.sortedByDescending { it.score }
    }
    
    /**
     * Vector similarity search using image embeddings
     */
    fun searchByImageEmbedding(queryEmbedding: FloatArray, limit: Int = 20): List<PhotoSearchResult> {
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
                        score = similarity
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
            searchByImageEmbedding(queryEmbedding, limit * 2).associateBy { it.filePath }
        } else emptyMap()
        
        // If no image embeddings, just return text results
        if (imageResults.isEmpty() && textResults.isNotEmpty()) {
            return textResults.values.toList().sortedByDescending { it.score }.take(limit)
        }
        
        // Combine scores
        val allPaths = textResults.keys + imageResults.keys
        val combined = allPaths.map { path ->
            val textScore = textResults[path]?.score ?: 0f
            val imageScore = imageResults[path]?.score ?: 0f
            val combinedScore = textWeight * textScore + imageWeight * imageScore
            
            (textResults[path] ?: imageResults[path])!!.copy(score = combinedScore)
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
        private const val DATABASE_VERSION = 2  // Bumped version to recreate without FTS5
    }
}

/**
 * Photo record for storage
 */
data class PhotoRecord(
    val id: Long = 0,
    val filePath: String,
    val fileName: String,
    val dateTaken: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val ocrText: String? = null,
    val imageEmbedding: FloatArray? = null,
    val textEmbedding: FloatArray? = null,
    val fileModified: Long = 0
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
    val score: Float
)
