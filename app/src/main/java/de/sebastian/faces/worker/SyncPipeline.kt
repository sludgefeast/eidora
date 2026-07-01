package de.sebastian.faces.worker

import android.content.Context
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf

object SyncPipeline {

    fun enqueue(context: Context) {
        WorkManager.getInstance(context)
            .beginWith(ModelDownloadWorker.buildRequest())
            .then(PhotoSyncWorker.buildRequest())
            .then(EmbeddingWorker.buildRequest())
            .then(ClusteringWorker.buildRequest())
            .enqueue()
    }

    fun enqueueClustering(context: Context) {
        WorkManager.getInstance(context)
            .beginWith(ClusteringWorker.buildRequest())
            .enqueue()
    }

    fun enqueueReSyncPhoto(context: Context, photoId: String) {
        val syncRequest = OneTimeWorkRequestBuilder<PhotoSyncWorker>()
            .setInputData(workDataOf(PhotoSyncWorker.KEY_PHOTO_ID to photoId))
            .build()
        WorkManager.getInstance(context)
            .beginWith(ModelDownloadWorker.buildRequest())
            .then(syncRequest)
            .then(EmbeddingWorker.buildRequest())
            .then(ClusteringWorker.buildRequest())
            .enqueue()
    }
}
