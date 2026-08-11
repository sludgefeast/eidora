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
 * Only lines carrying one of Eidora's log tags are included. Logcat is a
 * system-wide buffer that also holds other apps' output, so filtering by tag
 * keeps unrelated — and potentially private — information out of the export.
 *
 * Note that Android keeps only a limited ring buffer (a few hundred KB per
 * buffer). On a busy device that can be as little as the last few minutes,
 * so requesting a long window does not guarantee it is actually available.
 */
object LogExporter {
    /** Log tags used across Eidora. Only these are exported. */
    private val TAGS =
        listOf(
            "ClusteringWorker",
            "ContainerDownloader",
            "ContainerStore",
            "ContainerValidator",
            "DetectionWorker",
            "EmbeddingModel",
            "EmbeddingWorker",
            "PhotoAnalyzer",
            "PipelineWorker",
            "PowerGate",
            "ScanWorker",
            "ScrfdDetector",
            "SelectedModelResolver",
            "SinglePhotoWorker",
            "TfliteLoader",
            "TriageWorker",
            "YuNetDetector",
            "XmpHelper",
            "XmpWriteWorker",
        )

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
                readLogcat(range)
            } catch (t: Throwable) {
                "Failed to read logcat: ${t.message}"
            }
        return header + "\n" + body
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
            appendLine("Tags:      ${TAGS.joinToString(", ")}")
            appendLine("-".repeat(60))
        }
    }

    private fun readLogcat(range: Range): String {
        val command = mutableListOf("logcat", "-d", "-v", "time")
        range.hours?.let { hours ->
            // logcat -t '<MM-DD hh:mm:ss.mmm>' prints everything since that time
            val since = Date(System.currentTimeMillis() - hours * 3600_000L)
            command += listOf("-t", SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(since))
        }
        // Restrict to Eidora's tags, silence everything else
        command += TAGS.map { "$it:V" }
        command += "*:S"

        val process = Runtime.getRuntime().exec(command.toTypedArray())
        val output =
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                reader.readText()
            }
        process.waitFor()
        return output.ifBlank { "(no matching log entries in this range)" }
    }

    /** Suggested file name for the export, e.g. "eidora-log-2026-07-22-1403.txt". */
    fun suggestedFileName(): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date())
        return "eidora-log-$stamp.txt"
    }
}
