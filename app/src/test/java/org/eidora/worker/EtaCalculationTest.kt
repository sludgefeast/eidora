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

@DisplayName("PhotoSyncWorker.remainingMillis")
class EtaCalculationTest {
    private val minute = 60_000L

    @Nested
    @DisplayName("returns null")
    inner class NoEstimate {
        @Test
        fun `before enough items are done`() {
            assertNull(PhotoSyncWorker.remainingMillis(minute, 0, done = 4, total = 100))
        }

        @Test
        fun `when all items are done`() {
            assertNull(PhotoSyncWorker.remainingMillis(minute, 0, done = 100, total = 100))
        }

        @Test
        fun `when paused time exceeds elapsed time`() {
            assertNull(PhotoSyncWorker.remainingMillis(minute, 2 * minute, done = 10, total = 100))
        }
    }

    @Test
    @DisplayName("extrapolates from the per-item average")
    fun basicEstimate() {
        // 10 of 100 done in 10 minutes -> 1 min/item -> 90 min remaining
        val remaining = PhotoSyncWorker.remainingMillis(10 * minute, 0, done = 10, total = 100)
        assertEquals(90 * minute, remaining)
    }

    @Test
    @DisplayName("excludes paused time from the average")
    fun pausedTimeExcluded() {
        // 30 of 100 done. 90 min wall-clock, but 60 of those were spent blocked
        // by the PowerGate -> 30 min of actual work -> 1 min/item -> 70 min left.
        val remaining =
            PhotoSyncWorker.remainingMillis(
                elapsedMs = 90 * minute,
                pausedMs = 60 * minute,
                done = 30,
                total = 100,
            )
        assertEquals(70 * minute, remaining)
    }

    @Test
    @DisplayName("a long pause does not inflate the estimate")
    fun pauseDoesNotInflate() {
        // Same progress, same real work time; only the pause differs.
        val withoutPause =
            PhotoSyncWorker.remainingMillis(30 * minute, 0, done = 30, total = 100)
        val withPause =
            PhotoSyncWorker.remainingMillis(90 * minute, 60 * minute, done = 30, total = 100)
        assertEquals(withoutPause, withPause)
    }

    @Test
    @DisplayName("estimate shrinks as more items complete")
    fun shrinksWithProgress() {
        val early = PhotoSyncWorker.remainingMillis(10 * minute, 0, done = 10, total = 100)!!
        val later = PhotoSyncWorker.remainingMillis(50 * minute, 0, done = 50, total = 100)!!
        assertTrue(later < early, "later estimate ($later) should be below early ($early)")
    }

    @Test
    @DisplayName("ignoring the pause would have kept the estimate high")
    fun regressionAgainstOldBehaviour() {
        // The old formula divided wall-clock time by done, so after a long
        // pause the ETA stayed roughly at its pre-pause value.
        val corrected =
            PhotoSyncWorker.remainingMillis(90 * minute, 60 * minute, done = 30, total = 100)!!
        val uncorrected =
            PhotoSyncWorker.remainingMillis(90 * minute, 0, done = 30, total = 100)!!
        assertNotNull(corrected)
        assertTrue(
            corrected < uncorrected,
            "corrected ($corrected) must be below uncorrected ($uncorrected)",
        )
    }
}
