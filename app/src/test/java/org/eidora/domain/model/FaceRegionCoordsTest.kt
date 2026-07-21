// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@DisplayName("FaceRegionCoords.rotate")
class FaceRegionCoordsTest {
    private val tolerance = 1e-5f

    private fun assertCoordsEqual(
        expected: FaceRegionCoords,
        actual: FaceRegionCoords,
    ) {
        assertEquals(expected.x, actual.x, tolerance, "x")
        assertEquals(expected.y, actual.y, tolerance, "y")
        assertEquals(expected.w, actual.w, tolerance, "w")
        assertEquals(expected.h, actual.h, tolerance, "h")
    }

    // A face in the upper-left area, wider than tall, so rotations are visible.
    private val sample = FaceRegionCoords(x = 0.25f, y = 0.40f, w = 0.20f, h = 0.10f)

    @Nested
    @DisplayName("identity rotations")
    inner class Identity {
        @Test
        fun `0 degrees returns same coords`() {
            assertCoordsEqual(sample, sample.rotate(0))
        }

        @Test
        fun `360 degrees returns same coords`() {
            assertCoordsEqual(sample, sample.rotate(360))
        }
    }

    @Test
    @DisplayName("90° clockwise maps center and swaps dimensions")
    fun rotate90() {
        val r = sample.rotate(90)
        // x' = 1 - y, y' = x, w' = h, h' = w
        assertCoordsEqual(FaceRegionCoords(x = 0.60f, y = 0.25f, w = 0.10f, h = 0.20f), r)
    }

    @Test
    @DisplayName("180° mirrors both axes, keeps dimensions")
    fun rotate180() {
        val r = sample.rotate(180)
        assertCoordsEqual(FaceRegionCoords(x = 0.75f, y = 0.60f, w = 0.20f, h = 0.10f), r)
    }

    @Test
    @DisplayName("270° maps center and swaps dimensions")
    fun rotate270() {
        val r = sample.rotate(270)
        // x' = y, y' = 1 - x, w' = h, h' = w
        assertCoordsEqual(FaceRegionCoords(x = 0.40f, y = 0.75f, w = 0.10f, h = 0.20f), r)
    }

    @Test
    @DisplayName("four 90° rotations return to the original")
    fun fourRotationsIdentity() {
        val result = sample.rotate(90).rotate(90).rotate(90).rotate(90)
        assertCoordsEqual(sample, result)
    }

    @Test
    @DisplayName("90° + 270° cancel out")
    fun rotate90Then270() {
        assertCoordsEqual(sample, sample.rotate(90).rotate(270))
    }

    @ParameterizedTest(name = "negative {0}° equals positive equivalent")
    @ValueSource(ints = [-90, -180, -270, -360])
    fun negativeAnglesNormalize(negative: Int) {
        val positive = ((negative % 360) + 360) % 360
        assertCoordsEqual(sample.rotate(positive), sample.rotate(negative))
    }

    @Test
    @DisplayName("angles above 360 normalize")
    fun largeAnglesNormalize() {
        assertCoordsEqual(sample.rotate(90), sample.rotate(450))
        assertCoordsEqual(sample.rotate(180), sample.rotate(540))
    }
}
