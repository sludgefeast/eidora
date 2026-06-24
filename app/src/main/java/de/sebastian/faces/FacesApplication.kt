package de.sebastian.faces

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import de.sebastian.faces.data.db.DatabaseProvider
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FacesApplication : Application() {

    val database by lazy { DatabaseProvider.getInstance(this) }

    override fun attachBaseContext(base: android.content.Context?) {
        super.attachBaseContext(base)
        installCrashLogger()
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("FACESDIAG", "FacesApplication.onCreate() reached")
        createNotificationChannels()
    }

    private fun installCrashLogger() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashLog(thread, throwable)
            } catch (e: Exception) {
                Log.e("FACESCRASH", "Failed to write crash log", e)
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashLog(thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        Log.e("FACESCRASH", "CRASH in thread ${thread.name}:\n$sw")

        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val content = "Time: $timestamp\nThread: ${thread.name}\n\n$sw"

        val candidates = listOfNotNull(
            runCatching { File(getExternalFilesDir(null), "crash_logs") }.getOrNull(),
            runCatching { File(filesDir, "crash_logs") }.getOrNull(),
            runCatching { File(cacheDir, "crash_logs") }.getOrNull()
        )
        for (dir in candidates) {
            try {
                dir.mkdirs()
                File(dir, "crash_$timestamp.txt").writeText(content)
                Log.d("FACESCRASH", "Crash log written to ${dir.absolutePath}")
                break
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
