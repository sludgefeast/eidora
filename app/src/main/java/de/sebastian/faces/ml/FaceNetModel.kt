package de.sebastian.faces.ml

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.ops.NormalizeOp
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

class FaceNetModel(context: Context) : Closeable {

    private val interpreter: Interpreter
    private val imageProcessor: ImageProcessor

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
        val options = Interpreter.Options().apply { numThreads = 4 }
        interpreter = Interpreter(mappedBuffer, options)

        imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(160, 160, ResizeOp.ResizeMethod.BILINEAR))
            .add(StandardizeOp())
            .build()
    }

    /**
     * Computes a 512-dimensional embedding for the given face bitmap.
     * Input should be a cropped face image (no padding).
     */
    fun computeEmbedding(faceBitmap: Bitmap): FloatArray {
        val tensorImage = TensorImage.fromBitmap(faceBitmap)
        val processed = imageProcessor.process(tensorImage)

        val outputBuffer = Array(1) { FloatArray(512) }
        interpreter.run(processed.buffer, outputBuffer)
        return outputBuffer[0]
    }

    override fun close() {
        interpreter.close()
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
