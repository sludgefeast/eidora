// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
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
import org.eidora.data.db.DatabaseProvider
import org.eidora.data.db.FaceRegionEntity
import org.eidora.ml.EmbeddingModel
import org.eidora.util.ThumbnailHelper
import org.eidora.util.toFaceRegionCoords
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "EmbeddingWorker"
private const val PARALLELISM = 3
private const val ACTIVE_NOTIFIER_INTERVAL_MS = 500L
private const val IDLE_NOTIFIER_INTERVAL_MS = 10_000L

class EmbeddingWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    override suspend fun doWork(): Result {
        if (!org.eidora.util.PermissionChecker.hasWorkerPermissions(applicationContext)) {
            Log.w(TAG, "Missing media/all-files permission – aborting embedding calculation")
            return Result.failure()
        }
        val db = DatabaseProvider.getInstance(applicationContext)
        val faceDao = db.faceRegionDao()
        val photoDao = db.photoDao()

        // Models are downloaded only after explicit user consent. If they are
        // not present yet, end quietly – the UI prompts the user to download.
        if (!org.eidora.ml.ModelDownloader.allModelsReady(applicationContext)) {
            Log.i(TAG, "ML models not downloaded yet – skipping embedding run")
            return Result.success()
        }

        val model =
            try {
                EmbeddingModel(applicationContext)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to initialize embedding model", t)
                return Result.failure()
            }
        Log.i(TAG, "Embedding model initialized on backend: ${model.backend}")

        return try {
            val pending: List<FaceRegionEntity> = faceDao.findWithoutEmbedding()
            val total = pending.size
            if (total == 0) return Result.success()

            val powerGate = PowerGate(applicationContext)
            val powerConfig =
                try {
                    org.eidora.data.settings.SettingsProvider
                        .get(applicationContext)
                        .getPowerConfig()
                } catch (t: Throwable) {
                    org.eidora.data.settings.PowerConfig(
                    minBatteryPercent = org.eidora.data.settings.SettingsRepository.DEFAULT_MIN_BATTERY_PERCENT,
                    maxBatteryTempCelsius = org.eidora.data.settings.SettingsRepository.DEFAULT_MAX_BATTERY_TEMP,
                    resumeBatteryPercent = org.eidora.data.settings.SettingsRepository.DEFAULT_RESUME_BATTERY_PERCENT,
                    resumeBatteryTempCelsius = org.eidora.data.settings.SettingsRepository.DEFAULT_RESUME_BATTERY_TEMP,
                    )
                }

            val done = AtomicInteger(0)
            val startedAt = System.currentTimeMillis()
            // Shared status: either "X%" or a pause reason – the notifier reads this
            val currentStatus =
                java.util.concurrent.atomic
                    .AtomicReference<String>("")

            val notifierScope =
                kotlinx.coroutines.CoroutineScope(
                    kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob(),
                )
            @Suppress("UNUSED_VARIABLE")
            val notifierJob =
                notifierScope.launch {
                    // Time spent blocked by the PowerGate, excluded from the ETA.
                    val pausedMs =
                        java.util.concurrent.atomic
                            .AtomicLong(0L)
                    var lastTick = System.currentTimeMillis()
                    var lastPosted: Pair<Int, String>? = null
                    while (isActive) {
                        val current = done.get()
                        val progress = if (total == 0) 0 else (current * 100) / total
                        // A non-empty status is a PowerGate/pause reason: show it
                        // without an ETA and without the Pause action. The time
                        // spent blocked is accumulated and excluded from the ETA
                        // so the estimate reflects actual processing speed.
                        val status = currentStatus.get()
                        val blocked = status.isNotEmpty()
                        val nowTick = System.currentTimeMillis()
                        if (blocked) pausedMs.addAndGet(nowTick - lastTick)
                        lastTick = nowTick
                        val eta =
                            if (blocked) {
                                ""
                            } else {
                                PhotoSyncWorker.formatEta(
                                    applicationContext,
                                    startedAt,
                                    current,
                                    total,
                                    pausedMs.get(),
                                )
                            }
                        val message =
                            when {
                                blocked -> status
                                eta.isNotEmpty() -> "$progress% – $eta"
                                else -> "$progress%"
                            }
                        val posted = progress to message
                        if (posted != lastPosted) {
                            try {
                                setForeground(
                                    NotificationHelper.embeddingForegroundInfoWithMessage(
                                        applicationContext,
                                        progress,
                                        message,
                                        gateBlocked = blocked,
                                    ),
                                )
                                lastPosted = posted
                            } catch (t: Throwable) {
                                // ignore
                            }
                        }
                        // Back off while blocked - see PhotoSyncWorker for why.
                        kotlinx.coroutines.delay(
                            if (blocked) IDLE_NOTIFIER_INTERVAL_MS else ACTIVE_NOTIFIER_INTERVAL_MS,
                        )
                    }
                }

            try {
            // Producer: crop face bitmaps in parallel on IO dispatcher
            // Consumer (implicit): compute embedding via mutex-guarded interpreter,
            // then write result back to DB
            pending
                .asFlow()
                .flatMapMerge(concurrency = PARALLELISM) { face ->
                    flow {
                        powerGate.awaitOk(
                            powerConfig,
                            isStopped = { isStopped },
                        ) { reason ->
                            // Only update the shared status – the notifier handles display
                            currentStatus.set(reason)
                        }
                        if (isStopped) return@flow
                        currentStatus.set("") // clear pause reason when gate opens
                        val bitmap =
                            try {
                                val photo = photoDao.findById(face.photoId) ?: return@flow
                                val photoFile = File(photo.path)
                                if (!photoFile.exists()) return@flow
                                val coords = face.regionJson.toFaceRegionCoords()
                                ThumbnailHelper.cropForEmbedding(photoFile, coords)
                            } catch (t: Throwable) {
                                Log.e(TAG, "Failed to prepare face ${face.id}, marking failed", t)
                                // Permanent failure: mark it so clustering stops
                                // waiting for this face's embedding.
                                try {
                                    faceDao.markEmbeddingFailed(face.id)
                                } catch (inner: Throwable) {
                                    Log.w(TAG, "Could not mark face ${face.id} failed", inner)
                                }
                                null
                            }
                        if (bitmap != null) emit(face to bitmap)
                    }.flowOn(Dispatchers.IO)
                }.buffer(capacity = PARALLELISM)
                .collect { (face, bitmap) ->
                    try {
                        val embedding = model.computeEmbedding(bitmap)
                        faceDao.updateEmbedding(face.id, EmbeddingModel.floatArrayToBytes(embedding))
                        // Refine quality score now that we have the crop bitmap:
                        // add sharpness signal on top of the size+rotation score
                        // already computed at detection time.
                        try {
                            val coords = face.regionJson.toFaceRegionCoords()
                            val refined =
                                org.eidora.util.FaceQuality.compute(
                                    coords = coords,
                                    rotationRad = null, // rotation already baked into initial score
                                    faceBitmap = bitmap,
                                )
                            // Blend detection-time score (2/3) with sharpness (1/3)
                            val prev = face.qualityScore ?: refined
                            val blended = prev * 0.667f + refined * 0.333f
                            faceDao.updateQualityScore(face.id, blended)
                        } catch (t: Throwable) {
                            Log.w(TAG, "Quality score refinement failed for ${face.id}", t)
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed embedding for face ${face.id}, marking failed", t)
                        try {
                            faceDao.markEmbeddingFailed(face.id)
                        } catch (inner: Throwable) {
                            Log.w(TAG, "Could not mark face ${face.id} failed", inner)
                        }
                    } finally {
                        bitmap.recycle()
                    }
                    val current = done.incrementAndGet()
                    setProgress(
                        workDataOf(
                            PhotoSyncWorker.KEY_PROGRESS to (current * 100) / total,
                            PhotoSyncWorker.KEY_STATUS to
                                applicationContext.getString(org.eidora.R.string.notif_embedding_title),
                        ),
                    )
                }

            } finally {
                // MUST run even on cancellation – a leaked notifier from a
                // stopped run would fight the next run over the notification.
                notifierScope.cancel()
            }

            Result.success()
        } catch (t: Throwable) {
            Log.e(TAG, "Unhandled error in EmbeddingWorker", t)
            Result.failure()
        } finally {
            try {
                model.close()
            } catch (t: Throwable) {
                Log.w(TAG, "Error closing model", t)
            }
            try {
                androidx.core.app.NotificationManagerCompat
                    .from(applicationContext)
                    .cancel(NotificationHelper.NOTIFICATION_ID_EMBEDDING)
            } catch (t: Throwable) {
                // ignore
            }
        }
    }

    companion object {
        fun buildRequest(): OneTimeWorkRequest = OneTimeWorkRequestBuilder<EmbeddingWorker>().build()
    }
}
