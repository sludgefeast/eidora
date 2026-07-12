package de.sebastian.eidora.worker

import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.work.*
import de.sebastian.eidora.data.db.DatabaseProvider
import de.sebastian.eidora.data.db.FaceRegionEntity
import de.sebastian.eidora.data.db.PersonEntity
import de.sebastian.eidora.data.db.PhotoEntity
import de.sebastian.eidora.domain.model.FaceRegionCoords
import de.sebastian.eidora.ml.ScrfdDetector
import de.sebastian.eidora.util.*
import de.sebastian.eidora.worker.NotificationHelper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

private const val TAG = "PhotoSyncWorker"
private const val SYNC_PARALLELISM = 3

class PhotoSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val db by lazy { DatabaseProvider.getInstance(applicationContext) }
    private val photoDao by lazy { db.photoDao() }
    private val personDao by lazy { db.personDao() }
    private val faceDao by lazy { db.faceRegionDao() }

    private val detector by lazy {
        try {
            ScrfdDetector(applicationContext)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize SCRFD detector", t)
            null
        }
    }

    override suspend fun doWork(): Result {
        val singlePhotoId = inputData.getString(KEY_PHOTO_ID)
        return try {
            if (singlePhotoId != null) doSinglePhotoSync(singlePhotoId)
            else doFullSync()
        } catch (t: Throwable) {
            Log.e(TAG, "Unhandled error in doWork", t)
            Result.failure()
        } finally {
            try { detector?.close() } catch (t: Throwable) { Log.w(TAG, "Error closing detector", t) }
            try {
                androidx.core.app.NotificationManagerCompat.from(applicationContext)
                    .cancel(NotificationHelper.NOTIFICATION_ID_SYNC)
            } catch (t: Throwable) { /* ignore */ }
        }
    }

    // -----------------------------------------------------------------------
    // Full sync
    // -----------------------------------------------------------------------

    private suspend fun doFullSync(): Result {
        try { setForeground(NotificationHelper.syncForegroundInfo(applicationContext, 0, applicationContext.getString(de.sebastian.eidora.R.string.notif_scanning_start))) } catch (t: Throwable) { android.util.Log.w("FACES", "setForeground failed", t) }
        val patterns = try {
            de.sebastian.eidora.data.settings.SettingsProvider.get(applicationContext).getFilenamePatterns()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to load filename patterns, using empty list (no filter)", t)
            emptyList()
        }
        val folderBlacklist = try {
            de.sebastian.eidora.data.settings.SettingsProvider.get(applicationContext).getFolderBlacklist()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to load folder blacklist, using defaults", t)
            de.sebastian.eidora.data.settings.SettingsRepository.DEFAULT_FOLDER_BLACKLIST
        }
        val mediaEntries = try {
            collectJpegsFromMediaStore(patterns, folderBlacklist) { count ->
                try {
                    setForegroundAsync(
                        NotificationHelper.syncForegroundInfo(
                            applicationContext, 0, applicationContext.getString(de.sebastian.eidora.R.string.notif_scanning, count)
                        )
                    )
                } catch (t: Throwable) { /* ignore progress errors */ }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to query MediaStore for JPEGs", t)
            return Result.failure()
        }

        setProgress(workDataOf(KEY_STATUS to applicationContext.getString(de.sebastian.eidora.R.string.notif_scanning_start)))

        // Aves-style reconciliation: the MediaStore is the source of truth.
        // deleted = DB minus MediaStore; work set = new or modified entries.
        val dbStateByPath = try {
            photoDao.getAllPathsWithModified().associateBy { it.path }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to read DB paths", t); return Result.failure()
        }
        val fsPaths = mediaEntries.map { it.file.absolutePath }.toSet()

        (dbStateByPath.keys - fsPaths).forEach { path ->
            try { deletePhoto(path) } catch (t: Throwable) { Log.e(TAG, "Failed to delete photo $path", t) }
        }

        // Keep new, changed, AND not-yet-analyzed photos. The analyzed check
        // makes an interrupted sync self-healing: photos whose processing was
        // cut short (killed app) are picked up again on the next run.
        val jpegFiles = mediaEntries.filter { entry ->
            val db = dbStateByPath[entry.file.absolutePath]
            db == null || db.modifiedAt / 1000 != entry.modifiedSec || !db.analyzed
        }.map { it.file }
        Log.i(TAG, "Sync work set: ${jpegFiles.size} of ${mediaEntries.size} photos (new or modified)")

        val doneCount = java.util.concurrent.atomic.AtomicInteger(0)
        val currentFile = java.util.concurrent.atomic.AtomicReference<String>("")
        val total = jpegFiles.size
        val startedAt = System.currentTimeMillis()

        val powerGate = PowerGate(applicationContext)
        val powerConfig = try {
            de.sebastian.eidora.data.settings.SettingsProvider.get(applicationContext).getPowerConfig()
        } catch (t: Throwable) {
            de.sebastian.eidora.data.settings.PowerConfig(
                minBatteryPercent = 20,
                maxBatteryTempCelsius = 40.0f
            )
        }

        val notifierScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())
        val notifierJob = notifierScope.launch {
            while (isActive) {
                val current = doneCount.get()
                val progress = if (total == 0) 0 else (current * 100) / total
                val file = currentFile.get()
                val eta = formatEta(applicationContext, startedAt, current, total)
                val message = if (eta.isNotEmpty()) "$file – $eta" else file
                try {
                    setForeground(NotificationHelper.syncForegroundInfo(applicationContext, progress, message))
                } catch (t: Throwable) { /* ignore */ }
                kotlinx.coroutines.delay(500)
            }
        }

        @OptIn(ExperimentalCoroutinesApi::class)
        flow { jpegFiles.forEach { emit(it) } }
            .flatMapMerge(concurrency = SYNC_PARALLELISM) { file ->
                flow {
                    powerGate.awaitOk(
                        powerConfig.minBatteryPercent,
                        powerConfig.maxBatteryTempCelsius
                    ) { reason ->
                        currentFile.set(reason)
                    }
                    currentFile.set(file.name)
                    try {
                        processFile(file)
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to process file ${file.name}, skipping", t)
                    }
                    emit(file)
                }
            }
            .collect { _ ->
                val current = doneCount.incrementAndGet()
                setProgress(workDataOf(KEY_PROGRESS to (current * 100) / total))
            }

        notifierJob.cancel()

        try { personDao.deleteOrphaned() } catch (t: Throwable) { Log.e(TAG, "Failed to delete orphaned persons", t) }

        setProgress(workDataOf(KEY_PROGRESS to 100, KEY_STATUS to applicationContext.getString(de.sebastian.eidora.R.string.notif_done)))
        return Result.success()
    }

    // -----------------------------------------------------------------------
    // Single photo re-sync
    // -----------------------------------------------------------------------

    private suspend fun doSinglePhotoSync(photoId: String): Result {
        val photo = try { photoDao.findById(photoId) } catch (t: Throwable) {
            Log.e(TAG, "Failed to find photo $photoId", t); return Result.failure()
        } ?: return Result.success()

        val file = File(photo.path)
        if (!file.exists()) {
            try {
                deleteFaceRegionsForPhoto(photoId)
                photoDao.deleteById(photoId)
                personDao.deleteOrphaned()
            } catch (t: Throwable) { Log.e(TAG, "Failed to delete missing photo", t) }
            return Result.success()
        }

        try { importXmpAndAnalyze(file, photoId) } catch (t: Throwable) {
            Log.e(TAG, "Failed to import/analyze ${file.name}", t)
        }
        try { personDao.deleteOrphaned() } catch (t: Throwable) { Log.e(TAG, "deleteOrphaned failed", t) }
        return Result.success()
    }

    // -----------------------------------------------------------------------
    // Per-file processing
    // -----------------------------------------------------------------------

    /**
     * Queries the MediaStore for JPEG images and returns them as File objects.
     * Filter patterns are applied to the display name (filename).
     * Much faster than walking the file system for large photo collections.
     */
    data class MediaEntry(val file: File, val modifiedSec: Long)

    private fun collectJpegsFromMediaStore(
        patterns: List<String>,
        folderBlacklist: Set<String>,
        onProgress: (Int) -> Unit
    ): List<MediaEntry> {
        val uri = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            android.provider.MediaStore.Images.Media._ID,
            android.provider.MediaStore.Images.Media.DATA,
            android.provider.MediaStore.Images.Media.DISPLAY_NAME,
            android.provider.MediaStore.Images.Media.RELATIVE_PATH,
            android.provider.MediaStore.Images.Media.DATE_MODIFIED,
            android.provider.MediaStore.Images.Media.MIME_TYPE
        )
        val selection = "${android.provider.MediaStore.Images.Media.MIME_TYPE} = ?"
        val selectionArgs = arrayOf("image/jpeg")

        val result = mutableListOf<MediaEntry>()
        applicationContext.contentResolver.query(
            uri, projection, selection, selectionArgs,
            "${android.provider.MediaStore.Images.Media.DATE_TAKEN} DESC"
        )?.use { cursor ->
            val dataCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DATA)
            val nameCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DISPLAY_NAME)
            val relPathCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.RELATIVE_PATH)
            val modCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DATE_MODIFIED)
            var scanned = 0
            while (cursor.moveToNext()) {
                scanned++
                if (scanned % 500 == 0) onProgress(scanned)
                // Folder blacklist check (normalize trailing slash)
                val relPath = cursor.getString(relPathCol)?.trimEnd('/') ?: ""
                if (folderBlacklist.any { relPath == it || relPath.startsWith("$it/") }) continue
                val name = cursor.getString(nameCol) ?: continue
                if (!de.sebastian.eidora.data.settings.SettingsRepository
                        .matchesAnyPattern(name, patterns)) continue
                val path = cursor.getString(dataCol) ?: continue
                val file = File(path)
                if (file.isFile) result.add(MediaEntry(file, cursor.getLong(modCol)))
            }
        }
        return result
    }

    private suspend fun processFile(file: File) {
        val path = file.absolutePath
        val modifiedAt = file.lastModified()
        val takenAt = try { FileUtil.readTakenAt(file) } catch (t: Throwable) {
            Log.w(TAG, "Could not read takenAt for ${file.name}"); null
        }

        val existing = photoDao.findByPath(path)
        when {
            existing == null -> {
                val photoId = UUID.randomUUID().toString()
                photoDao.upsert(PhotoEntity(id = photoId, path = path, modifiedAt = modifiedAt, takenAt = takenAt, analyzed = false))
                importXmpAndAnalyze(file, photoId)
            }
            existing.modifiedAt != modifiedAt -> {
                photoDao.update(existing.id, modifiedAt, takenAt, analyzed = false)
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

    private suspend fun importXmpAndAnalyze(file: File, photoId: String) {
        val xmpRegions = try {
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
                    val qualityScore = de.sebastian.eidora.util.FaceQuality.computeFast(
                        xmpRegion.coords, rotationRad = null
                    )
                    faceDao.insert(FaceRegionEntity(
                        id = faceId, photoId = photoId,
                        personId = person?.id, name = xmpRegion.name,
                        regionJson = xmpRegion.coords.toJson(), ignored = false,
                        qualityScore = qualityScore
                    ))
                    ThumbnailHelper.createThumbnail(applicationContext, file, xmpRegion.coords, faceId)
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to import XMP region", t)
                }
            }
            photoDao.updateAnalyzed(photoId, true)
            try { refreshPersonTags(file, photoId) } catch (t: Throwable) {
                Log.e(TAG, "Failed to refresh person tags for ${file.name}", t)
            }
        } else {
            runMlKit(file, photoId)
        }
    }

    private suspend fun runMlKit(file: File, photoId: String) {
        val det = detector
        if (det == null) {
            Log.w(TAG, "SCRFD detector not available, skipping ${file.name}")
            photoDao.updateAnalyzed(photoId, true)
            return
        }

        val bitmap = try {
            de.sebastian.eidora.util.BitmapLoader.loadOrientedBitmap(file, maxSize = 2048)
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

        val faces = try {
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
        bitmap.recycle()

        if (faces.isNotEmpty()) {
            val xmpRegions = mutableListOf<XmpFaceRegion>()
            faces.forEach { face ->
                try {
                    val coords = FaceRegionCoords(
                        x = face.xMin + face.width / 2f,
                        y = face.yMin + face.height / 2f,
                        w = face.width,
                        h = face.height
                    )
                    val faceId = UUID.randomUUID().toString()
                    // Compute quality score from bbox size and rotation angle.
                    // Sharpness requires loading the crop which is expensive here
                    // and will be refined later (EmbeddingWorker has the bitmap).
                    val qualityScore = de.sebastian.eidora.util.FaceQuality.computeFast(
                        coords, face.rotationRadians
                    )
                    faceDao.insert(FaceRegionEntity(
                        id = faceId, photoId = photoId,
                        personId = null, name = null,
                        regionJson = coords.toJson(), ignored = false,
                        qualityScore = qualityScore
                    ))
                    ThumbnailHelper.createThumbnail(applicationContext, file, coords, faceId)
                    xmpRegions.add(XmpFaceRegion(name = null, coords = coords))
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to process face in ${file.name}", t)
                }
            }

            if (xmpRegions.isNotEmpty()) {
                try {
                    XmpHelper.writeFaceRegions(file, xmpRegions)
                    photoDao.updateModifiedAt(photoId, file.lastModified())
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to write XMP regions to ${file.name}", t)
                }
            }
        }

        photoDao.updateAnalyzed(photoId, true)
    }

    private suspend fun findOrCreatePerson(name: String): PersonEntity {
        return personDao.findByName(name) ?: PersonEntity(
            id = UUID.randomUUID().toString(), name = name
        ).also { personDao.insert(it) }
    }

    private suspend fun refreshPersonTags(file: File, photoId: String) {
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
            try { ThumbnailHelper.deleteThumbnail(applicationContext, it.id) } catch (t: Throwable) { /* ignore */ }
        }
        faceDao.deleteByPhotoId(photoId)
    }

    companion object {
        const val KEY_PROGRESS = "progress"
        const val KEY_STATUS = "status"
        const val KEY_PHOTO_ID = "photo_id"

        fun buildRequest(): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<PhotoSyncWorker>().build()

        /**
         * Estimates remaining time from throughput so far.
         * Returns empty string when not enough data (< 5 items done).
         */
        internal fun formatEta(context: android.content.Context, startedAt: Long, done: Int, total: Int): String {
            if (done < 5 || done >= total) return ""
            val elapsed = System.currentTimeMillis() - startedAt
            if (elapsed <= 0) return ""
            val perItem = elapsed.toDouble() / done
            val remainingMs = ((total - done) * perItem).toLong()
            return context.getString(de.sebastian.eidora.R.string.notif_eta_left, formatDuration(remainingMs))
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
