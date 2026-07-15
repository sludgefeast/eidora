package org.eidora.data.settings

import android.content.Context

object SettingsProvider {
    @Volatile private var instance: SettingsRepository? = null

    fun get(context: Context): SettingsRepository =
        instance ?: synchronized(this) {
            instance ?: SettingsRepository(context.applicationContext).also { instance = it }
        }
}
