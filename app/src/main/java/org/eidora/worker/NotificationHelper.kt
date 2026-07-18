package org.eidora.worker

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import org.eidora.MainActivity
import org.eidora.R

object NotificationHelper {
    private const val CHANNEL_SYNC = "sync"
    const val NOTIFICATION_ID_SYNC = 1001
    const val NOTIFICATION_ID_EMBEDDING = 1002
    const val NOTIFICATION_ID_CLUSTERING = 1003
    const val NOTIFICATION_ID_DOWNLOAD = 1004

    fun syncForegroundInfo(
        context: Context,
        progress: Int,
        status: String,
    ): ForegroundInfo {
        val notification =
            buildNotification(
                context,
                context.getString(R.string.notification_sync_title),
                status,
                progress,
            )
        return makeForegroundInfo(NOTIFICATION_ID_SYNC, notification)
    }

    fun embeddingForegroundInfo(
        context: Context,
        progress: Int,
    ): ForegroundInfo {
        val notification =
            buildNotification(context, context.getString(R.string.notif_embedding_title), "$progress%", progress)
        return makeForegroundInfo(NOTIFICATION_ID_EMBEDDING, notification)
    }

    fun embeddingForegroundInfoWithMessage(
        context: Context,
        progress: Int,
        message: String,
    ): ForegroundInfo {
        val notification =
            buildNotification(context, context.getString(R.string.notif_embedding_title), message, progress)
        return makeForegroundInfo(NOTIFICATION_ID_EMBEDDING, notification)
    }

    fun clusteringForegroundInfo(
        context: Context,
        progress: Int = -1,
        message: String? = null,
        cancelIntent: android.app.PendingIntent? = null,
    ): ForegroundInfo {
        val notification =
            buildNotification(
                context,
                context.getString(R.string.notif_clustering_title),
                message ?: context.getString(R.string.notif_running),
                progress,
                cancelIntent = cancelIntent,
            )
        return makeForegroundInfo(NOTIFICATION_ID_CLUSTERING, notification)
    }

    fun modelDownloadForegroundInfo(
        context: Context,
        progress: Int,
    ): ForegroundInfo {
        val notification =
            buildNotification(
                context,
                context.getString(R.string.notification_download_model),
                "$progress%",
                progress,
            )
        return makeForegroundInfo(NOTIFICATION_ID_DOWNLOAD, notification)
    }

    fun cancelSync(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID_SYNC)
        nm.cancel(NOTIFICATION_ID_EMBEDDING)
        nm.cancel(NOTIFICATION_ID_CLUSTERING)
    }

    private fun makeForegroundInfo(
        id: Int,
        notification: Notification,
    ): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }

    private fun contentIntent(context: Context): PendingIntent {
        val intent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        val flags =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        return PendingIntent.getActivity(context, 0, intent, flags)
    }

    private fun buildNotification(
        context: Context,
        title: String,
        text: String,
        progress: Int,
        cancelIntent: android.app.PendingIntent? = null,
    ): Notification {
        val builder =
            NotificationCompat
                .Builder(context, CHANNEL_SYNC)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(android.graphics.Color.parseColor("#EC4899"))
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(contentIntent(context))
                .setOngoing(true)
                .setSilent(true)

        if (cancelIntent != null) {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.action_cancel),
                cancelIntent,
            )
        }

        if (progress >= 0) {
            builder.setProgress(100, progress, false)
        } else {
            builder.setProgress(0, 0, true)
        }

        return builder.build()
    }
}
