// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.util

import android.graphics.Bitmap
import org.eidora.domain.model.FaceRegionCoords
import kotlin.math.exp

/**
 * Combines three quality signals into a single [0..1] weight used when
 * computing weighted centroids. Higher = more representative of the person.
 *
 * 1. **Size score** – bounding-box area relative to full image. Large faces
 *    produce higher-resolution crops and better embeddings.
 * 2. **Frontality score** – derived from the SCRFD eye-rotation angle.
 *    Near-zero angle → frontal (score ≈ 1); large angle → profile (score ≈ 0).
 * 3. **Sharpness score** – Laplacian variance of the face crop. Blurry
 *    images → low variance → low score.
 */
object FaceQuality {
    /**
     * Computes a quality score in [0..1] for a detected face.
     *
     * @param coords         Normalized bbox from the DB
     * @param rotationRad    Rotation angle from SCRFD landmarks (may be null for
     *                       imported XMP regions that had no landmark data)
     * @param faceBitmap     Pre-cropped face bitmap (should be the embedding crop)
     */
    fun compute(
        coords: FaceRegionCoords,
        rotationRad: Float?,
        faceBitmap: Bitmap?,
    ): Float {
        val sizeScore = sizeScore(coords.w, coords.h)
        val frontalScore = if (rotationRad != null) frontalScore(rotationRad) else 0.5f
        val sharpScore = if (faceBitmap != null) sharpnessScore(faceBitmap) else 0.5f

        // Weighted product of the three signals
        return (sizeScore * 0.40f + frontalScore * 0.35f + sharpScore * 0.25f)
            .coerceIn(0f, 1f)
    }

    /**
     * Quick estimate without a bitmap: useful when we only have bbox + angle.
     * Sharpness defaults to the neutral value 0.5.
     */
    fun computeFast(
        coords: FaceRegionCoords,
        rotationRad: Float?,
    ): Float = compute(coords, rotationRad, null)

    // -----------------------------------------------------------------------

    /** Logistic curve: 1% area → 0.27, 5% → 0.73, 10% → 0.88, 25% → 0.98 */
    private fun sizeScore(
        w: Float,
        h: Float,
    ): Float {
        val area = (w * h).coerceIn(0f, 1f)
        return 1f / (1f + exp(-10f * (area - 0.05f)))
    }

    /** Gaussian around 0 rad; FWHM ≈ 40° means ±20° still scores > 0.5. */
    private fun frontalScore(rotationRad: Float): Float {
        val sigma = 0.35f // radians ≈ 20°
        return exp(-(rotationRad * rotationRad) / (2f * sigma * sigma))
    }

    /**
     * Laplacian-variance sharpness. We compute the variance of the discrete
     * Laplacian of the grayscale image – a classic blur detector.
     * The result is mapped to [0..1] with a soft normalisation.
     */
    private fun sharpnessScore(bitmap: Bitmap): Float {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 3 || h < 3) return 0.5f

        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        // Convert to grayscale, then apply 3x3 Laplacian kernel
        val gray =
            FloatArray(w * h) { i ->
                val px = pixels[i]
                0.299f * (px shr 16 and 0xFF) +
                    0.587f * (px shr 8 and 0xFF) +
                    0.114f * (px and 0xFF)
            }

        var sum = 0.0
        var count = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val lap =
                    -4f * gray[y * w + x] +
                        gray[(y - 1) * w + x] + gray[(y + 1) * w + x] +
                        gray[y * w + (x - 1)] + gray[y * w + (x + 1)]
                sum += lap * lap
                count++
            }
        }
        val variance = if (count == 0) 0f else (sum / count).toFloat()

        // Soft normalisation: variance of ~100 → score ~0.73; ~500 → ~0.98
        return 1f - exp(-variance / 200f)
    }
}
