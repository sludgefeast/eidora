// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.worker

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager

object SyncPipeline {
    const val UNIQUE_SYNC_NAME = "eidora-sync-pipeline"
    const val UNIQUE_CLUSTERING_NAME = "eidora-clustering"
    const val PERIODIC_SYNC_NAME = "eidora-periodic-sync"

    // -----------------------------------------------------------------------
    // Sync (Photo → Embedding). The model container is downloaded separately on
    // first run (see ContainerDownloader), not as part of this chain.
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
                ScanWorker.buildRequest(),
            ).then(TriageWorker.buildRequest())
            .then(DetectionWorker.buildRequest())
            .then(EmbeddingWorker.buildRequest())
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
                ScanWorker.buildForceRequest(),
            ).then(TriageWorker.buildRequest())
            .then(DetectionWorker.buildRequest())
            .then(EmbeddingWorker.buildRequest())
            .enqueue()
    }

    /**
     * Cancels the running sync + clustering chains and clears pause state.
     * Call before a destructive reset so no worker keeps writing to rows that
     * are about to change underneath it.
     */
    /**
     * Clears pause state and cancels clustering before a re-analyze. Note it
     * deliberately does NOT cancelUniqueWork(UNIQUE_SYNC_NAME): the follow-up
     * enqueueRedetectAll uses beginUniqueWork(..., REPLACE, ...) which already
     * replaces any running sync chain under that name. Cancelling here first
     * raced with that REPLACE and could leave the new chain cancelled too —
     * which is exactly why "re-analyze" appeared to do nothing.
     */
    fun cancelRunningSync(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(UNIQUE_CLUSTERING_NAME)
        PauseState.setPaused(context, false)
    }

    /**
     * Re-analyze everything: start straight at detection. The caller has already
     * cancelled the running chain (via [cancelRunningSync]), cleared all face
     * data and set every photo to NEEDS_DETECTION, so scan and triage would have
     * nothing to do — we skip them and run Detection → Embedding → Clustering.
     */
    fun enqueueRedetectAll(context: Context) {
        WorkManager
            .getInstance(context)
            .beginUniqueWork(
                UNIQUE_SYNC_NAME,
                ExistingWorkPolicy.REPLACE,
                DetectionWorker.buildRequest(),
            ).then(EmbeddingWorker.buildRequest())
            .enqueue()
    }

    fun enqueueReSyncPhoto(
        context: Context,
        photoId: String,
    ) {
        WorkManager
            .getInstance(context)
            .beginUniqueWork(
                UNIQUE_SYNC_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                SinglePhotoWorker.buildRequest(photoId),
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

    /**
     * Cancels the running sync and clustering chains and restarts the pipeline
     * from the beginning. Used after the folder whitelist changed: the current
     * run is scanning/analysing a set of folders that is no longer correct.
     *
     * The XMP write chain ([XmpWriteWorker], unique name "eidora-xmp-write") is
     * deliberately NOT cancelled — it is an independent chain that persists
     * user-confirmed names into photo files, and interrupting it could leave
     * files without their metadata.
     */
    fun restartAfterFolderChange(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(UNIQUE_SYNC_NAME)
        wm.cancelUniqueWork(UNIQUE_CLUSTERING_NAME)
        // Clear any pause state so the new run isn't stuck paused from before.
        PauseState.setPaused(context, false)
        // REPLACE (via enqueueForce) supersedes whatever is left of the old
        // chain, and a force run rescans every photo in the new folder set.
        enqueueForce(context)
    }

    // -----------------------------------------------------------------------
    // State queries for mutual exclusion
    // -----------------------------------------------------------------------

    private fun isClusteringRunning(context: Context): Boolean =
        WorkManager
            .getInstance(context)
            .getWorkInfosForUniqueWork(UNIQUE_CLUSTERING_NAME)
            .get()
            ?.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
            ?: false
}
