// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.eidora.data.db.DatabaseProvider
import org.eidora.data.db.FaceRegionEntity
import org.eidora.data.db.PersonEntity
import org.eidora.R
import org.eidora.data.db.PhotoEntity
import org.eidora.data.settings.PowerConfig
import org.eidora.data.settings.SettingsRepository
import org.eidora.domain.model.FaceRegionCoords
import org.eidora.ml.FaceDetector
import org.eidora.util.*
import java.io.File
import java.util.UUID

private const val TAG = "PhotoSyncWorker"
private const val SYNC_PARALLELISM = 3

// Notification refresh rate while processing. Fast enough to feel live.
private const val ACTIVE_NOTIFIER_INTERVAL_MS = 500L

// Refresh rate while blocked by the PowerGate. Nothing changes during a pause
// apart from the reason text, so polling fast would burn battery and keep the
// CPU from idling - which delays the very cool-down we are waiting for.
private const val IDLE_NOTIFIER_INTERVAL_MS = 10_000L

class PhotoSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    private val db by lazy { DatabaseProvider.getInstance(applicationContext) }
    private val photoDao by lazy { db.photoDao() }
    private val personDao by lazy { db.personDao() }
    private val faceDao by lazy { db.faceRegionDao() }

    // Set once at the start of a sync run, based on the chosen detection model.
    private var detector: FaceDetector? = null

    private suspend fun ensureDetector(): FaceDetector? {
        detector?.let { return it }
        val d = org.eidora.ml.container.SelectedModelResolver.openDetector(applicationContext)
        if (d == null) {
            Log.e(TAG, "Failed to initialize detector from selected container")
            return null
        }
        detector = d
        Log.i(TAG, "Detector initialized on backend: ${d.backend}")
        return d
    }

    override suspend fun doWork(): Result {
        if (!org.eidora.util.PermissionChecker.hasWorkerPermissions(applicationContext)) {
            Log.w(TAG, "Missing media/all-files permission – aborting sync")
            return Result.failure()
        }
        // Don't run until the user has finished setup: folders chosen AND the
        // free model container present. This also guards the daily periodic
        // worker, which is scheduled independently of the UI setup gates.
        val setupComplete =
            try {
                org.eidora.data.settings.SettingsProvider
                    .get(applicationContext)
                    .getFolderWizardDone() &&
                    org.eidora.ml.container.ContainerDownloader
                        .isFreeContainerReady(applicationContext)
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "Setup-state check failed – deferring sync", t)
                false
            }
        if (!setupComplete) {
            Log.i(TAG, "Setup not complete – skipping sync")
            return Result.success()
        }
        val singlePhotoId = inputData.getString(KEY_PHOTO_ID)
        return try {
            if (singlePhotoId != null) {
                doSinglePhotoSync(singlePhotoId)
            } else {
                doFullSync()
            }
        } catch (t: Throwable) {
            t.rethrowIfCancellation()
            Log.e(TAG, "Unhandled error in doWork", t)
            Result.failure()
        } finally {
            try {
                detector?.close()
            } catch (t: Throwable) {
                Log.w(TAG, "Error closing detector", t)
            }
            try {
                androidx.core.app.NotificationManagerCompat
                    .from(applicationContext)
                    .cancel(NotificationHelper.NOTIFICATION_ID_SYNC)
            } catch (t: Throwable) {
                // ignore
            }
        }
    }

    // -----------------------------------------------------------------------
    // Full sync
    // -----------------------------------------------------------------------

    private suspend fun doFullSync(): Result {
        try {
            setForeground(
                NotificationHelper.syncForegroundInfo(
                    applicationContext,
                    0,
                    applicationContext.getString(R.string.notif_scanning_start),
                ),
            )
        } catch (
            t: Throwable,
        ) {
            android.util.Log.w("FACES", "setForeground failed", t)
        }

        // ---- Single notification writer -----------------------------------
        // All phases (media scan, analysis) report via these shared fields;
        // ONLY the notifier loop below calls setForeground. Multiple writers
        // (previously: async scan callbacks racing the notifier) made the
        // notification flip between "scanning" and analysis messages.
        val doneCount =
            java.util.concurrent.atomic
                .AtomicInteger(0)
        val totalCount =
            java.util.concurrent.atomic
                .AtomicInteger(0)
        val currentFile =
            java.util.concurrent.atomic
                .AtomicReference<String>(applicationContext.getString(R.string.notif_scanning_start))
        val gateBlocked =
            java.util.concurrent.atomic
                .AtomicBoolean(false)
        val startedAnalysisAt =
            java.util.concurrent.atomic
                .AtomicLong(0L)
        // Milliseconds spent blocked by the PowerGate during the analysis
        // phase. Subtracted from the elapsed time so the ETA reflects actual
        // processing speed rather than wall-clock time including pauses.
        val pausedMs =
            java.util.concurrent.atomic
                .AtomicLong(0L)

        val notifierScope =
            kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob(),
            )
        notifierScope.launch {
            var lastTick = System.currentTimeMillis()
            // Remember what was last posted: while nothing changes there is
            // no reason to hand another notification to the system server.
            var lastPosted: Pair<Int, String>? = null
            while (isActive) {
                val now = System.currentTimeMillis()
                val total = totalCount.get()
                val current = doneCount.get()
                val progress = if (total == 0) 0 else (current * 100) / total
                val file = currentFile.get()
                val blocked = gateBlocked.get()
                val startedAt = startedAnalysisAt.get()
                // While the gate blocks processing, add the elapsed tick to
                // the paused accumulator instead of letting it inflate the
                // per-item average.
                if (blocked && startedAt != 0L) {
                    pausedMs.addAndGet(now - lastTick)
                }
                lastTick = now
                val eta =
                    if (blocked || total == 0 || startedAt == 0L) {
                        ""
                    } else {
                        formatEta(applicationContext, startedAt, current, total, pausedMs.get())
                    }
                val message = if (eta.isNotEmpty()) "$file – $eta" else file
                val posted = progress to message
                if (posted != lastPosted) {
                    try {
                        setForeground(
                            NotificationHelper.syncForegroundInfo(
                                applicationContext,
                                progress,
                                message,
                                gateBlocked = blocked,
                            ),
                        )
                        lastPosted = posted
                    } catch (t: Throwable) {
                        // ignore
                    }
                }
                // Poll slowly while the gate blocks us: nothing is being
                // processed, so a fast tick would only keep the CPU awake
                // and heat the device we are waiting to cool down.
                kotlinx.coroutines.delay(if (blocked) IDLE_NOTIFIER_INTERVAL_MS else ACTIVE_NOTIFIER_INTERVAL_MS)
            }
        }

        try {
            val folderWhitelist =
                try {
                    org.eidora.data.settings.SettingsProvider
                        .get(applicationContext)
                        .getFolderWhitelist()
                } catch (c: kotlinx.coroutines.CancellationException) {
                    throw c // never swallow cancellation – let the coroutine stop
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to load folder whitelist, using defaults", t)
                    SettingsRepository.DEFAULT_FOLDER_WHITELIST
                }
            val mediaEntries =
                try {
                    collectJpegsFromMediaStore(folderWhitelist) { count ->
                        currentFile.set(applicationContext.getString(R.string.notif_scanning, count))
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to query MediaStore for JPEGs", t)
                    return Result.failure()
                }

            val prefs = applicationContext.getSharedPreferences("sync_state", android.content.Context.MODE_PRIVATE)
            val nowSec = System.currentTimeMillis() / 1000
            val lastSyncSec = prefs.getLong("last_sync_timestamp_sec", 0L)
            val isForce = inputData.getBoolean(KEY_FORCE, false)

            // -----------------------------------------------------------------------
            // Step 1: Incremental scan – only new/modified entries since last sync.
            // Fast: MediaStore returns a tiny result set on normal app starts.
            // -----------------------------------------------------------------------
            val changedEntries =
                try {
                    collectJpegsFromMediaStore(
                        folderWhitelist,
                        sinceModifiedSec = if (isForce) 0L else lastSyncSec,
                    ) { count ->
                        currentFile.set(applicationContext.getString(R.string.notif_scanning, count))
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to query MediaStore for JPEGs", t)
                    return Result.failure()
                }

            // Also include not-yet-analyzed photos from a previous interrupted run.
            val unanalyzed =
                try {
                    photoDao
                        .getAllPathsWithModified()
                        .filter { !it.analyzed }
                        .map { MediaEntry(java.io.File(it.path), it.modifiedAt / 1000, it.folder) }
                } catch (t: Throwable) {
                    emptyList()
                }

            // Exclude photos with a pending XMP write – their mtime will change
            // when XmpWriteWorker runs, so don't treat them as "modified" yet.
            val pendingXmpPaths =
                try {
                    photoDao.getPendingXmpWrites().map { it.path }.toSet()
                } catch (t: Throwable) {
                    emptySet<String>()
                }

            val workEntries =
                (changedEntries + unanalyzed)
                    .distinctBy { it.file.absolutePath }
                    .filter { it.file.absolutePath !in pendingXmpPaths }

            Log.i(
                TAG,
                "Sync work set: ${workEntries.size} entries " +
                    "(${changedEntries.size} changed since ${lastSyncSec}s, ${unanalyzed.size} unanalyzed)",
            )

            // Step 2: Deletion check – only run periodically (every ~24h) or on force.
            val lastDeletionCheck = prefs.getLong("last_deletion_check_sec", 0L)
            val deletionCheckIntervalSec = 24 * 3600L
            val isPeriodic = inputData.keyValueMap.isEmpty()
            if (isForce || isPeriodic || nowSec - lastDeletionCheck > deletionCheckIntervalSec) {
                if (isStopped) return Result.success()
                runDeletionCheck(prefs, nowSec)
            }

            setProgress(workDataOf(KEY_STATUS to applicationContext.getString(R.string.notif_scanning_start)))
            val jpegFiles = workEntries

            // Analysis phase begins: publish totals so the notifier switches from
            // scan messages to per-file progress with ETA.
            totalCount.set(jpegFiles.size)
            startedAnalysisAt.set(System.currentTimeMillis())
            val total = jpegFiles.size
            Log.i(TAG, "Starting face detection for $total photos")

            val powerGate = PowerGate(applicationContext)
            val powerConfig =
                try {
                    org.eidora.data.settings.SettingsProvider
                        .get(applicationContext)
                        .getPowerConfig()
                } catch (t: Throwable) {
                    PowerConfig(
                        minBatteryPercent = SettingsRepository.DEFAULT_MIN_BATTERY_PERCENT,
                        maxBatteryTempCelsius = SettingsRepository.DEFAULT_MAX_BATTERY_TEMP,
                        resumeBatteryPercent = SettingsRepository.DEFAULT_RESUME_BATTERY_PERCENT,
                        resumeBatteryTempCelsius = SettingsRepository.DEFAULT_RESUME_BATTERY_TEMP,
                    )
                }

            @OptIn(ExperimentalCoroutinesApi::class)
            flow { jpegFiles.forEach { emit(it) } }
                .flatMapMerge(concurrency = SYNC_PARALLELISM) { entry ->
                    flow {
                        powerGate.awaitOk(powerConfig) { reason ->
                            gateBlocked.set(true)
                            currentFile.set(reason)
                        }
                        gateBlocked.set(false)
                        currentFile.set(entry.file.name)
                        try {
                            processFile(entry.file, entry.folder)
                        } catch (t: Throwable) {
                            Log.e(TAG, "Failed to process file ${entry.file.name}, skipping", t)
                        }
                        emit(entry)
                    }
                }.collect { _ ->
                    val current = doneCount.incrementAndGet()
                    if (current % 500 == 0) {
                        Log.i(TAG, "Detection progress: $current / $total")
                    }
                    setProgress(workDataOf(KEY_PROGRESS to (current * 100) / total))
                }
            Log.i(TAG, "Face detection finished: ${doneCount.get()} / $total")

            try {
                personDao.deleteOrphaned()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to delete orphaned persons", t)
            }

            // Remove thumbnail files left behind by cascade deletes or interrupted
            // runs. Cheap: one query plus a directory listing.
            try {
                val validIds = faceDao.allIds().toSet()
                val removed = org.eidora.util.ThumbnailHelper.sweepOrphans(applicationContext, validIds)
                if (removed > 0) Log.i(TAG, "Swept $removed orphan thumbnail(s)")
            } catch (t: Throwable) {
                t.rethrowIfCancellation()
                Log.w(TAG, "Thumbnail sweep failed", t)
            }

            // Persist sync timestamp so next run only fetches newer entries
            prefs.edit().putLong("last_sync_timestamp_sec", nowSec).apply()

            // Remove old generation-based fast path key if present
            prefs.edit().remove("media_generation").apply()

            setProgress(
                workDataOf(
                    KEY_PROGRESS to 100,
                    KEY_STATUS to applicationContext.getString(R.string.notif_done),
                ),
            )
            return Result.success()
        } finally {
            // MUST run even on cancellation: a leaked notifier loop from a
            // previous (stopped) run would fight the new run's notifier over
            // the same notification, making the message jump back and forth.
            notifierScope.cancel()
        }
    }

    // -----------------------------------------------------------------------
    // Single photo re-sync
    // -----------------------------------------------------------------------

    private suspend fun doSinglePhotoSync(photoId: String): Result {
        val photo =
            try {
                photoDao.findById(photoId)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to find photo $photoId", t)
                return Result.failure()
            } ?: return Result.success()

        val file = File(photo.path)
        if (!file.exists()) {
            try {
                deleteFaceRegionsForPhoto(photoId)
                photoDao.deleteById(photoId)
                personDao.deleteOrphaned()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to delete missing photo", t)
            }
            return Result.success()
        }

        try {
            importXmpAndAnalyze(file, photoId)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to import/analyze ${file.name}", t)
        }
        try {
            personDao.deleteOrphaned()
        } catch (t: Throwable) {
            Log.e(TAG, "deleteOrphaned failed", t)
        }
        return Result.success()
    }

    // -----------------------------------------------------------------------
    // Per-file processing
    // -----------------------------------------------------------------------

    /**
     * Queries the MediaStore for JPEG images and returns them as File objects.
     * Much faster than walking the file system for large photo collections.
     */
    data class MediaEntry(
        val file: File,
        val modifiedSec: Long,
        /** MediaStore RELATIVE_PATH without trailing slash, e.g. "DCIM/Camera". */
        val folder: String,
    )

    /**
     * Removes DB photos whose files no longer exist in the MediaStore.
     * Uses a lightweight DATA-only query (no full metadata scan).
     * Respects cancellation via isStopped.
     */
    private suspend fun runDeletionCheck(
        prefs: android.content.SharedPreferences,
        nowSec: Long,
    ) {
        Log.i(TAG, "Running deletion check")
        try {
            val allMediaPaths = mutableSetOf<String>()
            val uri = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val proj = arrayOf(android.provider.MediaStore.Images.Media.DATA)
            val sel = "${android.provider.MediaStore.Images.Media.MIME_TYPE} = ?"
            applicationContext.contentResolver
                .query(uri, proj, sel, arrayOf("image/jpeg"), null)
                ?.use { cursor ->
                    val col = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DATA)
                    while (cursor.moveToNext()) {
                        if (isStopped) break
                        cursor.getString(col)?.let { allMediaPaths.add(it) }
                    }
                }
            if (isStopped) return
            val dbPaths = photoDao.getAllPathsWithModified().map { it.path }.toSet()
            (dbPaths - allMediaPaths).forEach { path ->
                if (isStopped) return@forEach
                try {
                    deletePhoto(path)
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
        onProgress: (Int) -> Unit,
    ): List<MediaEntry> {
        val uri = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection =
            arrayOf(
                android.provider.MediaStore.Images.Media.DATA,
                android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                android.provider.MediaStore.Images.Media.DATE_MODIFIED,
            )
        val selection =
            if (sinceModifiedSec > 0L) {
                "${android.provider.MediaStore.Images.Media.MIME_TYPE} = ? AND " +
                    "${android.provider.MediaStore.Images.Media.DATE_MODIFIED} > ?"
            } else {
                "${android.provider.MediaStore.Images.Media.MIME_TYPE} = ?"
            }
        val selectionArgs =
            if (sinceModifiedSec > 0L) {
                arrayOf("image/jpeg", sinceModifiedSec.toString())
            } else {
                arrayOf("image/jpeg")
            }

        val result = mutableListOf<MediaEntry>()
        applicationContext.contentResolver
            .query(
                uri,
                projection,
                selection,
                selectionArgs,
                "${android.provider.MediaStore.Images.Media.DATE_MODIFIED} DESC",
            )?.use { cursor ->
                val dataCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DATA)
                val relPathCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.RELATIVE_PATH)
                val modCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DATE_MODIFIED)
                var scanned = 0
                while (cursor.moveToNext()) {
                    scanned++
                    if (scanned % 500 == 0) onProgress(scanned)
                    val relPath = cursor.getString(relPathCol)?.trimEnd('/') ?: ""
                    if (folderWhitelist.isNotEmpty() &&
                        !folderWhitelist.any { relPath == it || relPath.startsWith("$it/") }
                    ) {
                        continue
                    }
                    val path = cursor.getString(dataCol) ?: continue
                    val file = File(path)
                    if (file.isFile) result.add(MediaEntry(file, cursor.getLong(modCol), relPath))
                }
            }
        return result
    }

    private suspend fun processFile(
        file: File,
        folder: String,
    ) {
        val path = file.absolutePath
        val modifiedAt = file.lastModified()
        val takenAt =
            try {
                FileUtil.readTakenAt(file)
            } catch (t: Throwable) {
                Log.w(TAG, "Could not read takenAt for ${file.name}")
                null
            }

        val existing = photoDao.findByPath(path)
        when {
            existing == null -> {
                val photoId = UUID.randomUUID().toString()
                photoDao.upsert(
                    PhotoEntity(
                        id = photoId,
                        path = path,
                        folder = folder,
                        modifiedAt = modifiedAt,
                        takenAt = takenAt,
                        analyzed = false,
                    ),
                )
                importXmpAndAnalyze(file, photoId)
            }
            existing.modifiedAt != modifiedAt -> {
                photoDao.update(existing.id, modifiedAt, takenAt, analyzed = false)
                photoDao.updateFolder(existing.id, folder)
                deleteFaceRegionsForPhoto(existing.id)
                importXmpAndAnalyze(file, existing.id)
            }
            !existing.analyzed -> {
                // Recovery: a previous run was interrupted after registering
                // the photo but before finishing analysis. Clear any partial
                // face regions and re-run the import/detection from scratch.
                deleteFaceRegionsForPhoto(existing.id)
                importXmpAndAnalyze(file, existing.id)
            }
        }
    }

    private suspend fun importXmpAndAnalyze(
        file: File,
        photoId: String,
    ) {
        val xmpRegions =
            try {
                XmpHelper.readFaceRegions(file)
            } catch (t: Throwable) {
                Log.e(TAG, "XMP read failed for ${file.name}", t)
                emptyList()
            }

        if (xmpRegions.isNotEmpty()) {
            xmpRegions.forEach { xmpRegion ->
                try {
                    val faceId = UUID.randomUUID().toString()
                    val person = xmpRegion.name?.let { name -> findOrCreatePerson(name) }
                    // No SCRFD data available → derive quality from bbox size only.
                    // Sharpness will be refined in EmbeddingWorker when the crop
                    // bitmap is available. rotationRad = null → frontalScore defaults to 0.5.
                    val qualityScore =
                        org.eidora.util.FaceQuality.computeFast(
                            xmpRegion.coords,
                            rotationRad = null,
                        )
                    faceDao.insert(
                        FaceRegionEntity(
                            id = faceId,
                            photoId = photoId,
                            personId = person?.id,
                            name = xmpRegion.name,
                            regionJson = xmpRegion.coords.toJson(),
                            ignored = false,
                            qualityScore = qualityScore,
                        ),
                    )
                    ThumbnailHelper.createThumbnail(applicationContext, file, xmpRegion.coords, faceId)
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to import XMP region", t)
                }
            }
            photoDao.updateAnalyzed(photoId, true)
            try {
                refreshPersonTags(file, photoId)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to refresh person tags for ${file.name}", t)
            }
        } else {
            runFaceDetection(file, photoId)
        }
    }

    private suspend fun runFaceDetection(
        file: File,
        photoId: String,
    ) {
        val det = ensureDetector()
        if (det == null) {
            // Models not downloaded yet (or init failed). Do NOT mark the photo
            // as analyzed – it will be picked up again once models are present.
            Log.w(TAG, "Detector not available, deferring ${file.name}")
            return
        }

        val bitmap =
            try {
                org.eidora.util.BitmapLoader
                    .loadOrientedBitmap(file, maxSize = 2048)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load bitmap for ${file.name}", t)
                photoDao.updateAnalyzed(photoId, true)
                return
            }
        if (bitmap == null) {
            Log.w(TAG, "Bitmap decode returned null for ${file.name}")
            photoDao.updateAnalyzed(photoId, true)
            return
        }

        val faces =
            try {
                det.detect(bitmap)
            } catch (t: Throwable) {
                Log.e(TAG, "SCRFD detection failed for ${file.name}", t)
                photoDao.updateAnalyzed(photoId, true)
                bitmap.recycle()
                return
            } finally {
                // We keep the bitmap alive until after detect() returns; recycle here.
                // detect() already recycles its internally-resized copy.
            }
        val srcW = bitmap.width.toFloat()
        val srcH = bitmap.height.toFloat()
        bitmap.recycle()

        if (faces.isNotEmpty()) {
            val xmpRegions = mutableListOf<XmpFaceRegion>()
            faces.forEach { face ->
                try {
                    // DetectedFace coords are in source-image PIXELS; FaceRegionCoords
                    // are NORMALIZED (0..1) — divide by the image size, or the
                    // regions render far outside the photo and crops fail.
                    val coords =
                        FaceRegionCoords(
                            x = (face.xMin + face.width / 2f) / srcW,
                            y = (face.yMin + face.height / 2f) / srcH,
                            w = face.width / srcW,
                            h = face.height / srcH,
                        )
                    val faceId = UUID.randomUUID().toString()
                    // Compute quality score from bbox size and rotation angle.
                    // Sharpness requires loading the crop which is expensive here
                    // and will be refined later (EmbeddingWorker has the bitmap).
                    val qualityScore =
                        org.eidora.util.FaceQuality.computeFast(
                            coords,
                            face.rotationRadians,
                        )
                    faceDao.insert(
                        FaceRegionEntity(
                            id = faceId,
                            photoId = photoId,
                            personId = null,
                            name = null,
                            regionJson = coords.toJson(),
                            ignored = false,
                            qualityScore = qualityScore,
                        ),
                    )
                    ThumbnailHelper.createThumbnail(applicationContext, file, coords, faceId)
                    xmpRegions.add(XmpFaceRegion(name = null, coords = coords))
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to process face in ${file.name}", t)
                }
            }

            if (xmpRegions.isNotEmpty()) {
                try {
                    // If the photo has no capture date and the user wants missing
                    // dates filled (Aves-style), anchor its chronological order by
                    // writing the file's modification time as DateTimeOriginal in
                    // the same save. Only touches files that lack a date.
                    val photo = photoDao.findById(photoId)
                    val fillMissingDate =
                        org.eidora.data.settings.SettingsProvider
                            .get(applicationContext)
                            .getFillMissingDate()
                    val fillDate =
                        if (photo?.takenAt == null && fillMissingDate) {
                            file.lastModified()
                        } else {
                            null
                        }
                    XmpHelper.writeFaceRegions(file, xmpRegions, fillDate)
                    // If we wrote a date, reflect it in the DB so the photo sorts
                    // correctly without waiting for a re-scan.
                    if (fillDate != null) {
                        val written = FileUtil.readTakenAt(file)
                        if (written != null) photoDao.updateTakenAt(photoId, written)
                    }
                    photoDao.updateModifiedAt(photoId, file.lastModified())
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to write XMP regions to ${file.name}", t)
                }
            }
        }

        photoDao.updateAnalyzed(photoId, true)
    }

    private suspend fun findOrCreatePerson(name: String): PersonEntity =
        personDao.findByName(name) ?: PersonEntity(
            id = UUID.randomUUID().toString(),
            name = name,
        ).also { personDao.insert(it) }

    private suspend fun refreshPersonTags(
        file: File,
        photoId: String,
    ) {
        val faces = faceDao.findByPhotoId(photoId)
        val xmpRegions = faces.map { XmpFaceRegion(name = it.name, coords = it.regionJson.toFaceRegionCoords()) }
        XmpHelper.writeFaceRegions(file, xmpRegions)
        photoDao.updateModifiedAt(photoId, file.lastModified())
    }

    private suspend fun deletePhoto(path: String) {
        val photo = photoDao.findByPath(path) ?: return
        deleteFaceRegionsForPhoto(photo.id)
        photoDao.deleteByPath(path)
    }

    private suspend fun deleteFaceRegionsForPhoto(photoId: String) {
        faceDao.findByPhotoId(photoId).forEach {
            try {
                ThumbnailHelper.deleteThumbnail(applicationContext, it.id)
            } catch (t: Throwable) {
                // ignore
            }
        }
        faceDao.deleteByPhotoId(photoId)
    }

    companion object {
        const val KEY_PROGRESS = "progress"
        const val KEY_STATUS = "status"
        const val KEY_PHOTO_ID = "photo_id"
        const val KEY_FORCE = "force"

        fun buildRequest(): OneTimeWorkRequest = OneTimeWorkRequestBuilder<PhotoSyncWorker>().build()

        fun buildForceRequest(): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<PhotoSyncWorker>()
                .setInputData(workDataOf(KEY_FORCE to true))
                .build()

        /**
         * Estimates the remaining time from the average processing time per
         * item so far. Returns an empty string when there is not enough data
         * yet (fewer than 5 items done).
         *
         * [pausedMs] is the time spent blocked by the PowerGate (low battery or
         * high temperature). It is subtracted from the elapsed wall-clock time,
         * because no items are processed while blocked — counting it would
         * inflate the per-item average and keep the ETA high even though
         * progress was made before the pause.
         */
        internal fun formatEta(
            context: android.content.Context,
            startedAt: Long,
            done: Int,
            total: Int,
            pausedMs: Long = 0L,
        ): String {
            val remainingMs =
                remainingMillis(
                    elapsedMs = System.currentTimeMillis() - startedAt,
                    pausedMs = pausedMs,
                    done = done,
                    total = total,
                ) ?: return ""
            return context.getString(R.string.notif_eta_left, formatDuration(remainingMs))
        }

        /**
         * Remaining milliseconds based on the average time per processed item,
         * or null when no meaningful estimate is possible yet.
         *
         * [pausedMs] (time blocked by the PowerGate) is subtracted from
         * [elapsedMs]: no items are processed while blocked, so including it
         * would inflate the per-item average and keep the estimate high even
         * after substantial progress.
         */
        internal fun remainingMillis(
            elapsedMs: Long,
            pausedMs: Long,
            done: Int,
            total: Int,
        ): Long? {
            if (done < 5 || done >= total) return null
            val working = elapsedMs - pausedMs
            if (working <= 0) return null
            val perItem = working.toDouble() / done
            return ((total - done) * perItem).toLong()
        }

        private fun formatDuration(ms: Long): String {
            val totalSec = ms / 1000
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            return when {
                h > 0 -> "%dh %dm".format(h, m)
                m > 0 -> "%dm %ds".format(m, s)
                else -> "%ds".format(s)
            }
        }
    }
}
