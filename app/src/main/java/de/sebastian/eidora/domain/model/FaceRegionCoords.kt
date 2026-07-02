package de.sebastian.eidora.domain.model

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
)
