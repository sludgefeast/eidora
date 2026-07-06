package de.sebastian.eidora.worker

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf

object SyncPipeline {

    private const val UNIQUE_WORK_NAME = "eidora-sync-pipeline"

    fun enqueue(context: Context) {
        WorkManager.getInstance(context)
            .beginUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                PhotoSyncWorker.buildRequest()
            )
            .then(ModelDownloadWorker.buildRequest())
            .then(EmbeddingWorker.buildRequest())
            .then(ClusteringWorker.buildRequest())
            .enqueue()
    }

    fun enqueueForce(context: Context) {
        WorkManager.getInstance(context)
            .beginUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                PhotoSyncWorker.buildRequest()
            )
            .then(ModelDownloadWorker.buildRequest())
            .then(EmbeddingWorker.buildRequest())
            .then(ClusteringWorker.buildRequest())
            .enqueue()
    }

    fun enqueueClustering(context: Context) {
        WorkManager.getInstance(context)
            .beginUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                ClusteringWorker.buildRequest()
            )
            .enqueue()
    }

    fun enqueueReSyncPhoto(context: Context, photoId: String) {
        val syncRequest = OneTimeWorkRequestBuilder<PhotoSyncWorker>()
            .setInputData(workDataOf(PhotoSyncWorker.KEY_PHOTO_ID to photoId))
            .build()
        WorkManager.getInstance(context)
            .beginUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                syncRequest
            )
            .then(ModelDownloadWorker.buildRequest())
            .then(EmbeddingWorker.buildRequest())
            .then(ClusteringWorker.buildRequest())
            .enqueue()
    }
}
