// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.worker

import android.content.Context
import org.eidora.util.EidoraLog
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.eidora.data.db.DatabaseProvider
import org.eidora.data.db.PhotoStage
import java.io.File

/**
 * Re-processes a single photo on demand (e.g. after the user edits names in
 * one photo). Forces a fresh triage + detection for just that photo, reusing
 * the shared [PhotoAnalyzer]. Kept separate from the batch pipeline so a
 * one-photo action doesn't spin up scan/triage/detection over the whole library.
 */
class SinglePhotoWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    private val db by lazy { DatabaseProvider.getInstance(applicationContext) }
    private val photoDao by lazy { db.photoDao() }
    private val personDao by lazy { db.personDao() }
    private val analyzer by lazy { PhotoAnalyzer(applicationContext) }

    override suspend fun doWork(): Result {
        val photoId = inputData.getString(KEY_PHOTO_ID) ?: return Result.success()
        val photo =
            try {
                photoDao.findById(photoId)
            } catch (t: Throwable) {
                EidoraLog.e(TAG, "Failed to find photo $photoId", t)
                return Result.failure()
            } ?: return Result.success()

        val file = File(photo.path)
        if (!file.exists()) {
            try {
                analyzer.deleteFaceRegionsForPhoto(photoId)
                photoDao.deleteById(photoId)
                personDao.deleteOrphaned()
            } catch (t: Throwable) {
                EidoraLog.e(TAG, "Failed to delete missing photo", t)
            }
            return Result.success()
        }

        try {
            // Force reprocessing: clear regions and reset to NEW so triage
            // re-imports/re-detects instead of short-circuiting on a DONE row.
            analyzer.deleteFaceRegionsForPhoto(photoId)
            photoDao.update(photoId, photo.modifiedAt, photo.takenAt, stage = PhotoStage.NEW)
            val stage = analyzer.triage(file, photo.folder)
            if (stage == PhotoStage.NEEDS_DETECTION) {
                analyzer.detect(file, photo.folder)
            }
        } catch (t: Throwable) {
            t.rethrowIfCancellation()
            EidoraLog.e(TAG, "Failed to re-sync ${file.name}", t)
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "SinglePhotoWorker"
        const val KEY_PHOTO_ID = "photo_id"

        fun buildRequest(photoId: String): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<SinglePhotoWorker>()
                .setInputData(workDataOf(KEY_PHOTO_ID to photoId))
                .build()
    }
}
