package com.aksoyapps.edgeqslm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.aksoyapps.edgeqslm.diagnostics.ThesisDiagnostics
import java.util.UUID
import androidx.core.app.NotificationCompat
import com.aksoyapps.edgeqslm.photos.*
import com.aksoyapps.edgeqslm.photos.IndexingType
import com.aksoyapps.edgeqslm.photos.IndexingState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Foreground Service for background photo indexing
 * 
 * Features:
 * - Survives app backgrounding and screen off
 * - Wake lock keeps CPU running
 * - START_STICKY restarts service if killed
 * - Notification shows progress
 * - Supports both ML (OCR/CLIP) and VLM indexing
 */
class IndexingService : Service() {
    
    companion object {
        private const val TAG = "IndexingService"
        private const val CHANNEL_ID = "indexing_channel"
        private const val NOTIFICATION_ID = 1001
        
        // Actions
        const val ACTION_START_ML_INDEXING = "com.aksoyapps.edgeqslm.START_ML_INDEXING"
        const val ACTION_START_VLM_INDEXING = "com.aksoyapps.edgeqslm.START_VLM_INDEXING"
        const val ACTION_START_THESIS_INDEXING = "com.aksoyapps.edgeqslm.START_THESIS_INDEXING"
        const val ACTION_PAUSE_THESIS_INDEXING = "com.aksoyapps.edgeqslm.PAUSE_THESIS_INDEXING"
        const val ACTION_RESUME_THESIS_INDEXING = "com.aksoyapps.edgeqslm.RESUME_THESIS_INDEXING"
        const val ACTION_COMPLETE_MISSING_THESIS_CHANNELS = "com.aksoyapps.edgeqslm.COMPLETE_MISSING_THESIS_CHANNELS"
        const val EXTRA_SELECTED_PATHS = "selected_paths"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_INCLUDE_SEMANTIC = "include_semantic"
        const val EXTRA_REINDEX_REQUEST_ID = "reindex_request_id"
        const val ACTION_STOP_INDEXING = "com.aksoyapps.edgeqslm.STOP_INDEXING"
        
        // Extras
        const val EXTRA_FOLDER_PATH = "folder_path"
        const val EXTRA_FORCE_INDEX = "force_index"
    }

    // Binder for Activity communication
    private val binder = IndexingBinder()
    
    inner class IndexingBinder : Binder() {
        fun getService(): IndexingService = this@IndexingService
    }
    
    // State
    private val _indexingState = MutableStateFlow(IndexingState())
    val indexingState: StateFlow<IndexingState> = _indexingState.asStateFlow()
    
    // Components
    private var wakeLock: PowerManager.WakeLock? = null
    private var indexingJob: Job? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var logger: IndexingLogger
    private var sessionStartTime: Long = 0
    
    // Service-owned handles must outlive any bound Activity.
    private var photoIndexer: PhotoIndexer? = null
    private val jobPreferences by lazy { getSharedPreferences("m1_indexing_job", Context.MODE_PRIVATE) }
    
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
        createNotificationChannel()
        logger = IndexingLogger(this)

    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: action=${intent?.action}")
        
        if (indexingJob?.isActive == true && intent?.action in setOf(
            ACTION_START_THESIS_INDEXING, ACTION_RESUME_THESIS_INDEXING, ACTION_COMPLETE_MISSING_THESIS_CHANNELS,
            ACTION_START_ML_INDEXING, ACTION_START_VLM_INDEXING)) return START_STICKY
        when (intent?.action) {
            ACTION_START_THESIS_INDEXING -> {
                val paths = intent.getStringArrayListExtra(EXTRA_SELECTED_PATHS)?.distinct().orEmpty()
                val reindexRequestId = intent.getStringExtra(EXTRA_REINDEX_REQUEST_ID)
                val selection = if (reindexRequestId != null) PhotoSelectionStore(this).snapshot() else null
                if (paths.isEmpty()) {
                    onIndexingError("Choose photos before indexing")
                } else if (reindexRequestId != null && (runCatching { UUID.fromString(reindexRequestId) }.isFailure ||
                        selection?.reviewed != true || selection.sessionId != intent.getStringExtra(EXTRA_SESSION_ID) ||
                        selection.photos.map { it.path } != paths)) {
                    onIndexingError("The reindex request no longer matches the confirmed photo selection")
                } else {
                    val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: UUID.randomUUID().toString()
                    val continuation = ThesisIndexingStateStore(this).snapshot()
                        .continueSelectedReindex(sessionId, paths, reindexRequestId)
                    startThesisIndexing(continuation ?: ThesisIndexingSession(
                        sessionId = sessionId,
                        selectedPaths = paths,
                        includeSemantic = intent.getBooleanExtra(EXTRA_INCLUDE_SEMANTIC, true),
                        reindexRequestId = reindexRequestId,
                    ))
                }
            }
            ACTION_RESUME_THESIS_INDEXING -> {
                val saved = ThesisIndexingStateStore(this).snapshot()?.takeIf { it.canResume }
                if (saved != null) startThesisIndexing(saved.copy(resumeInterruptedSources = true))
                else onIndexingError("No paused or interrupted indexing session is ready to resume")
            }
            ACTION_PAUSE_THESIS_INDEXING -> pauseIndexing()
            ACTION_COMPLETE_MISSING_THESIS_CHANNELS -> {
                val paths = intent.getStringArrayListExtra(EXTRA_SELECTED_PATHS).orEmpty()
                val session = ThesisIndexingStateStore(this).snapshot()
                if (session?.canRequestCompletion == true && session.sessionId == intent.getStringExtra(EXTRA_SESSION_ID) &&
                    session.selectedPaths == paths) {
                    startThesisIndexing(session.copy(completeMissingChannels = true, resumeInterruptedSources = true))
                } else onIndexingError("The saved indexing session no longer matches the selected photos")
            }
            ACTION_START_ML_INDEXING -> {
                val folderPath = intent.getStringExtra(EXTRA_FOLDER_PATH)
                startMlIndexing(folderPath)
            }
            ACTION_START_VLM_INDEXING -> {
                val folderPath = intent.getStringExtra(EXTRA_FOLDER_PATH)
                val force = intent.getBooleanExtra(EXTRA_FORCE_INDEX, false)
                startVlmIndexing(folderPath, force)
            }
            ACTION_STOP_INDEXING -> {
                stopIndexing()
            }
            null -> {
                val folderPath = jobPreferences.getString(EXTRA_FOLDER_PATH, null)
                when (jobPreferences.getString("action", null)) {
                    ACTION_START_THESIS_INDEXING -> {
                        ThesisIndexingStateStore(this).snapshot()?.takeIf { it.canResume || it.status == "PENDING" }
                            ?.let { startThesisIndexing(it.copy(resumeInterruptedSources = false)) }
                    }
                    ACTION_START_VLM_INDEXING -> startVlmIndexing(folderPath, false, recovery = true)
                    ACTION_START_ML_INDEXING -> startMlIndexing(folderPath)
                }
            }
        }
        
        // START_STICKY: Restart service if killed by system
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
    
    override fun onDestroy() {
        Log.i(TAG, "Service destroyed")
        indexingJob?.cancel()
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }
    
    private fun rememberJob(action: String, folderPath: String?) {
        check(jobPreferences.edit().putString("action", action)
            .putString(EXTRA_FOLDER_PATH, folderPath).commit()) { "Could not persist indexing scope" }
    }
    
    /**
     * Start ML (OCR/CLIP) indexing
     */
    private fun startMlIndexing(folderPath: String?) {
        if (indexingJob?.isActive == true) {
            Log.w(TAG, "Indexing already running")
            return
        }
        
        rememberJob(ACTION_START_ML_INDEXING, folderPath)
        acquireWakeLock()
        
        // Start foreground
        startForeground(NOTIFICATION_ID, createNotification("Starting ML indexing...", 0))
        sessionStartTime = System.currentTimeMillis()
        
        _indexingState.value = IndexingState(
            isRunning = true,
            type = IndexingType.ML,
            message = "Starting ML indexing..."
        )
        
        indexingJob = serviceScope.launch {
            val indexer = PhotoIndexer(applicationContext)
            photoIndexer = indexer
            try {
                val models = java.io.File(getExternalFilesDir(null), "models")
                val imageModel = java.io.File(models, "clip-image.onnx")
                val textModel = java.io.File(models, "clip-text.onnx")
                if (imageModel.isFile && textModel.isFile) {
                    indexer.initialize(imageModel.path, textModel.path,
                        java.io.File(models, "clip_tokenizer").path)
                }
                if (folderPath != null) {
                    indexer.setScanFolder(folderPath)
                }
                
                var lastIndexed = 0
                
                indexer.indexFolder().collect { progress ->
                    val progressPercent = if (progress.total > 0) 
                        (progress.current * 100 / progress.total) else 0
                    
                    _indexingState.value = _indexingState.value.copy(
                        current = progress.current,
                        total = progress.total,
                        progress = progressPercent / 100f,
                        message = progress.message
                    )
                    
                    updateNotification("ML: ${progress.current}/${progress.total}", progressPercent)
                    
                    // Log individual item if we have a file name (implies an action occurred)
                    if (progress.currentFile.isNotEmpty()) {
                         logger.logItem(ItemIndexingLog(
                            timestamp = System.currentTimeMillis(),
                            type = IndexingType.ML,
                            fileName = progress.currentFile,
                            latencyMs = progress.latencyMs,
                            imageSize = progress.imageSize,
                            success = progress.success,
                            error = if (!progress.success) progress.message else null
                        ))
                    }
                    
                    if (progress.isComplete) {
                        val duration = System.currentTimeMillis() - sessionStartTime
                        logger.logSession(IndexingSessionLog(
                            timestamp = sessionStartTime,
                            type = IndexingType.ML,
                            durationMs = duration,
                            totalItems = progress.total,
                            successItems = progress.indexed + progress.skipped,
                            failedItems = progress.failed,
                            folderPath = folderPath ?: "default"
                        ))
                        onIndexingComplete("ML indexing completed: ${progress.current} photos")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "ML indexing error: ${e.message}", e)
                onIndexingError("ML indexing error: ${e.message}")
            } finally {
                indexer.close()
                photoIndexer = null
            }
        }
    }
    
    /**
     * Start VLM indexing
     */
    private fun startVlmIndexing(folderPath: String?, force: Boolean, recovery: Boolean = false) {
        if (indexingJob?.isActive == true) {
            Log.w(TAG, "Indexing already running")
            return
        }

        rememberJob(ACTION_START_VLM_INDEXING, folderPath)
        acquireWakeLock()
        
        // Start foreground
        startForeground(NOTIFICATION_ID, createNotification("Starting VLM indexing...", 0))
        sessionStartTime = System.currentTimeMillis()
        
        _indexingState.value = IndexingState(
            isRunning = true,
            type = IndexingType.VLM,
            message = "Starting VLM indexing..."
        )
        
        indexingJob = serviceScope.launch {
            val store = PhotoVectorStore(applicationContext)
            val indexer = VlmIndexer(VlmImageAnalyzer(applicationContext), store)
            try {
                var lastIndexed = 0
                var lastFailed = 0
                
                indexer.indexAllPhotos(folderPath, force, resumeIncomplete = !recovery).collect { progress ->
                    val progressPercent = if (progress.total > 0) 
                        (progress.current * 100 / progress.total) else 0
                    
                    _indexingState.value = _indexingState.value.copy(
                        current = progress.current,
                        total = progress.total,
                        progress = progressPercent / 100f,
                        message = progress.message
                    )
                    
                    updateNotification("VLM: ${progress.current}/${progress.total}", progressPercent)
                    if (progress.status == VlmIndexStatus.ERROR) {
                        onIndexingError(progress.message)
                    }
                    
                    // Log items
                    if (progress.current > lastIndexed) {
                        logger.logItem(ItemIndexingLog(
                            timestamp = System.currentTimeMillis(),
                            type = IndexingType.VLM,
                            fileName = progress.currentFile,
                            latencyMs = progress.latencyMs,
                            imageSize = progress.imageSize,
                            success = true
                        ))
                        lastIndexed = progress.current
                    }
                    if (progress.failed > lastFailed) {
                        logger.logItem(ItemIndexingLog(
                            timestamp = System.currentTimeMillis(),
                            type = IndexingType.VLM,
                            fileName = progress.currentFile,
                            latencyMs = progress.latencyMs,
                            imageSize = progress.imageSize,
                            success = false,
                            error = "Indexing failed"
                        ))
                        lastFailed = progress.failed
                    }
                    
                    if (progress.status == VlmIndexStatus.COMPLETED) {
                        val duration = System.currentTimeMillis() - sessionStartTime
                        logger.logSession(IndexingSessionLog(
                            timestamp = sessionStartTime,
                            type = IndexingType.VLM,
                            durationMs = duration,
                            totalItems = progress.total,
                            successItems = progress.current,
                            failedItems = progress.failed,
                            folderPath = folderPath ?: "default"
                        ))
                        onIndexingComplete("VLM indexing completed: ${progress.current} photos")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "VLM indexing error: ${e.message}", e)
                onIndexingError("VLM indexing error: ${e.message}")
            } finally {
                store.close()
            }
        }
    }
    
    /**
     * Stop current indexing
     */
    private fun startThesisIndexing(session: ThesisIndexingSession) {
        if (indexingJob?.isActive == true) return
        if (ThesisIndexingStateStore(this).snapshot()?.isRunning == true) {
            ThesisDiagnostics.get(this).event("index_start_rejected", mapOf("reason" to "existing_run_active"), session.sessionId)
            stopSelf()
            return
        }
        rememberJob(ACTION_START_THESIS_INDEXING, null)
        acquireWakeLock()
        startForeground(NOTIFICATION_ID, createNotification("Preparing selected photos", 0))
        indexingJob = serviceScope.launch {
            try {
                ThesisIndexingController(applicationContext).run(session) { progress ->
                    val percent = if (progress.total > 0) progress.processed * 100 / progress.total else 0
                    _indexingState.value = IndexingState(
                        isRunning = progress.isRunning, type = IndexingType.ML, current = progress.processed,
                        total = progress.total, progress = percent / 100f,
                        message = ThesisIndexingControlPresentation.message(progress.status, progress.stage, progress.processed, progress.total),
                        error = progress.errorCode,
                    )
                    updateNotification(_indexingState.value.message, percent)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                ThesisDiagnostics.get(this@IndexingService).event("index_service_failure",
                    mapOf("reason" to failure.javaClass.simpleName), session.sessionId)
            } finally {
                ThesisIndexingStateStore(this@IndexingService).snapshot()?.let { finalState ->
                    _indexingState.value = _indexingState.value.copy(
                        isRunning = finalState.isRunning,
                        message = ThesisIndexingControlPresentation.message(finalState.status, finalState.stage,
                            finalState.processed, finalState.total), error = finalState.errorCode,
                    )
                }
                jobPreferences.edit().clear().commit()
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    fun pauseIndexing() {
        val stateStore = ThesisIndexingStateStore(this)
        val current = stateStore.snapshot()
        if (current?.status in setOf("PAUSING", "CANCELLING") || ThesisIndexingController.isPauseRequested ||
            ThesisIndexingController.isCancellationRequested) return
        if (!ThesisIndexingController.requestPause()) return
        jobPreferences.edit().clear().commit()
        stateStore.snapshot()?.takeIf { it.isRunning }?.let { session ->
            val message = ThesisIndexingControlPresentation.message("PAUSING", session.stage, session.processed, session.total)
            _indexingState.value = _indexingState.value.copy(isRunning = true, message = message)
            updateNotification(message, if (session.total > 0) session.processed * 100 / session.total else 0)
            ThesisDiagnostics.get(this).event("background_pause_requested", emptyMap(), session.sessionId)
        }
    }

    fun stopIndexing() {
        jobPreferences.edit().clear().commit()
        val stateStore = ThesisIndexingStateStore(this)
        val current = stateStore.snapshot()
        if (current?.status == "CANCELLING" || ThesisIndexingController.isCancellationRequested) return
        val running = indexingJob
        if (current?.isRunning == true || running?.isActive == true) {
            ThesisDiagnostics.get(this).event("background_cancel_requested",
                mapOf("stage" to (current?.stage ?: "UNKNOWN")), current?.sessionId)
        }
        val controllerCancelled = ThesisIndexingController.cancelActive()
        if (running == null && controllerCancelled) {
            _indexingState.value = _indexingState.value.copy(isRunning = true,
                message = ThesisIndexingControlPresentation.message("CANCELLING", "", 0, 0))
            return
        }
        if (running == null || running.isCompleted) {
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        _indexingState.value = _indexingState.value.copy(isRunning = true,
            message = ThesisIndexingControlPresentation.message("CANCELLING", "", 0, 0))
        running.cancel()
        serviceScope.launch {
            running.join()
            releaseWakeLock()
            val finalState = stateStore.snapshot()
            _indexingState.value = _indexingState.value.copy(isRunning = false,
                message = finalState?.let { ThesisIndexingControlPresentation.message(it.status, it.stage, it.processed, it.total) }
                    ?: "Indexing stopped")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun onIndexingComplete(message: String) {
        Log.i(TAG, message)
        jobPreferences.edit().clear().commit()
        _indexingState.value = _indexingState.value.copy(
            isRunning = false, progress = 1f, message = message
        )
        releaseWakeLock()
        updateNotification(message, 100)
        
        // Keep notification for a moment, then stop
        val completedJob = indexingJob
        serviceScope.launch {
            delay(3000)
            if (indexingJob === completedJob && indexingJob?.isActive != true) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }
    
    private fun onIndexingError(message: String) {
        jobPreferences.edit().clear().commit()
        releaseWakeLock()
        _indexingState.value = _indexingState.value.copy(
            isRunning = false,
            message = message,
            error = message
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    
    // === Notification ===
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Photo Indexing",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress while indexing photos"
                setShowBadge(false)
            }
            
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(message: String, progress: Int): Notification {
        val stopIntent = Intent(this, IndexingService::class.java).apply {
            action = ACTION_STOP_INDEXING
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )
        
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE
        )
        
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("EdgeQ Photo Indexing")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setOngoing(true)
            .setProgress(100, progress, progress == 0)
            .setContentIntent(openPendingIntent)
        val state = ThesisIndexingStateStore(this).snapshot()
        val controlStatus = ThesisIndexingControlPresentation.observedStatus(state?.status.orEmpty(),
            state?.isRunning == true, ThesisIndexingController.isPauseRequested,
            ThesisIndexingController.isCancellationRequested)
        if (jobPreferences.getString("action", null) == ACTION_START_THESIS_INDEXING &&
            controlStatus !in setOf("PAUSING", "CANCELLING")) {
            val pause = Intent(this, IndexingService::class.java).setAction(ACTION_PAUSE_THESIS_INDEXING)
            val pendingPause = PendingIntent.getService(this, 1, pause, PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(android.R.drawable.ic_media_pause, "Pause", pendingPause)
        }
        if (controlStatus != "CANCELLING")
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop now", stopPendingIntent)
        return builder.build()
    }
    
    private fun updateNotification(message: String, progress: Int) {
        val notification = createNotification(message, progress)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }
    
    // === Wake Lock ===
    
    fun isWakeLockHeld(): Boolean = wakeLock?.isHeld == true

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "EdgeQ:IndexingWakeLock"
        ).apply {
            acquire(60 * 60 * 1000L) // Max 1 hour
        }
        ThesisDiagnostics.get(this).event("index_wake_lock", mapOf("held" to true, "timeout_ms" to 3600000))
    }
    
    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "Wake lock released")
            }
        }
        wakeLock = null
        ThesisDiagnostics.get(this).event("index_wake_lock", mapOf("held" to false))
    }
}


// IndexingState and IndexingType moved to shared module
