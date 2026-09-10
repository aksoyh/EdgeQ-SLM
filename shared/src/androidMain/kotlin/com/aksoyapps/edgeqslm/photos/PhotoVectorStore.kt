package com.aksoyapps.edgeqslm.photos

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.aksoyapps.edgeqslm.photos.embedding.SemanticEmbeddingContract
import com.aksoyapps.edgeqslm.photos.embedding.SemanticEmbeddingRecord
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Photo metadata and embeddings storage using SQLite
 * Uses simple LIKE query for text search (FTS5 not available on all devices)
 */
class PhotoVectorStore(context: Context, databaseName: String = DATABASE_NAME) :
    SQLiteOpenHelper(context.applicationContext, databaseName, null, DATABASE_VERSION), SemanticIndexStore, SemanticSearchStore {
    
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
                semantic_source TEXT,
                semantic_core_version TEXT,
                semantic_embedding BLOB,
                semantic_embedding_space TEXT,
                semantic_embedding_version TEXT,
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
        val columns = photoColumns(db).toMutableSet()
        val requiredLegacyColumns = setOf(
            "id", "file_path", "file_name", "date_taken", "width", "height", "ocr_text",
            "image_embedding", "text_embedding", "indexed_at", "file_modified"
        )
        check(columns.containsAll(requiredLegacyColumns)) { "Unsupported photo schema: missing legacy columns" }

        fun addColumn(name: String, declaration: String) {
            if (columns.add(name)) db.execSQL("ALTER TABLE photos ADD COLUMN $name $declaration")
        }

        if (oldVersion < 4) {
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
            addColumn("folder_id", "INTEGER DEFAULT 1")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_photos_folder ON photos(folder_id)")
        }

        if (oldVersion < 3) {
            addColumn("vlm_description", "TEXT")
            addColumn("vlm_tags", "TEXT")
            addColumn("vlm_indexed_at", "INTEGER")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_photos_vlm_tags ON photos(vlm_tags)")
        }

        if (oldVersion < 5) {
            check(columns.containsAll(setOf("folder_id", "vlm_description", "vlm_tags", "vlm_indexed_at"))) {
                "Unsupported photo schema: missing v4 columns"
            }
            addColumn("semantic_source", "TEXT")
        }
        if (oldVersion < 6) {
            check(columns.containsAll(setOf("folder_id", "vlm_description", "vlm_tags", "vlm_indexed_at", "semantic_source"))) {
                "Unsupported photo schema: missing v5 columns"
            }
            addColumn("semantic_core_version", "TEXT")
            addColumn("semantic_embedding", "BLOB")
            addColumn("semantic_embedding_space", "TEXT")
            addColumn("semantic_embedding_version", "TEXT")
        }
    }

    private fun photoColumns(db: SQLiteDatabase): Set<String> =
        db.rawQuery("PRAGMA table_info(photos)", null).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
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
            photo.dateTaken?.let { put("date_taken", it) }
            photo.width?.let { put("width", it) }
            photo.height?.let { put("height", it) }
            photo.ocrText?.let { put("ocr_text", it) }
            photo.imageEmbedding?.let { put("image_embedding", it.toByteArray()) }
            photo.textEmbedding?.let { put("text_embedding", it.toByteArray()) }
            put("indexed_at", System.currentTimeMillis())
            put("file_modified", photo.fileModified)
        }

        db.beginTransaction()
        try {
            val existingId = db.rawQuery(
                "SELECT id FROM photos WHERE folder_id = ? AND file_path = ? ORDER BY id LIMIT 1",
                arrayOf(photo.folderId.toString(), photo.filePath)
            ).use { if (it.moveToFirst()) it.getLong(0) else null }
            val id = if (existingId != null) {
                check(db.update("photos", values, "id = ?", arrayOf(existingId.toString())) == 1)
                existingId
            } else {
                photo.vlmDescription?.let {
                    values.put("vlm_description", it)
                    values.put("vlm_indexed_at", System.currentTimeMillis())
                }
                photo.vlmTags?.let { values.put("vlm_tags", it) }
                db.insertOrThrow("photos", null, values)
            }
            db.setTransactionSuccessful()
            return id
        } finally {
            db.endTransaction()
        }
    }

    fun getPhotoForVlm(id: Long): PhotoRecord? = readableDatabase.query(
        "photos", VLM_SOURCE_COLUMNS, "id = ?", arrayOf(id.toString()), null, null, null
    ).use { if (it.moveToFirst()) it.toVlmPhoto() else null }

    fun getPhotoForVlmPath(path: String): PhotoRecord? = readableDatabase.query(
        "photos", VLM_SOURCE_COLUMNS, "file_path = ?", arrayOf(path), null, null, "id ASC", "1"
    ).use { if (it.moveToFirst()) it.toVlmPhoto() else null }

    fun getVlmSourcePage(folderPath: String? = null, afterId: Long = 0, limit: Int = 64): List<PhotoRecord> {
        require(afterId >= 0 && limit > 0)
        val conditions = if (folderPath == null) "id > ?" else "id > ? AND file_path LIKE ? ESCAPE '\\'"
        val args = if (folderPath == null) arrayOf(afterId.toString()) else {
            val prefix = folderPath.trimEnd('/') + "/"
            val escaped = prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            arrayOf(afterId.toString(), "$escaped%")
        }
        return readableDatabase.query(
            "photos", VLM_SOURCE_COLUMNS, conditions, args, null, null, "id ASC", limit.toString()
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toVlmPhoto()) }
        }
    }

    fun compareAndSetVlmSource(
        rowId: Long,
        expectedFileModified: Long,
        expectedSource: String?,
        newSource: String,
        description: String? = null,
        tags: String? = null
    ): Boolean {
        val db = writableDatabase
        val sourceCondition = if (expectedSource == null) "semantic_source IS NULL" else "semantic_source = ?"
        val conditions = "id = ? AND file_modified = ? AND $sourceCondition"
        val args = listOf(rowId.toString(), expectedFileModified.toString()).let {
            if (expectedSource == null) it else it + expectedSource
        }.toTypedArray()

        db.beginTransaction()
        try {
            val previous = db.query(
                "photos", arrayOf("vlm_description", "vlm_tags"), conditions, args, null, null, null
            ).use { cursor ->
                if (cursor.moveToFirst()) Pair(cursor.getString(0), cursor.getString(1)) else null
            } ?: return false
            val values = ContentValues().apply {
                put("semantic_source", newSource)
                if (newSource != expectedSource) SEMANTIC_EMBEDDING_COLUMNS.forEach(::putNull)
                if (description != null && previous.first.isNullOrBlank()) put("vlm_description", description)
                if (!tags.isNullOrBlank()) {
                    val oldTags = previous.second
                    put("vlm_tags", when {
                        oldTags.isNullOrBlank() -> tags
                        oldTags.contains(tags) -> oldTags
                        else -> "$oldTags\n$tags"
                    })
                }
                if (description != null || tags != null) put("vlm_indexed_at", System.currentTimeMillis())
            }
            val updated = db.update("photos", values, conditions, args)
            check(updated <= 1) { "VLM source write affected multiple rows" }
            db.setTransactionSuccessful()
            return updated == 1
        } finally {
            db.endTransaction()
        }
    }

    private fun Cursor.toVlmPhoto(): PhotoRecord = PhotoRecord(
        id = getLong(0),
        folderId = getLong(1),
        filePath = getString(2),
        fileName = getString(3),
        dateTaken = if (isNull(4)) null else getLong(4),
        width = if (isNull(5)) null else getInt(5),
        height = if (isNull(6)) null else getInt(6),
        ocrText = getString(7),
        fileModified = getLong(8),
        vlmDescription = getString(9),
        vlmTags = getString(10),
        semanticSource = getString(11)
    )

    override fun getSemanticSourcePage(folderPath: String?, afterId: Long, limit: Int): List<SemanticIndexRow> {
        require(afterId >= 0 && limit in 1..64)
        val conditions = if (folderPath == null) "id > ?" else "id > ? AND file_path LIKE ? ESCAPE '\\'"
        val args = if (folderPath == null) arrayOf(afterId.toString()) else {
            val prefix = (folderPath.trimEnd('/') + "/").replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            arrayOf(afterId.toString(), "$prefix%")
        }
        return readableDatabase.query(
            "photos", arrayOf("id", "file_modified", "semantic_source") + SEMANTIC_EMBEDDING_COLUMNS,
            conditions, args, null, null, "id ASC", limit.toString(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(SemanticIndexRow(
                    id = cursor.getLong(0), fileModified = cursor.getLong(1), source = cursor.getString(2),
                    embedding = SemanticEmbeddingRecord(
                        coreVersion = cursor.getString(3), blob = if (cursor.isNull(4)) null else cursor.getBlob(4),
                        space = cursor.getString(5), encoderVersion = cursor.getString(6),
                    ),
                ))
            }
        }
    }

    override fun compareAndSetSemanticEmbedding(row: SemanticIndexRow, embedding: SemanticEmbeddingRecord): Boolean {
        require(row.currentSource()?.currentProjection?.isEligible == true) {
            "Semantic writes require current eligible search projection"
        }
        val source = requireNotNull(row.source)
        // Re-encode a defensive snapshot after boundary validation before passing bytes to SQLite.
        val validated = SemanticEmbeddingContract.encode(SemanticEmbeddingContract.decode(embedding, source), source)
        val values = ContentValues().apply {
            put("semantic_core_version", validated.coreVersion)
            put("semantic_embedding", validated.blob)
            put("semantic_embedding_space", validated.space)
            put("semantic_embedding_version", validated.encoderVersion)
        }
        return writableDatabase.update(
            "photos", values, "id = ? AND file_modified = ? AND semantic_source = ?",
            arrayOf(row.id.toString(), row.fileModified.toString(), source),
        ) == 1
    }

    override fun getSemanticSearchPage(afterId: Long, limit: Int): List<SemanticSearchRow> {
        require(afterId >= 0 && limit in 1..64)
        // Reject oversized/type-invalid blobs before they can exhaust an Android CursorWindow.
        return readableDatabase.rawQuery("""
            SELECT id, file_modified, semantic_source, semantic_core_version,
                   CASE WHEN typeof(semantic_embedding) = 'blob' AND length(semantic_embedding) = 1536
                        THEN semantic_embedding ELSE NULL END,
                   semantic_embedding_space, semantic_embedding_version, file_path, file_name
            FROM photos WHERE id > ? ORDER BY id ASC LIMIT ?
        """, arrayOf(afterId.toString(), limit.toString())).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(SemanticSearchRow(
                    index = SemanticIndexRow(
                        id = cursor.getLong(0), fileModified = cursor.getLong(1), source = cursor.getString(2),
                        embedding = SemanticEmbeddingRecord(
                            coreVersion = cursor.getString(3), blob = if (cursor.isNull(4)) null else cursor.getBlob(4),
                            space = cursor.getString(5), encoderVersion = cursor.getString(6),
                        ),
                    ),
                    filePath = cursor.getString(7), fileName = cursor.getString(8),
                ))
            }
        }
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
    fun searchByText(query: String, limit: Int = 20, eligiblePaths: Set<String>? = null): List<PhotoSearchResult> {
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
        """, args + (if (eligiblePaths == null) limit else Int.MAX_VALUE).toString())
        
        val results = mutableListOf<PhotoSearchResult>()
        cursor.use {
            while (it.moveToNext()) {
                if (eligiblePaths != null && it.getString(1) !in eligiblePaths) continue
                if (results.size == limit) break
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
    fun searchByImageEmbedding(queryEmbedding: FloatArray, queryText: String = "", limit: Int = 20, eligiblePaths: Set<String>? = null): List<PhotoSearchResult> {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT id, file_path, file_name, ocr_text, image_embedding FROM photos WHERE image_embedding IS NOT NULL",
            null
        )
        
        val results = mutableListOf<Pair<PhotoSearchResult, Float>>()
        cursor.use {
            while (it.moveToNext()) {
                if (eligiblePaths != null && it.getString(1) !in eligiblePaths) continue
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
        limit: Int = 20,
        eligiblePaths: Set<String>? = null
    ): List<PhotoSearchResult> {
        val textResults = if (textQuery.isNotBlank()) {
            searchByText(textQuery, limit * 2, eligiblePaths).associateBy { it.filePath }
        } else emptyMap()
        
        val imageResults = if (queryEmbedding != null) {
            searchByImageEmbedding(queryEmbedding, textQuery, limit * 2, eligiblePaths).associateBy { it.filePath }
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
        private const val DATABASE_VERSION = 6
        private val SEMANTIC_EMBEDDING_COLUMNS = arrayOf(
            "semantic_core_version", "semantic_embedding", "semantic_embedding_space", "semantic_embedding_version"
        )
        private val VLM_SOURCE_COLUMNS = arrayOf(
            "id", "folder_id", "file_path", "file_name", "date_taken", "width", "height",
            "ocr_text", "file_modified", "vlm_description", "vlm_tags", "semantic_source"
        )
    }
    
    /**
     * Load all indexed photos for SLM in-memory scoring
     */
    fun getAllPhotosForSlm(limit: Int = 5000, eligiblePaths: Set<String>? = null): List<PhotoRecord> {
        val db = readableDatabase
        val cursor = db.rawQuery("""
            SELECT id, file_path, file_name, ocr_text, semantic_source, file_modified
            FROM photos
            LIMIT ?
        """, arrayOf((if (eligiblePaths == null) limit else Int.MAX_VALUE).toString()))
        val records = mutableListOf<PhotoRecord>()
        cursor.use {
            while (it.moveToNext()) {
                if (eligiblePaths != null && it.getString(1) !in eligiblePaths) continue
                if (records.size == limit) break
                val source = it.getString(4)?.let { value ->
                    runCatching { StructuredSemanticSource.parsePersisted(value) }.getOrNull()
                }
                val projectedText = source?.takeIf { value ->
                    value.imageRevision.fileModified == it.getLong(5)
                }?.currentProjection?.takeIf { projection -> projection.isEligible }?.coreText
                records.add(PhotoRecord(
                    id = it.getLong(0),
                    filePath = it.getString(1) ?: "",
                    fileName = it.getString(2) ?: "",
                    ocrText = it.getString(3),
                    vlmDescription = projectedText,
                    vlmTags = projectedText
                ))
            }
        }
        return records
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
    val vlmTags: String? = null,
    val semanticSource: String? = null
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
