package org.eidora.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.sqrt

private const val TAG = "EmbeddingModel"
private const val INPUT_SIZE = 112
private const val EMBEDDING_DIM = 512

/**
 * Face embedding using ArcFace w600k_mbf (MobileFaceNet backbone,
 * InsightFace) converted to TFLite. Bundled as an APK asset at build time
 * (downloaded from the models-v3 release by the build workflow).
 *
 * Input: NHWC float32 112x112, RGB, normalized as (pixel - 127.5) / 127.5.
 * Output: 512-dim embedding (unnormalized; cosineDistance normalizes).
 * Thread-safe: the TFLite interpreter is guarded by a mutex.
 */
class EmbeddingModel(
    context: Context,
) : Closeable {
    private val interpreter: Interpreter
    private val gpuDelegate: GpuDelegate?
    private val mutex = Mutex()

    val backend: String

    init {
        val buffer =
            context.assets.openFd("arcface_w600k_mbf_float32.tflite").use { afd ->
                java.io.FileInputStream(afd.fileDescriptor).channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    afd.startOffset,
                    afd.declaredLength,
                )
            }

        val gpu = tryCreateGpu(buffer)
        if (gpu != null) {
            interpreter = gpu.first
            gpuDelegate = gpu.second
            backend = "GPU"
        } else {
            interpreter = Interpreter(buffer, Interpreter.Options().apply { numThreads = 4 })
            gpuDelegate = null
            backend = "CPU"
        }
        Log.i(TAG, "ArcFace embedding model initialized on $backend")
    }

    private fun tryCreateGpu(buffer: java.nio.MappedByteBuffer): Pair<Interpreter, GpuDelegate>? {
        return try {
            val compat = CompatibilityList()
            if (!compat.isDelegateSupportedOnThisDevice) return null
            val delegate = GpuDelegate()
            val options = Interpreter.Options().addDelegate(delegate)
            Pair(Interpreter(buffer, options), delegate)
        } catch (t: Throwable) {
            Log.w(TAG, "GPU delegate init failed, falling back to CPU", t)
            null
        }
    }

    /**
     * Computes a 512-dimensional embedding for the given face bitmap.
     * Preprocessing runs concurrently across coroutines; only the ML
     * inference is serialized.
     */
    suspend fun computeEmbedding(faceBitmap: Bitmap): FloatArray {
        val resized = Bitmap.createScaledBitmap(faceBitmap, INPUT_SIZE, INPUT_SIZE, true)
        val input = bitmapToBuffer(resized)
        if (resized !== faceBitmap) resized.recycle()

        val outputBuffer = Array(1) { FloatArray(EMBEDDING_DIM) }
        mutex.withLock {
            interpreter.run(input, outputBuffer)
        }
        return outputBuffer[0]
    }

    private fun bitmapToBuffer(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4).order(ByteOrder.nativeOrder())
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (px in pixels) {
            // ArcFace expects RGB, normalized as (x - 127.5) / 127.5
            val r = ((px shr 16 and 0xFF) - 127.5f) / 127.5f
            val g = ((px shr 8 and 0xFF) - 127.5f) / 127.5f
            val b = ((px and 0xFF) - 127.5f) / 127.5f
            buffer.putFloat(r)
            buffer.putFloat(g)
            buffer.putFloat(b)
        }
        buffer.rewind()
        return buffer
    }

    override fun close() {
        try {
            interpreter.close()
        } catch (t: Throwable) {
            // ignore
        }
        try {
            gpuDelegate?.close()
        } catch (t: Throwable) {
            // ignore
        }
    }

    // -----------------------------------------------------------------------
    // FloatArray utilities (model-independent)
    // -----------------------------------------------------------------------

    companion object {
        fun floatArrayToBytes(array: FloatArray): ByteArray {
            val buffer = ByteBuffer.allocate(array.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            array.forEach { buffer.putFloat(it) }
            return buffer.array()
        }

        fun bytesToFloatArray(bytes: ByteArray): FloatArray {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(bytes.size / 4) { buffer.float }
        }

        fun cosineDistance(
            a: FloatArray,
            b: FloatArray,
        ): Float {
            var dot = 0f
            var normA = 0f
            var normB = 0f
            for (i in a.indices) {
                dot += a[i] * b[i]
                normA += a[i] * a[i]
                normB += b[i] * b[i]
            }
            val denom = sqrt(normA) * sqrt(normB)
            return if (denom < 1e-10f) 1f else 1f - (dot / denom)
        }

        fun centroid(embeddings: List<FloatArray>): FloatArray = weightedCentroid(embeddings.map { it to 1f })

        /**
         * Weighted centroid: each embedding is weighted by its quality score.
         * Falls back to equal weights if all weights are zero or the list is empty.
         *
         * @param embeddingsWithWeights list of (embedding, qualityScore) pairs
         */
        fun weightedCentroid(embeddingsWithWeights: List<Pair<FloatArray, Float>>): FloatArray {
            if (embeddingsWithWeights.isEmpty()) return FloatArray(EMBEDDING_DIM)

            val totalWeight = embeddingsWithWeights.sumOf { it.second.toDouble() }.toFloat()
            val norm = if (totalWeight > 1e-6f) totalWeight else embeddingsWithWeights.size.toFloat()

            val result = FloatArray(EMBEDDING_DIM)
            embeddingsWithWeights.forEach { (emb, weight) ->
                val w = if (totalWeight > 1e-6f) weight else 1f
                for (i in emb.indices) result[i] += emb[i] * w
            }
            for (i in result.indices) result[i] /= norm
            return result
        }
    }
}
