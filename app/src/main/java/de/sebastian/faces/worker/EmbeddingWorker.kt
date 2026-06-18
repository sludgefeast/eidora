package de.sebastian.faces.worker

import android.content.Context
import androidx.work.*
import de.sebastian.faces.data.db.FacesDatabase
import de.sebastian.faces.ml.FaceNetModel
import de.sebastian.faces.util.ThumbnailHelper
import de.sebastian.faces.util.toFaceRegionCoords
import java.io.File

class EmbeddingWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val db = FacesDatabase.getInstance(applicationContext)
        val faceDao = db.faceRegionDao()
        val photoDao = db.photoDao()

        val model = FaceNetModel(applicationContext)
        return try {
            val pending = faceDao.findWithoutEmbedding()
            pending.forEachIndexed { index, face ->
                setProgress(workDataOf(
                    PhotoSyncWorker.KEY_PROGRESS to ((index + 1) * 100) / pending.size,
                    PhotoSyncWorker.KEY_STATUS to "Computing embeddings…"
                ))

                val photo = photoDao.findById(face.photoId) ?: return@forEachIndexed
                val photoFile = File(photo.path)
                if (!photoFile.exists()) return@forEachIndexed

                val coords = face.regionJson.toFaceRegionCoords()
                val bitmap = ThumbnailHelper.cropForEmbedding(photoFile, coords) ?: return@forEachIndexed

                val embedding = model.computeEmbedding(bitmap)
                bitmap.recycle()

                val bytes = FaceNetModel.floatArrayToBytes(embedding)
                faceDao.updateEmbedding(face.id, bytes)
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        } finally {
            model.close()
        }
    }

    companion object {
        const val WORK_NAME = "embedding"

        fun buildRequest(): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<EmbeddingWorker>().build()
    }
}
