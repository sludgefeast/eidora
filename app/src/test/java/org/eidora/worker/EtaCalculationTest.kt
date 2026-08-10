// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.worker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("EtaEstimator")
class EtaCalculationTest {
    private val minute = 60_000L

    // Helper: feed the estimator a steady stream of items, each taking
    // [perItemMs], starting at t0. Returns the estimator positioned at [done].
    private fun steady(
        estimator: EtaEstimator,
        done: Int,
        perItemMs: Long,
        startMs: Long = 0L,
    ): EtaEstimator {
        var now = startMs
        // Prime at 0 items.
        estimator.update(0, now)
        for (i in 1..done) {
            now += perItemMs
            estimator.update(i, now)
        }
        return estimator
    }

    @Nested
    @DisplayName("returns null")
    inner class NoEstimate {
        @Test
        fun `while still in the warm-up window`() {
            // warmup = 5: items within the window are counted but not measured,
            // so there is no estimate yet.
            val est = EtaEstimator(warmup = 5)
            steady(est, done = 4, perItemMs = minute)
            assertNull(est.remainingMillis(done = 4, total = 100))
        }

        @Test
        fun `when all items are done`() {
            val est = EtaEstimator(warmup = 2)
            steady(est, done = 100, perItemMs = minute)
            assertNull(est.remainingMillis(done = 100, total = 100))
        }
    }

    @Test
    @DisplayName("extrapolates from the per-item time")
    fun basicEstimate() {
        // Steady 1 min/item. After warm-up, 10 of 100 done -> 90 min remaining.
        val est = EtaEstimator(warmup = 2)
        steady(est, done = 10, perItemMs = minute)
        val remaining = est.remainingMillis(done = 10, total = 100)
        assertNotNull(remaining)
        // Allow a small tolerance: the EMA converges to the steady rate.
        assertEquals(90.0, remaining!!.toDouble() / minute, 0.001)
    }

    @Test
    @DisplayName("excludes paused time from the estimate")
    fun pausedTimeExcluded() {
        // Two estimators at the same real work pace (1 min/item). One also spends
        // 60 min blocked, reported via addPaused; the estimates must match.
        val noPause = EtaEstimator(warmup = 2)
        steady(noPause, done = 30, perItemMs = minute)

        val withPause = EtaEstimator(warmup = 2)
        var now = 0L
        withPause.update(0, now)
        for (i in 1..30) {
            now += minute
            // On item 15, a 60-minute PowerGate pause happens before processing.
            if (i == 15) {
                withPause.addPaused(60 * minute)
                now += 60 * minute
            }
            withPause.update(i, now)
        }

        val a = noPause.remainingMillis(done = 30, total = 100)!!
        val b = withPause.remainingMillis(done = 30, total = 100)!!
        // Same underlying rate -> estimates within a tight tolerance.
        assertEquals(a.toDouble() / minute, b.toDouble() / minute, 0.5)
    }

    @Test
    @DisplayName("estimate shrinks as more items complete")
    fun shrinksWithProgress() {
        val est = EtaEstimator(warmup = 2)
        steady(est, done = 10, perItemMs = minute)
        val early = est.remainingMillis(done = 10, total = 100)!!
        // Continue to 50 done at the same pace.
        var now = 10 * minute
        for (i in 11..50) {
            now += minute
            est.update(i, now)
        }
        val later = est.remainingMillis(done = 50, total = 100)!!
        assertTrue(later < early, "later estimate ($later) should be below early ($early)")
    }

    @Test
    @DisplayName("adapts to a speed change (EMA)")
    fun adaptsToSpeedChange() {
        // Run fast (30 s/item) for a while, then slow down (2 min/item). The EMA
        // should move the estimate up toward the new, slower rate rather than
        // staying anchored to the fast start like a whole-run average would.
        val est = EtaEstimator(warmup = 2)
        var now = 0L
        est.update(0, now)
        for (i in 1..20) {
            now += 30_000L
            est.update(i, now)
        }
        val afterFast = est.remainingMillis(done = 20, total = 100)!!

        for (i in 21..40) {
            now += 2 * minute
            est.update(i, now)
        }
        val afterSlow = est.remainingMillis(done = 40, total = 100)!!

        // Per remaining item is now much larger, so despite fewer items left the
        // estimate should have risen well above the fast-phase figure.
        assertTrue(
            afterSlow > afterFast,
            "estimate after slowdown ($afterSlow) should exceed fast-phase ($afterFast)",
        )
    }
}
