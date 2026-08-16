// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.worker

import android.content.Context
import org.eidora.util.EidoraLog
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.cancel
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
import org.eidora.data.settings.PowerConfig
import org.eidora.data.settings.SettingsRepository
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
            EidoraLog.w(TAG, "Missing media/all-files permission – aborting embedding calculation")
            return Result.failure()
        }
        val db = DatabaseProvider.getInstance(applicationContext)
        val faceDao = db.faceRegionDao()
        val photoDao = db.photoDao()

        // The free container is downloaded only after explicit user consent. If
        // it isn't present yet, end quietly – the UI prompts for the download.
        if (!org.eidora.ml.container.ContainerDownloader.isFreeContainerReady(applicationContext)) {
            EidoraLog.i(TAG, "Model container not ready yet – skipping embedding run")
            return Result.success()
        }

        val handle =
            org.eidora.ml.container.SelectedModelResolver.openEmbedder(applicationContext)
        if (handle == null) {
            EidoraLog.e(TAG, "Failed to initialize embedding model from selected container")
            return Result.failure()
        }
        val model = handle.embedder
        EidoraLog.i(TAG, "Embedding model initialized on backend: ${model.backend}")

        return withWakeLock(applicationContext, TAG) {
            try {
            val pending: List<FaceRegionEntity> = faceDao.findWithoutEmbedding()
            val total = pending.size
            if (total == 0) {
                // Nothing to embed, but centroids/representatives may still be
                // unset (e.g. embeddings finished in an earlier run that never
                // clustered). enqueueClustering is unique work, so this is a
                // cheap no-op when clustering already ran.
                SyncPipeline.enqueueClustering(applicationContext)
                return@withWakeLock Result.success()
            }
            EidoraLog.i(TAG, "Starting embedding run for $total faces")

            val timer = RunTimer(TAG, "Embedding ($total faces)")
            val pauseManual = java.util.concurrent.atomic.AtomicBoolean(false)

            val powerGate = PowerGate(applicationContext)
            val powerConfig =
                try {
                    org.eidora.data.settings.SettingsProvider
                        .get(applicationContext)
                        .getPowerConfig()
                } catch (t: Throwable) {
                    t.rethrowIfCancellation()
                    PowerConfig(
                        minBatteryPercent = SettingsRepository.DEFAULT_MIN_BATTERY_PERCENT,
                        maxBatteryTempCelsius = SettingsRepository.DEFAULT_MAX_BATTERY_TEMP,
                        resumeBatteryPercent = SettingsRepository.DEFAULT_RESUME_BATTERY_PERCENT,
                        resumeBatteryTempCelsius = SettingsRepository.DEFAULT_RESUME_BATTERY_TEMP,
                    )
                }

            val done = AtomicInteger(0)
            // Smoothed ETA: EMA of per-item time, warm-up skipped. Replaces the
            // plain overall-average estimate, which stayed skewed for a long time
            // after any early fast/slow stretch.
            val etaEstimator = EtaEstimator()
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
                    var lastTick = System.currentTimeMillis()
                    var lastPosted: Triple<Int, String, String>? = null
                    var wasBlocked = false
                    while (isActive) {
                        val current = done.get()
                        val progress = if (total == 0) 0 else (current * 100) / total
                        // A non-empty status is a PowerGate/pause reason: show it
                        // without an ETA and without the Pause action. The time
                        // spent blocked is fed to the estimator as paused time so
                        // the estimate reflects actual processing speed.
                        val status = currentStatus.get()
                        val blocked = status.isNotEmpty()
                        // Bank pause spans for the run summary.
                        if (blocked && !wasBlocked) {
                            timer.pauseStarted(manual = pauseManual.get())
                        } else if (!blocked && wasBlocked) {
                            timer.pauseEnded()
                        }
                        wasBlocked = blocked
                        val nowTick = System.currentTimeMillis()
                        if (blocked) etaEstimator.addPaused(nowTick - lastTick)
                        lastTick = nowTick
                        etaEstimator.update(current, nowTick)
                        val eta =
                            if (blocked) {
                                ""
                            } else {
                                formatEtaFrom(
                                    applicationContext,
                                    etaEstimator,
                                    current,
                                    total,
                                )
                            }
                        // Progress on the content line, ETA on its own subText line
                        // (or the pause reason when blocked). Keeping them apart
                        // stops the notification flipping between one and two lines.
                        val message =
                            when {
                                blocked -> status
                                else -> "$progress%"
                            }
                        val posted = Triple(progress, message, eta)
                        if (posted != lastPosted) {
                            try {
                                setForeground(
                                    NotificationHelper.embeddingForegroundInfoWithMessage(
                                        applicationContext,
                                        progress,
                                        message,
                                        gateBlocked = blocked,
                                        eta = eta.ifEmpty { null },
                                    ),
                                )
                                lastPosted = posted
                            } catch (t: Throwable) {
                                // ignore
                            }
                        }
                        // Back off while blocked - nothing changes during a pause.
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
                            ) { reason, isManual ->
                                // Only update the shared status – the notifier handles display
                                pauseManual.set(isManual)
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
                                    EidoraLog.e(TAG, "Failed to prepare face ${face.id}, marking failed", t)
                                    // Permanent failure: mark it so clustering stops
                                    // waiting for this face's embedding.
                                    try {
                                        faceDao.markEmbeddingFailed(face.id)
                                    } catch (inner: Throwable) {
                                        EidoraLog.w(TAG, "Could not mark face ${face.id} failed", inner)
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
                                EidoraLog.w(TAG, "Quality score refinement failed for ${face.id}", t)
                            }
                        } catch (t: Throwable) {
                            EidoraLog.e(TAG, "Failed embedding for face ${face.id}, marking failed", t)
                            try {
                                faceDao.markEmbeddingFailed(face.id)
                            } catch (inner: Throwable) {
                                EidoraLog.w(TAG, "Could not mark face ${face.id} failed", inner)
                            }
                        } finally {
                            bitmap.recycle()
                        }
                        val current = done.incrementAndGet()
                        // Periodic heartbeat so a running worker is visible in the
                        // log (every item would flood the ring buffer; every 500
                        // keeps a readable trail across a long run).
                        if (current % 500 == 0) {
                            EidoraLog.i(TAG, "Embeddings progress: $current / $total")
                        }
                        setProgress(
                            workDataOf(
                                NotificationHelper.KEY_PROGRESS to (current * 100) / total,
                                NotificationHelper.KEY_STATUS to
                                    applicationContext.getString(org.eidora.R.string.notif_embedding_title),
                            ),
                        )
                    }
            } finally {
                // MUST run even on cancellation – a leaked notifier from a
                // stopped run would fight the next run over the notification.
                notifierScope.cancel()
            }

            timer.finish(done.get())
            EidoraLog.i(TAG, "Embedding run finished: ${done.get()} / $total processed")
            // Chain clustering after embeddings so a first-run sync (faces come
            // from XMP metadata, then get embedded) also computes centroids and
            // representative faces. The WorkManager chain ends at embedding, and
            // clustering is otherwise only triggered by user actions — without
            // this, freshly imported named persons show no avatar until the user
            // manually clusters.
            SyncPipeline.enqueueClustering(applicationContext)
            Result.success()
        } catch (t: Throwable) {
            t.rethrowIfCancellation()
            EidoraLog.e(TAG, "Unhandled error in EmbeddingWorker", t)
            Result.failure()
        } finally {
            try {
                model.close()
            } catch (t: Throwable) {
                EidoraLog.w(TAG, "Error closing model", t)
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
    }

    companion object {
        fun buildRequest(): OneTimeWorkRequest = OneTimeWorkRequestBuilder<EmbeddingWorker>().build()
    }
}
