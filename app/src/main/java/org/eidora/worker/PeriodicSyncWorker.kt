// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Thin periodic trigger. A PeriodicWorkRequest can't itself be a chain, so this
 * worker just kicks off the normal Scan → Triage → Detection → Embedding
 * pipeline (which is a unique chain and will no-op if already running).
 */
class PeriodicSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        SyncPipeline.enqueue(applicationContext)
        return Result.success()
    }
}
