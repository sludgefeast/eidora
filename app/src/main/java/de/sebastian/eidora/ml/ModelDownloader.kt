package de.sebastian.eidora.ml

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object ModelDownloader {

    private const val TAG = "ModelDownloader"

    data class ModelInfo(
        val filename: String,
        val url: String,
        val minSizeBytes: Long
    )

    val FACENET = ModelInfo(
        filename = "facenet_512.tflite",
        url = "https://github.com/shubham0204/OnDevice-Face-Recognition-Android/raw/v0.0.1/app/src/main/assets/facenet_512.tflite",
        minSizeBytes = 1_000_000L
    )

    val BLAZEFACE = ModelInfo(
        filename = "blazeface_full_range.tflite",
        url = "https://github.com/shubham0204/OnDevice-Face-Recognition-Android/raw/v0.0.1/app/src/main/assets/face_detection_full_range.tflite",
        minSizeBytes = 100_000L
    )

    private val ALL_MODELS = listOf(FACENET, BLAZEFACE)

    /** Kept for backwards compatibility with existing callers. */
    val MODEL_URL: String get() = FACENET.url

    fun modelFile(context: Context, info: ModelInfo = FACENET): File =
        File(context.filesDir, info.filename)

    fun isDownloaded(context: Context, info: ModelInfo = FACENET): Boolean {
        val file = modelFile(context, info)
        return file.exists() && file.length() >= info.minSizeBytes
    }

    /**
     * True when all models required for the pipeline are present.
     */
    fun allModelsReady(context: Context): Boolean =
        ALL_MODELS.all { isDownloaded(context, it) }

    /**
     * Downloads every missing model. Progress callback aggregates over all
     * pending downloads (0-100 across the entire batch).
     */
    fun download(context: Context, onProgress: ((Int) -> Unit)? = null): Boolean {
        val pending = ALL_MODELS.filter { !isDownloaded(context, it) }
        if (pending.isEmpty()) return true

        pending.forEachIndexed { index, info ->
            val ok = downloadOne(context, info) { p ->
                if (onProgress != null) {
                    val overall = ((index * 100 + p) / pending.size)
                    onProgress(overall)
                }
            }
            if (!ok) return false
        }
        return true
    }

    private fun downloadOne(
        context: Context,
        info: ModelInfo,
        onProgress: ((Int) -> Unit)?
    ): Boolean {
        val target = File(context.filesDir, info.filename)
        val tempFile = File(context.filesDir, "${info.filename}.part")

        return try {
            val url = URL(info.url)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true
            connection.connect()

            if (connection.responseCode !in 200..299) {
                Log.e(TAG, "Download ${info.filename} failed: HTTP ${connection.responseCode}")
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
                            onProgress(((downloadedBytes * 100) / totalBytes).toInt())
                        }
                    }
                }
            }

            if (target.exists()) target.delete()
            if (!tempFile.renameTo(target)) {
                Log.e(TAG, "Failed to rename temp file for ${info.filename}")
                return false
            }

            Log.i(TAG, "Model ${info.filename} downloaded (${target.length()} bytes)")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Download ${info.filename} failed", t)
            tempFile.delete()
            false
        }
    }
}
