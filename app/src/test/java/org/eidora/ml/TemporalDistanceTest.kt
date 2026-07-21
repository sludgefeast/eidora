// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.ml

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@DisplayName("TemporalDistance.penalty")
class TemporalDistanceTest {
    private val tolerance = 1e-4f
    private val reference = 0.5f
    private val yearMs = (365.25 * 24 * 3600 * 1000).toLong()

    @Nested
    @DisplayName("returns zero")
    inner class ZeroCases {
        @Test
        fun `when weight is zero`() {
            assertEquals(0f, TemporalDistance.penalty(0L, yearMs, weight = 0f, reference = reference))
        }

        @Test
        fun `when weight is negative`() {
            assertEquals(0f, TemporalDistance.penalty(0L, yearMs, weight = -1f, reference = reference))
        }

        @Test
        fun `when either timestamp is null`() {
            assertEquals(0f, TemporalDistance.penalty(null, yearMs, weight = 1f, reference = reference))
            assertEquals(0f, TemporalDistance.penalty(yearMs, null, weight = 1f, reference = reference))
        }

        @Test
        fun `when either timestamp is zero or negative`() {
            assertEquals(0f, TemporalDistance.penalty(0L, yearMs, weight = 1f, reference = reference))
            assertEquals(0f, TemporalDistance.penalty(yearMs, -5L, weight = 1f, reference = reference))
        }

        @Test
        fun `when both timestamps are identical`() {
            val t = 10 * yearMs
            assertEquals(0f, TemporalDistance.penalty(t, t, weight = 1f, reference = reference), tolerance)
        }
    }

    @Test
    @DisplayName("at the half-life the penalty is about half the reference")
    fun halfLifePenalty() {
        // HALF_LIFE_YEARS = 3.0 → at Δt = 3 years, penalty ≈ reference/2
        val a = 0L + yearMs // avoid the 0 guard
        val b = a + 3 * yearMs
        val p = TemporalDistance.penalty(a, b, weight = 1f, reference = reference)
        assertEquals(reference / 2f, p, 0.02f)
    }

    @Test
    @DisplayName("penalty grows monotonically with time distance")
    fun monotonicIncrease() {
        val base = yearMs
        val p1 = TemporalDistance.penalty(base, base + yearMs, 1f, reference)
        val p3 = TemporalDistance.penalty(base, base + 3 * yearMs, 1f, reference)
        val p10 = TemporalDistance.penalty(base, base + 10 * yearMs, 1f, reference)
        assertTrue(p1 < p3, "1yr ($p1) should be < 3yr ($p3)")
        assertTrue(p3 < p10, "3yr ($p3) should be < 10yr ($p10)")
    }

    @Test
    @DisplayName("penalty is symmetric in time direction")
    fun symmetric() {
        val a = yearMs
        val b = a + 5 * yearMs
        val forward = TemporalDistance.penalty(a, b, 1f, reference)
        val backward = TemporalDistance.penalty(b, a, 1f, reference)
        assertEquals(forward, backward, tolerance)
    }

    @Test
    @DisplayName("penalty never exceeds the reference")
    fun cappedAtReference() {
        val a = yearMs
        val b = a + 100 * yearMs // extreme distance
        val p = TemporalDistance.penalty(a, b, weight = 5f, reference = reference)
        assertTrue(p <= reference, "penalty $p must not exceed reference $reference")
    }

    @ParameterizedTest(name = "higher weight {0} gives higher or equal penalty")
    @ValueSource(floats = [0.5f, 1.0f, 2.0f, 4.0f])
    fun higherWeightHigherPenalty(weight: Float) {
        val a = yearMs
        val b = a + 2 * yearMs
        val p = TemporalDistance.penalty(a, b, weight, reference)
        val pBase = TemporalDistance.penalty(a, b, 1f, reference)
        if (weight >= 1f) {
            assertTrue(p >= pBase - tolerance, "weight $weight penalty $p vs base $pBase")
        }
        assertTrue(p <= reference)
    }
}
