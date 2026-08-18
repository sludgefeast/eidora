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
import org.eidora.ml.FaceAligner
import java.io.File

object ThumbnailHelper {
    private const val THUMBNAIL_SIZE = 128
    private const val PADDING_FACTOR = 0.10f

    // Aligned faces are produced at the embedder's canonical template size
    // (both ArcFace and SFace use 112×112). computeEmbedding scales its input to
    // the model's inputSize anyway, but aligning straight to 112 keeps the
    // canonical geometry exact.
    private const val ALIGN_OUTPUT_SIZE = 112

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
                EidoraLog.d("ThumbnailHelper", "fallback after error: ${e.message}")
                ExifInterface.ORIENTATION_NORMAL
            }
        val matrix =
            Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                    ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> {
                        postRotate(90f)
                        postScale(-1f, 1f)
                    }
                    ExifInterface.ORIENTATION_TRANSVERSE -> {
                        postRotate(270f)
                        postScale(-1f, 1f)
                    }
                }
            }
        if (matrix.isIdentity) return raw
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
            EidoraLog.d("ThumbnailHelper", "fallback after error: ${e.message}")
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
     * Removes thumbnail files that have no corresponding face row. Guards
     * against leaks from paths that delete faces without deleting their
     * thumbnail (e.g. an ON DELETE CASCADE removes the DB row only). Returns
     * the number of files deleted.
     *
     * [validFaceIds] is the set of all face ids currently in the database.
     */
    fun sweepOrphans(
        context: Context,
        validFaceIds: Set<String>,
    ): Int {
        val dir = File(context.filesDir, "thumbnails")
        val files = dir.listFiles() ?: return 0
        var removed = 0
        for (file in files) {
            val id = file.nameWithoutExtension
            if (id !in validFaceIds) {
                if (file.delete()) removed++
            }
        }
        return removed
    }

    /**
     * Crops the face region WITHOUT padding, scaled square crop for the embedding model input.
     * Coords are in the rotated (visually correct) image space.
     */
    /**
     * Produces an aligned 112×112 face bitmap for embedding when landmarks are
     * present, using [FaceAligner] to warp the five landmarks onto the embedder's
     * canonical template. This is what makes embeddings of the same person at
     * different head poses land close together. Falls back to [cropForEmbedding]
     * when there are no landmarks (older regions, XMP imports) so nothing breaks;
     * such faces just keep the previous, un-aligned behaviour.
     */
    fun alignForEmbedding(
        photoFile: File,
        coords: FaceRegionCoords,
    ): Bitmap? {
        val lm = coords.landmarks
        if (lm == null || lm.size < 10) {
            // No landmarks — cannot align; fall back to the plain crop.
            return cropForEmbedding(photoFile, coords)
        }
        return try {
            val original = loadRotatedBitmap(photoFile) ?: return null
            val imgW = original.width.toFloat()
            val imgH = original.height.toFloat()
            // Landmarks are stored normalized to the (already-rotated) image, in
            // the same orientation as loadRotatedBitmap returns, so scale them
            // straight to pixels.
            val lmPixels =
                FloatArray(10) { idx ->
                    if (idx % 2 == 0) lm[idx] * imgW else lm[idx] * imgH
                }
            val aligned = FaceAligner.align(original, lmPixels, ALIGN_OUTPUT_SIZE)
            original.recycle()
            // If alignment failed (degenerate landmarks), fall back to the crop.
            aligned ?: cropForEmbedding(photoFile, coords)
        } catch (e: Exception) {
            EidoraLog.d("ThumbnailHelper", "fallback after error: ${e.message}")
            cropForEmbedding(photoFile, coords)
        }
    }

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
            EidoraLog.d("ThumbnailHelper", "fallback after error: ${e.message}")
            null
        }
    }
}
