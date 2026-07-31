// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.ml.container

import android.content.Context
import android.graphics.Bitmap
import org.eidora.ml.DetectedFace
import org.eidora.ml.EmbeddingModel
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
    private data class NamedCrop(val person: String, val bitmap: Bitmap)

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
    ): EmbeddingResult {
        // Crop every named face from every photo, straight from its metadata.
        val crops = mutableListOf<NamedCrop>()
        for (name in listPhotos(context)) {
            val file = stageAsset(context, name)
            for (region in XmpHelper.readFaceRegions(file)) {
                val person = region.name ?: continue
                val crop = ThumbnailHelper.cropForEmbedding(file, region.coords) ?: continue
                crops.add(NamedCrop(person, crop))
            }
        }

        // Embed each crop, keeping the crop bitmap for display.
        val embedded =
            crops.map { Triple(it.person, it.bitmap, embedder.computeEmbedding(it.bitmap)) }

        // All distinct pairs, labelled same/different by person name, each
        // carrying both face thumbnails.
        val pairs = mutableListOf<Pair2>()
        for (i in embedded.indices) {
            for (j in i + 1 until embedded.size) {
                val (pi, bi, ei) = embedded[i]
                val (pj, bj, ej) = embedded[j]
                val same = pi == pj
                val dist = EmbeddingModel.cosineDistance(ei, ej)
                pairs.add(Pair2(pi, pj, bi, bj, dist, same))
            }
        }

        // Same-person pairs first (the key comparison), then by ascending
        // distance so the most similar pairs lead each group.
        val sorted =
            pairs.sortedWith(
                compareByDescending<Pair2> { it.samePerson }.thenBy { it.distance },
            )

        return EmbeddingResult(
            pairs = sorted,
            edge = clustering?.edge,
            clusterMatch = clustering?.clusterMatch,
            individualMatch = clustering?.individualMatch,
        )
    }
}
