package de.sebastian.faces.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import de.sebastian.faces.domain.model.FaceRegionCoords
import java.io.File

object ThumbnailHelper {

    private const val THUMBNAIL_SIZE = 128
    private const val PADDING_FACTOR = 0.10f

    fun thumbnailFile(context: Context, faceRegionId: String): File {
        val dir = File(context.filesDir, "thumbnails")
        dir.mkdirs()
        return File(dir, "$faceRegionId.webp")
    }

    /**
     * Creates a 128x128 WebP thumbnail for the face region with 10% padding.
     * Returns true on success.
     */
    fun createThumbnail(context: Context, photoFile: File, coords: FaceRegionCoords, faceRegionId: String): Boolean {
        return try {
            val original = BitmapFactory.decodeFile(photoFile.absolutePath) ?: return false
            val imgW = original.width.toFloat()
            val imgH = original.height.toFloat()

            // Convert normalized center coords to pixel rect with padding
            val cx = coords.x * imgW
            val cy = coords.y * imgH
            val halfW = (coords.w * imgW) / 2f
            val halfH = (coords.h * imgH) / 2f
            val padX = halfW * PADDING_FACTOR
            val padY = halfH * PADDING_FACTOR

            val rect = RectF(
                (cx - halfW - padX).coerceAtLeast(0f),
                (cy - halfH - padY).coerceAtLeast(0f),
                (cx + halfW + padX).coerceAtMost(imgW),
                (cy + halfH + padY).coerceAtMost(imgH)
            )

            val cropped = Bitmap.createBitmap(
                original,
                rect.left.toInt(), rect.top.toInt(),
                rect.width().toInt(), rect.height().toInt()
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

    fun deleteThumbnail(context: Context, faceRegionId: String) {
        thumbnailFile(context, faceRegionId).delete()
    }

    /**
     * Crops the face region WITHOUT padding, scaled to 160x160 for FaceNet embedding input.
     */
    fun cropForEmbedding(photoFile: File, coords: FaceRegionCoords): Bitmap? {
        return try {
            val original = BitmapFactory.decodeFile(photoFile.absolutePath) ?: return null
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
