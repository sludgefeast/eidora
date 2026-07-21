// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.worker

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import org.eidora.R
import org.eidora.ml.ModelDownloader

private const val TAG = "ModelDownloadWorker"

class ModelDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Log.i(TAG, "ModelDownloadWorker started")

        if (ModelDownloader.allModelsReady(applicationContext)) {
            Log.i(TAG, "All models already downloaded, skipping")
            return Result.success()
        }

        return try {
            try {
                setForeground(NotificationHelper.modelDownloadForegroundInfo(applicationContext, 0))
            } catch (t: Throwable) {
                Log.w(TAG, "setForeground failed, continuing without foreground service", t)
            }

            Log.i(TAG, "Starting model download (attempt ${runAttemptCount + 1})")
            val outcome =
                ModelDownloader.download(applicationContext) { progress ->
                    if (progress % 10 == 0) Log.d(TAG, "Download progress: $progress%")
                }

            when (outcome) {
                ModelDownloader.DownloadOutcome.SUCCESS -> {
                    Log.i(TAG, "Model download successful – triggering full sync")
                    // Photos deferred while models were missing have analyzed=false
                    // but old DATE_MODIFIED values; a force sync rescans everything
                    // and the !analyzed recovery path picks them up.
                    SyncPipeline.enqueueForce(applicationContext)
                    Result.success()
                }
                ModelDownloader.DownloadOutcome.HASH_MISMATCH -> {
                    // File was downloaded but content differs from expected.
                    // Give up quickly – retrying will almost certainly yield the same bad file.
                    if (runAttemptCount >= 2) {
                        Log.e(TAG, "Model hash mismatch after ${runAttemptCount + 1} attempts, giving up")
                        showPermanentFailureNotification()
                        Result.failure()
                    } else {
                        Log.w(TAG, "Model hash mismatch, will retry")
                        Result.retry()
                    }
                }
                ModelDownloader.DownloadOutcome.NETWORK_ERROR -> {
                    if (runAttemptCount >= 5) {
                        Log.e(
                            TAG,
                            "Model download kept failing (network), giving up after ${runAttemptCount + 1} attempts",
                        )
                        showPermanentFailureNotification()
                        Result.failure()
                    } else {
                        Log.w(TAG, "Model download failed (network), will retry")
                        Result.retry()
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Unhandled error in ModelDownloadWorker", t)
            Result.retry()
        }
    }

    private fun showPermanentFailureNotification() {
        val ctx = applicationContext
        val notification =
            NotificationCompat
                .Builder(ctx, "sync")
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(android.graphics.Color.parseColor("#EC4899"))
                .setContentTitle(ctx.getString(R.string.model_download_failed_title))
                .setContentText(ctx.getString(R.string.model_download_failed_message))
                .setStyle(
                    NotificationCompat
                        .BigTextStyle()
                        .bigText(ctx.getString(R.string.model_download_failed_message)),
                ).setAutoCancel(true)
                .build()
        try {
            NotificationManagerCompat.from(ctx).notify(1006, notification)
        } catch (t: Throwable) {
            // ignore
        }
    }

    companion object {
        fun buildRequest(): OneTimeWorkRequest {
            val constraints =
                Constraints
                    .Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            return OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    30_000L,
                    java.util.concurrent.TimeUnit.MILLISECONDS,
                ).build()
        }
    }
}
