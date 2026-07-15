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
    val h: Float
) {
    /**
     * Returns new coords after rotating the image by [degrees] (90 or -90 / 270).
     * All values stay normalized to [0..1].
     * x, y are center coords; w, h are dimensions.
     */
    fun rotate(degrees: Int): FaceRegionCoords = when (((degrees % 360) + 360) % 360) {
        90 -> FaceRegionCoords(
            x = 1f - y,
            y = x,
            w = h,
            h = w
        )
        180 -> FaceRegionCoords(
            x = 1f - x,
            y = 1f - y,
            w = w,
            h = h
        )
        270 -> FaceRegionCoords(
            x = y,
            y = 1f - x,
            w = h,
            h = w
        )
        else -> this
    }
}
