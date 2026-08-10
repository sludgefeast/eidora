// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.worker

import android.content.Context
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import org.eidora.R
import org.eidora.data.db.DatabaseProvider
import org.eidora.data.db.PhotoStage
import java.io.File

/**
 * Second pipeline stage (step 1/4 shown to the user): take every photo the
 * scan left at stage NEW, read its XMP, and either import existing face
 * metadata (advancing to DONE) or mark it NEEDS_DETECTION for the ML pass.
 * Cheap per photo, but worth its own ETA over a large library.
 */
class TriageWorker(
    context: Context,
    params: WorkerParameters,
) : PipelineWorker(context, params) {
    private val photoDao by lazy { DatabaseProvider.get(applicationContext).photoDao() }

    override val step: Int = NotificationHelper.STEP_TRIAGE

    override fun phaseTitle(): String = applicationContext.getString(R.string.notif_triage_title)

    override suspend fun loadItems(): List<WorkItem> =
        photoDao.getByStage(PhotoStage.NEW).map { WorkItem(File(it.path), it.folder) }

    override suspend fun processItem(item: WorkItem) {
        analyzer.triage(item.file, item.folder)
    }

    companion object {
        fun buildRequest(): OneTimeWorkRequest = OneTimeWorkRequestBuilder<TriageWorker>().build()
    }
}
