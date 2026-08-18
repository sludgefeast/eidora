// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.eidora.data.db.DatabaseProvider
import org.eidora.data.repository.FaceRepository
import org.eidora.data.settings.SettingsProvider
import org.eidora.util.EidoraLog

/**
 * Runs the "re-analyze all photos" reset off the UI. Previously this ran in a
 * rememberCoroutineScope tied to the confirmation dialog's composition: closing
 * the dialog left the composition and cancelled the scope mid-reset, so every
 * photo failed with ForgottenCoroutineScopeException and the operation appeared
 * to do nothing. A WorkManager job survives composition changes, navigation and
 * process death, which is what this heavy, whole-library reset needs.
 *
 * Steps: fully stop the running sync + clustering and wait for workers to stop,
 * then clear face data for the whitelisted folders, then enqueue detection.
 * Waiting first prevents a still-running triage pass from re-importing XMP
 * persons the reset is deleting.
 */
class ReanalyzeWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            EidoraLog.i(TAG, "Re-analyze all: starting")
            val repo =
                FaceRepository(
                    applicationContext,
                    DatabaseProvider.getInstance(applicationContext),
                )
            // Only the currently visible folders (the same whitelist the photo
            // grid shows), not every photo the DB has ever seen.
            val folders =
                SettingsProvider
                    .get(applicationContext)
                    .getFolderWhitelist()
                    .toList()
            SyncPipeline.cancelAndAwaitSync(applicationContext)
            repo.resetFoldersForRedetection(folders)
            SyncPipeline.enqueueRedetectAll(applicationContext)
            EidoraLog.i(TAG, "Re-analyze all: enqueued detection")
            Result.success()
        } catch (t: Throwable) {
            t.rethrowIfCancellation()
            EidoraLog.e(TAG, "Re-analyze all failed", t)
            Result.failure()
        }
    }

    companion object {
        private const val TAG = "ReanalyzeWorker"
        private const val UNIQUE_NAME = "eidora-reanalyze"

        /**
         * Enqueues the reset as unique work. REPLACE so a second tap restarts it
         * rather than stacking. Its own unique name keeps it separate from the
         * sync chain it cancels, so it never cancels itself.
         */
        fun enqueue(context: Context) {
            WorkManager
                .getInstance(context)
                .enqueueUniqueWork(
                    UNIQUE_NAME,
                    ExistingWorkPolicy.REPLACE,
                    buildRequest(),
                )
        }

        private fun buildRequest(): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<ReanalyzeWorker>().build()
    }
}
