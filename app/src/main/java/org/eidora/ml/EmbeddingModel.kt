// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
class EmbeddingModel private constructor(
    private val loaded: org.eidora.ml.TfliteLoader.Loaded,
    private val normalization: EmbeddingModelSpec.Normalization,
    private val label: String,
) : Closeable {
    private val interpreter: Interpreter = loaded.interpreter
    private val gpuDelegate: GpuDelegate? = loaded.gpuDelegate
    private val mutex = Mutex()

    // Input size and embedding dimension are DERIVED from the model's tensors,
    // not restated by any spec/manifest — the tflite is the single source of
    // truth for these shape values.
    private val inputSize: Int = interpreter.getInputTensor(0).shape().let { s ->
        // NHWC [1,S,S,3] or NCHW [1,3,S,S]
        if (s.size == 4 && s[3] == 3) {
            s[1]
        } else if (s.size == 4) {
            s[2]
        } else {
            112
        }
    }
    private val embeddingDim: Int = interpreter.getOutputTensor(0).shape().let { s ->
        s[s.size - 1]
    }

    val backend: String = loaded.backend

    init {
        Log.i(TAG, "Embedding model '$label' initialized on $backend " +
            "(input $inputSize, dim $embeddingDim)")
    }

    /** Legacy spec-based construction (loads from filesDir by spec filename). */
    constructor(
        context: Context,
        spec: EmbeddingModelSpec = EmbeddingModelSpec.DEFAULT,
    ) : this(
        org.eidora.ml.TfliteLoader.createInterpreter(
            org.eidora.ml.TfliteLoader.mapFile(java.io.File(context.filesDir, spec.filename)),
        ),
        spec.normalization,
        spec.id,
    )

    /** Container-based construction from an explicit model file. */
    constructor(
        context: Context,
        modelFile: java.io.File,
        normalization: EmbeddingModelSpec.Normalization,
    ) : this(
        org.eidora.ml.TfliteLoader.createInterpreter(
            org.eidora.ml.TfliteLoader.mapFile(modelFile),
        ),
        normalization,
        modelFile.name,
    )

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
        for (px in pixels) {
            val rRaw = (px shr 16 and 0xFF)
            val gRaw = (px shr 8 and 0xFF)
            val bRaw = (px and 0xFF)
            when (normalization) {
                EmbeddingModelSpec.Normalization.SIGNED_UNIT -> {
                    // (x - 127.5) / 127.5 → [-1, 1]
                    buffer.putFloat((rRaw - 127.5f) / 127.5f)
                    buffer.putFloat((gRaw - 127.5f) / 127.5f)
                    buffer.putFloat((bRaw - 127.5f) / 127.5f)
                }
                EmbeddingModelSpec.Normalization.ZERO_TO_ONE -> {
                    // x / 255 → [0, 1]
                    buffer.putFloat(rRaw / 255f)
                    buffer.putFloat(gRaw / 255f)
                    buffer.putFloat(bRaw / 255f)
                }
                EmbeddingModelSpec.Normalization.RAW_0_255 -> {
                    // raw pixel values, no scaling (OpenCV SFace)
                    buffer.putFloat(rRaw.toFloat())
                    buffer.putFloat(gRaw.toFloat())
                    buffer.putFloat(bRaw.toFloat())
                }
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

        /**
         * Picks the representative face for a person: the one whose embedding is
         * closest to the person's weighted centroid. Returns the index into
         * [embeddingsWithWeights], or -1 if the list is empty.
         *
         * A single face must always yield index 0 — that's the first-run case
         * (one named face imported from XMP metadata) where the person's avatar
         * would otherwise stay unset. Kept pure and separate from DAO access so
         * this behavior is unit-testable.
         */
        fun representativeFaceIndex(embeddingsWithWeights: List<Pair<FloatArray, Float>>): Int {
            if (embeddingsWithWeights.isEmpty()) return -1
            if (embeddingsWithWeights.size == 1) return 0
            val centroid = weightedCentroid(embeddingsWithWeights)
            var bestIdx = 0
            var bestDist = Float.MAX_VALUE
            embeddingsWithWeights.forEachIndexed { idx, (emb, _) ->
                val d = cosineDistance(emb, centroid)
                if (d < bestDist) {
                    bestDist = d
                    bestIdx = idx
                }
            }
            return bestIdx
        }
    }
}
