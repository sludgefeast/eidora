// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.worker

import android.content.Context
import org.eidora.R

/**
 * Smoothed remaining-time estimator based on an exponential moving average
 * (EMA) of the time spent per item, rather than the overall average.
 *
 * Why EMA: a plain total-average is dominated by the whole history, so once an
 * early stretch is unusually fast or slow the estimate stays skewed for a long
 * time. An EMA weights recent items more, so the ETA tracks the current
 * throughput and settles quickly after a speed change.
 *
 * Warm-up: the first [warmup] completed items are not measured (only counted).
 * Startup is atypical — model init, parallel tasks spinning up, JIT — so
 * measuring it would poison the first estimate. The clock effectively starts
 * once processing has settled.
 *
 * Pauses: call [addPaused] with time spent blocked (PowerGate); it is excluded
 * so the estimate reflects real processing speed, not wall-clock time.
 *
 * Not thread-safe; the notifier ticks it from a single coroutine.
 */
class EtaEstimator(
    private val warmup: Int = 8,
    private val alpha: Double = 0.15,
) {
    private var lastDone = 0
    private var lastTimeMs = 0L
    private var emaPerItemMs = 0.0
    private var haveEma = false
    private var pausedSinceLastMs = 0L

    /** Accumulate time (ms) spent blocked since the last update. */
    fun addPaused(ms: Long) {
        if (ms > 0) pausedSinceLastMs += ms
    }

    /**
     * Feed the current progress. Call on every notifier tick; it only updates
     * the average when [done] has advanced. [nowMs] is the current time.
     */
    fun update(
        done: Int,
        nowMs: Long,
    ) {
        if (lastTimeMs == 0L) {
            lastTimeMs = nowMs
            lastDone = done
            return
        }
        val deltaItems = done - lastDone
        if (deltaItems <= 0) return

        val working = (nowMs - lastTimeMs) - pausedSinceLastMs
        pausedSinceLastMs = 0
        lastTimeMs = nowMs
        lastDone = done
        if (working <= 0) return

        // Skip the warm-up window: count items but don't measure them.
        if (done <= warmup) return

        val perItem = working.toDouble() / deltaItems
        emaPerItemMs =
            if (!haveEma) {
                haveEma = true
                perItem
            } else {
                alpha * perItem + (1 - alpha) * emaPerItemMs
            }
    }

    /** Estimated remaining milliseconds, or null while warming up. */
    fun remainingMillis(
        done: Int,
        total: Int,
    ): Long? {
        if (!haveEma || done >= total) return null
        return ((total - done) * emaPerItemMs).toLong()
    }
}

/**
 * Formats a remaining-time string from an [EtaEstimator]. Returns an empty
 * string until the estimator has a stable estimate.
 */
fun formatEtaFrom(
    context: Context,
    estimator: EtaEstimator,
    done: Int,
    total: Int,
): String {
    val remainingMs = estimator.remainingMillis(done, total) ?: return ""
    return context.getString(R.string.notif_eta_left, formatEtaDuration(remainingMs))
}

private fun formatEtaDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h > 0 -> "%dh %dm".format(h, m)
        m > 0 -> "%dm %ds".format(m, s)
        else -> "%ds".format(s)
    }
}
