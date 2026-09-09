package com.aksoyapps.edgeqslm.photos

import android.content.Context
import com.aksoyapps.edgeqslm.diagnostics.ThesisDiagnostics

/** Legacy callers feed the bounded, redacted diagnostic stream. */
class IndexingLogger(context: Context) {
    private val diagnostics = ThesisDiagnostics.get(context)

    fun logSession(log: IndexingSessionLog) {
        diagnostics.event("indexing_session", mapOf(
            "type" to log.type.toString(), "duration_ms" to log.durationMs,
            "total_items" to log.totalItems, "success" to log.successItems,
            "failed" to log.failedItems, "source_id" to diagnostics.safeId(log.folderPath),
        ))
    }

    fun logItem(log: ItemIndexingLog) {
        diagnostics.event("indexing_photo", mapOf(
            "type" to log.type.toString(), "duration_ms" to log.latencyMs,
            "image_size_bytes" to log.imageSize, "success" to log.success,
            "photo_id" to diagnostics.safeId(log.fileName), "failure_observed" to (log.error != null),
        ), privateFields = mapOf("file_name" to log.fileName))
    }
}
