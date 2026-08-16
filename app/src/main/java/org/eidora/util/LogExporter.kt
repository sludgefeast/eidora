// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.util

import android.content.Context
import android.os.Build
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Collects Eidora's own logcat output for a bug report.
 *
 * Filters by the app's own process id, so the export contains everything this
 * app logs — any tag, plus crashes and uncaught exceptions — while never
 * including other apps' output. This is more complete than a hand-maintained
 * tag list (a newly added tag can't be forgotten) and keeps unrelated, possibly
 * private information out.
 *
 * Note that Android keeps only a limited ring buffer (a few hundred KB per
 * buffer). On a busy device that can be as little as the last few minutes,
 * so requesting a long window does not guarantee it is actually available.
 * Also, --pid matches the current process only: if the app was restarted
 * (e.g. after a crash), logs from the previous process are not included.
 */
object LogExporter {
    /**
     * Log tag of the per-process startup marker emitted by
     * EidoraApplication.onCreate. The export uses it to discover every Eidora
     * PID in the log buffer. Must match the tag used there.
     */
    const val MARKER_TAG = "EidoraStart"

    /** How far back to collect. */
    enum class Range(
        val hours: Int?,
    ) {
        LAST_HOUR(1),
        LAST_DAY(24),
        EVERYTHING(null),
    }

    /**
     * Reads the matching log lines and returns them as a single string,
     * prefixed with a short device/app header that helps when reading a report.
     */
    fun collect(
        context: Context,
        range: Range,
    ): String {
        val header = buildHeader(context, range)
        val body =
            try {
                readLogcat(context, range)
            } catch (t: Throwable) {
                "Failed to read logcat: ${t.message}"
            }
        // Append Eidora's own persisted log (survives logcat eviction). logcat
        // above still carries framework messages and crashes; this section
        // guarantees the app's own diagnostics are present regardless of buffer
        // pressure. Two sources, one exported file.
        val persisted =
            try {
                EidoraLog.readPersisted()
            } catch (t: Throwable) {
                ""
            }
        val persistedSection =
            if (persisted.isBlank()) {
                ""
            } else {
                "\n\n" +
                    "============================================================\n" +
                    "Eidora persistent log (survives logcat eviction)\n" +
                    "============================================================\n" +
                    persisted
            }
        return header + "\n" + body + persistedSection
    }

    private fun buildHeader(
        context: Context,
        range: Range,
    ): String {
        val versionName =
            try {
                context.packageManager
                    .getPackageInfo(context.packageName, 0)
                    .versionName
            } catch (t: Throwable) {
                "unknown"
            }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return buildString {
            appendLine("Eidora log export")
            appendLine("Exported:  $stamp")
            appendLine("App:       $versionName")
            appendLine("Device:    ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android:   ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Range:     ${range.hours?.let { "last $it h" } ?: "everything available"}")
            appendLine("Filter:    all Eidora processes (via startup marker)")
            appendLine("-".repeat(60))
        }
    }

    private fun readLogcat(
        context: Context,
        range: Range,
    ): String {
        // Read the main, system and crash buffers (not just the default main
        // one), so framework messages and crashes about our process are captured
        // too. -d dumps and exits; threadtime gives the PID column we filter on.
        val command = mutableListOf("logcat", "-d", "-b", "main,system,crash", "-v", "threadtime")
        range.hours?.let { hours ->
            val since = Date(System.currentTimeMillis() - hours * 3600_000L)
            command += listOf("-t", SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(since))
        }
        val raw = runLogcat(command)
        if (raw.isBlank()) return "(no matching log entries in this range)"

        // Figure out which PIDs belong to this app. Two independent signals, so a
        // process is found even when one of them is missing:
        //   1. The startup marker (EidoraApplication.onCreate → MARKER_TAG) names
        //      its own PID — reliable, but rolls out of the ring buffer on a
        //      long-running session.
        //   2. Any line mentioning our package name (crash headers, process
        //      starts, ANRs, framework messages) — survives even when the marker
        //      is gone, which was why long clustering runs exported no worker
        //      lines: the marker had aged out and only the marker was checked.
        // The current PID is always included too.
        //
        // We filter in Kotlin rather than with logcat --pid because logcat only
        // honours a single --pid, so several processes can't be requested at once.
        val pkg = context.packageName
        val ourPids = mutableSetOf(android.os.Process.myPid())
        raw.lineSequence().forEach { line ->
            if (line.contains(MARKER_TAG) || line.contains(pkg)) {
                pidOf(line)?.let { ourPids += it }
            }
        }

        val filtered =
            raw
                .lineSequence()
                .filter { line ->
                    val pid = pidOf(line)
                    // Keep our processes' lines; drop lines we can't attribute.
                    pid != null && pid in ourPids
                }.joinToString("\n")

        return filtered.ifBlank { "(no matching log entries in this range)" }
    }

    /**
     * Extracts the PID from a threadtime-format logcat line:
     * "MM-DD HH:MM:SS.mmm  PID  TID  LEVEL TAG: msg" — PID is the third
     * whitespace-separated column. Returns null for lines that don't match
     * (e.g. buffer separators like "--------- beginning of main").
     */
    private fun pidOf(line: String): Int? {
        val cols = line.trimStart().split(Regex("\\s+"))
        // cols[0]=date, cols[1]=time, cols[2]=PID
        return cols.getOrNull(2)?.toIntOrNull()
    }

    private fun runLogcat(command: List<String>): String {
        val process = Runtime.getRuntime().exec(command.toTypedArray())
        val output =
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                reader.readText()
            }
        process.waitFor()
        return output
    }

    /** Suggested file name for the export, e.g. "eidora-log-2026-07-22-1403.txt". */
    fun suggestedFileName(): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date())
        return "eidora-log-$stamp.txt"
    }
}
