package de.sebastian.eidora.ml

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object ModelDownloader {

    private const val TAG = "ModelDownloader"
    private const val MODEL_FILENAME = "facenet_512.tflite"

    // FaceNet512 model from shubham0204/OnDevice-Face-Recognition-Android
    private const val MODEL_URL =
        "https://github.com/shubham0204/OnDevice-Face-Recognition-Android/raw/main/app/src/main/assets/facenet_512.tflite"

    // SHA-256 of the expected model file for integrity verification
    private const val EXPECTED_SHA256 =
        "" // optional – leave empty to skip verification

    /**
     * Path where the downloaded model is stored.
     */
    fun modelFile(context: Context): File =
        File(context.filesDir, MODEL_FILENAME)

    /**
     * Checks whether the model has already been downloaded.
     */
    fun isDownloaded(context: Context): Boolean {
        val file = modelFile(context)
        return file.exists() && file.length() > 1_000_000  // sanity check: at least 1 MB
    }

    /**
     * Downloads the model to internal storage. Blocking call – should run in a
     * background coroutine or worker.
     *
     * @return true on success, false on any failure
     */
    fun download(context: Context, onProgress: ((Int) -> Unit)? = null): Boolean {
        val target = modelFile(context)
        val tempFile = File(context.filesDir, "$MODEL_FILENAME.part")

        return try {
            val url = URL(MODEL_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true
            connection.connect()

            if (connection.responseCode !in 200..299) {
                Log.e(TAG, "Download failed: HTTP ${connection.responseCode}")
                return false
            }

            val totalBytes = connection.contentLengthLong
            var downloadedBytes = 0L

            connection.inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        if (totalBytes > 0 && onProgress != null) {
                            val progress = ((downloadedBytes * 100) / totalBytes).toInt()
                            onProgress(progress)
                        }
                    }
                }
            }

            if (EXPECTED_SHA256.isNotBlank()) {
                val actual = sha256(tempFile)
                if (actual != EXPECTED_SHA256) {
                    Log.e(TAG, "SHA-256 mismatch: expected $EXPECTED_SHA256, got $actual")
                    tempFile.delete()
                    return false
                }
            }

            // Atomic rename
            if (target.exists()) target.delete()
            val renamed = tempFile.renameTo(target)
            if (!renamed) {
                Log.e(TAG, "Failed to rename temp file")
                return false
            }

            Log.i(TAG, "Model downloaded successfully (${target.length()} bytes)")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Download failed", t)
            tempFile.delete()
            false
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
