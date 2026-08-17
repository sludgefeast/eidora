// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.ml

import android.graphics.Bitmap
import android.graphics.Matrix
import org.eidora.util.EidoraLog

/**
 * Aligns a face to the canonical position ArcFace / SFace expect, using the five
 * facial landmarks (eyes, nose, mouth corners). Modern face embedders are
 * trained on aligned crops: the same face at different head poses only lands at
 * the same point in embedding space once the eyes/nose/mouth are mapped to fixed
 * reference coordinates. Without this, a slightly rotated or profile view of the
 * same person produces a very different embedding and fails to cluster — the
 * main cause of large numbers of singleton faces.
 *
 * The reference points are InsightFace's standard 112×112 template (arcface_src),
 * which both ArcFace and OpenCV SFace align to. We estimate a similarity
 * transform (rotation + uniform scale + translation) from the detected landmarks
 * to those references via the Umeyama method, then warp the source image.
 */
object FaceAligner {
    /**
     * InsightFace canonical 5-point template for a 112×112 output
     * (right eye, left eye, nose, right mouth, left mouth). Order MUST match the
     * detector's landmark order.
     */
    private val REFERENCE_112 =
        floatArrayOf(
            38.2946f, 51.6963f, // right eye
            73.5318f, 51.5014f, // left eye
            56.0252f, 71.7366f, // nose
            41.5493f, 92.3655f, // right mouth
            70.7299f, 92.2041f, // left mouth
        )

    private const val TEMPLATE_SIZE = 112

    /**
     * Warps [source] so the given [landmarks] (10 floats: x0,y0,…,x4,y4 in source
     * pixel coordinates, same order as REFERENCE_112) map onto the canonical
     * template, producing an [outputSize]×[outputSize] aligned face. Returns null
     * if landmarks are missing/degenerate.
     */
    fun align(
        source: Bitmap,
        landmarks: FloatArray,
        outputSize: Int,
    ): Bitmap? {
        if (landmarks.size < 10) return null
        val scale = outputSize.toFloat() / TEMPLATE_SIZE
        // Scale the reference template to the requested output size.
        val dst = FloatArray(10)
        for (i in 0 until 10) dst[i] = REFERENCE_112[i] * scale

        val matrix = similarityTransform(landmarks, dst) ?: return null
        return try {
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
                .let { warped ->
                    // createBitmap with a matrix grows the canvas; crop to the
                    // exact output square anchored at origin.
                    if (warped.width >= outputSize && warped.height >= outputSize) {
                        val cropped = Bitmap.createBitmap(warped, 0, 0, outputSize, outputSize)
                        if (cropped !== warped) warped.recycle()
                        cropped
                    } else {
                        warped
                    }
                }
        } catch (t: Throwable) {
            EidoraLog.w("FaceAligner", "fallback after error: ${t.message}")
            null
        }
    }

    /**
     * Estimates a 2D similarity transform (rotation + uniform scale +
     * translation) mapping [src] points to [dst] points (both 10 floats:
     * x0,y0,…) and returns it as an Android [Matrix]. Uses the closed-form
     * least-squares solution for a matrix of the form [[a,-b],[b,a]] — no SVD
     * needed, which keeps it exact and robust. Returns null if the source points
     * are degenerate (zero variance). Verified equal to the Umeyama/skimage
     * reference to floating-point precision.
     */
    private fun similarityTransform(
        src: FloatArray,
        dst: FloatArray,
    ): Matrix? {
        val n = 5
        var srcMeanX = 0f
        var srcMeanY = 0f
        var dstMeanX = 0f
        var dstMeanY = 0f
        for (i in 0 until n) {
            srcMeanX += src[i * 2]; srcMeanY += src[i * 2 + 1]
            dstMeanX += dst[i * 2]; dstMeanY += dst[i * 2 + 1]
        }
        srcMeanX /= n; srcMeanY /= n; dstMeanX /= n; dstMeanY /= n

        // a = Σ(sd·dd)/Σ|sd|², b = Σ(sd×dd)/Σ|sd|²  where sd, dd are centered.
        var dot = 0f // Σ (sd · dd)
        var cross = 0f // Σ (sd × dd)
        var den = 0f // Σ |sd|²
        for (i in 0 until n) {
            val sx = src[i * 2] - srcMeanX
            val sy = src[i * 2 + 1] - srcMeanY
            val dx = dst[i * 2] - dstMeanX
            val dy = dst[i * 2 + 1] - dstMeanY
            dot += sx * dx + sy * dy
            cross += sx * dy - sy * dx
            den += sx * sx + sy * sy
        }
        if (den < 1e-8f) return null
        val a = dot / den
        val b = cross / den

        // R = [[a,-b],[b,a]]; t = dstMean - R·srcMean.
        val tx = dstMeanX - (a * srcMeanX - b * srcMeanY)
        val ty = dstMeanY - (b * srcMeanX + a * srcMeanY)

        // Android Matrix row-major: [MSCALE_X, MSKEW_X, MTRANS_X, MSKEW_Y, MSCALE_Y, MTRANS_Y, …].
        val values =
            floatArrayOf(
                a, -b, tx,
                b, a, ty,
                0f, 0f, 1f,
            )
        return Matrix().apply { setValues(values) }
    }
}
