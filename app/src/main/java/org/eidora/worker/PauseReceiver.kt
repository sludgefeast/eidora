package org.eidora.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Toggles the global pause state for background processing.
 * Triggered by the "Pause" / "Resume" notification actions.
 * The action is encoded in the intent's action string.
 */
class PauseReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            ACTION_PAUSE -> PauseState.setPaused(context, true)
            ACTION_RESUME -> PauseState.setPaused(context, false)
        }
    }

    companion object {
        const val ACTION_PAUSE = "org.eidora.action.PAUSE"
        const val ACTION_RESUME = "org.eidora.action.RESUME"

        fun pauseIntent(context: Context): android.app.PendingIntent {
            val intent = Intent(context, PauseReceiver::class.java).setAction(ACTION_PAUSE)
            return android.app.PendingIntent.getBroadcast(
                context, 1, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
        }

        fun resumeIntent(context: Context): android.app.PendingIntent {
            val intent = Intent(context, PauseReceiver::class.java).setAction(ACTION_RESUME)
            return android.app.PendingIntent.getBroadcast(
                context, 2, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
