// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.ml

import android.graphics.Bitmap
import java.io.Closeable

/**
 * One detected face in source-image pixel coordinates.
 *
 * Coordinates are in SOURCE-IMAGE PIXELS (the bitmap passed to
 * [FaceDetector.detect]), not normalized and not model-input pixels. Both
 * YuNetDetector and ScrfdDetector honor this, so the rest of the pipeline does
 * not care which model produced a face: consumers (the sync pipeline, the
 * self-test overlay) treat every detector identically — divide by the bitmap
 * size to normalize, or scale to a displayed image directly.
 */
data class DetectedFace(
    val xMin: Float,
    val yMin: Float,
    val width: Float,
    val height: Float,
    val rotationRadians: Float,
    val score: Float,
    /**
     * Five facial landmarks in source-image pixel coordinates, 10 floats:
     * x0,y0,…,x4,y4 in order right eye, left eye, nose, right mouth, left mouth.
     * Null when the detector doesn't provide them. Used to align the face to the
     * embedder's canonical template before computing its embedding.
     */
    val landmarks: FloatArray? = null,
) {
    // Data class with a FloatArray needs explicit equals/hashCode so faces
    // compare by landmark contents, not array identity.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DetectedFace) return false
        return xMin == other.xMin &&
            yMin == other.yMin &&
            width == other.width &&
            height == other.height &&
            rotationRadians == other.rotationRadians &&
            score == other.score &&
            landmarks.contentEqualsNullable(other.landmarks)
    }

    override fun hashCode(): Int {
        var result = xMin.hashCode()
        result = 31 * result + yMin.hashCode()
        result = 31 * result + width.hashCode()
        result = 31 * result + height.hashCode()
        result = 31 * result + rotationRadians.hashCode()
        result = 31 * result + score.hashCode()
        result = 31 * result + (landmarks?.contentHashCode() ?: 0)
        return result
    }
}

private fun FloatArray?.contentEqualsNullable(other: FloatArray?): Boolean =
    when {
        this == null && other == null -> true
        this == null || other == null -> false
        else -> this.contentEquals(other)
    }

/**
 * Common interface for face detectors. Both SCRFD and YuNet implement this, so
 * the detection model can be swapped like the embedding model.
 */
interface FaceDetector : Closeable {
    /** Which inference backend is active ("GPU" or "CPU"), for logging. */
    val backend: String

    /** Detects faces in [source], returning boxes in source pixel coordinates. */
    suspend fun detect(source: Bitmap): List<DetectedFace>
}

/**
 * Describes a face-detection model so detectors can be swapped without touching
 * pipeline code — the detection counterpart to [EmbeddingModelSpec].
 *
 * Switching detection does NOT invalidate embeddings (faces are re-detected and
 * re-embedded either way), but it does change which crops feed the embedder, so
 * a switch triggers a full re-sync just like a folder change.
 *
 * @param id        stable id persisted in settings
 * @param filename  on-disk filename in filesDir (also the download target)
 * @param isFree    true if the weights are under a free/open license (F-Droid)
 */
data class DetectionModelSpec(
    val id: String,
    val filename: String,
    val isFree: Boolean,
    /** Effective license (the restrictive one) plus the reason, for the UI. */
    val license: ModelLicense,
) {
    companion object {
        /**
         * SCRFD 2.5G KPS (InsightFace). Research-only weights; highest quality
         * on small/rotated faces but not F-Droid friendly. This spec exists only
         * so an imported bring-your-own container using SCRFD runs with the right
         * parameters and license label — Eidora never offers or fetches it.
         */
        val SCRFD =
            DetectionModelSpec(
                id = "scrfd_2.5g_kps",
                filename = "scrfd_2.5g_kps_640_float32.tflite",
                isFree = false,
                license =
                    ModelLicense(
                        isFree = false,
                        effectiveNameRes = org.eidora.R.string.license_research_name,
                        reasonRes = org.eidora.R.string.license_reason_scrfd,
                    ),
            )

        /**
         * YuNet (OpenCV Zoo). Apache-2.0 weights — F-Droid friendly, and the
         * detector SFace is designed to pair with.
         */
        val YUNET =
            DetectionModelSpec(
                id = "yunet",
                filename = "yunet_2023mar_float32.tflite",
                isFree = true,
                license =
                    ModelLicense(
                        isFree = true,
                        effectiveNameRes = org.eidora.R.string.license_free_name,
                        reasonRes = org.eidora.R.string.license_reason_yunet,
                    ),
            )

        val ALL = listOf(SCRFD, YUNET)

        /** Prefer the free detector by default. */
        val DEFAULT = YUNET

        fun byId(id: String?): DetectionModelSpec = ALL.firstOrNull { it.id == id } ?: DEFAULT
    }
}
