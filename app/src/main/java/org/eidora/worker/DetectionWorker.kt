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
 * Third pipeline stage (step 2/4 shown to the user): run the ML face detector
 * on every photo triage marked NEEDS_DETECTION (no XMP metadata), then advance
 * it to DONE. The expensive pass, measured with its own ETA.
 */
class DetectionWorker(
    context: Context,
    params: WorkerParameters,
) : PipelineWorker(context, params) {
    private val photoDao by lazy { DatabaseProvider.get(applicationContext).photoDao() }

    override val step: Int = NotificationHelper.STEP_DETECTION

    override fun phaseTitle(): String = applicationContext.getString(R.string.notif_detection_title)

    override suspend fun loadItems(): List<WorkItem> =
        photoDao.getByStage(PhotoStage.NEEDS_DETECTION).map { WorkItem(File(it.path), it.folder) }

    override suspend fun processItem(item: WorkItem) {
        analyzer.detect(item.file, item.folder)
    }

    companion object {
        fun buildRequest(): OneTimeWorkRequest = OneTimeWorkRequestBuilder<DetectionWorker>().build()
    }
}
