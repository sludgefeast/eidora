package de.sebastian.faces.worker

import android.content.Context
import android.os.Environment
import androidx.work.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import de.sebastian.faces.data.db.FaceRegionEntity
import de.sebastian.faces.data.db.FacesDatabase
import de.sebastian.faces.data.db.PersonEntity
import de.sebastian.faces.data.db.PhotoEntity
import de.sebastian.faces.domain.model.FaceRegionCoords
import de.sebastian.faces.util.*
import kotlinx.coroutines.tasks.await
import java.io.File
import java.util.UUID

class PhotoSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val db = FacesDatabase.getInstance(applicationContext)
    private val photoDao = db.photoDao()
    private val personDao = db.personDao()
    private val faceDao = db.faceRegionDao()

    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .build()
        )
    }

    override suspend fun doWork(): Result {
        return try {
            val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            val jpegFiles = collectJpegs(dcim)

            setProgress(workDataOf(KEY_STATUS to "Scanning files…"))

            val dbPaths = photoDao.getAllPaths().toSet()
            val fsPaths = jpegFiles.map { it.absolutePath }.toSet()

            // 1. Delete photos whose files are gone
            val deleted = dbPaths - fsPaths
            var progress = 0
            deleted.forEach { path ->
                deletePhoto(path)
            }

            // 2. Process each file on disk
            jpegFiles.forEachIndexed { index, file ->
                progress = ((index + 1) * 100) / jpegFiles.size
                setProgress(workDataOf(KEY_PROGRESS to progress, KEY_STATUS to file.name))
                processFile(file)
            }

            // 3. Cleanup orphaned persons
            personDao.deleteOrphaned()

            setProgress(workDataOf(KEY_PROGRESS to 100, KEY_STATUS to "Done"))
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        } finally {
            detector.close()
        }
    }

    private fun collectJpegs(root: File): List<File> {
        if (!root.exists()) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile && FileUtil.isJpeg(it) }
            .toList()
    }

    private suspend fun processFile(file: File) {
        val path = file.absolutePath
        val modifiedAt = file.lastModified()
        val takenAt = FileUtil.readTakenAt(file)

        // DEBUG FILTER: only Jan–May 2026
        if (!FileUtil.isInDebugDateRange(takenAt)) return

        val existing = photoDao.findByPath(path)

        when {
            existing == null -> {
                // New photo
                val photoId = UUID.randomUUID().toString()
                photoDao.upsert(PhotoEntity(
                    id = photoId,
                    path = path,
                    modifiedAt = modifiedAt,
                    takenAt = takenAt,
                    analyzed = false
                ))
                importXmpAndAnalyze(file, photoId)
            }
            existing.modifiedAt != modifiedAt -> {
                // Changed photo – reset and re-import
                photoDao.update(existing.id, modifiedAt, takenAt, analyzed = false)
                deleteFaceRegionsForPhoto(existing.id)
                importXmpAndAnalyze(file, existing.id)
            }
            // else: unchanged, skip
        }
    }

    private suspend fun importXmpAndAnalyze(file: File, photoId: String) {
        val xmpRegions = XmpHelper.readFaceRegions(file)

        if (xmpRegions.isNotEmpty()) {
            // Import existing face regions from XMP
            xmpRegions.forEach { xmpRegion ->
                val faceId = UUID.randomUUID().toString()
                val person = xmpRegion.name?.let { findOrCreatePerson(it) }
                faceDao.insert(FaceRegionEntity(
                    id = faceId,
                    photoId = photoId,
                    personId = person?.id,
                    name = xmpRegion.name,
                    regionJson = xmpRegion.coords.toJson(),
                    ignored = false
                ))
                ThumbnailHelper.createThumbnail(applicationContext, file, xmpRegion.coords, faceId)
            }
            photoDao.updateAnalyzed(photoId, true)

            // Rewrite PersonInImage + People/ subjects
            refreshPersonTags(file, photoId)
        } else {
            // No XMP regions – run ML Kit
            runMlKit(file, photoId)
        }
    }

    private suspend fun runMlKit(file: File, photoId: String) {
        val inputImage = InputImage.fromFilePath(applicationContext, android.net.Uri.fromFile(file))
        val faces = detector.process(inputImage).await()

        if (faces.isNotEmpty()) {
            val photoForSize = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
            val imgW = photoForSize?.width?.toFloat() ?: 1f
            val imgH = photoForSize?.height?.toFloat() ?: 1f
            photoForSize?.recycle()

            val xmpRegions = mutableListOf<XmpFaceRegion>()

            faces.forEach { face ->
                val box = face.boundingBox
                val cx = (box.centerX() / imgW)
                val cy = (box.centerY() / imgH)
                val w = (box.width() / imgW)
                val h = (box.height() / imgH)
                val coords = FaceRegionCoords(cx, cy, w, h)

                val faceId = UUID.randomUUID().toString()
                faceDao.insert(FaceRegionEntity(
                    id = faceId,
                    photoId = photoId,
                    personId = null,
                    name = null,
                    regionJson = coords.toJson(),
                    ignored = false
                ))
                ThumbnailHelper.createThumbnail(applicationContext, file, coords, faceId)
                xmpRegions.add(XmpFaceRegion(name = null, coords = coords))
            }

            // Write unnamed regions to XMP
            XmpHelper.writeFaceRegions(file, xmpRegions)
            photoDao.updateModifiedAt(photoId, file.lastModified())
        }

        photoDao.updateAnalyzed(photoId, true)
    }

    private suspend fun findOrCreatePerson(name: String): PersonEntity {
        return personDao.findByName(name) ?: run {
            val person = PersonEntity(id = UUID.randomUUID().toString(), name = name)
            personDao.insert(person)
            person
        }
    }

    private suspend fun refreshPersonTags(file: File, photoId: String) {
        val confirmedFaces = faceDao.findByPhotoId(photoId).filter { it.name != null }
        val names = confirmedFaces.mapNotNull { it.name }.distinct()
        val xmpRegions = faceDao.findByPhotoId(photoId).map { face ->
            XmpFaceRegion(
                name = face.name,
                coords = face.regionJson.toFaceRegionCoords()
            )
        }
        XmpHelper.writeFaceRegions(file, xmpRegions)
        photoDao.updateModifiedAt(photoId, file.lastModified())
    }

    private suspend fun deletePhoto(path: String) {
        val photo = photoDao.findByPath(path) ?: return
        deleteFaceRegionsForPhoto(photo.id)
        photoDao.deleteByPath(path)
    }

    private suspend fun deleteFaceRegionsForPhoto(photoId: String) {
        val faces = faceDao.findByPhotoId(photoId)
        faces.forEach { face ->
            ThumbnailHelper.deleteThumbnail(applicationContext, face.id)
        }
        faceDao.deleteByPhotoId(photoId)
    }

    companion object {
        const val KEY_PROGRESS = "progress"
        const val KEY_STATUS = "status"

        const val WORK_NAME = "photo_sync"

        fun buildRequest(): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<PhotoSyncWorker>()
                .setConstraints(Constraints.Builder().build())
                .build()
    }
}
