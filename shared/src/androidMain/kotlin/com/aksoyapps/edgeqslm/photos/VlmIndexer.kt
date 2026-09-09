package com.aksoyapps.edgeqslm.photos

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlin.coroutines.coroutineContext

/** Resumable structured-source indexing. A generation is attempted at most once per row per run. */
class VlmIndexer(
    private val vlmAnalyzer: VlmSourceAnalyzer,
    private val vectorStore: PhotoVectorStore
) {
    /**
     * Explicit starts can resume incomplete rows. Sticky service recovery processes only unattempted
     * rows: it cannot know whether an interrupted native call already generated an answer.
     * The legacy force control never erases data or resamples a current valid source.
     */
    fun indexAllPhotos(
        folderPath: String? = null,
        force: Boolean = false,
        resumeIncomplete: Boolean = true,
        selectedPaths: Set<String>? = null,
        onRow: (String) -> Unit = {},
    ): Flow<VlmIndexProgress> = flow {
        val total = selectedPaths?.size ?: vectorStore.getPhotoCount()
        var completed = 0
        var failed = 0
        var afterId = 0L
        while (true) {
            coroutineContext.ensureActive()
            val page = vectorStore.getVlmSourcePage(folderPath, afterId, 16)
            if (page.isEmpty()) break
            var storageFailed = false
            vlmAnalyzer.withSession {
                for (photo in page) {
                    coroutineContext.ensureActive()
                    if (selectedPaths != null && photo.filePath !in selectedPaths) continue
                    val existing = parseSource(photo.semanticSource)
                    if (!resumeIncomplete && !force && photo.semanticSource != null &&
                        existing?.state != SemanticSourceState.VALID) {
                        if (existing?.state == SemanticSourceState.IN_PROGRESS) {
                            vectorStore.compareAndSetVlmSource(
                                photo.id, photo.fileModified, photo.semanticSource,
                                existing.copy(state = SemanticSourceState.INTERRUPTED,
                                    errorCode = "process_interrupted").serialize()
                            )
                        }
                        onRow(photo.filePath)
                        continue
                    }
                    emit(VlmIndexProgress(completed, total, failed, photo.fileName,
                        VlmIndexStatus.RUNNING, "Analyzing ${photo.fileName}"))
                    val result = indexRow(photo.id, allowInference = resumeIncomplete || force ||
                        photo.semanticSource == null)
                    if (result.success) completed++ else failed++
                    onRow(photo.filePath)
                    emit(VlmIndexProgress(completed, total, failed, photo.fileName,
                        VlmIndexStatus.RUNNING, result.error ?: "Source complete",
                        result.latencyMs))
                    if (result.error == "source_database_write_failed") {
                        storageFailed = true
                        break
                    }
                }
            }
            if (storageFailed) {
                emit(VlmIndexProgress(completed, total, failed, "", VlmIndexStatus.ERROR,
                    "Source database write failed; indexing stopped"))
                return@flow
            }
            afterId = page.last().id
        }
        emit(VlmIndexProgress(completed, total, failed, "", VlmIndexStatus.COMPLETED,
            "Completed: $completed current sources, $failed incomplete"))
    }.flowOn(Dispatchers.IO)

    suspend fun indexSinglePhoto(photoPath: String): VlmIndexResult =
        vlmAnalyzer.withSession {
            val photo = vectorStore.getPhotoForVlmPath(photoPath)
                ?: return@withSession failure("photo_not_indexed", 0)
            indexRow(photo.id)
        }

    private suspend fun indexRow(rowId: Long, allowInference: Boolean = true): VlmIndexResult {
        val start = System.currentTimeMillis()
        val photo = vectorStore.getPhotoForVlm(rowId) ?: return failure("photo_not_indexed", start)
        var expectedSource = photo.semanticSource
        val previous = parseSource(expectedSource)
        var imageRevision: ImageRevision? = null
        var raw: String? = null
        var imageTransform: VlmImageTransform? = null
        var claimed = false
        try {
            coroutineContext.ensureActive()
            imageRevision = VlmImageAnalyzer.imageRevision(photo.filePath)
            if (previous?.isCurrent(imageRevision) == true) {
                return VlmIndexResult(true, photo.vlmDescription.orEmpty(), photo.vlmTags.orEmpty(),
                    System.currentTimeMillis() - start)
            }
            // A source-envelope upgrade can reuse accepted raw output without any image inference.
            val reusable = previous?.rawGeneration?.let {
                runCatching { StructuredVisualSource.parseCurrent(it) }.getOrNull()
            }
            if (previous?.hasAcceptedInferenceContract() == true &&
                previous.imageRevision == imageRevision && reusable != null) {
                VlmSourceQuality.requireSearchableCandidate(reusable)
                val source = StructuredSemanticSource(
                    state = SemanticSourceState.VALID, imageRevision = imageRevision,
                    rawGeneration = previous.rawGeneration, structured = reusable,
                    imageTransform = previous.imageTransform
                )
                return commit(photo, expectedSource, source, start)
            }
            if (!allowInference) return failure("explicit_resume_required", start)
            val marker = if (previous?.structured != null) previous.asStale() else
                StructuredSemanticSource(SemanticSourceState.IN_PROGRESS, imageRevision)
            expectedSource = marker.serialize()
            check(writeSource(
                photo.id, photo.fileModified, photo.semanticSource, expectedSource
            )) { "source_claim_conflict" }
            claimed = true

            val analysis = vlmAnalyzer.analyzeImage(photo.filePath)
            raw = analysis.generation.text
            imageTransform = analysis.imageTransform
            coroutineContext.ensureActive()
            val structured = StructuredVisualSource.parseCurrent(raw)
            VlmSourceQuality.requireSearchableCandidate(structured)
            check(VlmImageAnalyzer.imageRevision(photo.filePath) == imageRevision) {
                "image_changed_during_inference"
            }
            val source = StructuredSemanticSource(
                state = SemanticSourceState.VALID, imageRevision = imageRevision,
                rawGeneration = raw, structured = structured, imageTransform = imageTransform
            )
            return commit(photo, expectedSource, source, start)
        } catch (e: CancellationException) {
            persistFailure(photo, expectedSource, previous, imageRevision, raw, claimed,
                SemanticSourceState.INTERRUPTED, "indexing_cancelled", imageTransform)
            throw e
        } catch (e: Exception) {
            val error = e.message ?: e.javaClass.simpleName
            persistFailure(photo, expectedSource, previous, imageRevision, raw, claimed,
                if (error in setOf("image_exceeds_admission_limit", "image_source_safety_limit",
                        "image_decode_budget_exceeded")) SemanticSourceState.DEFERRED
                else SemanticSourceState.INVALID, error, imageTransform)
            return failure(error, start)
        }
    }

    private fun commit(
        photo: PhotoRecord, expectedSource: String?, source: StructuredSemanticSource, start: Long
    ): VlmIndexResult {
        val structured = requireNotNull(source.structured)
        check(writeSource(
            photo.id, photo.fileModified, expectedSource, source.serialize(),
            description = structured.semanticDescription, tags = structured.searchableText()
        )) { "source_commit_conflict" }
        val saved = vectorStore.getPhotoForVlm(photo.id) ?: error("source_row_disappeared")
        return VlmIndexResult(true, saved.vlmDescription.orEmpty(), saved.vlmTags.orEmpty(),
            System.currentTimeMillis() - start)
    }

    private fun persistFailure(
        photo: PhotoRecord, expectedSource: String?, previous: StructuredSemanticSource?,
        revision: ImageRevision?, raw: String?, claimed: Boolean,
        state: SemanticSourceState, error: String, imageTransform: VlmImageTransform?
    ) {
        if (!claimed || revision == null) return
        // Retain the last complete structured source when a replacement attempt fails.
        val failed = if (previous?.structured != null)
            previous.asStale().copy(errorCode = error)
        else StructuredSemanticSource(state, revision, rawGeneration = raw, errorCode = error,
            imageTransform = imageTransform)
        try {
            vectorStore.compareAndSetVlmSource(
                photo.id, photo.fileModified, expectedSource, failed.serialize()
            )
        } catch (_: Exception) {
            // The durable claim remains incomplete when SQLite itself is unavailable.
        }
    }

    private fun writeSource(
        rowId: Long, expectedFileModified: Long, expectedSource: String?, newSource: String,
        description: String? = null, tags: String? = null
    ): Boolean = try {
        vectorStore.compareAndSetVlmSource(
            rowId, expectedFileModified, expectedSource, newSource, description, tags
        )
    } catch (e: Exception) {
        throw IllegalStateException("source_database_write_failed", e)
    }

    private fun parseSource(value: String?): StructuredSemanticSource? =
        value?.let { runCatching { StructuredSemanticSource.parsePersisted(it) }.getOrNull() }

    private fun failure(error: String, start: Long) = VlmIndexResult(
        false, "", "", if (start == 0L) 0 else System.currentTimeMillis() - start, error
    )

    fun isAvailable(): Boolean = vlmAnalyzer.isAvailable()

    fun getStats(): VlmIndexStats {
        var afterId = 0L
        var indexed = 0
        while (true) {
            val page = vectorStore.getVlmSourcePage(null, afterId, 64)
            if (page.isEmpty()) break
            indexed += page.count { photo ->
                val source = parseSource(photo.semanticSource)
                source?.isCurrent(source.imageRevision) == true &&
                    source.imageRevision.fileModified == photo.fileModified
            }
            afterId = page.last().id
        }
        val total = vectorStore.getPhotoCount()
        return VlmIndexStats(total, indexed, total - indexed)
    }
}

/**
 * Progress update during VLM indexing
 */
data class VlmIndexProgress(
    val current: Int,
    val total: Int,
    val failed: Int = 0,
    val currentFile: String,
    val status: VlmIndexStatus,
    val message: String,
    val latencyMs: Long = 0,
    val imageSize: Long = 0
)

enum class VlmIndexStatus {
    IDLE,
    RUNNING,
    COMPLETED,
    CANCELLED,
    ERROR
}

/**
 * Result of indexing a single photo
 */
data class VlmIndexResult(
    val success: Boolean,
    val description: String,
    val tags: String,
    val latencyMs: Long,
    val error: String? = null
)

/**
 * VLM indexing statistics
 */
data class VlmIndexStats(
    val totalPhotos: Int,
    val vlmIndexed: Int,
    val remaining: Int
) {
    val percentComplete: Float = if (totalPhotos > 0) vlmIndexed.toFloat() / totalPhotos else 0f
}
