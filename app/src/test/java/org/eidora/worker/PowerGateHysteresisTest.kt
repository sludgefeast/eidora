// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.worker

import org.eidora.data.settings.PowerConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("PowerGate hysteresis thresholds")
class PowerGateHysteresisTest {
    private val config =
        PowerConfig(
            minBatteryPercent = 20,
            maxBatteryTempCelsius = 40f,
            resumeBatteryPercent = 25,
            resumeBatteryTempCelsius = 35f,
        )

    @Nested
    @DisplayName("while running")
    inner class Running {
        @Test
        fun `uses the pause battery threshold`() {
            assertEquals(20, PowerGate.batteryLimitFor(config, currentlyBlocked = false))
        }

        @Test
        fun `uses the pause temperature threshold`() {
            assertEquals(40f, PowerGate.tempLimitFor(config, currentlyBlocked = false))
        }
    }

    @Nested
    @DisplayName("while blocked")
    inner class Blocked {
        @Test
        fun `uses the higher resume battery threshold`() {
            assertEquals(25, PowerGate.batteryLimitFor(config, currentlyBlocked = true))
        }

        @Test
        fun `uses the lower resume temperature threshold`() {
            assertEquals(35f, PowerGate.tempLimitFor(config, currentlyBlocked = true))
        }
    }

    @Test
    @DisplayName("the resume thresholds are stricter than the pause ones")
    fun hysteresisGapExists() {
        val pauseBattery = PowerGate.batteryLimitFor(config, currentlyBlocked = false)
        val resumeBattery = PowerGate.batteryLimitFor(config, currentlyBlocked = true)
        val pauseTemp = PowerGate.tempLimitFor(config, currentlyBlocked = false)
        val resumeTemp = PowerGate.tempLimitFor(config, currentlyBlocked = true)
        assertTrue(resumeBattery >= pauseBattery, "resume battery must not be below pause")
        assertTrue(resumeTemp <= pauseTemp, "resume temperature must not be above pause")
    }

    @Nested
    @DisplayName("misconfiguration is clamped")
    inner class Clamping {
        @Test
        fun `resume battery below pause falls back to pause`() {
            val bad = config.copy(resumeBatteryPercent = 10) // below the pause level
            assertEquals(20, PowerGate.batteryLimitFor(bad, currentlyBlocked = true))
        }

        @Test
        fun `resume temperature above pause falls back to pause`() {
            val bad = config.copy(resumeBatteryTempCelsius = 50f) // above the pause level
            assertEquals(40f, PowerGate.tempLimitFor(bad, currentlyBlocked = true))
        }
    }

    @Nested
    @DisplayName("oscillation scenario")
    inner class Oscillation {
        // The bug this feature fixes: at 40.5 °C the gate pauses; the device
        // cools to 39.9 °C and — with a single threshold — resumes instantly,
        // heats back over 40 °C within seconds, and pauses again.
        @Test
        fun `just below the pause temperature does not resume`() {
            val temperatureAfterBriefCooling = 39.9f
            val limitWhileBlocked = PowerGate.tempLimitFor(config, currentlyBlocked = true)
            assertTrue(
                temperatureAfterBriefCooling > limitWhileBlocked,
                "39.9 °C must still be above the resume limit ($limitWhileBlocked)",
            )
        }

        @Test
        fun `properly cooled device resumes`() {
            val temperatureAfterRealCooling = 34.5f
            val limitWhileBlocked = PowerGate.tempLimitFor(config, currentlyBlocked = true)
            assertTrue(
                temperatureAfterRealCooling <= limitWhileBlocked,
                "34.5 °C should be at or below the resume limit ($limitWhileBlocked)",
            )
        }
    }

    @Nested
    @DisplayName("polling backoff while blocked")
    inner class Backoff {
        @Test
        fun `first wait uses the base interval`() {
            assertEquals(5_000L, PowerGate.backoffDelayMs(1))
        }

        @Test
        fun `interval doubles with consecutive waits`() {
            assertEquals(5_000L, PowerGate.backoffDelayMs(1))
            assertEquals(10_000L, PowerGate.backoffDelayMs(2))
            assertEquals(20_000L, PowerGate.backoffDelayMs(3))
            assertEquals(40_000L, PowerGate.backoffDelayMs(4))
        }

        @Test
        fun `interval is capped at one minute`() {
            assertEquals(60_000L, PowerGate.backoffDelayMs(5))
            assertEquals(60_000L, PowerGate.backoffDelayMs(50))
            assertEquals(60_000L, PowerGate.backoffDelayMs(1000))
        }

        @Test
        fun `never returns a non-positive delay`() {
            (0..30).forEach { n ->
                assertTrue(PowerGate.backoffDelayMs(n) > 0, "delay for n=$n must be positive")
            }
        }

        @Test
        fun `a five minute pause costs far fewer polls than fixed polling`() {
            // Sum delays until five minutes of waiting have elapsed.
            var elapsed = 0L
            var polls = 0
            while (elapsed < 5 * 60_000L) {
                polls++
                elapsed += PowerGate.backoffDelayMs(polls)
            }
            // Fixed 5 s polling would need 60 wake-ups for the same period.
            assertTrue(polls < 20, "backoff should need well under 20 polls, was $polls")
        }
    }

    @Test
    @DisplayName("defaults with no gap behave like a single threshold")
    fun defaultsWithoutGap() {
        // PowerConfig's default arguments mirror the pause values, so older
        // stored configs without resume values keep the previous behaviour.
        val noGap = PowerConfig(minBatteryPercent = 20, maxBatteryTempCelsius = 40f)
        assertEquals(20, PowerGate.batteryLimitFor(noGap, currentlyBlocked = true))
        assertEquals(40f, PowerGate.tempLimitFor(noGap, currentlyBlocked = true))
    }
}
