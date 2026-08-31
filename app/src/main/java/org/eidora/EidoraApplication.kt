// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

// NOTE: setDiagnosticStackTraceMode/ComposeStackTraceMode live in
// androidx.compose.runtime.tooling, confirmed against the official AndroidX
// class index (developer.android.com/reference/kotlin/androidx/compose/
// runtime/tooling/ComposeStackTraceMode) — NOT androidx.compose.runtime,
// which caused the first "Unresolved reference" build failure. The opt-in
// marker for that package is ComposeToolingApi (also confirmed from the same
// index), not ExperimentalComposeRuntimeApi (that one lives in
// androidx.compose.runtime and marks different, unrelated APIs).
@file:OptIn(androidx.compose.runtime.tooling.ComposeToolingApi::class)

package org.eidora

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import org.eidora.data.db.DatabaseProvider
import org.eidora.util.EidoraLog
import org.eidora.worker.PeriodicSyncWorker
import java.util.concurrent.TimeUnit

class EidoraApplication : Application() {
    val database by lazy { DatabaseProvider.getInstance(this) }

    override fun onCreate() {
        super.onCreate()
        // Compose diagnostic stack traces: on crashes inside Compose's own
        // measure/layout/composition machinery (our own frames are absent from
        // the stack, e.g. "layout state is not idle before measure starts"),
        // this reconstructs the @Composable call hierarchy and attaches it as a
        // suppressed DiagnosticComposeException, pointing at the actual
        // offending composable instead of just framework internals.
        // SourceInformation is the most detailed mode but has a real
        // performance cost and needs non-minified source info, so debug builds
        // only — never enabled for release. Must run before any composition is
        // created, so this is the first thing in onCreate.
        if (BuildConfig.DEBUG) {
            androidx.compose.runtime.Composer.setDiagnosticStackTraceMode(
                androidx.compose.runtime.tooling.ComposeStackTraceMode.SourceInformation,
            )
        }
        // Wire up the persistent rotating log file before anything logs, so the
        // app's own diagnostics survive logcat ring-buffer eviction.
        org.eidora.util.EidoraLog.init(this)
        // Enlarge the logcat ring buffer (default is often only ~256 KB, i.e. a
        // few minutes on a busy device) so a full clustering run's diagnostics
        // survive until the user exports the log. Best-effort: it may be denied
        // on some devices, which is harmless. Runs off the main thread.
        Thread {
            try {
                Runtime.getRuntime().exec(arrayOf("logcat", "-G", "8M")).waitFor()
            } catch (t: Throwable) {
                EidoraLog.w("EidoraApplication", "Runtime.getRuntime().exec(arrayOf(logc failed: ${t.message}")
                // Not critical — export still works with the default buffer.
            }
        }.start()
        // Startup marker: one line per process start, carrying this process's
        // PID. LogExporter scans for these to learn which PIDs belong to Eidora
        // (including earlier, now-dead processes still in the log buffer), then
        // exports everything logged by those PIDs. See LogExporter.MARKER_TAG.
        android.util.Log.i(
            org.eidora.util.LogExporter.MARKER_TAG,
            "App process started (pid=${android.os.Process.myPid()})",
        )
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
        val request =
            PeriodicWorkRequestBuilder<PeriodicSyncWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints
                        .Builder()
                        .setRequiresCharging(true)
                        .build(),
                ).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            PERIODIC_SYNC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel(
                    CHANNEL_SYNC,
                    getString(R.string.notification_channel_sync),
                    NotificationManager.IMPORTANCE_LOW,
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
