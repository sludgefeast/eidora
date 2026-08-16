// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.ml.container

import org.eidora.util.EidoraLog
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.channels.FileChannel

/**
 * Class-2 validation: does each model's declared `output.type` fit the actual
 * TFLite tensor structure? (Class-1 well-formedness is done by the parser;
 * Class-3 self-test on real faces happens later.)
 *
 * This mirrors scripts/pack_container.py's tensor check so an import is held to
 * the same bar as a packed container. It reads only tensor shapes — never a
 * value the manifest restates, since the manifest carries no derivable values.
 */
object ContainerValidator {
    private const val TAG = "ContainerValidator"

    private val STRIDES = intArrayOf(8, 16, 32)

    sealed interface Result {
        data object Ok : Result

        data class Failed(val detail: String) : Result
    }

    /**
     * Validates every model file in an unpacked container dir against its
     * manifest. Returns the first failure, or Ok if all pass.
     */
    fun validate(dir: File, manifest: ContainerManifest): Result {
        for (model in manifest.models) {
            val file = File(dir, model.file)
            if (!file.isFile) {
                return Result.Failed("model file missing: ${model.file}")
            }
            val result =
                try {
                    checkModel(file, model)
                } catch (t: Throwable) {
                    EidoraLog.e(TAG, "Validation error for ${model.id}", t)
                    return Result.Failed("could not load ${model.id}: ${t.message}")
                }
            if (result is Result.Failed) return result
        }
        return Result.Ok
    }

    private fun checkModel(file: File, model: ContainerManifest.ModelEntry): Result {
        val buffer =
            java.io.FileInputStream(file).use { fis ->
                fis.channel.map(FileChannel.MapMode.READ_ONLY, 0, fis.channel.size())
            }
        Interpreter(buffer, Interpreter.Options()).use { interp ->
            val inputShape = interp.getInputTensor(0).shape()
            val outCount = interp.outputTensorCount
            val outShapes = (0 until outCount).map { interp.getOutputTensor(it).shape() }

            return when (model.output.type) {
                "single_vector" -> {
                    if (outCount != 1 || outShapes[0].size != 2) {
                        Result.Failed(
                            "${model.id}: expected one [1,D] output for single_vector, " +
                                "got ${outShapes.map { it.toList() }}",
                        )
                    } else {
                        Result.Ok
                    }
                }
                "multistride_scrfd", "multistride_yunet" -> {
                    checkMultistride(model.id, inputShape, outShapes)
                }
                else -> Result.Failed("${model.id}: unknown output.type ${model.output.type}")
            }
        }
    }

    /**
     * The multistride families output, per stride, tensors whose cell count is
     * grid*anchors where grid = (inputSize/stride)^2 and anchors is 1 or 2.
     * We derive the input size from the tensor (never from the manifest) and
     * confirm every output cell count fits some stride's grid.
     */
    private fun checkMultistride(
        id: String,
        inputShape: IntArray,
        outShapes: List<IntArray>,
    ): Result {
        if (inputShape.size != 4) {
            return Result.Failed("$id: expected a 4-D input, got ${inputShape.toList()}")
        }
        // NHWC [1,S,S,3] or NCHW [1,3,S,S] — pick the spatial dim.
        val size = if (inputShape[3] == 3) inputShape[1] else inputShape[2]
        if (size <= 0) {
            return Result.Failed("$id: could not read a valid input size")
        }
        val grids = STRIDES.map { (size / it) * (size / it) }.toSet()
        // The cell-count dimension is the large one. YuNet outputs are 3-D
        // [1, cells, channels] (cells in dim 1); SCRFD outputs are 2-D
        // [cells, channels] (cells in dim 0). Reading a fixed index breaks one
        // of them, so take the max dimension of each output as its cell count —
        // channels (1/4/10) are always far smaller than cells (hundreds+).
        val cellCounts =
            outShapes
                .filter { it.isNotEmpty() }
                .map { shape -> shape.maxOrNull() ?: 0 }
                .toSet()
        val allFit =
            cellCounts.all { cc ->
                grids.any { g -> g != 0 && cc % g == 0 && (cc / g == 1 || cc / g == 2) }
            }
        return if (allFit) {
            Result.Ok
        } else {
            Result.Failed(
                "$id: output cell counts $cellCounts don't match a stride pyramid " +
                    "for input size $size (grids $grids) — wrong output.type?",
            )
        }
    }
}
