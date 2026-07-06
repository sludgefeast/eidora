package de.sebastian.eidora.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import de.sebastian.eidora.data.db.DatabaseProvider
import de.sebastian.eidora.data.db.FaceRegionEntity
import de.sebastian.eidora.ml.FaceNetModel
import de.sebastian.eidora.util.ThumbnailHelper
import de.sebastian.eidora.util.toFaceRegionCoords
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "EmbeddingWorker"
private const val PARALLELISM = 3

class EmbeddingWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
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
        Log.i(TAG, "FaceNet model initialized on backend: ${model.backend}")

        return try {
            val pending: List<FaceRegionEntity> = faceDao.findWithoutEmbedding()
            val total = pending.size
            if (total == 0) return Result.success()

            val powerGate = PowerGate(applicationContext)
            val powerConfig = try {
                de.sebastian.eidora.data.settings.SettingsProvider.get(applicationContext).getPowerConfig()
            } catch (t: Throwable) {
                de.sebastian.eidora.data.settings.PowerConfig(
                    minBatteryPercent = 20,
                    maxBatteryTempCelsius = 40.0f
                )
            }

            val done = AtomicInteger(0)
            val startedAt = System.currentTimeMillis()

            val notifierScope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob()
            )
            val notifierJob = notifierScope.launch {
                while (isActive) {
                    val current = done.get()
                    val progress = if (total == 0) 0 else (current * 100) / total
                    val eta = PhotoSyncWorker.formatEta(startedAt, current, total)
                    val message = if (eta.isNotEmpty()) "$progress% – $eta" else "$progress%"
                    try {
                        setForeground(
                            NotificationHelper.embeddingForegroundInfoWithMessage(applicationContext, progress, message)
                        )
                    } catch (t: Throwable) { /* ignore */ }
                    kotlinx.coroutines.delay(500)
                }
            }

            // Producer: crop face bitmaps in parallel on IO dispatcher
            // Consumer (implicit): compute embedding via mutex-guarded interpreter,
            // then write result back to DB
            pending.asFlow()
                .flatMapMerge(concurrency = PARALLELISM) { face ->
                    flow {
                        powerGate.awaitOk(
                            powerConfig.minBatteryPercent,
                            powerConfig.maxBatteryTempCelsius
                        ) { reason ->
                            try {
                                setForegroundAsync(
                                    NotificationHelper.embeddingForegroundInfoWithMessage(applicationContext, 0, reason)
                                )
                            } catch (t: Throwable) { /* ignore */ }
                        }
                        val bitmap = try {
                            val photo = photoDao.findById(face.photoId) ?: return@flow
                            val photoFile = File(photo.path)
                            if (!photoFile.exists()) return@flow
                            val coords = face.regionJson.toFaceRegionCoords()
                            ThumbnailHelper.cropForEmbedding(photoFile, coords)
                        } catch (t: Throwable) {
                            Log.e(TAG, "Failed to prepare face ${face.id}, skipping", t); null
                        }
                        if (bitmap != null) emit(face to bitmap)
                    }.flowOn(Dispatchers.IO)
                }
                .buffer(capacity = PARALLELISM)
                .collect { (face, bitmap) ->
                    try {
                        val embedding = model.computeEmbedding(bitmap)
                        faceDao.updateEmbedding(face.id, FaceNetModel.floatArrayToBytes(embedding))
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed embedding for face ${face.id}, skipping", t)
                    } finally {
                        bitmap.recycle()
                    }
                    val current = done.incrementAndGet()
                    setProgress(workDataOf(
                        PhotoSyncWorker.KEY_PROGRESS to (current * 100) / total,
                        PhotoSyncWorker.KEY_STATUS to "Computing embeddings…"
                    ))
                }

            notifierJob.cancel()

            try {
                androidx.core.app.NotificationManagerCompat.from(applicationContext)
                    .cancel(NotificationHelper.NOTIFICATION_ID_EMBEDDING)
            } catch (t: Throwable) { /* ignore */ }

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
