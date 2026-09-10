package com.aksoyapps.edgeqslm.photos

import android.content.Context
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.content.pm.ServiceInfo
import com.aksoyapps.edgeqslm.diagnostics.ThesisDiagnostics
import com.aksoyapps.edgeqslm.photos.models.ModelDelivery
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * WorkManager Worker for background photo indexing
 * Runs when device is charging, on WiFi, and idle
 */
class PhotoIndexingWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {
    
    companion object {
        private const val TAG = "PhotoIndexingWorker"
        private const val WORK_NAME = "photo_indexing"
        private const val THESIS_WORK_NAME = "thesis_photo_indexing"
        private const val KEY_THESIS = "thesis_selection"
        private val thesisScheduleLock = Any()

        fun scheduleThesisIndexing(context: Context, session: ThesisIndexingSession) = synchronized(thesisScheduleLock) {
            require(matchesActiveSelection(context, session)) { "Review and confirm the current photos before scheduling" }
            ThesisIndexingStateStore(context, scheduled = true).save(session.copy(status = "PENDING", finishedAt = null))
            val request = PeriodicWorkRequestBuilder<PhotoIndexingWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build())
                .setInputData(workDataOf(KEY_THESIS to true))
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(THESIS_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
            ThesisDiagnostics.get(context).event("background_scheduled", mapOf("selected" to session.total, "interval_hours" to 6, "requires_charging" to true), session.sessionId)
        }

        fun cancelThesisIndexing(context: Context) = synchronized(thesisScheduleLock) {
            val store = ThesisIndexingStateStore(context, scheduled = true)
            store.snapshot()?.let { store.save(it.copy(status = "CANCELLED", finishedAt = System.currentTimeMillis())) }
            WorkManager.getInstance(context).cancelUniqueWork(THESIS_WORK_NAME)
            ThesisDiagnostics.get(context).event("background_schedule_cancelled")
        }

        fun invalidateThesisScheduleIfSelectionChanged(context: Context): Boolean = synchronized(thesisScheduleLock) {
            val scheduled = scheduledThesisSelection(context)
            if (scheduled == null) {
                WorkManager.getInstance(context).cancelUniqueWork(THESIS_WORK_NAME)
                return@synchronized false
            }
            if (matchesActiveSelection(context, scheduled)) return@synchronized false
            cancelThesisIndexing(context)
            ThesisDiagnostics.get(context).event("background_schedule_invalidated", mapOf(
                "reason" to "selection_changed", "previous_selected" to scheduled.total), scheduled.sessionId)
            true
        }

        fun scheduledThesisSelection(context: Context): ThesisIndexingSession? = synchronized(thesisScheduleLock) {
            ThesisIndexingStateStore(context, scheduled = true).snapshot()?.takeIf { it.status == "PENDING" }
        }

        suspend fun thesisScheduleStatus(context: Context): ThesisScheduleStatus = withContext(Dispatchers.IO) {
            val scheduled = synchronized(thesisScheduleLock) { ThesisIndexingStateStore(context, scheduled = true).snapshot() }
            val activeWork = WorkManager.getInstance(context).getWorkInfosForUniqueWork(THESIS_WORK_NAME).get()
                .firstOrNull { !it.state.isFinished }
            val matches = scheduled != null && matchesActiveSelection(context, scheduled)
            ThesisScheduleStatus(scheduled?.total ?: 0, when {
                scheduled == null && activeWork != null -> "MISSING_SELECTION"
                scheduled?.status != "PENDING" -> if (activeWork != null) "STOPPING" else "OFF"
                !matches -> "SELECTION_CHANGED"
                activeWork == null -> "OFF"
                else -> activeWork.state.name
            })
        }

        private fun matchesActiveSelection(context: Context, session: ThesisIndexingSession): Boolean {
            val current = PhotoSelectionStore(context).snapshot()
            return ThesisSchedulePolicy.matches(session.sessionId, session.selectedPaths,
                current?.sessionId, current?.photos.orEmpty().map { it.path }, current?.reviewed == true)
        }

        private fun scheduleCanRun(context: Context, selected: ThesisIndexingSession): Boolean {
            val scheduled = scheduledThesisSelection(context)
            return scheduled != null && scheduled.sessionId == selected.sessionId && scheduled.selectedPaths == selected.selectedPaths &&
                matchesActiveSelection(context, selected)
        }

        fun getThesisWorkInfo(context: Context) = WorkManager.getInstance(context).getWorkInfosForUniqueWorkLiveData(THESIS_WORK_NAME)
        
        // Input data keys
        const val KEY_IMAGE_MODEL_PATH = "image_model_path"
        const val KEY_TEXT_MODEL_PATH = "text_model_path"
        const val KEY_VOCAB_PATH = "vocab_path"
        
        // Progress data keys
        const val KEY_PROGRESS = "progress"
        const val KEY_CURRENT = "current"
        const val KEY_TOTAL = "total"
        const val KEY_MESSAGE = "message"
        
        /**
         * Schedule periodic indexing (every 6 hours when conditions are met)
         */
        fun schedulePeriodicIndexing(
            context: Context,
            imageModelPath: String,
            textModelPath: String,
            vocabPath: String? = null
        ) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED) // WiFi only
                .setRequiresCharging(true)                      // Must be charging
                .setRequiresDeviceIdle(true)                    // Device idle
                .setRequiresBatteryNotLow(true)                 // Battery OK
                .build()
            
            val inputData = workDataOf(
                KEY_IMAGE_MODEL_PATH to imageModelPath,
                KEY_TEXT_MODEL_PATH to textModelPath,
                KEY_VOCAB_PATH to vocabPath
            )
            
            val request = PeriodicWorkRequestBuilder<PhotoIndexingWorker>(
                repeatInterval = 6,
                repeatIntervalTimeUnit = TimeUnit.HOURS,
                flexTimeInterval = 1,
                flexTimeIntervalUnit = TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .setInputData(inputData)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()
            
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            
            Log.d(TAG, "Periodic indexing scheduled")
        }
        
        /**
         * Run indexing immediately (one-time)
         */
        fun runNow(
            context: Context,
            imageModelPath: String,
            textModelPath: String,
            vocabPath: String? = null
        ): Operation {
            val inputData = workDataOf(
                KEY_IMAGE_MODEL_PATH to imageModelPath,
                KEY_TEXT_MODEL_PATH to textModelPath,
                KEY_VOCAB_PATH to vocabPath
            )
            
            val request = OneTimeWorkRequestBuilder<PhotoIndexingWorker>()
                .setInputData(inputData)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            
            return WorkManager.getInstance(context).enqueue(request)
        }
        
        /**
         * Cancel scheduled indexing
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Indexing cancelled")
        }
        
        /**
         * Get work status
         */
        fun getWorkInfo(context: Context) = 
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkLiveData(WORK_NAME)
    }
    
    override suspend fun doWork(): Result {
        if (inputData.getBoolean(KEY_THESIS, false)) {
            val selected = scheduledThesisSelection(applicationContext)
            if (selected == null || !scheduleCanRun(applicationContext, selected)) {
                ThesisDiagnostics.get(applicationContext).event("background_skipped", mapOf("reason" to "selection_changed"), selected?.sessionId)
                invalidateThesisScheduleIfSelectionChanged(applicationContext)
                return Result.success()
            }
            if (ModelDelivery(applicationContext).isBusy) {
                ThesisDiagnostics.get(applicationContext).event("background_skipped", mapOf("reason" to "model_delivery_active"), selected.sessionId)
                return Result.success()
            }
            setForeground(getForegroundInfo())
            if (!scheduleCanRun(applicationContext, selected)) {
                ThesisDiagnostics.get(applicationContext).event("background_skipped", mapOf("reason" to "selection_changed"), selected.sessionId)
                invalidateThesisScheduleIfSelectionChanged(applicationContext)
                return Result.success()
            }
            ThesisDiagnostics.get(applicationContext).event("background_run_start", mapOf("selected" to selected.total), selected.sessionId)
            ThesisIndexingController(applicationContext).run(selected.copy(
                startedAt = System.currentTimeMillis(), status = "PENDING", completeMissingChannels = true,
            ), canStart = {
                scheduleCanRun(applicationContext, selected).also { allowed ->
                    if (!allowed) ThesisDiagnostics.get(applicationContext).event("background_skipped",
                        mapOf("reason" to "selection_changed"), selected.sessionId)
                }
            }) { progress ->
                setProgressAsync(workDataOf(KEY_CURRENT to progress.processed, KEY_TOTAL to progress.total, KEY_MESSAGE to progress.stage))
            }
            val completed = ThesisIndexingStateStore(applicationContext).snapshot()
            return if (completed?.status == "COMPLETED") Result.success() else Result.failure()
        }
        val imageModelPath = inputData.getString(KEY_IMAGE_MODEL_PATH)
        val textModelPath = inputData.getString(KEY_TEXT_MODEL_PATH)
        val vocabPath = inputData.getString(KEY_VOCAB_PATH)
        
        if (imageModelPath == null || textModelPath == null) {
            Log.e(TAG, "Model paths not provided")
            return Result.failure()
        }
        
        Log.d(TAG, "Starting photo indexing...")
        
        val indexer = PhotoIndexer(applicationContext)
        
        return try {
            // Initialize encoders
            if (!indexer.initialize(imageModelPath, textModelPath, vocabPath)) {
                Log.e(TAG, "Failed to initialize encoders")
                return Result.failure()
            }
            
            // Run indexing and report progress
            indexer.indexFolder().collect { progress ->
                // Report progress to observers
                setProgress(workDataOf(
                    KEY_PROGRESS to progress.progress,
                    KEY_CURRENT to progress.current,
                    KEY_TOTAL to progress.total,
                    KEY_MESSAGE to progress.message
                ))
                
                if (progress.current % 50 == 0) {
                    Log.d(TAG, "Progress: ${progress.current}/${progress.total} - ${progress.message}")
                }
            }
            
            Log.d(TAG, "Photo indexing completed")
            Result.success()
            
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Indexing failed: ${e.message}", e)
            Result.retry()
        } finally {
            indexer.close()
        }
    }
    
    override suspend fun getForegroundInfo(): ForegroundInfo {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("photo_indexing", "Scheduled photo indexing", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = androidx.core.app.NotificationCompat.Builder(
            applicationContext, 
            "photo_indexing"
        )
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("Indexing Photos")
            .setContentText("Scanning your photos for search...")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(1002, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else ForegroundInfo(1002, notification)
    }
}
