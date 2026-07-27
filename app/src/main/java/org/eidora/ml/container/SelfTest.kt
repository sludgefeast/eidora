// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.ml.container

import android.content.Context
import android.graphics.Bitmap
import org.eidora.ml.DetectedFace
import org.eidora.ml.EmbeddingModel
import org.eidora.util.BitmapLoader
import java.io.File

/**
 * Runs the on-device self-test (Class-3 validation) for a container model on
 * the bundled synthetic test images, producing results the user can judge.
 *
 * Two kinds:
 *  - detection: run the detector on a scene, return boxes to draw over it;
 *  - embedding: run the embedder on two people × two shots, return the cosine
 *    distances plus the manifest's clustering thresholds so the numbers can be
 *    read against the decision the app would actually make.
 *
 * The test is optional and non-gating — this just computes; the screen shows the
 * outcome and the user decides.
 */
object SelfTest {
    private const val ASSET_DIR = "selftest"

    // Bundled embedding faces: two people, two shots each (see
    // scripts/generate_selftest_images.py).
    private val FACE_A = listOf("face_a_1.jpg", "face_a_2.jpg")
    private val FACE_B = listOf("face_b_1.jpg", "face_b_2.jpg")
    private const val SCENE = "detect_scene.jpg"
    private const val SCENE_ROTATED = "detect_scene_rotated.jpg"

    data class DetectionResult(
        val scene: Bitmap,
        val faces: List<DetectedFace>,
        /** Same scene but carrying an EXIF rotation, to check orientation handling. */
        val rotatedScene: Bitmap,
        val rotatedFaces: List<DetectedFace>,
    ) {
        /** A quick heuristic hint; the user still decides. */
        val looksReasonable: Boolean
            get() = faces.size in 2..5 && rotatedFaces.size == faces.size
    }

    data class Pair2(val label: String, val distance: Float, val samePerson: Boolean)

    data class EmbeddingResult(
        val pairs: List<Pair2>,
        val edge: Float?,
        val clusterMatch: Float?,
        val individualMatch: Float?,
    ) {
        val sameMax: Float get() = pairs.filter { it.samePerson }.maxOf { it.distance }
        val diffMin: Float get() = pairs.filter { !it.samePerson }.minOf { it.distance }

        /** Same-person clearly closer than different-person. */
        val looksReasonable: Boolean get() = diffMin > sameMax
    }

    /** Runs the detection self-test with [detector] on the bundled scene(s). */
    suspend fun runDetection(
        context: Context,
        detector: org.eidora.ml.FaceDetector,
    ): DetectionResult {
        val scene = loadAsset(context, SCENE) ?: error("missing $SCENE")
        val rotated = loadAsset(context, SCENE_ROTATED) ?: error("missing $SCENE_ROTATED")
        return DetectionResult(
            scene = scene,
            faces = detector.detect(scene),
            rotatedScene = rotated,
            rotatedFaces = detector.detect(rotated),
        )
    }

    /** Runs the embedding self-test with [embedder], using [clustering] for thresholds. */
    suspend fun runEmbedding(
        context: Context,
        embedder: EmbeddingModel,
        clustering: ContainerManifest.Clustering?,
    ): EmbeddingResult {
        val a = FACE_A.map { embed(context, embedder, it) }
        val b = FACE_B.map { embed(context, embedder, it) }

        val pairs = listOf(
            Pair2("A₁–A₂", EmbeddingModel.cosineDistance(a[0], a[1]), true),
            Pair2("B₁–B₂", EmbeddingModel.cosineDistance(b[0], b[1]), true),
            Pair2("A₁–B₁", EmbeddingModel.cosineDistance(a[0], b[0]), false),
            Pair2("A₂–B₂", EmbeddingModel.cosineDistance(a[1], b[1]), false),
        )
        return EmbeddingResult(
            pairs = pairs,
            edge = clustering?.edge,
            clusterMatch = clustering?.clusterMatch,
            individualMatch = clustering?.individualMatch,
        )
    }

    private suspend fun embed(context: Context, embedder: EmbeddingModel, asset: String): FloatArray {
        val bmp = loadAsset(context, asset) ?: error("missing $asset")
        return embedder.computeEmbedding(bmp)
    }

    /** Loads a bundled asset with EXIF orientation applied (via the InputStream path). */
    private fun loadAsset(context: Context, name: String): Bitmap? =
        BitmapLoader.loadOrientedBitmap(
            openStream = { context.assets.open("$ASSET_DIR/$name") },
        )
}
