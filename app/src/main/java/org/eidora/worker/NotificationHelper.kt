// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.worker

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import org.eidora.MainActivity
import org.eidora.R
import org.eidora.util.EidoraLog

object NotificationHelper {
    private const val CHANNEL_SYNC = "sync"
    const val NOTIFICATION_ID_SYNC = 1001
    const val NOTIFICATION_ID_EMBEDDING = 1002
    const val NOTIFICATION_ID_CLUSTERING = 1003
    const val NOTIFICATION_ID_DOWNLOAD = 1004

    // Pipeline steps as the user perceives them, each a long-running phase with
    // its own progress/ETA. Numbered so the notification title can show
    // "Step X/N", giving the user a mental model that more phases follow instead
    // of the app appearing to restart each time a new phase begins. Media
    // scanning is a brief preparation and is not counted as a step.
    const val TOTAL_STEPS = 4
    const val STEP_TRIAGE = 1 // checking photos for existing face metadata
    const val STEP_DETECTION = 2 // ML face detection
    const val STEP_EMBEDDING = 3 // embedding computation
    const val STEP_CLUSTERING = 4 // grouping faces into people

    // Progress keys published via WorkManager setProgress, read by observers.
    const val KEY_PROGRESS = "progress"
    const val KEY_STATUS = "status"

    /**
     * Prefixes a phase title with its step position, e.g. "Step 2/4 · Detecting
     * faces". When [step] is null (a phase that isn't part of the numbered
     * pipeline, e.g. media scanning) the plain title is returned unchanged.
     */
    private fun titleWithStep(
        context: Context,
        title: String,
        step: Int?,
    ): String =
        if (step == null) {
            title
        } else {
            context.getString(R.string.notif_step, step, TOTAL_STEPS, title)
        }

    fun syncForegroundInfo(
        context: Context,
        progress: Int,
        status: String,
        gateBlocked: Boolean = false,
        eta: String? = null,
        step: Int? = null,
        title: String? = null,
    ): ForegroundInfo {
        val notification =
            buildNotification(
                context,
                titleWithStep(
                    context,
                    title ?: context.getString(R.string.notification_sync_title),
                    step,
                ),
                status,
                progress,
                gateBlocked = gateBlocked,
                eta = eta,
            )
        return makeForegroundInfo(NOTIFICATION_ID_SYNC, notification)
    }

    fun embeddingForegroundInfoWithMessage(
        context: Context,
        progress: Int,
        message: String,
        gateBlocked: Boolean = false,
        eta: String? = null,
    ): ForegroundInfo {
        val notification =
            buildNotification(
                context,
                titleWithStep(
                    context,
                    context.getString(R.string.notif_embedding_title),
                    STEP_EMBEDDING,
                ),
                message,
                progress,
                gateBlocked = gateBlocked,
                eta = eta,
            )
        return makeForegroundInfo(NOTIFICATION_ID_EMBEDDING, notification)
    }

    fun clusteringForegroundInfo(
        context: Context,
        progress: Int = -1,
        message: String? = null,
        cancelIntent: android.app.PendingIntent? = null,
        gateBlocked: Boolean = false,
    ): ForegroundInfo {
        val notification =
            buildNotification(
                context,
                titleWithStep(
                    context,
                    context.getString(R.string.notif_clustering_title),
                    STEP_CLUSTERING,
                ),
                message ?: context.getString(R.string.notif_running),
                progress,
                cancelIntent = cancelIntent,
                gateBlocked = gateBlocked,
            )
        return makeForegroundInfo(NOTIFICATION_ID_CLUSTERING, notification)
    }

    /**
     * Updates the clustering notification directly (not via setForeground), so a
     * long synchronous phase like Chinese Whispers label propagation can post a
     * heartbeat ("round X") without a suspend context. Keeps the same progress
     * bar value; only the text changes, so the user sees the app is still working
     * instead of a bar that looks frozen. No-op if notifications aren't permitted.
     */
    fun updateClusteringNotification(
        context: Context,
        progress: Int,
        message: String,
    ) {
        try {
            val notification =
                buildNotification(
                    context,
                    titleWithStep(
                        context,
                        context.getString(R.string.notif_clustering_title),
                        STEP_CLUSTERING,
                    ),
                    message,
                    progress,
                )
            androidx.core.app.NotificationManagerCompat
                .from(context)
                .notify(NOTIFICATION_ID_CLUSTERING, notification)
        } catch (t: Throwable) {
            // Best-effort heartbeat; never let a notification update break
            // clustering. (Missing POST_NOTIFICATIONS permission lands here.)
            EidoraLog.d("NotificationHelper", "clustering heartbeat failed: ${t.message}")
        }
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
        gateBlocked: Boolean = false,
        eta: String? = null,
    ): Notification {
        val builder =
            NotificationCompat
                .Builder(context, CHANNEL_SYNC)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(android.graphics.Color.parseColor("#EC4899"))
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(contentIntent(context))
                .setDeleteIntent(CancelReceiver.deleteIntent(context))
                .setOngoing(false)
                .setSilent(true)
                // Hide the "when" timestamp: this is a live progress notification,
                // not a point-in-time event. Because we only re-post it on content
                // change (and poll slowly while paused), Android would otherwise
                // show an ever-growing "N min ago" that looks stale.
                .setShowWhen(false)

        // The remaining-time estimate goes on its own line (subText) so the file
        // name on the content line can be shown in full width and simply ellipsized
        // when long — instead of the two sharing one line and making the whole
        // notification flip between one and two lines as the name length changes.
        if (!eta.isNullOrEmpty()) {
            builder.setSubText(eta)
        }

        // Manual pause always offers Resume. A power/thermal gate block offers
        // no pause action at all – pausing a run that is already blocked by the
        // PowerGate would be meaningless.
        if (PauseState.isPaused(context)) {
            builder.addAction(
                android.R.drawable.ic_media_play,
                context.getString(R.string.action_resume),
                PauseReceiver.resumeIntent(context),
            )
        } else if (!gateBlocked) {
            builder.addAction(
                android.R.drawable.ic_media_pause,
                context.getString(R.string.action_pause),
                PauseReceiver.pauseIntent(context),
            )
        }

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
