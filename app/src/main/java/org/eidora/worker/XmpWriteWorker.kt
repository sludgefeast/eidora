// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.worker

import android.content.Context
import org.eidora.util.EidoraLog
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.eidora.data.db.DatabaseProvider
import org.eidora.util.XmpFaceRegion
import org.eidora.util.XmpHelper
import org.eidora.util.toFaceRegionCoords
import java.io.File

private const val TAG = "XmpWriteWorker"
private const val PARALLELISM = 4

/**
 * Writes pending XMP metadata for all photos flagged with pending_xmp_write = true.
 * Runs as a short-lived background job after any user interaction that changes
 * face/person metadata. Parallel writes, max [PARALLELISM] at a time.
 */
class XmpWriteWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!org.eidora.util.PermissionChecker.hasWorkerPermissions(applicationContext)) {
            EidoraLog.w(TAG, "Missing media/all-files permission – aborting XMP write")
            return Result.failure()
        }
        val db = DatabaseProvider.getInstance(applicationContext)
        val photoDao = db.photoDao()
        val faceDao = db.faceRegionDao()

        val pending =
            try {
                photoDao.getPendingXmpWrites()
            } catch (t: Throwable) {
                EidoraLog.e(TAG, "Failed to load pending XMP writes", t)
                return Result.retry()
            }

        if (pending.isEmpty()) return Result.success()
        EidoraLog.i(TAG, "Writing XMP for ${pending.size} photos")

        coroutineScope {
            pending.chunked(PARALLELISM).forEach { batch ->
                batch
                    .map { photo ->
                        async(Dispatchers.IO) {
                            try {
                                val file = File(photo.path)
                                if (!file.exists()) {
                                    photoDao.clearPendingXmpWrite(photo.id, photo.modifiedAt)
                                    return@async
                                }
                                val faces = faceDao.findByPhotoId(photo.id)
                                val regions =
                                    faces.map { face ->
                                        XmpFaceRegion(
                                            name = face.name,
                                            coords = face.regionJson.toFaceRegionCoords(),
                                        )
                                    }
                                XmpHelper.writeFaceRegions(file, regions)
                                val newModifiedAt = file.lastModified()
                                // Clear flag + update modifiedAt atomically so the
                                // next sync sees the correct timestamp and skips this photo
                                photoDao.clearPendingXmpWrite(photo.id, newModifiedAt)
                                // Notify MediaStore so other apps see the update
                                android.media.MediaScannerConnection.scanFile(
                                    applicationContext,
                                    arrayOf(file.absolutePath),
                                    arrayOf("image/jpeg"),
                                    null,
                                )
                                EidoraLog.d(TAG, "XMP written for ${file.name}")
                            } catch (t: Throwable) {
                                EidoraLog.e(TAG, "XMP write failed for ${photo.path}", t)
                                // Leave pending_xmp_write = true so next run retries
                            }
                        }
                    }.forEach { it.await() }
            }
        }

        return Result.success()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "eidora-xmp-write"

        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<XmpWriteWorker>()
                    .setConstraints(
                        Constraints
                            .Builder()
                            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                            .build(),
                    ).build(),
            )
        }
    }
}
