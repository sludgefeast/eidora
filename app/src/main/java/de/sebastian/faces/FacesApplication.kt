package de.sebastian.faces

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FacesApplication : Application() {

    // installCrashLogger() must run as the very first statement in attachBaseContext,
    // before any other class is touched, so we catch crashes happening during
    // static init / classloading too (these happen before onCreate()).
    override fun attachBaseContext(base: android.content.Context?) {
        super.attachBaseContext(base)
        installCrashLogger()
    }

    override fun onCreate() {
        super.onCreate()
        try {
            createNotificationChannels()
        } catch (t: Throwable) {
            writeCrashLog(Thread.currentThread(), t, tag = "onCreate")
        }
    }

    private fun installCrashLogger() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashLog(thread, throwable, tag = "uncaught")
            } catch (e: Exception) {
                Log.e("FACESCRASH", "Failed to write crash log", e)
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashLog(thread: Thread, throwable: Throwable, tag: String) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        Log.e("FACESCRASH", "[$tag] ${sw}")

        // Try multiple locations in case one isn't ready yet this early in startup
        val candidates = listOfNotNull(
            runCatching { File(getExternalFilesDir(null), "crash_logs") }.getOrNull(),
            runCatching { File(filesDir, "crash_logs") }.getOrNull(),
            runCatching { File(cacheDir, "crash_logs") }.getOrNull()
        )

        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val content = buildString {
            appendLine("Tag: $tag")
            appendLine("Time: $timestamp")
            appendLine("Thread: ${thread.name}")
            appendLine("Exception:")
            appendLine(sw.toString())
        }

        for (dir in candidates) {
            try {
                dir.mkdirs()
                val logFile = File(dir, "crash_$timestamp.txt")
                logFile.writeText(content)
                Log.e("FACESCRASH", "Crash written to ${logFile.absolutePath}")
            } catch (e: Exception) {
                Log.e("FACESCRASH", "Could not write to ${dir.absolutePath}", e)
            }
        }
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
    }
}
