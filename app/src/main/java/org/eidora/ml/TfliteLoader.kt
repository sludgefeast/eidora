// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.ml

import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.File
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Shared helpers for loading a TFLite model from an arbitrary file and creating
 * an interpreter with GPU-then-CPU fallback.
 *
 * The per-model classes (YuNetDetector, ScrfdDetector, EmbeddingModel) each
 * grew their own copy of "mmap the file, try GPU, fall back to CPU". As models
 * move into containers they must load from an arbitrary path rather than a
 * fixed filesDir name, so that logic lives here once and both the legacy
 * spec-based constructors and the new container-based ones use it.
 */
object TfliteLoader {
    private const val TAG = "TfliteLoader"

    /** Memory-maps a .tflite file for use as interpreter input. */
    fun mapFile(file: File): MappedByteBuffer =
        java.io.FileInputStream(file).channel.use { channel ->
            channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
        }

    /** An interpreter plus the delegate (if any) and a backend label. */
    data class Loaded(
        val interpreter: Interpreter,
        val gpuDelegate: GpuDelegate?,
        val backend: String,
    )

    /**
     * Creates an interpreter for [buffer], trying the GPU delegate first and
     * falling back to a 4-thread CPU interpreter. Caller closes both the
     * interpreter and (if present) the delegate.
     */
    fun createInterpreter(buffer: MappedByteBuffer): Loaded {
        tryCreateGpu(buffer)?.let { (interp, delegate) ->
            return Loaded(interp, delegate, "GPU")
        }
        val interp = Interpreter(buffer, Interpreter.Options().apply { numThreads = 4 })
        return Loaded(interp, null, "CPU")
    }

    private fun tryCreateGpu(buffer: MappedByteBuffer): Pair<Interpreter, GpuDelegate>? {
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
}
