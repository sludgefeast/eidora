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
        val sha256: String
    )

    val FACENET = ModelInfo(
        filename = "facenet_512.tflite",
        url = "https://github.com/shubham0204/OnDevice-Face-Recognition-Android/raw/v0.0.1/app/src/main/assets/facenet_512.tflite",
        sha256 = "82b2083e7f0e4c4d9ebcd309b3f08c3ca4d1a7963806bb67a410fa9bb32e9e8e"
    )

    private val ALL_MODELS = listOf(FACENET)

    /** Kept for backwards compatibility with existing callers. */
    val MODEL_URL: String get() = FACENET.url

    fun modelFile(context: Context, info: ModelInfo = FACENET): File =
        File(context.filesDir, info.filename)

    fun isDownloaded(context: Context, info: ModelInfo = FACENET): Boolean {
        val file = modelFile(context, info)
        return file.exists() && verify(file, info) == VerifyResult.OK
    }

    /**
     * True when all models required for the pipeline are present.
     */
    fun allModelsReady(context: Context): Boolean =
        ALL_MODELS.all { isDownloaded(context, it) }

    enum class VerifyResult { OK, WRONG_HASH }

    /** Verifies the file's SHA-256 matches [info.sha256]. */
    fun verify(file: File, info: ModelInfo): VerifyResult {
        val actual = sha256(file)
        if (actual == null || !actual.equals(info.sha256, ignoreCase = true)) {
            Log.w(TAG, "${info.filename}: hash mismatch (got $actual)")
            return VerifyResult.WRONG_HASH
        }
        return VerifyResult.OK
    }

    private fun sha256(file: File): String? {
        return try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    md.update(buffer, 0, read)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (t: Throwable) {
            Log.w(TAG, "SHA-256 computation failed", t); null
        }
    }

    enum class DownloadOutcome {
        /** All missing models were downloaded and verified. */
        SUCCESS,
        /** A network error occurred (transient) – caller should retry. */
        NETWORK_ERROR,
        /** A model was downloaded but its hash did not match (persistent). */
        HASH_MISMATCH
    }

    /**
     * Downloads every missing model. Progress callback aggregates over all
     * pending downloads (0-100 across the entire batch).
     */
    fun download(context: Context, onProgress: ((Int) -> Unit)? = null): DownloadOutcome {
        val pending = ALL_MODELS.filter { !isDownloaded(context, it) }
        if (pending.isEmpty()) return DownloadOutcome.SUCCESS

        pending.forEachIndexed { index, info ->
            val outcome = downloadOne(context, info) { p ->
                if (onProgress != null) {
                    val overall = ((index * 100 + p) / pending.size)
                    onProgress(overall)
                }
            }
            if (outcome != DownloadOutcome.SUCCESS) return outcome
        }
        return DownloadOutcome.SUCCESS
    }

    private fun downloadOne(
        context: Context,
        info: ModelInfo,
        onProgress: ((Int) -> Unit)?
    ): DownloadOutcome {
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
                return DownloadOutcome.NETWORK_ERROR
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
                return DownloadOutcome.NETWORK_ERROR
            }

            val verifyResult = verify(target, info)
            if (verifyResult != VerifyResult.OK) {
                Log.e(TAG, "${info.filename} failed hash verification, removing")
                target.delete()
                return DownloadOutcome.HASH_MISMATCH
            }

            Log.i(TAG, "Model ${info.filename} downloaded and verified (${target.length()} bytes)")
            DownloadOutcome.SUCCESS
        } catch (t: Throwable) {
            Log.e(TAG, "Download ${info.filename} failed", t)
            tempFile.delete()
            DownloadOutcome.NETWORK_ERROR
        }
    }
}
