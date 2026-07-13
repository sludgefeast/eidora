package de.sebastian.eidora

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import de.sebastian.eidora.data.db.DatabaseProvider
import de.sebastian.eidora.worker.PhotoSyncWorker
import java.util.concurrent.TimeUnit

class EidoraApplication : Application() {

    val database by lazy { DatabaseProvider.getInstance(this) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        schedulePeriodicSync()
    }

    /**
     * Registers a daily background sync with WorkManager.
     * Survives process kills and device reboots.
     * Runs only when charging to avoid draining the battery.
     * KEEP policy: if already scheduled, leave it unchanged.
     */
    private fun schedulePeriodicSync() {
        val request = PeriodicWorkRequestBuilder<PhotoSyncWorker>(1, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresCharging(true)
                    .build()
            )
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            PERIODIC_SYNC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_SYNC,
                getString(R.string.notification_channel_sync),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_SYNC = "sync"
        const val PERIODIC_SYNC_WORK_NAME = "eidora-periodic-sync"
    }
}
