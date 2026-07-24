// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

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

/**
 * Face embedding using a pluggable 112x112 TFLite model described by an
 * [EmbeddingModelSpec]. The spec supplies the filename, input size, embedding
 * dimension and pixel normalization, so different models (research-grade
 * ArcFace or a freely-licensed MobileFaceNet) share this one inference path.
 *
 * Input: NHWC float32, RGB, normalized per [EmbeddingModelSpec.normalization].
 * Output: [EmbeddingModelSpec.embeddingDim]-dim embedding (unnormalized;
 * cosineDistance normalizes).
 * Thread-safe: the TFLite interpreter is guarded by a mutex.
 */
class EmbeddingModel(
    context: Context,
    private val spec: EmbeddingModelSpec = EmbeddingModelSpec.DEFAULT,
) : Closeable {
    private val interpreter: Interpreter
    private val gpuDelegate: GpuDelegate?
    private val mutex = Mutex()

    private val inputSize = spec.inputSize
    private val embeddingDim = spec.embeddingDim

    val backend: String

    init {
        // Loaded from filesDir – downloaded at runtime after user consent.
        val modelFile = java.io.File(context.filesDir, spec.filename)
        val buffer =
            java.io.FileInputStream(modelFile).channel.use { channel ->
                channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
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
        Log.i(TAG, "Embedding model '${spec.id}' initialized on $backend")
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
     * Computes the face embedding for the given bitmap (dimension depends on
     * the model spec).
     * Preprocessing runs concurrently across coroutines; only the ML
     * inference is serialized.
     */
    suspend fun computeEmbedding(faceBitmap: Bitmap): FloatArray {
        val resized = Bitmap.createScaledBitmap(faceBitmap, inputSize, inputSize, true)
        val input = bitmapToBuffer(resized)
        if (resized !== faceBitmap) resized.recycle()

        val outputBuffer = Array(1) { FloatArray(embeddingDim) }
        mutex.withLock {
            interpreter.run(input, outputBuffer)
        }
        return outputBuffer[0]
    }

    private fun bitmapToBuffer(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(inputSize * inputSize * 3 * 4).order(ByteOrder.nativeOrder())
        val pixels = IntArray(inputSize * inputSize)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        val signed = spec.normalization == EmbeddingModelSpec.Normalization.SIGNED_UNIT
        for (px in pixels) {
            val rRaw = (px shr 16 and 0xFF)
            val gRaw = (px shr 8 and 0xFF)
            val bRaw = (px and 0xFF)
            if (signed) {
                // (x - 127.5) / 127.5 → [-1, 1]
                buffer.putFloat((rRaw - 127.5f) / 127.5f)
                buffer.putFloat((gRaw - 127.5f) / 127.5f)
                buffer.putFloat((bRaw - 127.5f) / 127.5f)
            } else {
                // x / 255 → [0, 1]
                buffer.putFloat(rRaw / 255f)
                buffer.putFloat(gRaw / 255f)
                buffer.putFloat(bRaw / 255f)
            }
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
         * The dimension is taken from the embeddings themselves, so this works
         * for any model.
         *
         * @param embeddingsWithWeights list of (embedding, qualityScore) pairs
         */
        fun weightedCentroid(embeddingsWithWeights: List<Pair<FloatArray, Float>>): FloatArray {
            if (embeddingsWithWeights.isEmpty()) return FloatArray(0)
            val dim = embeddingsWithWeights.first().first.size

            val totalWeight = embeddingsWithWeights.sumOf { it.second.toDouble() }.toFloat()
            val norm = if (totalWeight > 1e-6f) totalWeight else embeddingsWithWeights.size.toFloat()

            val result = FloatArray(dim)
            embeddingsWithWeights.forEach { (emb, weight) ->
                val w = if (totalWeight > 1e-6f) weight else 1f
                for (i in emb.indices) result[i] += emb[i] * w
            }
            for (i in result.indices) result[i] /= norm
            return result
        }
    }
}
