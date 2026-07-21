// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager

/**
 * Cancels all background processing (sync pipeline + clustering).
 * Triggered when the user swipes away the progress notification
 * (set as the notification's deleteIntent), or from a Cancel action.
 * Also clears any pause state so the next run starts fresh.
 */
class CancelReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(SyncPipeline.UNIQUE_SYNC_NAME)
        wm.cancelUniqueWork(SyncPipeline.UNIQUE_CLUSTERING_NAME)
        PauseState.setPaused(context, false)
    }

    companion object {
        fun deleteIntent(context: Context): android.app.PendingIntent {
            val intent = Intent(context, CancelReceiver::class.java)
            return android.app.PendingIntent.getBroadcast(
                context, 3, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
