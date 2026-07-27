// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.ml.container

import android.content.Context
import org.eidora.ml.EmbeddingModel
import org.eidora.ml.EmbeddingModelSpec
import org.eidora.ml.FaceDetector
import org.eidora.ml.ScrfdDetector
import org.eidora.ml.YuNetDetector
import java.io.File

/**
 * Instantiates a working detector or embedder from a container's model entry:
 * given the unpacked container dir and a [ContainerManifest.ModelEntry], it
 * picks the right implementation from the entry's `output.type` (the "family")
 * and feeds it the manifest's parameters and the container's .tflite path.
 *
 * This is the bridge between the data world (manifest describing a model) and
 * the running world (a FaceDetector / EmbeddingModel doing inference). Both the
 * self-test screen and, later, the live pipeline go through here, so there's a
 * single place that knows how a manifest maps to a runnable model.
 *
 * A model with an `output.type` this build doesn't implement is the honest
 * ceiling of the data-only approach: it can't be run by manifest alone, so we
 * throw [UnsupportedModelException] rather than guess.
 */
object ContainerModelRunner {
    class UnsupportedModelException(message: String) : Exception(message)

    /** Builds a [FaceDetector] for a detection model entry. */
    fun openDetector(
        context: Context,
        dir: File,
        model: ContainerManifest.ModelEntry,
    ): FaceDetector {
        require(model.task == ContainerManifest.TASK_DETECTION) {
            "openDetector called with a ${model.task} model"
        }
        val file = File(dir, model.file)
        if (!file.isFile) throw UnsupportedModelException("model file missing: ${model.file}")

        return when (model.output.type) {
            "multistride_yunet" ->
                YuNetDetector(
                    context,
                    modelFile = file,
                    scoreThreshold = model.output.scoreThreshold ?: 0.6f,
                    nmsIouThreshold = model.output.nmsIouThreshold ?: 0.3f,
                )
            "multistride_scrfd" ->
                ScrfdDetector(
                    context,
                    modelFile = file,
                    scoreThreshold = model.output.scoreThreshold ?: 0.5f,
                    nmsIouThreshold = model.output.nmsIouThreshold ?: 0.4f,
                )
            else ->
                throw UnsupportedModelException(
                    "this build can't run detection output.type '${model.output.type}'",
                )
        }
    }

    /** Builds an [EmbeddingModel] for an embedding model entry. */
    fun openEmbedder(
        context: Context,
        dir: File,
        model: ContainerManifest.ModelEntry,
    ): EmbeddingModel {
        require(model.task == ContainerManifest.TASK_EMBEDDING) {
            "openEmbedder called with a ${model.task} model"
        }
        if (model.output.type != "single_vector") {
            throw UnsupportedModelException(
                "this build can't run embedding output.type '${model.output.type}'",
            )
        }
        val file = File(dir, model.file)
        if (!file.isFile) throw UnsupportedModelException("model file missing: ${model.file}")

        return EmbeddingModel(
            context,
            modelFile = file,
            normalization = mapNormalization(model.input.normalization),
        )
    }

    /** Reads the clustering thresholds an embedding entry declares, if any. */
    fun clusteringOf(model: ContainerManifest.ModelEntry): ContainerManifest.Clustering? =
        model.clustering

    private fun mapNormalization(manifest: String): EmbeddingModelSpec.Normalization =
        when (manifest) {
            "raw_0_255" -> EmbeddingModelSpec.Normalization.RAW_0_255
            "zero_to_one" -> EmbeddingModelSpec.Normalization.ZERO_TO_ONE
            // 127/127 and 127.5/128 both map to the signed-unit path; the
            // fractional difference is immaterial to the embedding.
            "signed_127_127", "signed_127_128" -> EmbeddingModelSpec.Normalization.SIGNED_UNIT
            else -> throw UnsupportedModelException("unknown normalization '$manifest'")
        }
}
