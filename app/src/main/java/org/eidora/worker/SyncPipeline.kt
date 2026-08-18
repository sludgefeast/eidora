// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.worker

import org.eidora.util.EidoraLog

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.map

object SyncPipeline {
    const val UNIQUE_SYNC_NAME = "eidora-sync-pipeline"
    const val UNIQUE_CLUSTERING_NAME = "eidora-clustering"
    const val PERIODIC_SYNC_NAME = "eidora-periodic-sync"

    // Bounded wait for workers to stop after a cancel, before a destructive
    // reset. ~20 × 100ms = 2s max, enough for a worker to unwind without
    // hanging the caller if one is stuck.
    private const val CANCEL_WAIT_MAX_TRIES = 20
    private const val CANCEL_WAIT_INTERVAL_MS = 100L

    // -----------------------------------------------------------------------
    // Sync (Photo → Embedding). The model container is downloaded separately on
    // first run (see ContainerDownloader), not as part of this chain.
    // -----------------------------------------------------------------------

    fun enqueue(context: Context) {
        if (isClusteringRunning(context)) {
            EidoraLog.i("SyncPipeline", "Clustering active, sync will wait")
        }
        // Normally KEEP: don't disturb a chain that's already running or queued.
        // But a fresh install / update can leave an ORPHANED unique chain behind
        // — WorkManager reports it as present (so KEEP silently ignores our new
        // request), yet its later stages can't run because their prerequisites no
        // longer exist ("Prerequisite … doesn't exist; not enqueuing"). Detect
        // that stuck state and replace the chain once; otherwise keep.
        val policy =
            if (isChainOrphaned(context)) {
                EidoraLog.w("SyncPipeline", "Orphaned sync chain detected; replacing it")
                ExistingWorkPolicy.REPLACE
            } else {
                ExistingWorkPolicy.KEEP
            }
        WorkManager
            .getInstance(context)
            .beginUniqueWork(
                UNIQUE_SYNC_NAME,
                policy,
                ScanWorker.buildRequest(),
            ).then(TriageWorker.buildRequest())
            .then(DetectionWorker.buildRequest())
            .then(EmbeddingWorker.buildRequest())
            .enqueue()
    }

    /**
     * True when the unique sync chain is orphaned — the reinstall/force-stop
     * artefact seen in practice. Signature from real logs: the head stage (scan)
     * is ENQUEUED, but the follow-up stages' WorkSpecs are gone ("Prerequisite …
     * doesn't exist; not enqueuing"), so nothing ever runs. A HEALTHY freshly
     * enqueued chain has the head ENQUEUED *and* its followers BLOCKED (waiting);
     * an orphaned one has an enqueued/pending head with NO blocked followers and
     * nothing running. That "head present, body missing" shape is the reliable
     * tell — more so than counting stages, since WorkManager keeps SUCCEEDED
     * infos around for a while. Best-effort and synchronous; any failure returns
     * false so we fall back to the safe KEEP behaviour.
     */
    private fun isChainOrphaned(context: Context): Boolean =
        try {
            val infos =
                WorkManager
                    .getInstance(context)
                    .getWorkInfosForUniqueWork(UNIQUE_SYNC_NAME)
                    .get()
            when {
                infos.isNullOrEmpty() -> false // no chain: KEEP will create one
                infos.any { it.state == WorkInfo.State.RUNNING } -> false // healthy
                else -> {
                    val pendingHead =
                        infos.any {
                            it.state == WorkInfo.State.ENQUEUED
                        }
                    val hasBlockedFollowers =
                        infos.any { it.state == WorkInfo.State.BLOCKED }
                    // Head pending but no blocked followers and nothing running →
                    // the body was lost: orphaned.
                    pendingHead && !hasBlockedFollowers
                }
            }
        } catch (t: Throwable) {
            EidoraLog.w("SyncPipeline", "Orphan check failed, keeping chain", t)
            false
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
     * Cancels the clustering chain and clears pause state, but deliberately does
     * NOT touch the sync chain. Used where a follow-up beginUniqueWork(REPLACE)
     * will take over the sync chain itself; cancelling it here first would race
     * with that REPLACE and could leave the new chain cancelled too.
     */
    fun cancelRunningSync(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(UNIQUE_CLUSTERING_NAME)
        PauseState.setPaused(context, false)
    }

    /**
     * Fully stops ALL background work (sync chain + clustering) and BLOCKS until
     * the workers have actually stopped, then clears pause state. Use before a
     * destructive reset that runs *before* re-enqueuing (e.g. re-analyze): the
     * deletion must not overlap a still-running triage/detection pass, or that
     * pass re-imports XMP persons the reset just deleted. Because we wait here
     * and only re-enqueue AFTER the reset, there's no race with a REPLACE.
     * Best-effort bounded wait so a stuck worker can't hang the caller.
     */
    fun cancelAndAwaitSync(context: Context) {
        val wm = WorkManager.getInstance(context)
        try {
            // cancelUniqueWork returns an Operation; .result.get() blocks until
            // the cancellation has been processed by WorkManager.
            wm.cancelUniqueWork(UNIQUE_SYNC_NAME).result.get()
            wm.cancelUniqueWork(UNIQUE_CLUSTERING_NAME).result.get()
        } catch (t: Throwable) {
            EidoraLog.w("SyncPipeline", "cancel operation failed", t)
        }
        // A cancelled CoroutineWorker still needs a moment to unwind. Poll until
        // neither chain has a RUNNING stage, up to a bounded number of tries.
        try {
            var tries = 0
            while (tries < CANCEL_WAIT_MAX_TRIES) {
                val running =
                    (
                        wm.getWorkInfosForUniqueWork(UNIQUE_SYNC_NAME).get().orEmpty() +
                            wm.getWorkInfosForUniqueWork(UNIQUE_CLUSTERING_NAME).get().orEmpty()
                    ).any { it.state == WorkInfo.State.RUNNING }
                if (!running) break
                Thread.sleep(CANCEL_WAIT_INTERVAL_MS)
                tries++
            }
            if (tries >= CANCEL_WAIT_MAX_TRIES) {
                EidoraLog.w("SyncPipeline", "Workers still running after wait; proceeding anyway")
            }
        } catch (t: Throwable) {
            EidoraLog.w("SyncPipeline", "wait-for-stop failed", t)
        }
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

    /**
     * Reactive stream of whether clustering is currently running or queued, for
     * in-app progress feedback (so the Persons screen can show that grouping is
     * in progress instead of looking frozen). Emits on every work-state change.
     */
    fun clusteringRunningFlow(context: Context): kotlinx.coroutines.flow.Flow<Boolean> =
        WorkManager
            .getInstance(context)
            .getWorkInfosForUniqueWorkFlow(UNIQUE_CLUSTERING_NAME)
            .map { infos ->
                infos.any {
                    it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
                }
            }
}
