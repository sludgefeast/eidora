// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.eidora.data.settings.PowerConfig
import org.eidora.data.settings.SettingsProvider
import org.eidora.data.settings.SettingsRepository
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** One media file plus the folder it was found in. */
data class WorkItem(
    val file: File,
    val folder: String,
)

/**
 * Base class for the pipeline's per-item workers (triage, detection). Provides
 * the shared machinery so each concrete worker only declares its items, its
 * per-item action, its notification and its step:
 *   - a foreground notifier coroutine that ticks progress + a smoothed ETA,
 *   - PowerGate gating (pause on low battery / high temperature),
 *   - bounded-parallel processing of the item list.
 *
 * Scanning is a separate, non-item worker (ScanWorker) and does not use this.
 */
abstract class PipelineWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    protected val analyzer by lazy { PhotoAnalyzer(applicationContext) }

    // Progress state shared with the notifier coroutine.
    private val doneCount = AtomicInteger(0)
    private val totalCount = AtomicInteger(0)
    private val gateBlocked = AtomicBoolean(false)
    private val currentFile = AtomicReference("")

    /** The items this worker should process. */
    protected abstract suspend fun loadItems(): List<WorkItem>

    /** Process one item. Implementations advance the photo's DB stage. */
    protected abstract suspend fun processItem(item: WorkItem)

    /** The pipeline step number (see NotificationHelper.STEP_*). */
    protected abstract val step: Int

    /** Localised phase title shown in the notification (with the step prefix). */
    protected abstract fun phaseTitle(): String

    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun doWork(): Result {
        val items = loadItems()
        android.util.Log.i("PipelineWorker", "${phaseTitle()} (step $step): ${items.size} items")
        if (items.isEmpty()) return Result.success()

        totalCount.set(items.size)
        doneCount.set(0)
        currentFile.set(items.first().file.name)

        val powerGate = PowerGate(applicationContext)
        val powerConfig = loadPowerConfig()
        val estimator = EtaEstimator()

        val notifierScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val notifierJob =
            notifierScope.launch {
                var lastTick = System.currentTimeMillis()
                var lastPosted: Triple<Int, String, String>? = null
                while (isActive) {
                    val now = System.currentTimeMillis()
                    val total = totalCount.get()
                    val current = doneCount.get()
                    val progress = if (total == 0) 0 else (current * 100) / total
                    val file = currentFile.get()
                    val blocked = gateBlocked.get()
                    if (blocked) estimator.addPaused(now - lastTick)
                    lastTick = now
                    val eta =
                        if (blocked || total == 0) {
                            ""
                        } else {
                            estimator.update(current, now)
                            formatEtaFrom(applicationContext, estimator, current, total)
                        }
                    val posted = Triple(progress, file, eta)
                    if (posted != lastPosted) {
                        try {
                            setForeground(
                                NotificationHelper.syncForegroundInfo(
                                    applicationContext,
                                    progress,
                                    file,
                                    gateBlocked = blocked,
                                    eta = eta.ifEmpty { null },
                                    step = step,
                                    title = phaseTitle(),
                                ),
                            )
                            lastPosted = posted
                        } catch (t: Throwable) {
                            // ignore
                        }
                    }
                    delay(if (blocked) IDLE_NOTIFIER_INTERVAL_MS else ACTIVE_NOTIFIER_INTERVAL_MS)
                }
            }

        try {
            flow { items.forEach { emit(it) } }
                .flatMapMerge(concurrency = PIPELINE_PARALLELISM) { item ->
                    flow {
                        powerGate.awaitOk(powerConfig, isStopped = { isStopped }) { reason ->
                            gateBlocked.set(true)
                            currentFile.set(reason)
                        }
                        gateBlocked.set(false)
                        currentFile.set(item.file.name)
                        try {
                            processItem(item)
                        } catch (t: Throwable) {
                            t.rethrowIfCancellation()
                            Log.e(TAG, "Failed to process ${item.file.name}, skipping", t)
                        }
                        emit(item)
                    }
                }.collect { doneCount.incrementAndGet() }
        } finally {
            notifierJob.cancel()
        }
        return Result.success()
    }

    private suspend fun loadPowerConfig(): PowerConfig =
        try {
            SettingsProvider.get(applicationContext).getPowerConfig()
        } catch (t: Throwable) {
            PowerConfig(
                minBatteryPercent = SettingsRepository.DEFAULT_MIN_BATTERY_PERCENT,
                maxBatteryTempCelsius = SettingsRepository.DEFAULT_MAX_BATTERY_TEMP,
                resumeBatteryPercent = SettingsRepository.DEFAULT_RESUME_BATTERY_PERCENT,
                resumeBatteryTempCelsius = SettingsRepository.DEFAULT_RESUME_BATTERY_TEMP,
            )
        }

    companion object {
        private const val TAG = "PipelineWorker"
        private const val PIPELINE_PARALLELISM = 3
        private const val ACTIVE_NOTIFIER_INTERVAL_MS = 500L
        private const val IDLE_NOTIFIER_INTERVAL_MS = 10_000L
    }
}
