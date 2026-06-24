package de.sebastian.faces

import android.app.Application
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// DIAGNOSTIC STEP 1: Application with only crash logger.
// No database, no notification channels, no WorkManager.
class FacesApplication : Application() {

    override fun attachBaseContext(base: android.content.Context?) {
        super.attachBaseContext(base)
        installCrashLogger()
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("FACESDIAG", "FacesApplication.onCreate() reached")
        // Write marker to internal storage - no external storage needed
        try {
            File(filesDir, "step1_app_oncreate.txt")
                .writeText("reached at ${System.currentTimeMillis()}")
        } catch (t: Throwable) {
            Log.e("FACESDIAG", "Could not write marker", t)
        }
    }

    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                Log.e("FACESCRASH", "CRASH: $sw")
                val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
                val content = "Thread: ${thread.name}\n\n$sw"
                // Try all possible locations
                for (dir in listOf(
                    runCatching { File(filesDir, "crash_logs") }.getOrNull(),
                    runCatching { cacheDir }.getOrNull()
                ).filterNotNull()) {
                    try {
                        dir.mkdirs()
                        File(dir, "crash_$ts.txt").writeText(content)
                        Log.d("FACESCRASH", "Written to ${dir.absolutePath}/crash_$ts.txt")
                        break
                    } catch (e: Exception) { /* try next */ }
                }
            } catch (e: Exception) { /* last resort */ }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
