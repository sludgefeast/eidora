// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.worker

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf

object SyncPipeline {
    const val UNIQUE_SYNC_NAME = "eidora-sync-pipeline"
    const val UNIQUE_CLUSTERING_NAME = "eidora-clustering"
    const val PERIODIC_SYNC_NAME = "eidora-periodic-sync"

    // -----------------------------------------------------------------------
    // Sync (Photo → Embedding). Model download is NOT part of the chain –
    // it runs only after explicit user consent via enqueueModelDownload().
    // -----------------------------------------------------------------------

    fun enqueue(context: Context) {
        if (isClusteringRunning(context)) {
            android.util.Log.i("SyncPipeline", "Clustering active, sync will wait")
        }
        WorkManager
            .getInstance(context)
            .beginUniqueWork(
                UNIQUE_SYNC_NAME,
                ExistingWorkPolicy.KEEP,
                PhotoSyncWorker.buildRequest(),
            ).then(EmbeddingWorker.buildRequest())
            .enqueue()
    }

    fun enqueueForce(context: Context) {
        context
            .getSharedPreferences("sync_state", android.content.Context.MODE_PRIVATE)
            .edit()
            .remove("last_sync_timestamp_sec")
            .apply()
        WorkManager
            .getInstance(context)
            .beginUniqueWork(
                UNIQUE_SYNC_NAME,
                ExistingWorkPolicy.REPLACE,
                PhotoSyncWorker.buildForceRequest(),
            ).then(EmbeddingWorker.buildRequest())
            .enqueue()
    }

    fun enqueueReSyncPhoto(
        context: Context,
        photoId: String,
    ) {
        val syncRequest =
            OneTimeWorkRequestBuilder<PhotoSyncWorker>()
                .setInputData(workDataOf(PhotoSyncWorker.KEY_PHOTO_ID to photoId))
                .build()
        WorkManager
            .getInstance(context)
            .beginUniqueWork(
                UNIQUE_SYNC_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                syncRequest,
            ).then(EmbeddingWorker.buildRequest())
            .enqueue()
    }

    // -----------------------------------------------------------------------
    // Clustering – manual only, runs as a separate unique work chain
    // -----------------------------------------------------------------------

    /**
     * Enqueues a manual clustering run.
     * @param rejectSuggestions delete all existing unnamed suggestions first
     * @param removeUnconfirmed remove unconfirmed faces from named persons first
     */
    fun enqueueClustering(
        context: Context,
        rejectSuggestions: Boolean = false,
        removeUnconfirmed: Boolean = false,
    ) {
        WorkManager
            .getInstance(context)
            .enqueueUniqueWork(
                UNIQUE_CLUSTERING_NAME,
                ExistingWorkPolicy.KEEP,
                ClusteringWorker.buildRequest(rejectSuggestions, removeUnconfirmed),
            )
    }

    /** User-initiated model download (after the consent dialog). */
    fun enqueueModelDownload(context: Context) {
        WorkManager
            .getInstance(context)
            .enqueueUniqueWork(
                "eidora-model-download",
                ExistingWorkPolicy.KEEP,
                ModelDownloadWorker.buildRequest(),
            )
    }

    // -----------------------------------------------------------------------
    // State queries for mutual exclusion
    // -----------------------------------------------------------------------

    fun isClusteringRunning(context: Context): Boolean =
        WorkManager
            .getInstance(context)
            .getWorkInfosForUniqueWork(UNIQUE_CLUSTERING_NAME)
            .get()
            ?.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
            ?: false
}
