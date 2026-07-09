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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private const val TAG = "YuNetDetector"

private const val INPUT_SIZE = 640
private val STRIDES = intArrayOf(8, 16, 32)
private const val SCORE_THRESHOLD = 0.6f
private const val NMS_IOU_THRESHOLD = 0.3f

/**
 * Face detection using YuNet (2023mar) converted to TFLite with a fixed
 * 640x640 input. Decodes the three-scale, anchor-free output of YuNet.
 *
 * Input: NHWC float32, BGR channel order, raw pixel values 0-255.
 * Outputs (12 tensors, matched by shape at runtime):
 *   per stride s in {8,16,32}, with n = (640/s)^2 grid cells:
 *   - cls  [1, n, 1]
 *   - obj  [1, n, 1]
 *   - bbox [1, n, 4]
 *   - kps  [1, n, 10]
 */
class YuNetDetector(context: Context) : Closeable {

    data class DetectedFace(
        val xMin: Float,
        val yMin: Float,
        val width: Float,
        val height: Float,
        val rotationRadians: Float,
        val score: Float
    )

    private val interpreter: Interpreter
    private val gpuDelegate: GpuDelegate?
    private val mutex = Mutex()

    val backend: String

    // Output tensor indices resolved by shape once at init.
    private data class ScaleOutputs(val cls: Int, val obj: Int, val bbox: Int, val kps: Int)
    private val scaleOutputs: Map<Int, ScaleOutputs>

    init {
        // The YuNet model is bundled as an APK asset at build time
        // (downloaded from the models-v1 release by the build workflow).
        val buffer = context.assets.openFd("yunet_640_float32.tflite").use { afd ->
            java.io.FileInputStream(afd.fileDescriptor).channel.map(
                FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength
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

        scaleOutputs = resolveOutputIndices()
        Log.i(TAG, "YuNet initialized on $backend, outputs: $scaleOutputs")
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
     * Maps output tensor indices to (stride, kind) by inspecting shapes.
     * For stride s: n = (INPUT_SIZE/s)^2. Shapes: [1,n,1] cls/obj, [1,n,4] bbox, [1,n,10] kps.
     * cls and obj are disambiguated by tensor order (cls_8 < obj_8 in the
     * original ONNX; onnx2tf preserves relative order of same-shape outputs).
     */
    private fun resolveOutputIndices(): Map<Int, ScaleOutputs> {
        val gridSizes = STRIDES.associateWith { (INPUT_SIZE / it) * (INPUT_SIZE / it) }
        // stride -> list of indices with [1, n, 1]
        val onesByStride = mutableMapOf<Int, MutableList<Int>>()
        val bboxByStride = mutableMapOf<Int, Int>()
        val kpsByStride = mutableMapOf<Int, Int>()

        for (i in 0 until interpreter.outputTensorCount) {
            val shape = interpreter.getOutputTensor(i).shape()
            if (shape.size != 3 || shape[0] != 1) continue
            val n = shape[1]
            val c = shape[2]
            val stride = gridSizes.entries.find { it.value == n }?.key ?: continue
            when (c) {
                1 -> onesByStride.getOrPut(stride) { mutableListOf() }.add(i)
                4 -> bboxByStride[stride] = i
                10 -> kpsByStride[stride] = i
            }
        }

        return STRIDES.associateWith { stride ->
            val ones = onesByStride[stride]
                ?: throw IllegalStateException("Missing cls/obj outputs for stride $stride")
            if (ones.size != 2) throw IllegalStateException("Expected 2 [1,n,1] outputs for stride $stride, got ${ones.size}")
            ScaleOutputs(
                cls = ones[0],
                obj = ones[1],
                bbox = bboxByStride[stride] ?: throw IllegalStateException("Missing bbox for stride $stride"),
                kps = kpsByStride[stride] ?: throw IllegalStateException("Missing kps for stride $stride")
            )
        }
    }

    suspend fun detect(source: Bitmap): List<DetectedFace> {
        val resized = Bitmap.createScaledBitmap(source, INPUT_SIZE, INPUT_SIZE, true)
        val input = bitmapToBgrBuffer(resized)
        if (resized !== source) resized.recycle()

        // Prepare output arrays
        val outputs = HashMap<Int, Any>()
        val buffers = HashMap<Int, Array<Array<FloatArray>>>()
        for ((_, idx) in scaleOutputs) {
            for ((tensorIdx, channels) in listOf(idx.cls to 1, idx.obj to 1, idx.bbox to 4, idx.kps to 10)) {
                val n = interpreter.getOutputTensor(tensorIdx).shape()[1]
                val arr = Array(1) { Array(n) { FloatArray(channels) } }
                outputs[tensorIdx] = arr
                buffers[tensorIdx] = arr
            }
        }

        mutex.withLock {
            interpreter.runForMultipleInputsOutputs(arrayOf<Any>(input), outputs)
        }

        val candidates = ArrayList<DetectedFace>()
        for (stride in STRIDES) {
            val idx = scaleOutputs[stride]!!
            val cls = buffers[idx.cls]!![0]
            val obj = buffers[idx.obj]!![0]
            val bbox = buffers[idx.bbox]!![0]
            val kps = buffers[idx.kps]!![0]
            decodeScale(stride, cls, obj, bbox, kps, candidates)
        }
        return nms(candidates)
    }

    private fun decodeScale(
        stride: Int,
        cls: Array<FloatArray>,
        obj: Array<FloatArray>,
        bbox: Array<FloatArray>,
        kps: Array<FloatArray>,
        out: MutableList<DetectedFace>
    ) {
        val cols = INPUT_SIZE / stride
        val n = cols * cols
        for (i in 0 until n) {
            val clsScore = cls[i][0].coerceIn(0f, 1f)
            val objScore = obj[i][0].coerceIn(0f, 1f)
            val score = sqrt(clsScore * objScore)
            if (score < SCORE_THRESHOLD) continue

            val row = i / cols
            val col = i % cols

            val cx = (col + bbox[i][0]) * stride
            val cy = (row + bbox[i][1]) * stride
            val w = exp(bbox[i][2].coerceAtMost(10f)) * stride
            val h = exp(bbox[i][3].coerceAtMost(10f)) * stride

            val xMin = (cx - w / 2f) / INPUT_SIZE
            val yMin = (cy - h / 2f) / INPUT_SIZE

            // Landmarks: (right eye, left eye, nose, right mouth, left mouth)
            val rightEyeX = (col + kps[i][0]) * stride
            val rightEyeY = (row + kps[i][1]) * stride
            val leftEyeX = (col + kps[i][2]) * stride
            val leftEyeY = (row + kps[i][3]) * stride

            val rotation = kotlin.math.atan2(
                (rightEyeY - leftEyeY).toDouble(),
                (rightEyeX - leftEyeX).toDouble()
            ).toFloat()

            out.add(
                DetectedFace(
                    xMin = xMin.coerceIn(0f, 1f),
                    yMin = yMin.coerceIn(0f, 1f),
                    width = (w / INPUT_SIZE).coerceIn(0f, 1f),
                    height = (h / INPUT_SIZE).coerceIn(0f, 1f),
                    rotationRadians = rotation,
                    score = score
                )
            )
        }
    }

    private fun bitmapToBgrBuffer(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4).order(ByteOrder.nativeOrder())
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (px in pixels) {
            // YuNet expects BGR order, raw 0-255 values (no normalization)
            val r = (px shr 16 and 0xFF).toFloat()
            val g = (px shr 8 and 0xFF).toFloat()
            val b = (px and 0xFF).toFloat()
            buffer.putFloat(b); buffer.putFloat(g); buffer.putFloat(r)
        }
        buffer.rewind()
        return buffer
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

    override fun close() {
        try { interpreter.close() } catch (t: Throwable) { /* ignore */ }
        try { gpuDelegate?.close() } catch (t: Throwable) { /* ignore */ }
    }
}
