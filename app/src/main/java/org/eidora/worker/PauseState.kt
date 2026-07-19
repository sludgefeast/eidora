package org.eidora.worker

import android.content.Context

/**
 * Global pause flag for background processing, persisted in SharedPreferences.
 * Workers check this in their PowerGate wait loop and pause when set.
 * Controlled via the "Pause"/"Resume" notification actions.
 */
object PauseState {
    private const val PREFS = "eidora_pause"
    private const val KEY_PAUSED = "paused"

    fun isPaused(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_PAUSED, false)

    fun setPaused(context: Context, paused: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PAUSED, paused)
            .apply()
    }
}
