// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Drop-in replacement for [android.util.Log] that writes every entry to logcat
 * *and* to a small rotating file, so Eidora's own logs survive even when the
 * logcat ring buffer has evicted them (e.g. after a long clustering run). The
 * file is merged into the export by [LogExporter], giving one combined log.
 *
 * logcat stays the source for framework messages and crashes (which never reach
 * this file); the rotating file guarantees the app's own diagnostics persist.
 *
 * Call [init] once from Application.onCreate. Before init, calls still reach
 * logcat; only the file side is skipped.
 *
 * Signatures mirror android.util.Log so existing call sites need only swap the
 * class name.
 */
object EidoraLog {
    // Two files rotated: when the active file exceeds MAX_BYTES it becomes the
    // ".1" backup and a fresh active file starts. Total on-disk ≈ 2 × MAX_BYTES.
    private const val MAX_BYTES = 4L * 1024 * 1024
    private const val FILE_NAME = "eidora-log.txt"
    private const val BACKUP_NAME = "eidora-log.1.txt"

    private val lock = Any()
    private var logDir: File? = null
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** Wire up the file side. Safe to call more than once. */
    fun init(context: Context) {
        synchronized(lock) {
            if (logDir == null) logDir = context.filesDir
        }
    }

    fun i(
        tag: String,
        msg: String,
    ) {
        Log.i(tag, msg)
        write("I", tag, msg, null)
    }

    fun w(
        tag: String,
        msg: String,
        tr: Throwable? = null,
    ) {
        if (tr != null) Log.w(tag, msg, tr) else Log.w(tag, msg)
        write("W", tag, msg, tr)
    }

    fun e(
        tag: String,
        msg: String,
        tr: Throwable? = null,
    ) {
        if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
        write("E", tag, msg, tr)
    }

    fun d(
        tag: String,
        msg: String,
    ) {
        Log.d(tag, msg)
        // Debug lines are intentionally NOT persisted: they are the high-volume,
        // low-value lines (e.g. per-item traces) that would churn the file.
    }

    /** Returns the persisted log text (active + backup, oldest first), or "". */
    fun readPersisted(): String {
        synchronized(lock) {
            val dir = logDir ?: return ""
            val active = File(dir, FILE_NAME)
            val backup = File(dir, BACKUP_NAME)
            val sb = StringBuilder()
            if (backup.exists()) sb.append(backup.readText())
            if (active.exists()) sb.append(active.readText())
            return sb.toString()
        }
    }

    private fun write(
        level: String,
        tag: String,
        msg: String,
        tr: Throwable?,
    ) {
        val dir = logDir ?: return
        synchronized(lock) {
            try {
                val active = File(dir, FILE_NAME)
                if (active.exists() && active.length() > MAX_BYTES) {
                    val backup = File(dir, BACKUP_NAME)
                    if (backup.exists()) backup.delete()
                    active.renameTo(backup)
                }
                val line = buildString {
                    append(timeFmt.format(Date()))
                    append(' ').append(level)
                    append(' ').append(tag).append(": ").append(msg)
                    append('\n')
                    if (tr != null) append(Log.getStackTraceString(tr)).append('\n')
                }
                active.appendText(line)
            } catch (t: Throwable) {
                // Never let logging break the app; logcat already has the line.
            }
        }
    }
}
