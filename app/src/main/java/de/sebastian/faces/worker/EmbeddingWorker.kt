package de.sebastian.faces.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import de.sebastian.faces.data.db.DatabaseProvider
import de.sebastian.faces.data.db.FaceRegionEntity
import de.sebastian.faces.ml.FaceNetModel
import de.sebastian.faces.util.ThumbnailHelper
import de.sebastian.faces.util.toFaceRegionCoords
import java.io.File

private const val TAG = "EmbeddingWorker"

class EmbeddingWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val db = DatabaseProvider.getInstance(applicationContext)
        val faceDao = db.faceRegionDao()
        val photoDao = db.photoDao()

        val model = try {
            FaceNetModel(applicationContext)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize FaceNet model", t)
            return Result.failure()
        }

        return try {
            val pending: List<FaceRegionEntity> = faceDao.findWithoutEmbedding()
            pending.forEachIndexed { index, face ->
                setProgress(workDataOf(
                    PhotoSyncWorker.KEY_PROGRESS to ((index + 1) * 100) / pending.size,
                    PhotoSyncWorker.KEY_STATUS to "Computing embeddings…"
                ))
                try {
                    val photo = photoDao.findById(face.photoId) ?: return@forEachIndexed
                    val photoFile = File(photo.path)
                    if (!photoFile.exists()) return@forEachIndexed

                    val coords = face.regionJson.toFaceRegionCoords()
                    val bitmap = ThumbnailHelper.cropForEmbedding(photoFile, coords) ?: return@forEachIndexed

                    val embedding = model.computeEmbedding(bitmap)
                    bitmap.recycle()
                    faceDao.updateEmbedding(face.id, FaceNetModel.floatArrayToBytes(embedding))
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to compute embedding for face ${face.id}, skipping", t)
                }
            }
            Result.success()
        } catch (t: Throwable) {
            Log.e(TAG, "Unhandled error in EmbeddingWorker", t)
            Result.failure()
        } finally {
            try { model.close() } catch (t: Throwable) { Log.w(TAG, "Error closing model", t) }
        }
    }

    companion object {
        fun buildRequest(): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<EmbeddingWorker>().build()
    }
}
