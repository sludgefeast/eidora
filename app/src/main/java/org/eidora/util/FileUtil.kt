// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.util

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

object FileUtil {
    /**
     * Reads EXIF DateTimeOriginal and returns epoch millis, or null if unavailable.
     */
    fun readTakenAt(file: File): Long? {
        return try {
            val exif = ExifInterface(file.absolutePath)
            val dateStr = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: return null
            val sdf = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
            sdf.parse(dateStr)?.time
        } catch (e: Exception) {
            EidoraLog.d("FileUtil", "fallback after error: ${e.message}")
            null
        }
    }
}
