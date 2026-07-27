// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.InputStream

object BitmapLoader {
    /**
     * Loads a bitmap from a JPEG file, applies EXIF orientation, and
     * downscales so the longer edge is at most [maxSize] pixels.
     * Returns null if decoding fails.
     */
    fun loadOrientedBitmap(
        file: File,
        maxSize: Int = 1024,
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val longer = maxOf(bounds.outWidth, bounds.outHeight)
        var sampleSize = 1
        while (longer / sampleSize > maxSize * 2) sampleSize *= 2

        val decoded =
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            ) ?: return null

        val exif =
            try {
                ExifInterface(file.absolutePath)
            } catch (t: Throwable) {
                null
            }
        val orientation =
            exif?.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            ) ?: ExifInterface.ORIENTATION_NORMAL

        val matrix = orientationMatrix(orientation)

        // Final downscale to maxSize if still too big
        val scale = maxSize.toFloat() / maxOf(decoded.width, decoded.height)
        if (scale < 1f) matrix.postScale(scale, scale)

        val rotated =
            if (matrix.isIdentity) {
                decoded
            } else {
                Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            }
        if (rotated !== decoded) decoded.recycle()
        return rotated
    }

    /**
     * Loads a bitmap from an [InputStream] (e.g. a bundled asset), applying EXIF
     * orientation. The stream is read twice — once for pixels, once for EXIF —
     * so the caller must supply a factory that opens a fresh stream each time.
     * Returns null if decoding fails.
     */
    fun loadOrientedBitmap(
        openStream: () -> InputStream?,
        maxSize: Int = 1024,
    ): Bitmap? {
        val decoded =
            (openStream() ?: return null).use { input ->
                BitmapFactory.decodeStream(
                    input,
                    null,
                    BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
                )
            } ?: return null

        val orientation =
            try {
                (openStream())?.use { input ->
                    ExifInterface(input).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL,
                    )
                } ?: ExifInterface.ORIENTATION_NORMAL
            } catch (t: Throwable) {
                ExifInterface.ORIENTATION_NORMAL
            }

        val matrix = orientationMatrix(orientation)
        val scale = maxSize.toFloat() / maxOf(decoded.width, decoded.height)
        if (scale < 1f) matrix.postScale(scale, scale)

        val rotated =
            if (matrix.isIdentity) {
                decoded
            } else {
                Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            }
        if (rotated !== decoded) decoded.recycle()
        return rotated
    }

    /** Builds the transform matrix for an EXIF orientation value. */
    private fun orientationMatrix(orientation: Int): Matrix {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
        }
        return matrix
    }
}
