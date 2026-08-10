// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.worker

import android.content.Context
import android.util.Log
import org.eidora.data.db.DatabaseProvider
import org.eidora.data.db.FaceRegionEntity
import org.eidora.data.db.PersonEntity
import org.eidora.data.db.PhotoEntity
import org.eidora.data.db.PhotoStage
import org.eidora.domain.model.FaceRegionCoords
import org.eidora.ml.FaceDetector
import org.eidora.util.BitmapLoader
import org.eidora.util.FaceQuality
import org.eidora.util.FileUtil
import org.eidora.util.ThumbnailHelper
import org.eidora.util.XmpFaceRegion
import org.eidora.util.XmpHelper
import org.eidora.util.toFaceRegionCoords
import org.eidora.util.toJson
import java.io.File
import java.util.UUID

/**
 * Shared photo-analysis logic used by the separate pipeline workers
 * (ScanWorker, TriageWorker, DetectionWorker). Extracted from the old
 * monolithic PhotoSyncWorker so each worker does one job while reusing the
 * same registration, XMP-import, detection and person-tagging code.
 *
 * Holds a lazily-initialised [FaceDetector]; only DetectionWorker needs it, so
 * triage/scan never pay for model init. Not thread-safe for detector init
 * across threads, but each worker runs its own instance.
 */
class PhotoAnalyzer(
    private val context: Context,
) {
    private val db by lazy { DatabaseProvider.get(context) }
    private val photoDao by lazy { db.photoDao() }
    private val personDao by lazy { db.personDao() }
    private val faceDao by lazy { db.faceRegionDao() }

    private var detector: FaceDetector? = null

    private suspend fun ensureDetector(): FaceDetector? {
        detector?.let { return it }
        val d = org.eidora.ml.container.SelectedModelResolver.openDetector(context)
        if (d == null) {
            Log.e(TAG, "Failed to initialize detector from selected container")
            return null
        }
        detector = d
        Log.i(TAG, "Detector initialized on backend: ${d.backend}")
        return d
    }

    /**
     * Triage one photo: register/refresh its DB row and, if it already has XMP
     * face regions, import them right away (advancing it to DONE). Returns the
     * resulting stage: [PhotoStage.DONE] when handled here, or
     * [PhotoStage.NEEDS_DETECTION] when the photo has no metadata and must go
     * through ML detection.
     */
    suspend fun triage(
        file: File,
        folder: String,
    ): Int {
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
        val photoId =
            when {
                existing == null -> {
                    val id = UUID.randomUUID().toString()
                    photoDao.upsert(
                        PhotoEntity(
                            id = id,
                            path = path,
                            folder = folder,
                            modifiedAt = modifiedAt,
                            takenAt = takenAt,
                            stage = PhotoStage.NEW,
                        ),
                    )
                    id
                }
                existing.modifiedAt != modifiedAt -> {
                    photoDao.update(existing.id, modifiedAt, takenAt, stage = PhotoStage.NEW)
                    photoDao.updateFolder(existing.id, folder)
                    deleteFaceRegionsForPhoto(existing.id)
                    existing.id
                }
                existing.stage != PhotoStage.DONE -> {
                    // Recovery: interrupted after registering but before finishing.
                    deleteFaceRegionsForPhoto(existing.id)
                    existing.id
                }
                else -> return PhotoStage.DONE // already done
            }

        val xmpRegions =
            try {
                XmpHelper.readFaceRegions(file)
            } catch (t: Throwable) {
                Log.e(TAG, "XMP read failed for ${file.name}", t)
                emptyList()
            }

        if (xmpRegions.isEmpty()) {
            // No metadata → mark for ML detection.
            photoDao.updateStage(photoId, PhotoStage.NEEDS_DETECTION)
            return PhotoStage.NEEDS_DETECTION
        }

        xmpRegions.forEach { xmpRegion ->
            try {
                val faceId = UUID.randomUUID().toString()
                val person = xmpRegion.name?.let { name -> findOrCreatePerson(name) }
                val qualityScore = FaceQuality.computeFast(xmpRegion.coords, rotationRad = null)
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
                ThumbnailHelper.createThumbnail(context, file, xmpRegion.coords, faceId)
            } catch (t: Throwable) {
                t.rethrowIfCancellation()
                Log.e(TAG, "Failed to import XMP region", t)
            }
        }
        photoDao.updateStage(photoId, PhotoStage.DONE)
        try {
            refreshPersonTags(file, photoId)
        } catch (t: Throwable) {
            t.rethrowIfCancellation()
            Log.e(TAG, "Failed to refresh person tags for ${file.name}", t)
        }
        return PhotoStage.DONE
    }

    /** Run ML detection on a photo (looked up by path) that has no XMP metadata. */
    suspend fun detect(
        file: File,
        @Suppress("UNUSED_PARAMETER") folder: String,
    ) {
        val photoId = photoDao.findByPath(file.absolutePath)?.id ?: return
        runFaceDetection(file, photoId)
    }

    private suspend fun runFaceDetection(
        file: File,
        photoId: String,
    ) {
        val det = ensureDetector()
        if (det == null) {
            // Models not ready. Leave the photo at its current stage so it is
            // retried once models are present.
            Log.w(TAG, "Detector not available, deferring ${file.name}")
            return
        }

        val bitmap =
            try {
                BitmapLoader.loadOrientedBitmap(file, maxSize = 2048)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load bitmap for ${file.name}", t)
                photoDao.updateStage(photoId, PhotoStage.DONE)
                return
            }
        if (bitmap == null) {
            Log.w(TAG, "Bitmap decode returned null for ${file.name}")
            photoDao.updateStage(photoId, PhotoStage.DONE)
            return
        }

        val faces =
            try {
                det.detect(bitmap)
            } catch (t: Throwable) {
                Log.e(TAG, "Detection failed for ${file.name}", t)
                photoDao.updateStage(photoId, PhotoStage.DONE)
                bitmap.recycle()
                return
            }
        val srcW = bitmap.width.toFloat()
        val srcH = bitmap.height.toFloat()
        bitmap.recycle()

        if (faces.isNotEmpty()) {
            val xmpRegions = mutableListOf<XmpFaceRegion>()
            faces.forEach { face ->
                try {
                    val coords =
                        FaceRegionCoords(
                            x = (face.xMin + face.width / 2f) / srcW,
                            y = (face.yMin + face.height / 2f) / srcH,
                            w = face.width / srcW,
                            h = face.height / srcH,
                        )
                    val faceId = UUID.randomUUID().toString()
                    val qualityScore = FaceQuality.computeFast(coords, face.rotationRadians)
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
                    ThumbnailHelper.createThumbnail(context, file, coords, faceId)
                    xmpRegions.add(XmpFaceRegion(name = null, coords = coords))
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to process face in ${file.name}", t)
                }
            }

            if (xmpRegions.isNotEmpty()) {
                try {
                    val photo = photoDao.findById(photoId)
                    val fillMissingDate =
                        org.eidora.data.settings.SettingsProvider
                            .get(context)
                            .getFillMissingDate()
                    val fillDate =
                        if (photo?.takenAt == null && fillMissingDate) {
                            file.lastModified()
                        } else {
                            null
                        }
                    XmpHelper.writeFaceRegions(file, xmpRegions, fillDate)
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

        photoDao.updateStage(photoId, PhotoStage.DONE)
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

    suspend fun deletePhoto(path: String) {
        val photo = photoDao.findByPath(path) ?: return
        deleteFaceRegionsForPhoto(photo.id)
        photoDao.deleteByPath(path)
    }

    suspend fun deleteFaceRegionsForPhoto(photoId: String) {
        faceDao.findByPhotoId(photoId).forEach {
            try {
                ThumbnailHelper.deleteThumbnail(context, it.id)
            } catch (t: Throwable) {
                // ignore
            }
        }
        faceDao.deleteByPhotoId(photoId)
    }

    companion object {
        private const val TAG = "PhotoAnalyzer"
    }
}
