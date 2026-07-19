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

    fun evaluate(
        status: PowerStatus,
        minBatteryPercent: Int,
        maxBatteryTempCelsius: Float,
    ): PowerGateResult {
        if (status.batteryPercent in 0 until minBatteryPercent) {
            return PowerGateResult.Blocked(
                context.getString(org.eidora.R.string.powergate_battery_low, minBatteryPercent, status.batteryPercent),
            )
        }
        if (status.batteryTempCelsius > 0f && status.batteryTempCelsius > maxBatteryTempCelsius) {
            return PowerGateResult.Blocked(
                context.getString(
                    org.eidora.R.string.powergate_battery_hot,
                    "%.1f".format(status.batteryTempCelsius),
                    "%.1f".format(maxBatteryTempCelsius),
                ),
            )
        }
        if (status.thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
            return PowerGateResult.Blocked(context.getString(org.eidora.R.string.powergate_thermal))
        }
        return PowerGateResult.Ok
    }

    /**
     * Suspends until the gate is open again. Calls [onWait] each check
     * with the block reason so the caller can update its notification.
     */
    suspend fun awaitOk(
        minBatteryPercent: Int,
        maxBatteryTempCelsius: Float,
        isStopped: () -> Boolean = { false },
        onWait: suspend (String) -> Unit,
    ) {
        while (true) {
            if (isStopped()) return
            // Manual pause takes precedence over power conditions
            if (PauseState.isPaused(context)) {
                onWait(context.getString(org.eidora.R.string.powergate_paused))
                delay(CHECK_INTERVAL_MS)
                continue
            }
            val status = currentStatus()
            val result = evaluate(status, minBatteryPercent, maxBatteryTempCelsius)
            if (result is PowerGateResult.Ok) return
            val reason = (result as PowerGateResult.Blocked).reason
            Log.i(TAG, reason)
            onWait(reason)
            delay(CHECK_INTERVAL_MS)
        }
    }
}
