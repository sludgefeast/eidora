package de.sebastian.eidora.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import de.sebastian.eidora.data.settings.SettingsProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AllowMobileDownloadReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ALLOW) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                SettingsProvider.get(context).setAllowMobileModelDownload(true)
                try {
                    NotificationManagerCompat.from(context).cancel(1005)
                } catch (t: Throwable) { /* ignore */ }
                SyncPipeline.enqueueForce(context)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_ALLOW = "de.sebastian.eidora.action.ALLOW_MOBILE_DOWNLOAD"
    }
}
