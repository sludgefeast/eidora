// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.domain.model

import kotlinx.serialization.Serializable

/**
 * Normalized MWG face region coordinates.
 * All values are relative to image dimensions (0.0 – 1.0).
 * x, y = center of region; w, h = width and height.
 */
@Serializable
data class FaceRegionCoords(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    /**
     * Optional five facial landmarks, normalized to [0..1] like x/y, in order
     * right eye, left eye, nose, right mouth, left mouth as 10 values
     * (x0,y0,…,x4,y4). Null for regions detected before alignment existed, or
     * imported from XMP (which carries only a box). Used to align the face to the
     * embedder's canonical template. The default null keeps older JSON (without
     * this field) readable.
     */
    val landmarks: List<Float>? = null,
) {
    /**
     * Returns new coords after rotating the image by [degrees] (90 or -90 / 270).
     * All values stay normalized to [0..1].
     * x, y are center coords; w, h are dimensions.
     */
    fun rotate(degrees: Int): FaceRegionCoords =
        when (((degrees % 360) + 360) % 360) {
            90 ->
                FaceRegionCoords(
                    x = 1f - y,
                    y = x,
                    w = h,
                    h = w,
                    landmarks = landmarks.rotatePoints(90),
                )
            180 ->
                FaceRegionCoords(
                    x = 1f - x,
                    y = 1f - y,
                    w = w,
                    h = h,
                    landmarks = landmarks.rotatePoints(180),
                )
            270 ->
                FaceRegionCoords(
                    x = y,
                    y = 1f - x,
                    w = h,
                    h = w,
                    landmarks = landmarks.rotatePoints(270),
                )
            else -> this
        }
}

/**
 * Rotates normalized landmark points (x,y pairs in [0..1]) by [degrees] the same
 * way the region box is rotated, so landmarks stay aligned with the face after
 * an image rotation. Null stays null.
 */
private fun List<Float>?.rotatePoints(degrees: Int): List<Float>? {
    if (this == null) return null
    val out = ArrayList<Float>(size)
    var i = 0
    while (i + 1 < size) {
        val px = this[i]
        val py = this[i + 1]
        when (((degrees % 360) + 360) % 360) {
            90 -> {
                out.add(1f - py)
                out.add(px)
            }
            180 -> {
                out.add(1f - px)
                out.add(1f - py)
            }
            270 -> {
                out.add(py)
                out.add(1f - px)
            }
            else -> {
                out.add(px)
                out.add(py)
            }
        }
        i += 2
    }
    return out
}
