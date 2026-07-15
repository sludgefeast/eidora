package org.eidora.util

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

object FileUtil {

    private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

    fun isJpeg(file: File): Boolean {
        if (!file.exists() || file.length() < 3) return false
        return try {
            val bytes = ByteArray(3)
            file.inputStream().use { it.read(bytes) }
            bytes[0] == JPEG_MAGIC[0] && bytes[1] == JPEG_MAGIC[1] && bytes[2] == JPEG_MAGIC[2]
        } catch (e: Exception) {
            false
        }
    }

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
            null
        }
    }

}
