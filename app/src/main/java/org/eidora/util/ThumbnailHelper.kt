// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import androidx.exifinterface.media.ExifInterface
import org.eidora.domain.model.FaceRegionCoords
import java.io.File

object ThumbnailHelper {
    private const val THUMBNAIL_SIZE = 128
    private const val PADDING_FACTOR = 0.10f

    fun thumbnailFile(
        context: Context,
        faceRegionId: String,
    ): File {
        val dir = File(context.filesDir, "thumbnails")
        dir.mkdirs()
        return File(dir, "$faceRegionId.webp")
    }

    /**
     * Loads a bitmap with EXIF rotation applied so pixel coordinates
     * match the visually correct orientation.
     * Uses inSampleSize to avoid decoding the full multi-megapixel image.
     * Target: we need the face crop at 160×160 px, so we only need
     * the image at ~2× the crop size for reasonable quality.
     */
    private fun loadRotatedBitmap(
        file: File,
        targetWidth: Int = 1024,
        targetHeight: Int = 1024,
    ): Bitmap? {
        // First pass: read dimensions only
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null

        // Compute sample size: largest power of 2 that keeps us above target
        var sampleSize = 1
        var w = opts.outWidth
        var h = opts.outHeight
        while (w / 2 >= targetWidth && h / 2 >= targetHeight) {
            sampleSize *= 2
            w /= 2
            h /= 2
        }

        // Second pass: decode at reduced resolution
        val decodeOpts =
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = android.graphics.Bitmap.Config.RGB_565 // 2 bytes/px vs 4
            }
        val raw = BitmapFactory.decodeFile(file.absolutePath, decodeOpts) ?: return null
        val orientation =
            try {
                ExifInterface(file.absolutePath).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            } catch (e: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }
        val degrees =
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    // flip + 90 handled below
                    90f
                }
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    // flip + 270 handled below
                    270f
                }
                else -> 0f
            }
        if (degrees == 0f) return raw
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        raw.recycle()
        return rotated
    }

    /**
     * Creates a 128x128 WebP thumbnail for the face region with 10% padding.
     * Coords are in the rotated (visually correct) image space.
     */
    fun createThumbnail(
        context: Context,
        photoFile: File,
        coords: FaceRegionCoords,
        faceRegionId: String,
    ): Boolean {
        return try {
            val original = loadRotatedBitmap(photoFile) ?: return false
            val imgW = original.width.toFloat()
            val imgH = original.height.toFloat()

            val cx = coords.x * imgW
            val cy = coords.y * imgH
            val halfW = (coords.w * imgW) / 2f
            val halfH = (coords.h * imgH) / 2f
            val padX = halfW * PADDING_FACTOR
            val padY = halfH * PADDING_FACTOR

            val rect =
                RectF(
                    (cx - halfW - padX).coerceAtLeast(0f),
                    (cy - halfH - padY).coerceAtLeast(0f),
                    (cx + halfW + padX).coerceAtMost(imgW),
                    (cy + halfH + padY).coerceAtMost(imgH),
                )

            // Make crop square to avoid distortion
            val cropSize = maxOf(rect.width(), rect.height())
            val squareLeft = ((rect.left + rect.right) / 2f - cropSize / 2f).coerceAtLeast(0f)
            val squareTop = ((rect.top + rect.bottom) / 2f - cropSize / 2f).coerceAtLeast(0f)
            val squareRight = (squareLeft + cropSize).coerceAtMost(imgW)
            val squareBottom = (squareTop + cropSize).coerceAtMost(imgH)

            val cropped =
                Bitmap.createBitmap(
                    original,
                    squareLeft.toInt(),
                    squareTop.toInt(),
                    (squareRight - squareLeft).toInt(),
                    (squareBottom - squareTop).toInt(),
                )
            val scaled = Bitmap.createScaledBitmap(cropped, THUMBNAIL_SIZE, THUMBNAIL_SIZE, true)

            val outFile = thumbnailFile(context, faceRegionId)
            outFile.outputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, out)
            }

            cropped.recycle()
            scaled.recycle()
            original.recycle()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun deleteThumbnail(
        context: Context,
        faceRegionId: String,
    ) {
        thumbnailFile(context, faceRegionId).delete()
    }

    /**
     * Crops the face region WITHOUT padding, scaled square crop for the embedding model input.
     * Coords are in the rotated (visually correct) image space.
     */
    fun cropForEmbedding(
        photoFile: File,
        coords: FaceRegionCoords,
    ): Bitmap? {
        return try {
            val original = loadRotatedBitmap(photoFile) ?: return null
            val imgW = original.width.toFloat()
            val imgH = original.height.toFloat()

            val cx = coords.x * imgW
            val cy = coords.y * imgH
            val halfW = (coords.w * imgW) / 2f
            val halfH = (coords.h * imgH) / 2f

            val left = (cx - halfW).coerceAtLeast(0f).toInt()
            val top = (cy - halfH).coerceAtLeast(0f).toInt()
            val right = (cx + halfW).coerceAtMost(imgW).toInt()
            val bottom = (cy + halfH).coerceAtMost(imgH).toInt()

            val cropped = Bitmap.createBitmap(original, left, top, right - left, bottom - top)
            val scaled = Bitmap.createScaledBitmap(cropped, 160, 160, true)
            cropped.recycle()
            original.recycle()
            scaled
        } catch (e: Exception) {
            null
        }
    }
}
