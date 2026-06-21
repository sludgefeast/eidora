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

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
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
            // Forward to system handler so Android still shows "App stopped" dialog
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashLog(thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))

        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val logDir = File(getExternalFilesDir(null), "crash_logs")
        logDir.mkdirs()
        val logFile = File(logDir, "crash_$timestamp.txt")

        logFile.writeText(
            buildString {
                appendLine("Time: $timestamp")
                appendLine("Thread: ${thread.name}")
                appendLine("Exception:")
                appendLine(sw.toString())
            }
        )

        Log.e("FACESCRASH", "Crash written to ${logFile.absolutePath}")
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
