package de.sebastian.eidora.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.Closeable
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

private const val TAG = "BlazeFaceDetector"

// MediaPipe BlazeFace Full-Range Sparse: 192x192 input, single anchor scale.
private const val INPUT_SIZE = 192
private const val NUM_ANCHORS = 2304
private const val NUM_COORDS = 16   // 4 bbox + 12 keypoints
private const val SCORE_THRESHOLD = 0.4f
private const val NMS_IOU_THRESHOLD = 0.3f

/**
 * On-device face detection using Google's BlazeFace Full-Range model
 * (MediaPipe). Reads the TFLite model from filesDir (downloaded via
 * ModelDownloader). Thread-safe – the interpreter is guarded by a mutex.
 */
class BlazeFaceDetector(context: Context) : Closeable {

    data class DetectedFace(
        /** Normalized [0..1] coordinates on the ORIGINAL bitmap. */
        val xMin: Float,
        val yMin: Float,
        val width: Float,
        val height: Float,
        /** Rotation in radians, derived from the eye-landmark axis. */
        val rotationRadians: Float,
        val score: Float
    )

    private val interpreter: Interpreter
    private val gpuDelegate: GpuDelegate?
    private val mutex = Mutex()
    private val anchors: FloatArray = generateAnchors()  // [NUM_ANCHORS * 2] (x_center, y_center)

    val backend: String

    init {
        val modelFile = ModelDownloader.modelFile(context, ModelDownloader.BLAZEFACE)
        if (!modelFile.exists()) {
            throw IllegalStateException("BlazeFace model not found at ${modelFile.absolutePath}")
        }
        val buffer = RandomAccessFile(modelFile, "r").use { raf ->
            raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
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
    }

    private fun tryCreateGpu(buffer: java.nio.MappedByteBuffer): Pair<Interpreter, GpuDelegate>? {
        return try {
            val compat = CompatibilityList()
            if (!compat.isDelegateSupportedOnThisDevice) return null
            val delegate = GpuDelegate()
            val options = Interpreter.Options().addDelegate(delegate)
            Pair(Interpreter(buffer, options), delegate)
        } catch (t: Throwable) {
            Log.w(TAG, "GPU init failed, using CPU", t); null
        }
    }

    /**
     * Runs detection on the given bitmap. Returns bounding boxes normalized
     * to the input bitmap's original width/height.
     */
    suspend fun detect(source: Bitmap): List<DetectedFace> {
        val resized = Bitmap.createScaledBitmap(source, INPUT_SIZE, INPUT_SIZE, true)
        val input = bitmapToBuffer(resized)
        if (resized !== source) resized.recycle()

        val rawBoxes = Array(1) { Array(NUM_ANCHORS) { FloatArray(NUM_COORDS) } }
        val rawScores = Array(1) { Array(NUM_ANCHORS) { FloatArray(1) } }

        mutex.withLock {
            val outputs = mapOf(0 to rawBoxes, 1 to rawScores)
            interpreter.runForMultipleInputsOutputs(arrayOf<Any>(input), outputs)
        }

        val decoded = decode(rawBoxes[0], rawScores[0])
        return nms(decoded)
    }

    private fun bitmapToBuffer(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4).order(ByteOrder.nativeOrder())
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (px in pixels) {
            // BlazeFace expects RGB, normalized to [-1, 1]
            val r = (px shr 16 and 0xFF) / 127.5f - 1f
            val g = (px shr 8 and 0xFF) / 127.5f - 1f
            val b = (px and 0xFF) / 127.5f - 1f
            buffer.putFloat(r); buffer.putFloat(g); buffer.putFloat(b)
        }
        buffer.rewind()
        return buffer
    }

    private fun decode(boxes: Array<FloatArray>, scores: Array<FloatArray>): List<DetectedFace> {
        val results = ArrayList<DetectedFace>()
        for (i in 0 until NUM_ANCHORS) {
            val score = sigmoid(scores[i][0].coerceIn(-80f, 80f))
            if (score < SCORE_THRESHOLD) continue

            val anchorX = anchors[i * 2]
            val anchorY = anchors[i * 2 + 1]
            val row = boxes[i]

            // Box: center-x, center-y, width, height (in input pixels)
            val cx = row[0] / INPUT_SIZE + anchorX
            val cy = row[1] / INPUT_SIZE + anchorY
            val w = row[2] / INPUT_SIZE
            val h = row[3] / INPUT_SIZE

            val xMin = cx - w / 2f
            val yMin = cy - h / 2f

            // Keypoints (6 pairs, indices 4..15 = kp0..kp5).
            // kp0 = right eye, kp1 = left eye (from the person's perspective).
            val rightEyeX = row[4] / INPUT_SIZE + anchorX
            val rightEyeY = row[5] / INPUT_SIZE + anchorY
            val leftEyeX = row[6] / INPUT_SIZE + anchorX
            val leftEyeY = row[7] / INPUT_SIZE + anchorY

            // Rotation: angle between the eye axis and horizontal.
            // Zero when eyes are level; positive when head tilted clockwise
            // (in image coordinates).
            val rotation = kotlin.math.atan2(
                (rightEyeY - leftEyeY).toDouble(),
                (rightEyeX - leftEyeX).toDouble()
            ).toFloat()

            results.add(
                DetectedFace(
                    xMin = xMin.coerceIn(0f, 1f),
                    yMin = yMin.coerceIn(0f, 1f),
                    width = w.coerceIn(0f, 1f),
                    height = h.coerceIn(0f, 1f),
                    rotationRadians = rotation,
                    score = score
                )
            )
        }
        return results
    }

    private fun nms(candidates: List<DetectedFace>): List<DetectedFace> {
        if (candidates.isEmpty()) return emptyList()
        val sorted = candidates.sortedByDescending { it.score }.toMutableList()
        val keep = ArrayList<DetectedFace>()
        while (sorted.isNotEmpty()) {
            val head = sorted.removeAt(0)
            keep.add(head)
            sorted.removeAll { iou(head, it) > NMS_IOU_THRESHOLD }
        }
        return keep
    }

    private fun iou(a: DetectedFace, b: DetectedFace): Float {
        val aX2 = a.xMin + a.width
        val aY2 = a.yMin + a.height
        val bX2 = b.xMin + b.width
        val bY2 = b.yMin + b.height
        val x1 = max(a.xMin, b.xMin)
        val y1 = max(a.yMin, b.yMin)
        val x2 = min(aX2, bX2)
        val y2 = min(aY2, bY2)
        if (x2 <= x1 || y2 <= y1) return 0f
        val inter = (x2 - x1) * (y2 - y1)
        val union = a.width * a.height + b.width * b.height - inter
        return if (union <= 0f) 0f else inter / union
    }

    private fun sigmoid(x: Float): Float = 1f / (1f + exp(-x))

    /**
     * MediaPipe BlazeFace Full-Range: single 48x48 feature map, 1 anchor per cell.
     * 48 * 48 = 2304 anchors.
     */
    private fun generateAnchors(): FloatArray {
        val gridSize = 48
        val anchors = FloatArray(NUM_ANCHORS * 2)
        var idx = 0
        for (y in 0 until gridSize) {
            for (x in 0 until gridSize) {
                anchors[idx++] = (x + 0.5f) / gridSize
                anchors[idx++] = (y + 0.5f) / gridSize
            }
        }
        return anchors
    }

    override fun close() {
        try { interpreter.close() } catch (t: Throwable) { /* ignore */ }
        try { gpuDelegate?.close() } catch (t: Throwable) { /* ignore */ }
    }
}
