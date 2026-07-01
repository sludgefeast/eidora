package de.sebastian.faces.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import de.sebastian.faces.R
import de.sebastian.faces.ml.ModelDownloader

private const val TAG = "ModelDownloadWorker"

class ModelDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (ModelDownloader.isDownloaded(applicationContext)) {
            return Result.success()
        }

        return try {
            try {
                setForeground(
                    NotificationHelper.modelDownloadForegroundInfo(applicationContext, 0)
                )
            } catch (t: Throwable) {
                Log.w(TAG, "setForeground failed", t)
            }
            setProgress(workDataOf(
                PhotoSyncWorker.KEY_STATUS to applicationContext.getString(R.string.notification_download_model)
            ))

            val success = ModelDownloader.download(applicationContext) { progress ->
                try {
                    setProgressAsync(workDataOf(PhotoSyncWorker.KEY_PROGRESS to progress))
                    // Foreground update is best-effort, ignore failures
                    setForegroundAsync(
                        NotificationHelper.modelDownloadForegroundInfo(applicationContext, progress)
                    )
                } catch (t: Throwable) { /* ignore progress errors */ }
            }

            if (success) Result.success() else Result.retry()
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
