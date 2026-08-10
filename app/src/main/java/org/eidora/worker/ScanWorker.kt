// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.worker

import android.content.Context
import android.content.SharedPreferences
import android.provider.MediaStore
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.eidora.data.db.DatabaseProvider
import org.eidora.data.db.PhotoEntity
import org.eidora.data.db.PhotoStage
import org.eidora.data.settings.SettingsProvider
import org.eidora.data.settings.SettingsRepository
import org.eidora.util.FileUtil
import java.io.File
import java.util.UUID

/**
 * First pipeline stage: find JPEGs via MediaStore and make sure the database
 * has a row for each (stage = NEW for anything new or modified), plus a
 * periodic deletion check for photos that disappeared from disk. Does no XMP
 * reading and no ML — that's TriageWorker and DetectionWorker.
 *
 * Registering rows here is what lets the later workers pull their inputs purely
 * from the database (getByStage) instead of an in-memory hand-off.
 */
class ScanWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    private val db by lazy { DatabaseProvider.getInstance(applicationContext) }
    private val photoDao by lazy { db.photoDao() }
    private val analyzer by lazy { PhotoAnalyzer(applicationContext) }

    override suspend fun doWork(): Result {
        val folderWhitelist =
            try {
                SettingsProvider.get(applicationContext).getFolderWhitelist()
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to load folder whitelist, using defaults", t)
                SettingsRepository.DEFAULT_FOLDER_WHITELIST
            }

        val prefs = applicationContext.getSharedPreferences("sync_state", Context.MODE_PRIVATE)
        val nowSec = System.currentTimeMillis() / 1000
        val lastSyncSec = prefs.getLong("last_sync_timestamp_sec", 0L)
        val isForce = inputData.getBoolean(KEY_FORCE, false)

        // Incremental scan: only entries new/modified since the last sync.
        val changedEntries =
            try {
                collectJpegsFromMediaStore(
                    folderWhitelist,
                    sinceModifiedSec = if (isForce) 0L else lastSyncSec,
                )
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to query MediaStore for JPEGs", t)
                return Result.failure()
            }

        // Exclude photos with a pending XMP write – their mtime will change when
        // XmpWriteWorker runs, so don't treat them as "modified" yet.
        val pendingXmpPaths =
            try {
                photoDao.getPendingXmpWrites().map { it.path }.toSet()
            } catch (t: Throwable) {
                emptySet<String>()
            }

        // Register/refresh rows for changed entries. New or modified files land
        // at stage NEW so TriageWorker picks them up.
        var registered = 0
        for (entry in changedEntries) {
            if (isStopped) break
            val path = entry.file.absolutePath
            if (path in pendingXmpPaths) continue
            try {
                registerPhoto(entry)
                registered++
            } catch (t: Throwable) {
                t.rethrowIfCancellation()
                Log.e(TAG, "Failed to register ${entry.file.name}", t)
            }
        }
        Log.i(TAG, "Scan registered/refreshed $registered of ${changedEntries.size} entries")

        // Deletion check: periodically (or on force) remove DB rows whose files
        // are gone from disk.
        val lastDeletionCheck = prefs.getLong("last_deletion_check_sec", 0L)
        val deletionCheckIntervalSec = 24 * 3600L
        val isPeriodic = inputData.keyValueMap.isEmpty()
        if (isForce || isPeriodic || nowSec - lastDeletionCheck > deletionCheckIntervalSec) {
            if (!isStopped) runDeletionCheck(prefs, nowSec)
        }

        // Record the sync timestamp so the next run scans incrementally.
        prefs.edit().putLong("last_sync_timestamp_sec", nowSec).apply()
        return Result.success()
    }

    /** Register or refresh one photo row, resetting to NEW when new/modified. */
    private suspend fun registerPhoto(entry: WorkItemModified) {
        val path = entry.file.absolutePath
        val modifiedAt = entry.file.lastModified()
        val existing = photoDao.findByPath(path)
        if (existing != null && existing.modifiedAt == modifiedAt && existing.stage == PhotoStage.DONE) {
            return // unchanged and already done
        }
        val takenAt =
            try {
                FileUtil.readTakenAt(entry.file)
            } catch (t: Throwable) {
                null
            }
        if (existing == null) {
            photoDao.upsert(
                PhotoEntity(
                    id = UUID.randomUUID().toString(),
                    path = path,
                    folder = entry.folder,
                    modifiedAt = modifiedAt,
                    takenAt = takenAt,
                    stage = PhotoStage.NEW,
                ),
            )
        } else if (existing.modifiedAt != modifiedAt) {
            photoDao.update(existing.id, modifiedAt, takenAt, stage = PhotoStage.NEW)
            photoDao.updateFolder(existing.id, entry.folder)
            analyzer.deleteFaceRegionsForPhoto(existing.id)
        } else if (existing.stage == PhotoStage.DONE) {
            // unchanged, done – nothing to do
        } else {
            // exists but not done: leave its stage so the right worker resumes it
        }
    }

    private suspend fun runDeletionCheck(
        prefs: SharedPreferences,
        nowSec: Long,
    ) {
        Log.i(TAG, "Running deletion check")
        try {
            val allMediaPaths = mutableSetOf<String>()
            val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val proj = arrayOf(MediaStore.Images.Media.DATA)
            val sel = "${MediaStore.Images.Media.MIME_TYPE} = ?"
            applicationContext.contentResolver
                .query(uri, proj, sel, arrayOf("image/jpeg"), null)
                ?.use { cursor ->
                    val col = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                    while (cursor.moveToNext()) {
                        if (isStopped) break
                        cursor.getString(col)?.let { allMediaPaths.add(it) }
                    }
                }
            if (isStopped) return
            val dbPaths = photoDao.getAllPathsWithModified().map { it.path }.toSet()
            for (path in (dbPaths - allMediaPaths)) {
                if (isStopped) break
                try {
                    analyzer.deletePhoto(path)
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to delete photo $path", t)
                }
            }
            prefs.edit().putLong("last_deletion_check_sec", nowSec).apply()
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.e(TAG, "Deletion check failed", t)
        }
    }

    private fun collectJpegsFromMediaStore(
        folderWhitelist: Set<String>,
        sinceModifiedSec: Long = 0L,
    ): List<WorkItemModified> {
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection =
            arrayOf(
                MediaStore.Images.Media.DATA,
                MediaStore.Images.Media.RELATIVE_PATH,
                MediaStore.Images.Media.DATE_MODIFIED,
            )
        val selection =
            if (sinceModifiedSec > 0L) {
                "${MediaStore.Images.Media.MIME_TYPE} = ? AND " +
                    "${MediaStore.Images.Media.DATE_MODIFIED} > ?"
            } else {
                "${MediaStore.Images.Media.MIME_TYPE} = ?"
            }
        val selectionArgs =
            if (sinceModifiedSec > 0L) {
                arrayOf("image/jpeg", sinceModifiedSec.toString())
            } else {
                arrayOf("image/jpeg")
            }

        val result = mutableListOf<WorkItemModified>()
        applicationContext.contentResolver
            .query(
                uri,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Images.Media.DATE_MODIFIED} DESC",
            )?.use { cursor ->
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                val relPathCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
                val modCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                while (cursor.moveToNext()) {
                    val relPath = cursor.getString(relPathCol)?.trimEnd('/') ?: ""
                    if (folderWhitelist.isNotEmpty() &&
                        !folderWhitelist.any { relPath == it || relPath.startsWith("$it/") }
                    ) {
                        continue
                    }
                    val path = cursor.getString(dataCol) ?: continue
                    val file = File(path)
                    if (file.isFile) result.add(WorkItemModified(file, relPath, cursor.getLong(modCol)))
                }
            }
        return result
    }

    /** A scanned media file with its MediaStore modification time (seconds). */
    private data class WorkItemModified(
        val file: File,
        val folder: String,
        val modifiedSec: Long,
    )

    companion object {
        private const val TAG = "ScanWorker"
        const val KEY_FORCE = "force"

        fun buildRequest(): OneTimeWorkRequest = OneTimeWorkRequestBuilder<ScanWorker>().build()

        fun buildForceRequest(): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<ScanWorker>()
                .setInputData(workDataOf(KEY_FORCE to true))
                .build()
    }
}
