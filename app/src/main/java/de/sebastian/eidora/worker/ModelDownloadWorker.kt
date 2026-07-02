package de.sebastian.eidora.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import de.sebastian.eidora.R
import de.sebastian.eidora.ml.ModelDownloader

private const val TAG = "ModelDownloadWorker"

class ModelDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.i(TAG, "ModelDownloadWorker started")

        if (ModelDownloader.isDownloaded(applicationContext)) {
            Log.i(TAG, "Model already downloaded, skipping")
            return Result.success()
        }

        return try {
            try {
                setForeground(NotificationHelper.modelDownloadForegroundInfo(applicationContext, 0))
            } catch (t: Throwable) {
                Log.w(TAG, "setForeground failed, continuing without foreground service", t)
            }

            Log.i(TAG, "Starting model download from ${ModelDownloader.MODEL_URL}")
            val success = ModelDownloader.download(applicationContext) { progress ->
                // Throttled progress log
                if (progress % 10 == 0) Log.d(TAG, "Download progress: $progress%")
            }

            if (success) {
                Log.i(TAG, "Model download successful")
                Result.success()
            } else {
                Log.w(TAG, "Model download failed, will retry")
                Result.retry()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Unhandled error in ModelDownloadWorker", t)
            Result.retry()
        }
    }

    companion object {
        fun buildRequest(): OneTimeWorkRequest {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            return OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    30_000L,
                    java.util.concurrent.TimeUnit.MILLISECONDS
                )
                .build()
        }
    }
}
