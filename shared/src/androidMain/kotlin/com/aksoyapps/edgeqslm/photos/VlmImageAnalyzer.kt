package com.aksoyapps.edgeqslm.photos

import android.content.Context
import com.aksoyapps.edgeqslm.diagnostics.ThesisDiagnostics
import android.graphics.BitmapFactory
import com.aksoyapps.edgeqslm.AndroidLlamaCppEngine
import com.aksoyapps.edgeqslm.GenerationResult
import java.io.File
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

interface VlmSourceAnalyzer {
    val lastDiagnostic: VlmSourceDiagnostic? get() = null
    fun isAvailable(): Boolean
    suspend fun <T> withSession(block: suspend () -> T): T
    suspend fun analyzeImage(imagePath: String): VlmSourceAnalysis
}

data class VlmSourceAnalysis(val generation: GenerationResult, val imageTransform: VlmImageTransform? = null)

/** The production structured indexing entry point. Models are resident only within a session. */
class VlmImageAnalyzer(context: Context) : VlmSourceAnalyzer {
    private val context = context.applicationContext
    private val diagnostics = ThesisDiagnostics.get(this.context)
    private val engine = AndroidLlamaCppEngine()
    private var sessionActive = false
    private val imageRuntime by lazy { VlmWorkingImage.runtime(this.context) }
    override var lastDiagnostic: VlmSourceDiagnostic? = null
        private set

    override fun isAvailable(): Boolean = runCatching {
        VlmInferenceContract.resolveArtifacts(context)
        VlmInferenceContract.prompt(context)
    }.isSuccess

    override suspend fun <T> withSession(block: suspend () -> T): T =
        try { engine.withExclusiveSession {
            sessionActive = true
            try {
                block()
            } finally {
                sessionActive = false
            }
        } } finally {
            diagnostics.modelState("vlm_lfm25_q5", "closed")
            diagnostics.modelState("vlm_lfm25_projector_q8", "closed")
        }

    override suspend fun analyzeImage(imagePath: String): VlmSourceAnalysis {
        var observation = VlmSourceDiagnostic(failureStage = VlmFailureStage.IMAGE_DECODE,
            inferenceCompleted = false, parseStatus = VlmEvidenceStatus.NOT_REACHED,
            schemaStatus = VlmEvidenceStatus.NOT_REACHED, eligibilityStatus = VlmEvidenceStatus.NOT_REACHED,
            projectionStatus = VlmEvidenceStatus.NOT_REACHED, observedThisRun = true)
        lastDiagnostic = observation
        try {
            check(sessionActive) { "VLM analysis requires an exclusive indexing session" }
            coroutineContext.ensureActive()
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(imagePath, options)
            observation = observation.copy(failureStage = VlmFailureStage.IMAGE_ADMISSION)
            val plan = VlmImageAdmissionPolicy.plan(options.outWidth, options.outHeight, File(imagePath).length())
            observation = observation.copy(failureStage = VlmFailureStage.IMAGE_RESIZE,
                workingWidth = plan.width, workingHeight = plan.height)
            val working = if (plan.resized) VlmWorkingImage.prepare(imagePath, plan, imageRuntime) else null
            coroutineContext.ensureActive()
            if (!engine.isVisionLoaded()) {
                observation = observation.copy(failureStage = VlmFailureStage.MODEL_LOAD)
                val artifacts = VlmInferenceContract.resolveArtifacts(context)
                diagnostics.modelState("vlm_lfm25_q5", "loading")
                diagnostics.measured("vlm_model_load") {
                    check(engine.loadModel(artifacts.model.absolutePath)) { "vlm_model_load_failed" }
                }
                diagnostics.modelState("vlm_lfm25_q5", "loaded")
                observation = observation.copy(failureStage = VlmFailureStage.PROJECTOR_LOAD)
                diagnostics.modelState("vlm_lfm25_projector_q8", "loading")
                diagnostics.measured("vlm_projector_load") {
                    check(engine.loadVisionProjector(artifacts.projector.absolutePath)) { "vlm_projector_load_failed" }
                }
                diagnostics.modelState("vlm_lfm25_projector_q8", "loaded")
            }
            val prompt = VlmInferenceContract.prompt(context)
            observation = observation.copy(failureStage = VlmFailureStage.IMAGE_DECODE)
            val rgb = working?.rgb ?: VlmWorkingImage.legacyRgb(imagePath, plan.width, plan.height)
            observation = observation.copy(failureStage = VlmFailureStage.IMAGE_ADMISSION)
            check(plan.width.toLong() * plan.height <= VlmInferenceContract.MAX_IMAGE_PIXELS &&
                rgb.size.toLong() == plan.width.toLong() * plan.height * 3) { "image_exceeds_admission_limit" }
            observation = observation.copy(failureStage = VlmFailureStage.INFERENCE)
            val generation = diagnostics.measured("vlm_inference", diagnostics.safeId(imagePath)) { engine.analyzeImage(
                imageData = rgb, width = plan.width, height = plan.height,
                prompt = prompt, maxTokens = VlmInferenceContract.MAX_TOKENS
            ) }
            lastDiagnostic = observation.withGeneration(generation)
            return VlmSourceAnalysis(generation, working?.transform)
        } catch (failure: Throwable) {
            lastDiagnostic = observation.failed(failure)
            throw failure
        }
    }

    companion object {
        fun imageRevision(path: String): ImageRevision {
            val file = File(path)
            require(file.isFile) { "image_not_found" }
            val modified = file.lastModified()
            val size = file.length()
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            check(file.lastModified() == modified && file.length() == size) { "image_changed_during_read" }
            return ImageRevision(modified, size, digest.digest().joinToString("") { "%02x".format(it) })
        }
    }
}
