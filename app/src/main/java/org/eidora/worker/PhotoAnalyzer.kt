// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.worker

import android.content.Context
import org.eidora.util.EidoraLog
import org.eidora.data.db.DatabaseProvider
import org.eidora.data.db.FaceRegionEntity
import org.eidora.data.db.PersonEntity
import org.eidora.data.db.PhotoEntity
import org.eidora.data.db.PhotoStage
import org.eidora.domain.model.FaceRegionCoords
import org.eidora.ml.DetectedFace
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
    private val db by lazy { DatabaseProvider.getInstance(context) }
    private val photoDao by lazy { db.photoDao() }
    private val personDao by lazy { db.personDao() }
    private val faceDao by lazy { db.faceRegionDao() }

    private var detector: FaceDetector? = null

    private suspend fun ensureDetector(): FaceDetector? {
        detector?.let { return it }
        val d = org.eidora.ml.container.SelectedModelResolver.openDetector(context)
        if (d == null) {
            EidoraLog.e(TAG, "Failed to initialize detector from selected container")
            return null
        }
        detector = d
        EidoraLog.i(TAG, "Detector initialized on backend: ${d.backend}")
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
                EidoraLog.w(TAG, "Could not read takenAt for ${file.name}")
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
                EidoraLog.e(TAG, "XMP read failed for ${file.name}", t)
                emptyList()
            }

        if (xmpRegions.isEmpty()) {
            // No metadata → mark for ML detection.
            photoDao.updateStage(photoId, PhotoStage.NEEDS_DETECTION)
            return PhotoStage.NEEDS_DETECTION
        }

        // XMP regions carry only a box, no landmarks — but alignment needs the
        // five landmarks. Detect the whole image once and later match each XMP
        // box to the detected face it overlaps most, borrowing its landmarks
        // (Weg A/C). If our detector doesn't find a given face, that region
        // simply stays landmark-less and falls back to the un-aligned crop.
        val detectedForLandmarks: List<DetectedFace> =
            try {
                val det = ensureDetector()
                val bmp = BitmapLoader.loadOrientedBitmap(file, maxSize = 2048)
                if (det != null && bmp != null) {
                    val faces = det.detect(bmp)
                    val w = bmp.width.toFloat()
                    val h = bmp.height.toFloat()
                    bmp.recycle()
                    // Precompute each detected face's normalized box + landmarks.
                    faces.map { f ->
                        f.copy(
                            xMin = f.xMin / w,
                            yMin = f.yMin / h,
                            width = f.width / w,
                            height = f.height / h,
                            landmarks =
                                f.landmarks?.let { lm ->
                                    FloatArray(lm.size) { i ->
                                        if (i % 2 == 0) lm[i] / w else lm[i] / h
                                    }
                                },
                        )
                    }
                } else {
                    emptyList()
                }
            } catch (t: Throwable) {
                t.rethrowIfCancellation()
                EidoraLog.w(TAG, "XMP landmark detection failed for ${file.name}", t)
                emptyList()
            }

        xmpRegions.forEach { xmpRegion ->
            try {
                val faceId = UUID.randomUUID().toString()
                val person = xmpRegion.name?.let { name -> findOrCreatePerson(name) }
                // Borrow landmarks from the detected face overlapping this XMP box
                // most (if any passes a minimum overlap), so it can be aligned.
                val matchedLandmarks =
                    bestOverlapLandmarks(xmpRegion.coords, detectedForLandmarks)
                val coords =
                    if (matchedLandmarks != null) {
                        xmpRegion.coords.copy(landmarks = matchedLandmarks)
                    } else {
                        xmpRegion.coords
                    }
                val qualityScore = FaceQuality.computeFast(coords, rotationRad = null)
                faceDao.insert(
                    FaceRegionEntity(
                        id = faceId,
                        photoId = photoId,
                        personId = person?.id,
                        name = xmpRegion.name,
                        regionJson = coords.toJson(),
                        ignored = false,
                        qualityScore = qualityScore,
                    ),
                )
                ThumbnailHelper.createThumbnail(context, file, coords, faceId)
            } catch (t: Throwable) {
                t.rethrowIfCancellation()
                EidoraLog.e(TAG, "Failed to import XMP region", t)
            }
        }
        photoDao.updateStage(photoId, PhotoStage.DONE)
        try {
            refreshPersonTags(file, photoId)
        } catch (t: Throwable) {
            t.rethrowIfCancellation()
            EidoraLog.e(TAG, "Failed to refresh person tags for ${file.name}", t)
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
            EidoraLog.w(TAG, "Detector not available, deferring ${file.name}")
            return
        }

        val bitmap =
            try {
                BitmapLoader.loadOrientedBitmap(file, maxSize = 2048)
            } catch (t: Throwable) {
                EidoraLog.e(TAG, "Failed to load bitmap for ${file.name}", t)
                photoDao.updateStage(photoId, PhotoStage.DONE)
                return
            }
        if (bitmap == null) {
            EidoraLog.w(TAG, "Bitmap decode returned null for ${file.name}")
            photoDao.updateStage(photoId, PhotoStage.DONE)
            return
        }

        val faces =
            try {
                det.detect(bitmap)
            } catch (t: Throwable) {
                EidoraLog.e(TAG, "Detection failed for ${file.name}", t)
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
                            // Normalize the detector's source-pixel landmarks to
                            // [0..1] so they survive resize/rotation like the box.
                            landmarks =
                                face.landmarks?.let { lm ->
                                    List(lm.size) { idx ->
                                        if (idx % 2 == 0) lm[idx] / srcW else lm[idx] / srcH
                                    }
                                },
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
                    EidoraLog.e(TAG, "Failed to process face in ${file.name}", t)
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
                    EidoraLog.e(TAG, "Failed to write XMP regions to ${file.name}", t)
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
                EidoraLog.d(TAG, "ThumbnailHelper.deleteThumbnail(contexti failed: ${t.message}")
            }
        }
        faceDao.deleteByPhotoId(photoId)
    }

    /**
     * Returns the landmarks of the detected face whose box overlaps [xmpBox]
     * most (by IoU), provided the overlap clears a minimum and that face has
     * landmarks. Both boxes are normalized; FaceRegionCoords stores x,y as the
     * CENTER, so convert to edges first. Returns null when nothing overlaps well
     * enough — the region then stays landmark-less and uses the un-aligned crop.
     */
    private fun bestOverlapLandmarks(
        xmpBox: FaceRegionCoords,
        detected: List<DetectedFace>,
    ): List<Float>? {
        if (detected.isEmpty()) return null
        val aL = xmpBox.x - xmpBox.w / 2f
        val aT = xmpBox.y - xmpBox.h / 2f
        val aR = xmpBox.x + xmpBox.w / 2f
        val aB = xmpBox.y + xmpBox.h / 2f
        var bestIou = MIN_XMP_OVERLAP
        var bestLm: List<Float>? = null
        detected.forEach { d ->
            val lm = d.landmarks ?: return@forEach
            // Detected face box is normalized with xMin/yMin as the top-left.
            val bL = d.xMin
            val bT = d.yMin
            val bR = d.xMin + d.width
            val bB = d.yMin + d.height
            val interL = maxOf(aL, bL)
            val interT = maxOf(aT, bT)
            val interR = minOf(aR, bR)
            val interB = minOf(aB, bB)
            val iw = interR - interL
            val ih = interB - interT
            if (iw <= 0f || ih <= 0f) return@forEach
            val inter = iw * ih
            val union = xmpBox.w * xmpBox.h + d.width * d.height - inter
            val iou = if (union <= 0f) 0f else inter / union
            if (iou > bestIou) {
                bestIou = iou
                bestLm = lm.toList()
            }
        }
        return bestLm
    }

    companion object {
        private const val TAG = "PhotoAnalyzer"

        // Minimum IoU for an XMP box to adopt a detected face's landmarks. Below
        // this the match is too weak to trust, and the region stays un-aligned.
        private const val MIN_XMP_OVERLAP = 0.3f
    }
}
