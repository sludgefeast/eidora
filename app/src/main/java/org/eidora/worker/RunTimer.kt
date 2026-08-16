// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.worker

import org.eidora.util.EidoraLog
import java.util.UUID

/**
 * Times one worker run for the log: total wall-clock time, time spent paused
 * (split into manual pauses and PowerGate pauses), and the resulting net
 * processing time. Each run gets a short UUID so its start/pause/end lines can
 * be told apart from other runs and from interleaved workers in the log.
 *
 * Usage: create at the start of doWork, call [pauseStarted]/[pauseEnded] around
 * each pause (the notifier loop already knows when the gate blocks), and call
 * [finish] in a finally block with how many items were processed.
 *
 * Not thread-safe; drive it from the single notifier/collector coroutine.
 */
class RunTimer(
    private val tag: String,
    private val label: String,
) {
    /** Short run id (first 8 chars of a UUID) — enough to disambiguate in a log. */
    val runId: String = UUID.randomUUID().toString().take(8)

    private val startedAtMs = System.currentTimeMillis()
    private var manualPausedMs = 0L
    private var powerPausedMs = 0L

    private var pauseStartedAtMs = 0L
    private var pauseIsManual = false

    init {
        EidoraLog.i(tag, "[$runId] $label started")
    }

    /** Mark the beginning of a pause. [manual] true = user pause, false = PowerGate. */
    fun pauseStarted(manual: Boolean) {
        if (pauseStartedAtMs != 0L) {
            // Pause type changed (e.g. manual pause released but power still
            // blocks): bank what we have so far and continue timing.
            bankCurrentPause()
        }
        pauseStartedAtMs = System.currentTimeMillis()
        pauseIsManual = manual
    }

    /** Mark the end of the current pause (if any). */
    fun pauseEnded() {
        if (pauseStartedAtMs == 0L) return
        bankCurrentPause()
        pauseStartedAtMs = 0L
    }

    private fun bankCurrentPause() {
        val elapsed = System.currentTimeMillis() - pauseStartedAtMs
        if (elapsed <= 0) return
        if (pauseIsManual) manualPausedMs += elapsed else powerPausedMs += elapsed
    }

    /** Log the summary line. Call once, in a finally block. */
    fun finish(itemsProcessed: Int) {
        pauseEnded() // close any pause still open
        val totalMs = System.currentTimeMillis() - startedAtMs
        val pausedMs = manualPausedMs + powerPausedMs
        val netMs = (totalMs - pausedMs).coerceAtLeast(0)
        EidoraLog.i(
            tag,
            "[$runId] $label finished: $itemsProcessed items in ${fmt(netMs)} net " +
                "(total ${fmt(totalMs)}, paused ${fmt(pausedMs)} = " +
                "manual ${fmt(manualPausedMs)} + power ${fmt(powerPausedMs)})",
        )
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return when {
            h > 0 -> "%dh%02dm%02ds".format(h, m, sec)
            m > 0 -> "%dm%02ds".format(m, sec)
            else -> "%ds".format(sec)
        }
    }
}
