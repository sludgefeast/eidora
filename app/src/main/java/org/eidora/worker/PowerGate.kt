// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.worker

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.delay

private const val TAG = "PowerGate"
private const val CHECK_INTERVAL_MS = 5000L

// Upper bound for the backoff while waiting for the gate to open. One minute
// is well within the time it takes a phone to cool by a few degrees.
private const val MAX_CHECK_INTERVAL_MS = 60_000L

data class PowerStatus(
    val batteryPercent: Int,
    val batteryTempCelsius: Float,
    val thermalStatus: Int, // PowerManager.THERMAL_STATUS_*
)

sealed class PowerGateResult {
    object Ok : PowerGateResult()

    data class Blocked(
        val reason: String,
    ) : PowerGateResult()
}

/**
 * Guards heavy work against overheating and battery drain. Reads limits
 * from SettingsRepository and blocks (with a status message) while the
 * current device state exceeds them.
 */
class PowerGate(
    private val context: Context,
) {
    fun currentStatus(): PowerStatus {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val tempTenths = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1

        val percent = if (level >= 0 && scale > 0) (level * 100) / scale else -1
        val tempC = if (tempTenths >= 0) tempTenths / 10f else -1f

        val thermal =
            try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                if (android.os.Build.VERSION.SDK_INT >= 29) pm.currentThermalStatus else 0
            } catch (t: Throwable) {
                0
            }

        return PowerStatus(percent, tempC, thermal)
    }

    /**
     * Evaluates the power conditions with hysteresis.
     *
     * [currentlyBlocked] selects which thresholds apply: while running, the
     * pause thresholds decide when to stop; while already blocked, the stricter
     * resume thresholds decide when to continue. Using one threshold for both
     * makes the gate oscillate — it would resume the instant the value crosses
     * back, then trip again moments later.
     */
    internal fun evaluate(
        status: PowerStatus,
        config: org.eidora.data.settings.PowerConfig,
        currentlyBlocked: Boolean,
    ): PowerGateResult {
        val batteryLimit = batteryLimitFor(config, currentlyBlocked)
        val tempLimit = tempLimitFor(config, currentlyBlocked)

        if (status.batteryPercent in 0 until batteryLimit) {
            return PowerGateResult.Blocked(
                context.getString(org.eidora.R.string.powergate_battery_low, batteryLimit, status.batteryPercent),
            )
        }
        if (status.batteryTempCelsius > 0f && status.batteryTempCelsius > tempLimit) {
            val fahrenheit = org.eidora.util.TemperatureUnit.useFahrenheit(context)
            return PowerGateResult.Blocked(
                context.getString(
                    org.eidora.R.string.powergate_battery_hot,
                    org.eidora.util.TemperatureUnit.format(status.batteryTempCelsius, fahrenheit),
                    org.eidora.util.TemperatureUnit.format(tempLimit, fahrenheit),
                ),
            )
        }
        if (status.thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
            return PowerGateResult.Blocked(context.getString(org.eidora.R.string.powergate_thermal))
        }
        return PowerGateResult.Ok
    }

    companion object {
        /**
         * Polling delay while the gate is closed.
         *
         * Checking every few seconds during a long pause is wasteful: nothing
         * is being processed, yet each wake-up keeps the CPU out of deep sleep
         * and — with several workers polling in parallel — measurably drains
         * the battery and warms the device we are waiting to cool down.
         *
         * The interval therefore grows from [CHECK_INTERVAL_MS] up to
         * [MAX_CHECK_INTERVAL_MS] the longer the wait lasts, which keeps the
         * gate responsive right after it closes while costing almost nothing
         * during a long cool-down.
         */
        internal fun backoffDelayMs(consecutiveWaits: Int): Long {
            val factor = 1L shl ((consecutiveWaits - 1).coerceIn(0, 5))
            return (CHECK_INTERVAL_MS * factor).coerceAtMost(MAX_CHECK_INTERVAL_MS)
        }

        /**
         * Battery threshold that applies right now: the pause level while
         * running, the (higher) resume level while blocked. Clamped so a
         * misconfigured resume value can never be stricter than the pause one.
         */
        internal fun batteryLimitFor(
            config: org.eidora.data.settings.PowerConfig,
            currentlyBlocked: Boolean,
        ): Int =
            if (currentlyBlocked) {
                config.resumeBatteryPercent.coerceAtLeast(config.minBatteryPercent)
            } else {
                config.minBatteryPercent
            }

        /**
         * Temperature threshold that applies right now: the pause level while
         * running, the (lower) resume level while blocked.
         */
        internal fun tempLimitFor(
            config: org.eidora.data.settings.PowerConfig,
            currentlyBlocked: Boolean,
        ): Float =
            if (currentlyBlocked) {
                config.resumeBatteryTempCelsius.coerceAtMost(config.maxBatteryTempCelsius)
            } else {
                config.maxBatteryTempCelsius
            }
    }

    /**
     * Suspends until the gate is open again. Calls [onWait] each check
     * with the block reason so the caller can update its notification.
     */
    suspend fun awaitOk(
        config: org.eidora.data.settings.PowerConfig,
        isStopped: () -> Boolean = { false },
        onWait: suspend (reason: String, isManual: Boolean) -> Unit,
    ) {
        // Once this call has blocked at least once, the stricter resume
        // thresholds apply until the gate opens again.
        var blocked = false
        // Consecutive blocked checks, used to back off the polling rate.
        var waits = 0
        while (true) {
            if (isStopped()) return
            // Manual pause takes precedence over power conditions
            if (PauseState.isPaused(context)) {
                onWait(context.getString(org.eidora.R.string.powergate_paused), true)
                waits++
                delay(backoffDelayMs(waits))
                continue
            }
            val status = currentStatus()
            val result = evaluate(status, config, currentlyBlocked = blocked)
            if (result is PowerGateResult.Ok) return
            blocked = true
            val reason = (result as PowerGateResult.Blocked).reason
            // Log only the first block and then occasionally - a pause can last
            // for many minutes and would otherwise flood the log.
            if (waits == 0 || waits % 20 == 0) Log.i(TAG, reason)
            onWait(reason, false)
            waits++
            delay(backoffDelayMs(waits))
        }
    }
}
