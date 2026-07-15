package de.sebastian.eidora.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager

/**
 * BroadcastReceiver that cancels the running clustering job.
 * Triggered by the "Abbrechen" action in the clustering notification.
 */
class CancelClusteringReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WorkManager.getInstance(context).cancelUniqueWork(SyncPipeline.UNIQUE_CLUSTERING_NAME)
    }
}
