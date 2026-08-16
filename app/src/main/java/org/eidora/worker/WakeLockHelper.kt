// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.worker

import android.content.Context
import android.os.PowerManager
import org.eidora.util.EidoraLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Runs [block] while holding a PARTIAL_WAKE_LOCK, so CPU-bound background work
 * (ML detection, embedding) keeps making progress when the screen is off and
 * the device would otherwise doze. The foreground-service notification keeps the
 * process alive, but only a wake lock keeps the CPU scheduled — without it the
 * work silently stalls until the app returns to the foreground.
 *
 * The lock is acquired with a short timeout and renewed periodically by a helper
 * coroutine for as long as [block] runs. This way the work can run for hours
 * (thousands of photos) while the lock is never held more than one renewal
 * interval beyond the process actually working — if the process is killed, the
 * OS frees the lock within that interval instead of holding it indefinitely.
 *
 * The lock is always released in a finally block, even on error or cancellation.
 * PowerGate still pauses on low battery / high temperature independently, so the
 * wake lock doesn't override the user's power limits — it only prevents doze
 * from freezing work that is supposed to be running.
 */
suspend fun <T> withWakeLock(
    context: Context,
    tag: String,
    block: suspend () -> T,
): T {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val wakeLock =
        powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Eidora:$tag")

    // Renew a bit more often than the timeout, so the lock never lapses while
    // work is ongoing but is auto-freed by the OS shortly after work stops.
    val renewScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val renewJob =
        renewScope.launch {
            while (isActive) {
                try {
                    wakeLock.acquire(RENEW_TIMEOUT_MS)
                } catch (t: Throwable) {
                    EidoraLog.w(tag, "Wake lock acquire failed", t)
                }
                delay(RENEW_INTERVAL_MS)
            }
        }
    EidoraLog.i(tag, "Wake lock acquired")

    try {
        return block()
    } finally {
        renewJob.cancel()
        // Release every hold this call stacked up (each timed acquire adds one).
        try {
            while (wakeLock.isHeld) {
                wakeLock.release()
            }
            EidoraLog.i(tag, "Wake lock released")
        } catch (t: Throwable) {
            EidoraLog.w(tag, "Wake lock release failed", t)
        }
    }
}

// Lock is granted for 5 minutes and renewed every 4, so there is always a
// minute of overlap and the lock never lapses mid-work.
private const val RENEW_TIMEOUT_MS = 5 * 60 * 1000L
private const val RENEW_INTERVAL_MS = 4 * 60 * 1000L
