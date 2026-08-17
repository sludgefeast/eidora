// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.ml.container

import android.content.Context
import android.graphics.Bitmap
import org.eidora.util.EidoraLog
import org.eidora.ml.DetectedFace
import org.eidora.ml.EmbeddingModel
import org.eidora.domain.model.FaceRegionCoords
import org.eidora.util.BitmapLoader
import org.eidora.util.ThumbnailHelper
import org.eidora.util.XmpHelper
import java.io.File

/**
 * On-device self-test (Class-3 validation) for a container model, run against
 * real bundled test photos.
 *
 * The photos live in assets/selftest/ as ordinary JPGs whose MWG face regions
 * (name + normalized box) are stored in their XMP — the same metadata Eidora
 * reads from any photo. Everything is derived from those files at runtime, so
 * the test set is extended or swapped just by changing the JPGs in that folder;
 * no code changes needed. Each JPG is copied to the cache once so the existing
 * File-based XMP and bitmap helpers work on it unchanged.
 *
 * Two independent checks:
 *  - detection: run the detector on each photo and compare the number of faces
 *    found against the number of face regions the metadata declares;
 *  - embedding: crop each named face straight from its metadata region (so this
 *    is independent of the detector), embed it, and check that two crops of the
 *    same person are closer than crops of different people. A person appearing
 *    in more than one photo gives the same-person pair.
 */
object SelfTest {
    private const val ASSET_DIR = "selftest"

    /** One face crop from metadata, tagged with the person's name. */

    // ---- Detection ---------------------------------------------------------

    data class PhotoDetection(
        val name: String,
        val photo: Bitmap,
        val faces: List<DetectedFace>,
        val expected: Int,
    )

    data class DetectionResult(val photos: List<PhotoDetection>) {
        /** Found count matches the metadata count for every photo. */
        val looksReasonable: Boolean
            get() = photos.isNotEmpty() && photos.all { it.faces.size == it.expected }
    }

    // ---- Embedding ---------------------------------------------------------

    data class Pair2(
        val personA: String,
        val personB: String,
        val thumbA: Bitmap,
        val thumbB: Bitmap,
        val distance: Float,
        val samePerson: Boolean,
    )

    data class EmbeddingResult(
        val pairs: List<Pair2>,
        val edge: Float?,
        val clusterMatch: Float?,
        val individualMatch: Float?,
        // Aligned-embedding spread for the side-by-side comparison. Null when no
        // faces could be aligned (e.g. no detector or no landmark matches).
        val alignedSameMax: Float? = null,
        val alignedDiffMin: Float? = null,
        val alignedPairCount: Int = 0,
    ) {
        private val same get() = pairs.filter { it.samePerson }
        private val diff get() = pairs.filter { !it.samePerson }

        val sameMax: Float? get() = same.maxOfOrNull { it.distance }
        val diffMin: Float? get() = diff.minOfOrNull { it.distance }

        /** Same-person clearly closer than different-person (needs both kinds). */
        val looksReasonable: Boolean
            get() {
                val s = sameMax
                val d = diffMin
                return s != null && d != null && d > s
            }

        /** Whether aligned embeddings separate same/different better than plain. */
        val alignedLooksReasonable: Boolean
            get() {
                val s = alignedSameMax
                val d = alignedDiffMin
                return s != null && d != null && d > s
            }
    }

    /** Lists the bundled test photos (any .jpg/.jpeg in the asset folder). */
    private fun listPhotos(context: Context): List<String> =
        (context.assets.list(ASSET_DIR) ?: emptyArray())
            .filter { it.endsWith(".jpg", true) || it.endsWith(".jpeg", true) }
            .sorted()

    /** Copies an asset to the cache so File-based helpers can read it. Cached. */
    private fun stageAsset(context: Context, name: String): File {
        val out = File(context.cacheDir, "selftest_$name")
        if (!out.exists() || out.length() == 0L) {
            context.assets.open("$ASSET_DIR/$name").use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
        }
        return out
    }

    // ---- Detection test ----------------------------------------------------

    suspend fun runDetection(
        context: Context,
        detector: org.eidora.ml.FaceDetector,
    ): DetectionResult {
        val results = mutableListOf<PhotoDetection>()
        for (name in listPhotos(context)) {
            val file = stageAsset(context, name)
            val expected = XmpHelper.readFaceRegions(file).size
            val bmp = BitmapLoader.loadOrientedBitmap(file, maxSize = 2048) ?: continue
            val faces = detector.detect(bmp)
            results.add(PhotoDetection(name, bmp, faces, expected))
        }
        return DetectionResult(results)
    }

    // ---- Embedding test ----------------------------------------------------

    suspend fun runEmbedding(
        context: Context,
        embedder: EmbeddingModel,
        clustering: ContainerManifest.Clustering?,
        detector: org.eidora.ml.FaceDetector?,
    ): EmbeddingResult {
        // For each named face we build TWO crops: the plain box crop (un-aligned,
        // the old behaviour) and, when we can recover landmarks by detecting the
        // photo and matching the XMP box, a landmark-aligned crop. Comparing the
        // same/different distance spread of both shows whether alignment actually
        // tightens same-person embeddings.
        data class DualCrop(
            val person: String,
            val plain: Bitmap,
            val aligned: Bitmap?,
        )
        val crops = mutableListOf<DualCrop>()
        for (name in listPhotos(context)) {
            val file = stageAsset(context, name)
            // Detect once for landmark recovery (normalized boxes + landmarks).
            val detected: List<org.eidora.ml.DetectedFace> =
                try {
                    val bmp = org.eidora.util.BitmapLoader.loadOrientedBitmap(file, maxSize = 2048)
                    if (detector != null && bmp != null) {
                        val faces = detector.detect(bmp)
                        val w = bmp.width.toFloat()
                        val h = bmp.height.toFloat()
                        bmp.recycle()
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
                    EidoraLog.w("SelfTest", "fallback after error: ${t.message}")
                    emptyList()
                }
            for (region in XmpHelper.readFaceRegions(file)) {
                val person = region.name ?: continue
                val plain = ThumbnailHelper.cropForEmbedding(file, region.coords) ?: continue
                // Attach landmarks from the best-overlapping detected face, then
                // align. If no match, aligned stays null (shown as "n/a").
                val lm = bestOverlapLandmarksForTest(region.coords, detected)
                val aligned =
                    if (lm != null) {
                        ThumbnailHelper.alignForEmbedding(file, region.coords.copy(landmarks = lm))
                    } else {
                        null
                    }
                crops.add(DualCrop(person, plain, aligned))
            }
        }

        // Embed both variants. For pairs, use the plain crops for the thumbnails
        // and compute distance for whichever variant we're reporting.
        val embedded =
            crops.map { c ->
                val ePlain = embedder.computeEmbedding(c.plain)
                val eAligned = c.aligned?.let { embedder.computeEmbedding(it) }
                Triple(c.person, c.plain, Pair(ePlain, eAligned))
            }

        val pairs = mutableListOf<Pair2>()
        val alignedDists = mutableListOf<Triple<Boolean, Float, Float>>() // same, plainDist, alignedDist
        for (i in embedded.indices) {
            for (j in i + 1 until embedded.size) {
                val (pi, bi, ei) = embedded[i]
                val (pj, bj, ej) = embedded[j]
                val same = pi == pj
                val distPlain = EmbeddingModel.cosineDistance(ei.first, ej.first)
                pairs.add(Pair2(pi, pj, bi, bj, distPlain, same))
                // Aligned distance only when BOTH faces have an aligned embedding.
                val ea = ei.second
                val eaj = ej.second
                if (ea != null && eaj != null) {
                    alignedDists.add(Triple(same, distPlain, EmbeddingModel.cosineDistance(ea, eaj)))
                }
            }
        }

        // Same-person pairs first (the key comparison), then by ascending
        // distance so the most similar pairs lead each group.
        val sorted =
            pairs.sortedWith(
                compareByDescending<Pair2> { it.samePerson }.thenBy { it.distance },
            )

        // Aligned same/different spread, for the side-by-side comparison.
        val alignedSame = alignedDists.filter { it.first }.map { it.third }
        val alignedDiff = alignedDists.filter { !it.first }.map { it.third }

        return EmbeddingResult(
            pairs = sorted,
            edge = clustering?.edge,
            clusterMatch = clustering?.clusterMatch,
            individualMatch = clustering?.individualMatch,
            alignedSameMax = alignedSame.maxOrNull(),
            alignedDiffMin = alignedDiff.minOrNull(),
            alignedPairCount = alignedDists.size,
        )
    }

    /**
     * Landmarks of the detected face overlapping [xmpBox] most (IoU ≥ 0.3), or
     * null. Mirror of PhotoAnalyzer.bestOverlapLandmarks for the self-test.
     */
    private fun bestOverlapLandmarksForTest(
        xmpBox: FaceRegionCoords,
        detected: List<org.eidora.ml.DetectedFace>,
    ): List<Float>? {
        if (detected.isEmpty()) return null
        val aL = xmpBox.x - xmpBox.w / 2f
        val aT = xmpBox.y - xmpBox.h / 2f
        val aR = xmpBox.x + xmpBox.w / 2f
        val aB = xmpBox.y + xmpBox.h / 2f
        var bestIou = 0.3f
        var bestLm: List<Float>? = null
        detected.forEach { d ->
            val lm = d.landmarks ?: return@forEach
            val interL = maxOf(aL, d.xMin)
            val interT = maxOf(aT, d.yMin)
            val interR = minOf(aR, d.xMin + d.width)
            val interB = minOf(aB, d.yMin + d.height)
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
}
