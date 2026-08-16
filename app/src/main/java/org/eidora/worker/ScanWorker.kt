// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.worker

import android.content.Context
import android.content.SharedPreferences
import android.provider.MediaStore
import org.eidora.util.EidoraLog
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
        EidoraLog.i(TAG, "ScanWorker started (force=${inputData.getBoolean(KEY_FORCE, false)})")
        val folderWhitelist =
            try {
                SettingsProvider.get(applicationContext).getFolderWhitelist()
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                EidoraLog.w(TAG, "Failed to load folder whitelist, using defaults", t)
                SettingsRepository.DEFAULT_FOLDER_WHITELIST
            }

        val prefs = applicationContext.getSharedPreferences("sync_state", Context.MODE_PRIVATE)
        val nowSec = System.currentTimeMillis() / 1000
        val lastSyncSec = prefs.getLong("last_sync_timestamp_sec", 0L)
        val isForce = inputData.getBoolean(KEY_FORCE, false)

        // If the database has no photos, the incremental timestamp is stale
        // (e.g. a destructive schema migration wiped the rows but the pref
        // survived). Fall back to a full scan so we don't skip everything.
        val dbPhotoCount =
            try {
                photoDao.countByStage(PhotoStage.NEW) +
                    photoDao.countByStage(PhotoStage.NEEDS_DETECTION) +
                    photoDao.countByStage(PhotoStage.DONE)
            } catch (t: Throwable) {
                0
            }
        val fullScan = isForce || dbPhotoCount == 0
        if (fullScan) {
            EidoraLog.i(TAG, "Full scan (force=$isForce, dbPhotos=$dbPhotoCount)")
        }

        // Incremental scan: only entries new/modified since the last sync.
        val changedEntries =
            try {
                collectJpegsFromMediaStore(
                    folderWhitelist,
                    sinceModifiedSec = if (fullScan) 0L else lastSyncSec,
                )
            } catch (t: Throwable) {
                EidoraLog.e(TAG, "Failed to query MediaStore for JPEGs", t)
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
        // Load every known photo once (one query) and match in memory, instead
        // of a findByPath round-trip per file. With thousands of photos the
        // per-file queries dominated the scan (≈55s for ~5600); a single bulk
        // read plus a hash-map lookup turns that into milliseconds of DB work.
        val existingByPath =
            try {
                photoDao.getAllPathsWithModified().associateBy { it.path }
            } catch (t: Throwable) {
                EidoraLog.e(TAG, "Failed to bulk-load existing photos", t)
                emptyMap()
            }

        var registered = 0
        for (entry in changedEntries) {
            if (isStopped) break
            val path = entry.file.absolutePath
            if (path in pendingXmpPaths) continue
            try {
                registerPhoto(entry, existingByPath[path])
                registered++
            } catch (t: Throwable) {
                t.rethrowIfCancellation()
                EidoraLog.e(TAG, "Failed to register ${entry.file.name}", t)
            }
        }
        EidoraLog.i(TAG, "Scan registered/refreshed $registered of ${changedEntries.size} entries")

        // Deletion check: periodically (or on force) remove DB rows whose files
        // are gone from disk.
        val lastDeletionCheck = prefs.getLong("last_deletion_check_sec", 0L)
        val deletionCheckIntervalSec = 24 * 3600L
        val isPeriodic = inputData.keyValueMap.isEmpty()
        if (isForce || isPeriodic || nowSec - lastDeletionCheck > deletionCheckIntervalSec) {
            if (!isStopped) runDeletionCheck(prefs, nowSec, existingByPath.keys)
        }

        // Record the sync timestamp so the next run scans incrementally.
        prefs.edit().putLong("last_sync_timestamp_sec", nowSec).apply()
        return Result.success()
    }

    /** Register or refresh one photo row, resetting to NEW when new/modified. */
    /** Register or refresh one photo row, resetting to NEW when new/modified.
     *  [existing] is the pre-loaded DB entry for this path (null if unknown),
     *  so no per-photo findByPath query is needed. */
    private suspend fun registerPhoto(
        entry: WorkItemModified,
        existing: org.eidora.data.db.PathModified?,
    ) {
        val path = entry.file.absolutePath
        val modifiedAt = entry.file.lastModified()
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
        }
        // else: exists, unchanged, not-yet-done → leave its stage so the right
        // worker resumes it.
    }

    private suspend fun runDeletionCheck(
        prefs: SharedPreferences,
        nowSec: Long,
        dbPaths: Set<String>,
    ) {
        EidoraLog.i(TAG, "Running deletion check")
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
            // dbPaths is the set already loaded by the scan — no second query.
            for (path in (dbPaths - allMediaPaths)) {
                if (isStopped) break
                try {
                    analyzer.deletePhoto(path)
                } catch (t: Throwable) {
                    EidoraLog.e(TAG, "Failed to delete photo $path", t)
                }
            }
            prefs.edit().putLong("last_deletion_check_sec", nowSec).apply()
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            EidoraLog.e(TAG, "Deletion check failed", t)
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
        var totalRows = 0
        var filteredOut = 0
        val sampleRelPaths = mutableListOf<String>()
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
                    totalRows++
                    val relPath = cursor.getString(relPathCol)?.trimEnd('/') ?: ""
                    if (sampleRelPaths.size < 10 && relPath !in sampleRelPaths) {
                        sampleRelPaths.add(relPath)
                    }
                    if (folderWhitelist.isNotEmpty() &&
                        !folderWhitelist.any { relPath == it || relPath.startsWith("$it/") }
                    ) {
                        filteredOut++
                        continue
                    }
                    val path = cursor.getString(dataCol) ?: continue
                    val file = File(path)
                    if (file.isFile) result.add(WorkItemModified(file, relPath, cursor.getLong(modCol)))
                }
            }
        EidoraLog.i(
            TAG,
            "MediaStore: $totalRows JPEG rows, $filteredOut filtered by whitelist " +
                "$folderWhitelist, ${result.size} kept. Sample folders: $sampleRelPaths",
        )
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
