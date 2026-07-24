// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.ml

/**
 * Everything the embedding pipeline needs to know about a specific model,
 * so that different 112x112 face-embedding models can be swapped in without
 * touching the inference code.
 *
 * IMPORTANT: switching to a spec with a different [id] invalidates every
 * embedding already stored in the database. Embeddings from different models
 * are not comparable (different vector spaces, often different dimensions), so
 * a switch must trigger a full re-embed. [id] is what the app persists and
 * compares to detect such a switch.
 *
 * @param id          stable identifier persisted in settings; changing model
 *                    means changing this value
 * @param filename    on-disk filename in filesDir (also the download target)
 * @param inputSize   square input edge in pixels (112 for all current models)
 * @param embeddingDim length of the output embedding vector
 * @param normalization how pixel values are mapped to floats before inference
 * @param isFree      true if the model's weights are under a free/open license
 *                    (F-Droid friendly); false for research-only weights
 */
data class EmbeddingModelSpec(
    val id: String,
    val filename: String,
    val inputSize: Int,
    val embeddingDim: Int,
    val normalization: Normalization,
    val isFree: Boolean,
) {
    /**
     * Pixel-to-float mapping. Different models were trained with different
     * input scaling; using the wrong one silently produces garbage embeddings.
     */
    enum class Normalization {
        /** (pixel - 127.5) / 127.5  → range [-1, 1]. ArcFace / InsightFace. */
        SIGNED_UNIT,

        /** pixel / 255  → range [0, 1]. Several MobileFaceNet exports. */
        ZERO_TO_ONE,
    }

    companion object {
        /**
         * ArcFace w600k_mbf (MobileFaceNet backbone, InsightFace). Research-only
         * weights (WebFace600K). Highest accuracy; not F-Droid friendly.
         */
        val ARCFACE =
            EmbeddingModelSpec(
                id = "arcface_w600k_mbf",
                filename = "arcface_w600k_mbf_float32.tflite",
                inputSize = 112,
                embeddingDim = 512,
                normalization = Normalization.SIGNED_UNIT,
                isFree = false,
            )

        /**
         * OpenCV SFace (MobileFaceNet backbone, SFace loss). Distributed by the
         * Open Source Vision Foundation in the opencv_zoo under Apache-2.0 —
         * including the model weights, not just the code. Designed to pair with
         * YuNet detection (5-landmark alignment). LFW accuracy ~0.994.
         *
         * Source: https://huggingface.co/opencv/face_recognition_sface
         * Paper: SFace (arXiv:2205.12010). License: Apache-2.0.
         *
         * VERIFIED: Apache-2.0 on the weights; MobileFaceNet 112x112; OpenCV's
         * recommended cosine-similarity threshold is 0.363 (i.e. cosine
         * DISTANCE ~0.637), useful when tuning the clustering thresholds for
         * this model.
         *
         * TO CONFIRM before release against the actual ONNX→TFLite export:
         *  - embeddingDim: the convert-sface workflow prints the true value
         *    ("EMBEDDING_DIM (from ONNX output)") — read it from the run log and
         *    set it here. 128 is the placeholder.
         *  - normalization: OpenCV does pixel scaling internally, so it is not
         *    visible in the Python wrapper. The SFace ONNX graph itself must be
         *    inspected; ZERO_TO_ONE is the common case but MUST be verified, as
         *    a wrong choice silently produces garbage embeddings.
         *  - download url + sha256: the workflow publishes the TFLite as release
         *    "models-free-v1" and prints its SHA-256; put both into
         *    ModelDownloader's SFACE_FREE entry.
         */
        val SFACE_FREE =
            EmbeddingModelSpec(
                id = "sface_opencv",
                filename = "sface_opencv_float32.tflite",
                inputSize = 112,
                embeddingDim = 128,
                normalization = Normalization.ZERO_TO_ONE,
                isFree = true,
            )

        /** All embedding specs the app knows about. */
        val ALL = listOf(ARCFACE, SFACE_FREE)

        /**
         * The default when the user has not chosen. Kept on ARCFACE for now
         * because SFACE_FREE still needs its download URL/hash and the two
         * to-confirm parameters above filled in; switch this to SFACE_FREE once
         * the free model is published and verified, so F-Droid users get a
         * fully-free default.
         */
        val DEFAULT = ARCFACE

        fun byId(id: String?): EmbeddingModelSpec = ALL.firstOrNull { it.id == id } ?: DEFAULT
    }
}
