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
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

private const val TAG = "YuNetDetector"
private const val INPUT_SIZE = 640
private val STRIDES = intArrayOf(8, 16, 32)
private const val SCORE_THRESHOLD = 0.6f
private const val NMS_IOU_THRESHOLD = 0.3f

/**
 * Face detection using YuNet (OpenCV Zoo, Apache-2.0) converted to TFLite.
 *
 * YuNet is anchor-free with three strides (8/16/32). For each stride it emits,
 * per grid cell: a classification score (cls), an objectness score (obj), a
 * 4-value bbox (cx, cy, w, h offsets) and 10 keypoint values (5 x,y pairs). The
 * final score is sqrt(cls * obj) and the box is decoded relative to the cell.
 *
 * Input: NCHW float32 640x640, RGB, raw pixel values (no scaling) — matching
 * OpenCV's FaceDetectorYN preprocessing.
 *
 * Output is letterbox-corrected back to source-image pixel coordinates so the
 * result is interchangeable with SCRFD's.
 */
class YuNetDetector private constructor(
    private val loaded: TfliteLoader.Loaded,
    private val scoreThreshold: Float,
    private val nmsIouThreshold: Float,
) : FaceDetector {
    private val interpreter: Interpreter = loaded.interpreter
    private val gpuDelegate: GpuDelegate? = loaded.gpuDelegate
    private val mutex = Mutex()

    override val backend: String = loaded.backend

    // Output tensor indices grouped by stride, resolved from tensor shapes so
    // the code is robust to onnx2tf's output ordering.
    private data class StrideOutputs(
        val cls: Int,
        val obj: Int,
        val bbox: Int,
        val kps: Int,
    )

    private val strideOutputs: Map<Int, StrideOutputs>

    init {
        strideOutputs = resolveOutputIndices()
        Log.i(TAG, "YuNet initialized on $backend, outputs: $strideOutputs")
    }

    /** Legacy construction: loads the free YuNet from filesDir by spec filename. */
    constructor(context: Context) : this(
        TfliteLoader.createInterpreter(
            TfliteLoader.mapFile(java.io.File(context.filesDir, DetectionModelSpec.YUNET.filename)),
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

    /**
     * Maps each stride to its four output tensor indices by matching the number
     * of cells (grid*grid) and the last-dim width (1 cls/obj, 4 bbox, 10 kps).
     *
     * VERIFIED against the actual converted TFLite: onnx2tf preserves YuNet's
     * ONNX output order (cls_8/16/32, obj_8/16/32, bbox_*, kps_*), though it
     * renames tensors to Identity_N. So for each cell count the first width-1
     * tensor is cls and the second is obj — exactly what the counter below
     * assumes. The decode math (score = sqrt(cls*obj), box = (cell+off)*stride,
     * size = exp(v)*stride) is verified against OpenCV's reference on real
     * photos.
     */
    private fun resolveOutputIndices(): Map<Int, StrideOutputs> {
        val cellsToStride =
            STRIDES.associateBy { stride -> (INPUT_SIZE / stride) * (INPUT_SIZE / stride) }

        // For each stride collect candidate indices by role.
        val cls = HashMap<Int, Int>()
        val obj = HashMap<Int, Int>()
        val bbox = HashMap<Int, Int>()
        val kps = HashMap<Int, Int>()

        val count = interpreter.outputTensorCount
        // Both cls and obj are width-1 with the same cell count, so shape alone
        // can't tell them apart. Collect all width-1 indices per cell count and
        // resolve by tensor index: YuNet emits cls_* before obj_* (verified in
        // the converted TFLite), so the SMALLER output index is cls, the larger
        // is obj. Using the index rather than iteration order makes this robust
        // to any output reordering (e.g. lexicographic Identity_10 vs _2).
        val width1ByCells = HashMap<Int, MutableList<Int>>()

        for (i in 0 until count) {
            val shape = interpreter.getOutputTensor(i).shape()
            // Shapes look like [1, cells, w] (or [1, cells*w] flattened).
            val cells: Int
            val w: Int
            if (shape.size == 3) {
                cells = shape[1]
                w = shape[2]
            } else {
                // Flattened: infer from total and known widths.
                val total = shape.fold(1) { a, b -> a * b }
                val stride = cellsToStride.keys.firstOrNull { c -> total % c == 0 }
                cells = stride ?: continue
                w = total / cells
            }
            val strideForCells = cellsToStride[cells] ?: continue
            when (w) {
                1 -> width1ByCells.getOrPut(cells) { mutableListOf() }.add(i)
                4 -> bbox[strideForCells] = i
                10 -> kps[strideForCells] = i
            }
        }

        // Assign the two width-1 tensors per stride: smaller index = cls.
        for ((cells, indices) in width1ByCells) {
            val stride = cellsToStride[cells] ?: continue
            val sorted = indices.sorted()
            if (sorted.isNotEmpty()) cls[stride] = sorted[0]
            if (sorted.size > 1) obj[stride] = sorted[1]
        }

        return STRIDES.associateWith { stride ->
            StrideOutputs(
                cls = cls[stride] ?: error("YuNet: missing cls tensor for stride $stride"),
                obj = obj[stride] ?: error("YuNet: missing obj tensor for stride $stride"),
                bbox = bbox[stride] ?: error("YuNet: missing bbox tensor for stride $stride"),
                kps = kps[stride] ?: error("YuNet: missing kps tensor for stride $stride"),
            )
        }
    }

    override suspend fun detect(source: Bitmap): List<DetectedFace> {
        // Letterbox the source into a 640x640 square, keeping aspect ratio.
        val srcW = source.width
        val srcH = source.height
        val scale = min(INPUT_SIZE.toFloat() / srcW, INPUT_SIZE.toFloat() / srcH)
        val newW = (srcW * scale).toInt()
        val newH = (srcH * scale).toInt()
        val dx = (INPUT_SIZE - newW) / 2
        val dy = (INPUT_SIZE - newH) / 2

        val resized = Bitmap.createScaledBitmap(source, newW, newH, true)
        val square = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(square).apply {
            drawColor(android.graphics.Color.BLACK)
            drawBitmap(resized, dx.toFloat(), dy.toFloat(), null)
        }
        if (resized !== source) resized.recycle()

        val input = bitmapToBuffer(square)
        square.recycle()

        // Prepare output buffers matching each tensor's shape.
        val outputs = HashMap<Int, Any>()
        for (i in 0 until interpreter.outputTensorCount) {
            val shape = interpreter.getOutputTensor(i).shape()
            outputs[i] = createOutputBuffer(shape)
        }

        mutex.withLock {
            interpreter.runForMultipleInputsOutputs(arrayOf<Any>(input), outputs)
        }

        val candidates = ArrayList<DetectedFace>()
        for (stride in STRIDES) {
            decodeStride(stride, strideOutputs.getValue(stride), outputs, scale, dx, dy, srcW, srcH, candidates)
        }
        return nms(candidates)
    }

    private fun bitmapToBuffer(bitmap: Bitmap): ByteBuffer {
        // NHWC float32, RGB, raw pixels [0,255]. The converted TFLite input
        // tensor is [1,640,640,3] (channel-LAST), so pixels are interleaved
        // R,G,B per pixel — NOT channel-first. (The upstream ONNX was NCHW;
        // onnx2tf transposes to NHWC, and the preprocessing must match the
        // tflite, or the model receives scrambled data and emits noise.)
        val buffer =
            ByteBuffer.allocateDirect(3 * INPUT_SIZE * INPUT_SIZE * 4).order(ByteOrder.nativeOrder())
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (px in pixels) {
            buffer.putFloat(((px shr 16) and 0xFF).toFloat()) // R
            buffer.putFloat(((px shr 8) and 0xFF).toFloat()) // G
            buffer.putFloat((px and 0xFF).toFloat()) // B
        }
        buffer.rewind()
        return buffer
    }

    private fun createOutputBuffer(shape: IntArray): Any {
        // Allocate nested float arrays matching the tensor shape.
        return when (shape.size) {
            3 -> Array(shape[0]) { Array(shape[1]) { FloatArray(shape[2]) } }
            2 -> Array(shape[0]) { FloatArray(shape[1]) }
            else -> FloatArray(shape.fold(1) { a, b -> a * b })
        }
    }

    private fun floatAt(
        buf: Any,
        cell: Int,
        col: Int,
        cols: Int,
    ): Float {
        // Read element [0, cell, col] regardless of nesting depth.
        return when (buf) {
            is Array<*> -> {
                @Suppress("UNCHECKED_CAST")
                val a0 = buf[0]
                when (a0) {
                    is Array<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        (a0 as Array<FloatArray>)[cell][col]
                    }
                    is FloatArray -> a0[cell * cols + col]
                    else -> 0f
                }
            }
            is FloatArray -> buf[cell * cols + col]
            else -> 0f
        }
    }

    private fun decodeStride(
        stride: Int,
        idx: StrideOutputs,
        outputs: Map<Int, Any>,
        scale: Float,
        dx: Int,
        dy: Int,
        srcW: Int,
        srcH: Int,
        out: MutableList<DetectedFace>,
    ) {
        val grid = INPUT_SIZE / stride
        val clsBuf = outputs.getValue(idx.cls)
        val objBuf = outputs.getValue(idx.obj)
        val bboxBuf = outputs.getValue(idx.bbox)

        for (row in 0 until grid) {
            for (colIdx in 0 until grid) {
                val cell = row * grid + colIdx
                val clsScore = floatAt(clsBuf, cell, 0, 1)
                val objScore = floatAt(objBuf, cell, 0, 1)
                val score = kotlin.math.sqrt(max(0f, clsScore) * max(0f, objScore))
                if (score < scoreThreshold) continue

                // Decode bbox: offsets relative to the cell, times stride.
                val bx = floatAt(bboxBuf, cell, 0, 4)
                val by = floatAt(bboxBuf, cell, 1, 4)
                val bw = floatAt(bboxBuf, cell, 2, 4)
                val bh = floatAt(bboxBuf, cell, 3, 4)

                val cx = (colIdx + bx) * stride
                val cy = (row + by) * stride
                val w = exp(bw) * stride
                val h = exp(bh) * stride

                // Letterbox coords → source pixel coords.
                var xMin = (cx - w / 2f - dx) / scale
                var yMin = (cy - h / 2f - dy) / scale
                val wSrc = w / scale
                val hSrc = h / scale

                // Clamp to image.
                xMin = xMin.coerceIn(0f, srcW.toFloat())
                yMin = yMin.coerceIn(0f, srcH.toFloat())
                val wClamped = min(wSrc, srcW - xMin)
                val hClamped = min(hSrc, srcH - yMin)
                if (wClamped <= 1f || hClamped <= 1f) continue

                out.add(
                    DetectedFace(
                        xMin = xMin,
                        yMin = yMin,
                        width = wClamped,
                        height = hClamped,
                        rotationRadians = 0f, // YuNet does not output roll
                        score = score,
                    ),
                )
            }
        }
    }

    private fun nms(candidates: List<DetectedFace>): List<DetectedFace> {
        if (candidates.isEmpty()) return emptyList()
        val sorted = candidates.sortedByDescending { it.score }
        val keep = ArrayList<DetectedFace>()
        val removed = BooleanArray(sorted.size)
        for (i in sorted.indices) {
            if (removed[i]) continue
            val a = sorted[i]
            keep.add(a)
            for (j in i + 1 until sorted.size) {
                if (removed[j]) continue
                if (iou(a, sorted[j]) > nmsIouThreshold) removed[j] = true
            }
        }
        return keep
    }

    private fun iou(
        a: DetectedFace,
        b: DetectedFace,
    ): Float {
        val ax2 = a.xMin + a.width
        val ay2 = a.yMin + a.height
        val bx2 = b.xMin + b.width
        val by2 = b.yMin + b.height
        val ix1 = max(a.xMin, b.xMin)
        val iy1 = max(a.yMin, b.yMin)
        val ix2 = min(ax2, bx2)
        val iy2 = min(ay2, by2)
        val iw = max(0f, ix2 - ix1)
        val ih = max(0f, iy2 - iy1)
        val inter = iw * ih
        val union = a.width * a.height + b.width * b.height - inter
        return if (union <= 0f) 0f else inter / union
    }

    override fun close() {
        interpreter.close()
        gpuDelegate?.close()
    }
}
