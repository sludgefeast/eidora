package de.sebastian.eidora.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.io.Closeable
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.sqrt

private const val TAG = "FaceNetModel"

class FaceNetModel(context: Context) : Closeable {

    private val interpreter: Interpreter
    private val gpuDelegate: GpuDelegate?
    private val imageProcessor: ImageProcessor
    private val mutex = Mutex()

    val backend: String

    init {
        val modelFile = ModelDownloader.modelFile(context)
        if (!modelFile.exists()) {
            throw IllegalStateException(
                "FaceNet model not found at ${modelFile.absolutePath}. " +
                    "Call ModelDownloader.download() first."
            )
        }

        val mappedBuffer = RandomAccessFile(modelFile, "r").use { raf ->
            raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
        }

        // Try GPU first; fall back to CPU on any error.
        val (createdInterpreter, createdDelegate, backendName) = tryCreateGpuInterpreter(mappedBuffer)
            ?: run {
                Log.i(TAG, "Using CPU backend (4 threads)")
                val cpuOptions = Interpreter.Options().apply { numThreads = 4 }
                Triple(Interpreter(mappedBuffer, cpuOptions), null, "CPU")
            }

        interpreter = createdInterpreter
        gpuDelegate = createdDelegate
        backend = backendName

        imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(160, 160, ResizeOp.ResizeMethod.BILINEAR))
            .add(StandardizeOp())
            .build()
    }

    private fun tryCreateGpuInterpreter(
        buffer: java.nio.MappedByteBuffer
    ): Triple<Interpreter, GpuDelegate, String>? {
        return try {
            val compat = CompatibilityList()
            if (!compat.isDelegateSupportedOnThisDevice) {
                Log.i(TAG, "GPU delegate not supported on this device")
                return null
            }
            val delegate = GpuDelegate(compat.bestOptionsForThisDevice)
            val options = Interpreter.Options().addDelegate(delegate)
            val interp = Interpreter(buffer, options)
            Log.i(TAG, "Using GPU backend")
            Triple(interp, delegate, "GPU")
        } catch (t: Throwable) {
            Log.w(TAG, "GPU delegate init failed, falling back to CPU", t)
            null
        }
    }

    /**
     * Computes a 512-dimensional embedding for the given face bitmap.
     * Thread-safe: the TFLite interpreter is guarded by a mutex.
     */
    suspend fun computeEmbedding(faceBitmap: Bitmap): FloatArray {
        // Preprocessing runs concurrently across coroutines; only the ML inference is serialized.
        val tensorImage = TensorImage.fromBitmap(faceBitmap)
        val processed = imageProcessor.process(tensorImage)

        val outputBuffer = Array(1) { FloatArray(512) }
        mutex.withLock {
            interpreter.run(processed.buffer, outputBuffer)
        }
        return outputBuffer[0]
    }

    override fun close() {
        try { interpreter.close() } catch (t: Throwable) { /* ignore */ }
        try { gpuDelegate?.close() } catch (t: Throwable) { /* ignore */ }
    }

    // -----------------------------------------------------------------------
    // FloatArray utilities
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

        fun cosineDistance(a: FloatArray, b: FloatArray): Float {
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

        fun centroid(embeddings: List<FloatArray>): FloatArray {
            if (embeddings.isEmpty()) return FloatArray(512)
            val result = FloatArray(512)
            embeddings.forEach { emb ->
                for (i in emb.indices) result[i] += emb[i]
            }
            val n = embeddings.size.toFloat()
            for (i in result.indices) result[i] /= n
            return result
        }
    }
}

/**
 * Standardizes pixel values: x' = (x - mean) / std_dev
 * Required preprocessing for FaceNet512.
 */
private class StandardizeOp : org.tensorflow.lite.support.common.TensorOperator {
    override fun apply(input: org.tensorflow.lite.support.tensorbuffer.TensorBuffer):
            org.tensorflow.lite.support.tensorbuffer.TensorBuffer {
        val pixels = input.floatArray
        val mean = pixels.average().toFloat()
        var std = sqrt(pixels.map { (it - mean) * (it - mean) }.sum() / pixels.size.toFloat())
        std = max(std, 1f / sqrt(pixels.size.toFloat()))
        for (i in pixels.indices) pixels[i] = (pixels[i] - mean) / std
        val output = org.tensorflow.lite.support.tensorbuffer.TensorBufferFloat
            .createFixedSize(input.shape, org.tensorflow.lite.DataType.FLOAT32)
        output.loadArray(pixels)
        return output
    }
}
