package de.sebastian.faces.worker

import android.content.Context
import androidx.work.WorkManager

object SyncPipeline {

    /**
     * Enqueues the three-step pipeline:
     * PhotoSync → EmbeddingWorker → ClusteringWorker
     *
     * Uses chaining so each step only starts when the previous one succeeds.
     */
    fun enqueue(context: Context) {
        WorkManager.getInstance(context)
            .beginWith(PhotoSyncWorker.buildRequest())
            .then(EmbeddingWorker.buildRequest())
            .then(ClusteringWorker.buildRequest())
            .enqueue()
    }

    /**
     * Enqueues only the clustering step (user-triggered re-cluster).
     */
    fun enqueueClustering(context: Context) {
        WorkManager.getInstance(context)
            .beginWith(ClusteringWorker.buildRequest())
            .enqueue()
    }

    /**
     * Enqueues a re-sync for a single photo (after "Re-detect faces" action).
     * Uses a unique work name per photo to avoid duplicates.
     */
    fun enqueueReSyncPhoto(context: Context, photoId: String) {
        val tag = "resync_$photoId"
        WorkManager.getInstance(context)
            .beginWith(
                androidx.work.OneTimeWorkRequestBuilder<PhotoSyncWorker>()
                    .setInputData(androidx.work.workDataOf(PhotoSyncWorker.KEY_PHOTO_ID to photoId))
                    .addTag(tag)
                    .build()
            )
            .then(EmbeddingWorker.buildRequest())
            .then(ClusteringWorker.buildRequest())
            .enqueue()
    }
}
