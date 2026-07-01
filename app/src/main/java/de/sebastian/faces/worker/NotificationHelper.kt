package de.sebastian.faces.worker

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import de.sebastian.faces.MainActivity
import de.sebastian.faces.R

object NotificationHelper {

    private const val CHANNEL_SYNC = "sync"
    private const val NOTIFICATION_ID_SYNC = 1001
    private const val NOTIFICATION_ID_EMBEDDING = 1002
    private const val NOTIFICATION_ID_CLUSTERING = 1003
    private const val NOTIFICATION_ID_DOWNLOAD = 1004

    fun syncForegroundInfo(context: Context, progress: Int, status: String): ForegroundInfo {
        val notification = buildNotification(
            context, context.getString(R.string.notification_sync_title), status, progress
        )
        return makeForegroundInfo(NOTIFICATION_ID_SYNC, notification)
    }

    fun embeddingForegroundInfo(context: Context, progress: Int): ForegroundInfo {
        val notification = buildNotification(context, "Computing embeddings", "$progress%", progress)
        return makeForegroundInfo(NOTIFICATION_ID_EMBEDDING, notification)
    }

    fun clusteringForegroundInfo(context: Context): ForegroundInfo {
        val notification = buildNotification(context, "Clustering faces", "Running…", -1)
        return makeForegroundInfo(NOTIFICATION_ID_CLUSTERING, notification)
    }

    fun modelDownloadForegroundInfo(context: Context, progress: Int): ForegroundInfo {
        val notification = buildNotification(
            context,
            context.getString(R.string.notification_download_model),
            "$progress%",
            progress
        )
        return makeForegroundInfo(NOTIFICATION_ID_DOWNLOAD, notification)
    }

    fun cancelSync(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID_SYNC)
        nm.cancel(NOTIFICATION_ID_EMBEDDING)
        nm.cancel(NOTIFICATION_ID_CLUSTERING)
    }

    private fun makeForegroundInfo(id: Int, notification: Notification): ForegroundInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    private fun contentIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getActivity(context, 0, intent, flags)
    }

    private fun buildNotification(
        context: Context, title: String, text: String, progress: Int
    ): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_SYNC)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent(context))
            .setOngoing(true)
            .setSilent(true)

        if (progress >= 0) {
            builder.setProgress(100, progress, false)
        } else {
            builder.setProgress(0, 0, true)
        }

        return builder.build()
    }
}
