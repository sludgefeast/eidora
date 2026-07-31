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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

private const val TAG = "ScrfdDetector"

private const val INPUT_SIZE = 640
private val STRIDES = intArrayOf(8, 16, 32)
private const val NUM_ANCHORS_PER_CELL = 2
private const val SCORE_THRESHOLD = 0.5f
private const val NMS_IOU_THRESHOLD = 0.4f

/**
 * Face detection using SCRFD_2.5G_KPS (InsightFace) converted to TFLite
 * with a fixed 640x640 input.
 *
 * Input: NHWC float32, RGB, normalized as (pixel - 127.5) / 128.
 * Outputs (9 tensors, matched by shape at runtime):
 *   per stride s in {8,16,32}, with n = (640/s)^2 * 2 anchors:
 *   - score [1, n, 1]
 *   - bbox  [1, n, 4]  (distances left/top/right/bottom in stride units)
 *   - kps   [1, n, 10] (5 landmark offsets in stride units)
 */
class ScrfdDetector private constructor(
    private val loaded: TfliteLoader.Loaded,
    private val scoreThreshold: Float,
    private val nmsIouThreshold: Float,
) : FaceDetector {

    private val interpreter: Interpreter = loaded.interpreter
    private val gpuDelegate: GpuDelegate? = loaded.gpuDelegate
    private val mutex = Mutex()

    override val backend: String = loaded.backend

    private data class ScaleOutputs(
        val score: Int,
        val bbox: Int,
        val kps: Int,
    )

    private val scaleOutputs: Map<Int, ScaleOutputs>

    init {
        scaleOutputs = resolveOutputIndices()
        Log.i(TAG, "SCRFD initialized on $backend, outputs: $scaleOutputs")
    }

    /** Legacy construction: loads SCRFD from filesDir by fixed filename. */
    constructor(context: Context) : this(
        TfliteLoader.createInterpreter(
            TfliteLoader.mapFile(
                java.io.File(context.filesDir, "scrfd_2.5g_kps_640_float32.tflite"),
            ),
        ),
        SCORE_THRESHOLD,
        NMS_IOU_THRESHOLD,
    )

    /** Container construction from an explicit model file with manifest thresholds. */
    constructor(
        context: Context,
        modelFile: java.io.File,
        scoreThreshold: Float,
        nmsIouThreshold: Float,
    ) : this(
        TfliteLoader.createInterpreter(TfliteLoader.mapFile(modelFile)),
        scoreThreshold,
        nmsIouThreshold,
    )

    private fun resolveOutputIndices(): Map<Int, ScaleOutputs> {
        val anchorCounts =
            STRIDES.associateWith {
                (INPUT_SIZE / it) * (INPUT_SIZE / it) * NUM_ANCHORS_PER_CELL
            }
        val scoreByStride = mutableMapOf<Int, Int>()
        val bboxByStride = mutableMapOf<Int, Int>()
        val kpsByStride = mutableMapOf<Int, Int>()

        for (i in 0 until interpreter.outputTensorCount) {
            val shape = interpreter.getOutputTensor(i).shape()
            Log.i(TAG, "Output tensor $i shape: ${shape.contentToString()}")
            // Accept both [1, n, c] (batched) and [n, c] (non-batched) layouts
            val (n, c) =
                when {
                    shape.size == 3 && shape[0] == 1 -> shape[1] to shape[2]
                    shape.size == 2 -> shape[0] to shape[1]
                    else -> continue
                }
            val stride = anchorCounts.entries.find { it.value == n }?.key ?: continue
            when (c) {
                1 -> scoreByStride[stride] = i
                4 -> bboxByStride[stride] = i
                10 -> kpsByStride[stride] = i
            }
        }

        return STRIDES.associateWith { stride ->
            ScaleOutputs(
                score =
                    scoreByStride[stride]
                        ?: throw IllegalStateException("Missing score output for stride $stride"),
                bbox =
                    bboxByStride[stride]
                        ?: throw IllegalStateException("Missing bbox output for stride $stride"),
                kps =
                    kpsByStride[stride]
                        ?: throw IllegalStateException("Missing kps output for stride $stride"),
            )
        }
    }

    /** Creates a correctly-shaped output buffer for tensor [tensorIdx]. */
    private fun makeOutputBuffer(tensorIdx: Int): Any {
        val shape = interpreter.getOutputTensor(tensorIdx).shape()
        return if (shape.size == 3) {
            Array(shape[0]) { Array(shape[1]) { FloatArray(shape[2]) } }
        } else {
            Array(shape[0]) { FloatArray(shape[1]) }
        }
    }

    /** Unwraps a 2D or 3D output buffer into rows of FloatArray. */
    @Suppress("UNCHECKED_CAST")
    private fun rows(buffer: Any): Array<FloatArray> =
        when {
            buffer is Array<*> && buffer.size == 1 && buffer[0] is Array<*> ->
                buffer[0] as Array<FloatArray>
            else -> buffer as Array<FloatArray>
        }

    override suspend fun detect(source: Bitmap): List<DetectedFace> {
        val resized = Bitmap.createScaledBitmap(source, INPUT_SIZE, INPUT_SIZE, true)
        val input = bitmapToBuffer(resized)
        if (resized !== source) resized.recycle()

        val outputs = HashMap<Int, Any>()
        for ((_, idx) in scaleOutputs) {
            for (tensorIdx in listOf(idx.score, idx.bbox, idx.kps)) {
                outputs[tensorIdx] = makeOutputBuffer(tensorIdx)
            }
        }

        mutex.withLock {
            interpreter.runForMultipleInputsOutputs(arrayOf<Any>(input), outputs)
        }

        val candidates = ArrayList<DetectedFace>()
        for (stride in STRIDES) {
            val idx = scaleOutputs[stride]!!
            decodeScale(
                stride,
                rows(outputs[idx.score]!!),
                rows(outputs[idx.bbox]!!),
                rows(outputs[idx.kps]!!),
                source.width,
                source.height,
                candidates,
            )
        }
        return nms(candidates)
    }

    private fun decodeScale(
        stride: Int,
        scores: Array<FloatArray>,
        bbox: Array<FloatArray>,
        kps: Array<FloatArray>,
        srcW: Int,
        srcH: Int,
        out: MutableList<DetectedFace>,
    ) {
        val cols = INPUT_SIZE / stride
        val n = cols * cols * NUM_ANCHORS_PER_CELL
        for (i in 0 until n) {
            val score = scores[i][0]
            if (score < scoreThreshold) continue

            val cellIdx = i / NUM_ANCHORS_PER_CELL
            val row = cellIdx / cols
            val col = cellIdx % cols
            val cx = col * stride.toFloat()
            val cy = row * stride.toFloat()

            // distance2bbox: distances are in stride units
            val x1 = cx - bbox[i][0] * stride
            val y1 = cy - bbox[i][1] * stride
            val x2 = cx + bbox[i][2] * stride
            val y2 = cy + bbox[i][3] * stride

            // Landmarks: right eye, left eye, nose, right mouth, left mouth
            val rightEyeX = cx + kps[i][0] * stride
            val rightEyeY = cy + kps[i][1] * stride
            val leftEyeX = cx + kps[i][2] * stride
            val leftEyeY = cy + kps[i][3] * stride

            val rotation =
                kotlin.math
                    .atan2(
                        (rightEyeY - leftEyeY).toDouble(),
                        (rightEyeX - leftEyeX).toDouble(),
                    ).toFloat()

            // The input was scaled (distorted, no letterbox) from source to
            // INPUT_SIZE, so map the box back to SOURCE PIXELS: normalize by
            // INPUT_SIZE, then multiply by the source dimension. DetectedFace
            // coords are source pixels (same contract as YuNetDetector), so the
            // pipeline and self-test treat both detectors identically.
            val sx = srcW.toFloat() / INPUT_SIZE
            val sy = srcH.toFloat() / INPUT_SIZE
            val px1 = (x1 * sx).coerceIn(0f, srcW.toFloat())
            val py1 = (y1 * sy).coerceIn(0f, srcH.toFloat())
            val px2 = (x2 * sx).coerceIn(0f, srcW.toFloat())
            val py2 = (y2 * sy).coerceIn(0f, srcH.toFloat())

            out.add(
                DetectedFace(
                    xMin = px1,
                    yMin = py1,
                    width = px2 - px1,
                    height = py2 - py1,
                    rotationRadians = rotation,
                    score = score,
                ),
            )
        }
    }

    private fun bitmapToBuffer(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4).order(ByteOrder.nativeOrder())
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (px in pixels) {
            // SCRFD expects RGB, normalized as (x - 127.5) / 128
            val r = ((px shr 16 and 0xFF) - 127.5f) / 128f
            val g = ((px shr 8 and 0xFF) - 127.5f) / 128f
            val b = ((px and 0xFF) - 127.5f) / 128f
            buffer.putFloat(r)
            buffer.putFloat(g)
            buffer.putFloat(b)
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
            sorted.removeAll { iou(head, it) > nmsIouThreshold }
        }
        return keep
    }

    private fun iou(
        a: DetectedFace,
        b: DetectedFace,
    ): Float {
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
}
