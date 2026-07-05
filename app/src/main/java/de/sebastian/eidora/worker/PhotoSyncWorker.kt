package de.sebastian.eidora.worker

import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.work.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import de.sebastian.eidora.data.db.DatabaseProvider
import de.sebastian.eidora.data.db.FaceRegionEntity
import de.sebastian.eidora.data.db.PersonEntity
import de.sebastian.eidora.data.db.PhotoEntity
import de.sebastian.eidora.domain.model.FaceRegionCoords
import de.sebastian.eidora.util.*
import de.sebastian.eidora.worker.NotificationHelper
import kotlinx.coroutines.tasks.await
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
            FaceDetection.getClient(
                FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                    .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                    .build()
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize ML Kit face detector", t)
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
        }
    }

    // -----------------------------------------------------------------------
    // Full sync
    // -----------------------------------------------------------------------

    private suspend fun doFullSync(): Result {
        try { setForeground(NotificationHelper.syncForegroundInfo(applicationContext, 0, "Scanning files…")) } catch (t: Throwable) { android.util.Log.w("FACES", "setForeground failed", t) }
        val patterns = try {
            de.sebastian.eidora.data.settings.SettingsProvider.get(applicationContext).getFilenamePatterns()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to load filename patterns, using empty list (no filter)", t)
            emptyList()
        }
        val cameraDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            "Camera"
        )
        val jpegFiles = try {
            collectJpegs(cameraDir, patterns) { count ->
                try {
                    setForeground(
                        NotificationHelper.syncForegroundInfo(
                            applicationContext, 0, "Scanning files… $count found"
                        )
                    )
                } catch (t: Throwable) { /* ignore progress errors */ }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to collect JPEGs from $cameraDir", t)
            return Result.failure()
        }

        setProgress(workDataOf(KEY_STATUS to "Scanning files…"))

        val dbPaths = try { photoDao.getAllPaths().toSet() } catch (t: Throwable) {
            Log.e(TAG, "Failed to read DB paths", t); return Result.failure()
        }
        val fsPaths = jpegFiles.map { it.absolutePath }.toSet()

        (dbPaths - fsPaths).forEach { path ->
            try { deletePhoto(path) } catch (t: Throwable) { Log.e(TAG, "Failed to delete photo $path", t) }
        }

        val doneCount = java.util.concurrent.atomic.AtomicInteger(0)
        val total = jpegFiles.size

        kotlinx.coroutines.flow.flow { jpegFiles.forEach { emit(it) } }
            .let { flow ->
                @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
                flow.flatMapMerge(concurrency = SYNC_PARALLELISM) { file ->
                    kotlinx.coroutines.flow.flow {
                        try {
                            processFile(file)
                        } catch (t: Throwable) {
                            Log.e(TAG, "Failed to process file ${file.name}, skipping", t)
                        }
                        emit(file)
                    }
                }
            }
            .collect { file ->
                val current = doneCount.incrementAndGet()
                val progress = (current * 100) / total
                setProgress(workDataOf(KEY_PROGRESS to progress, KEY_STATUS to file.name))
                try {
                    setForeground(NotificationHelper.syncForegroundInfo(applicationContext, progress, file.name))
                } catch (t: Throwable) {
                    android.util.Log.w("FACES", "setForeground failed", t)
                }
            }

        try { personDao.deleteOrphaned() } catch (t: Throwable) { Log.e(TAG, "Failed to delete orphaned persons", t) }

        setProgress(workDataOf(KEY_PROGRESS to 100, KEY_STATUS to "Done"))
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

    private fun collectJpegs(
        root: File,
        patterns: List<String>,
        onProgress: (Int) -> Unit
    ): List<File> {
        if (!root.exists()) return emptyList()
        val result = mutableListOf<File>()
        var scanned = 0
        for (file in root.walkTopDown()) {
            scanned++
            if (scanned % 500 == 0) onProgress(scanned)
            if (!file.isFile) continue
            if (!de.sebastian.eidora.data.settings.SettingsRepository
                    .matchesAnyPattern(file.name, patterns)) continue
            if (!FileUtil.isJpeg(file)) continue
            result.add(file)
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
                    faceDao.insert(FaceRegionEntity(
                        id = faceId, photoId = photoId,
                        personId = person?.id, name = xmpRegion.name,
                        regionJson = xmpRegion.coords.toJson(), ignored = false
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
            Log.w(TAG, "ML Kit detector not available, skipping ${file.name}")
            photoDao.updateAnalyzed(photoId, true)
            return
        }

        val faces = try {
            val inputImage = InputImage.fromFilePath(applicationContext, android.net.Uri.fromFile(file))
            det.process(inputImage).await()
        } catch (t: Throwable) {
            Log.e(TAG, "ML Kit detection failed for ${file.name}", t)
            photoDao.updateAnalyzed(photoId, true)
            return
        }

        if (faces.isNotEmpty()) {
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            try { android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts) } catch (t: Throwable) {
                Log.e(TAG, "Could not decode bitmap dimensions for ${file.name}", t)
                photoDao.updateAnalyzed(photoId, true)
                return
            }
            val rawW = opts.outWidth.toFloat().takeIf { it > 0 } ?: run {
                Log.w(TAG, "Invalid image dimensions for ${file.name}")
                photoDao.updateAnalyzed(photoId, true)
                return
            }
            val rawH = opts.outHeight.toFloat()

            // ML Kit rotates the image according to EXIF before detection.
            // Coordinates are in the rotated image space, so we must use
            // rotated dimensions for normalization.
            val rotation = try {
                androidx.exifinterface.media.ExifInterface(file.absolutePath)
                    .getAttributeInt(
                        androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                        androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
                    )
            } catch (t: Throwable) {
                androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
            }
            val isRotated90or270 = rotation == androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90
                || rotation == androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270
                || rotation == androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSVERSE
                || rotation == androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSPOSE

            // Use rotated dimensions for coordinate normalization
            val imgW = if (isRotated90or270) rawH else rawW
            val imgH = if (isRotated90or270) rawW else rawH

            val xmpRegions = mutableListOf<XmpFaceRegion>()
            faces.forEach { face ->
                try {
                    val box = face.boundingBox
                    val coords = FaceRegionCoords(
                        x = box.centerX() / imgW,
                        y = box.centerY() / imgH,
                        w = box.width() / imgW,
                        h = box.height() / imgH
                    )
                    val faceId = UUID.randomUUID().toString()
                    faceDao.insert(FaceRegionEntity(
                        id = faceId, photoId = photoId,
                        personId = null, name = null,
                        regionJson = coords.toJson(), ignored = false
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
    }
}
