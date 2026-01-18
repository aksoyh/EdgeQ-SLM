package com.aksoyapps.edgeqslm.photos

import android.content.Context
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.flow.collect
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
            indexer.close()
            Result.success()
            
        } catch (e: Exception) {
            Log.e(TAG, "Indexing failed: ${e.message}", e)
            indexer.close()
            Result.retry()
        }
    }
    
    override suspend fun getForegroundInfo(): ForegroundInfo {
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
        
        return ForegroundInfo(1002, notification)
    }
}
