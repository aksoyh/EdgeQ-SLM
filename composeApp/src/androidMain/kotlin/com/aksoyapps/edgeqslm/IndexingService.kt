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
    
    // Indexers (will be set by MainActivity)
    private var photoIndexer: PhotoIndexer? = null
    private var vlmIndexer: VlmIndexer? = null
    
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
        createNotificationChannel()
        acquireWakeLock()
        logger = IndexingLogger(this)
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: action=${intent?.action}")
        
        when (intent?.action) {
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
    
    /**
     * Set indexers from MainActivity
     */
    fun setIndexers(photoIndexer: PhotoIndexer?, vlmIndexer: VlmIndexer?) {
        this.photoIndexer = photoIndexer
        this.vlmIndexer = vlmIndexer
        Log.d(TAG, "Indexers set: photoIndexer=${photoIndexer != null}, vlmIndexer=${vlmIndexer != null}")
    }
    
    /**
     * Start ML (OCR/CLIP) indexing
     */
    private fun startMlIndexing(folderPath: String?) {
        if (_indexingState.value.isRunning) {
            Log.w(TAG, "Indexing already running")
            return
        }
        
        val indexer = photoIndexer
        if (indexer == null) {
            Log.e(TAG, "PhotoIndexer not set")
            stopSelf()
            return
        }
        
        // Start foreground
        startForeground(NOTIFICATION_ID, createNotification("Starting ML indexing...", 0))
        sessionStartTime = System.currentTimeMillis()
        
        _indexingState.value = IndexingState(
            isRunning = true,
            type = IndexingType.ML,
            message = "Starting ML indexing..."
        )
        
        indexingJob = serviceScope.launch {
            try {
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
                            successItems = progress.current, // Approximate if we don't track separately in Service
                            failedItems = progress.total - progress.current, // Approximate
                            folderPath = folderPath ?: "default"
                        ))
                        onIndexingComplete("ML indexing completed: ${progress.current} photos")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "ML indexing error: ${e.message}", e)
                onIndexingError("ML indexing error: ${e.message}")
            }
        }
    }
    
    /**
     * Start VLM indexing
     */
    private fun startVlmIndexing(folderPath: String?, force: Boolean) {
        if (_indexingState.value.isRunning) {
            Log.w(TAG, "Indexing already running")
            return
        }

        val indexer = vlmIndexer
        Log.i(TAG, "startVlmIndexing: folder=$folderPath, force=$force, indexer=${if (indexer!=null) "set" else "null"}, available=${indexer?.isAvailable()}")
        
        if (indexer == null || !indexer.isAvailable()) {
            Log.e(TAG, "VLM indexer not available")
            stopSelf()
            return
        }
        
        // Start foreground
        startForeground(NOTIFICATION_ID, createNotification("Starting VLM indexing...", 0))
        sessionStartTime = System.currentTimeMillis()
        
        _indexingState.value = IndexingState(
            isRunning = true,
            type = IndexingType.VLM,
            message = "Starting VLM indexing..."
        )
        
        indexingJob = serviceScope.launch {
            try {
                var lastIndexed = 0
                var lastFailed = 0
                
                indexer.indexAllPhotos(folderPath, force).collect { progress ->
                    val progressPercent = if (progress.total > 0) 
                        (progress.current * 100 / progress.total) else 0
                    
                    _indexingState.value = _indexingState.value.copy(
                        current = progress.current,
                        total = progress.total,
                        progress = progressPercent / 100f,
                        message = progress.message
                    )
                    
                    updateNotification("VLM: ${progress.current}/${progress.total}", progressPercent)
                    
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
            } catch (e: Exception) {
                Log.e(TAG, "VLM indexing error: ${e.message}", e)
                onIndexingError("VLM indexing error: ${e.message}")
            }
        }
    }
    
    /**
     * Stop current indexing
     */
    fun stopIndexing() {
        Log.i(TAG, "Stopping indexing")
        indexingJob?.cancel()
        _indexingState.value = IndexingState(
            isRunning = false,
            message = "Indexing cancelled"
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    
    private fun onIndexingComplete(message: String) {
        Log.i(TAG, message)
        _indexingState.value = IndexingState(
            isRunning = false,
            message = message
        )
        updateNotification(message, 100)
        
        // Keep notification for a moment, then stop
        serviceScope.launch {
            delay(3000)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }
    
    private fun onIndexingError(message: String) {
        _indexingState.value = IndexingState(
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
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("EdgeQ Photo Indexing")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setOngoing(true)
            .setProgress(100, progress, progress == 0)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .build()
    }
    
    private fun updateNotification(message: String, progress: Int) {
        val notification = createNotification(message, progress)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }
    
    // === Wake Lock ===
    
    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "EdgeQ:IndexingWakeLock"
        ).apply {
            acquire(60 * 60 * 1000L) // Max 1 hour
        }
        Log.d(TAG, "Wake lock acquired")
    }
    
    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "Wake lock released")
            }
        }
        wakeLock = null
    }
}


// IndexingState and IndexingType moved to shared module

