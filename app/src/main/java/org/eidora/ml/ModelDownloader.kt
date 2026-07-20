package org.eidora.ml

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
        /** SHA-256 of the released asset; null skips verification. */
        val sha256: String?,
        /** String resource describing what the model is used for. */
        val purposeRes: Int,
        /** Human-readable license note shown before download. */
        val license: String,
    )

    private const val RELEASE_BASE = "https://github.com/sludgefeast/eidora/releases/download"

    val ALL_MODELS =
        listOf(
            ModelInfo(
                filename = "scrfd_2.5g_kps_640_float32.tflite",
                url = "$RELEASE_BASE/models-v2/scrfd_2.5g_kps_640_float32.tflite",
                sha256 = "e3663e23b85a9412bf0b57b3855e3f9350fcd1f188ea4753685f862ae87ffcb3",
                purposeRes = org.eidora.R.string.model_purpose_detection,
                license = "InsightFace SCRFD – non-commercial research",
            ),
            ModelInfo(
                filename = "arcface_w600k_mbf_float32.tflite",
                url = "$RELEASE_BASE/models-v3/arcface_w600k_mbf_float32.tflite",
                sha256 = "9ba9c5bd395e20d7f464d98978c22a8d1cdb4c27543ef19b85917099b7dfef30",
                purposeRes = org.eidora.R.string.model_purpose_embedding,
                license = "InsightFace ArcFace (WebFace600K) – non-commercial research",
            ),
        )

    fun modelFile(
        context: Context,
        info: ModelInfo,
    ): File = File(context.filesDir, info.filename)

    fun isDownloaded(
        context: Context,
        info: ModelInfo,
    ): Boolean {
        val file = modelFile(context, info)
        return file.exists() && verify(file, info) == VerifyResult.OK
    }

    /**
     * True when all models required for the pipeline are present.
     */
    fun allModelsReady(context: Context): Boolean = ALL_MODELS.all { isDownloaded(context, it) }

    // ---- Availability check (HTTP HEAD) ------------------------------------

    data class ModelAvailability(
        val info: ModelInfo,
        val available: Boolean,
        /** Size in bytes from Content-Length, or null if unknown. */
        val sizeBytes: Long?,
    )

    /**
     * Checks via HTTP HEAD whether each missing model is still downloadable
     * and how large it is. A non-2xx response marks the model unavailable –
     * usually meaning this app version is outdated and a newer release
     * bundles different models.
     */
    fun checkAvailability(context: Context): List<ModelAvailability> =
        ALL_MODELS
            .filter { !isDownloaded(context, it) }
            .map { info ->
                try {
                    val connection = URL(info.url).openConnection() as HttpURLConnection
                    connection.requestMethod = "HEAD"
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 15_000
                    connection.instanceFollowRedirects = true
                    connection.connect()
                    val ok = connection.responseCode in 200..299
                    val size = connection.contentLengthLong.takeIf { it > 0 }
                    connection.disconnect()
                    ModelAvailability(info, ok, size)
                } catch (t: Throwable) {
                    Log.w(TAG, "Availability check failed for ${info.filename}", t)
                    ModelAvailability(info, available = false, sizeBytes = null)
                }
            }

    enum class VerifyResult { OK, WRONG_HASH }

    /** Verifies the file's SHA-256 matches [ModelInfo.sha256]; null hash passes. */
    fun verify(
        file: File,
        info: ModelInfo,
    ): VerifyResult {
        val expected = info.sha256 ?: return VerifyResult.OK
        val actual = sha256(file)
        if (actual == null || !actual.equals(expected, ignoreCase = true)) {
            Log.w(TAG, "${info.filename}: hash mismatch (got $actual)")
            return VerifyResult.WRONG_HASH
        }
        return VerifyResult.OK
    }

    private fun sha256(file: File): String? =
        try {
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
            Log.w(TAG, "SHA-256 computation failed", t)
            null
        }

    enum class DownloadOutcome {
        /** All missing models were downloaded and verified. */
        SUCCESS,

        /** A network error occurred (transient) – caller should retry. */
        NETWORK_ERROR,

        /** A model was downloaded but its hash did not match (persistent). */
        HASH_MISMATCH,
    }

    /**
     * Downloads every missing model. Progress callback aggregates over all
     * pending downloads (0-100 across the entire batch).
     */
    fun download(
        context: Context,
        onProgress: ((Int) -> Unit)? = null,
    ): DownloadOutcome {
        val pending = ALL_MODELS.filter { !isDownloaded(context, it) }
        if (pending.isEmpty()) return DownloadOutcome.SUCCESS

        pending.forEachIndexed { index, info ->
            val outcome =
                downloadOne(context, info) { p ->
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
        onProgress: ((Int) -> Unit)?,
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
