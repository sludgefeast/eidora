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
    /** Effective license (the restrictive one) plus the reason, for the UI. */
    val license: ModelLicense,
    /**
     * Default clustering thresholds tuned for this model. Embeddings from
     * different models live in different spaces, so thresholds do not transfer:
     * ArcFace clusters well around 0.50 cosine distance, while SFace (OpenCV's
     * cosine-similarity threshold 0.363 → distance ~0.637) needs looser values.
     * These become the defaults when the model is selected; the user can still
     * override them in settings.
     *
     * All are cosine DISTANCE (1 - similarity); larger = more permissive.
     */
    val defaultThresholds: Thresholds,
) {
    /** Per-model default clustering thresholds (cosine distance). */
    data class Thresholds(
        val edge: Float,
        val clusterMatch: Float,
        val individualMatch: Float,
    )

    /**
     * Pixel-to-float mapping. Different models were trained with different
     * input scaling; using the wrong one silently produces garbage embeddings.
     */
    enum class Normalization {
        /** (pixel - 127.5) / 127.5  → range [-1, 1]. ArcFace / InsightFace. */
        SIGNED_UNIT,

        /** pixel / 255  → range [0, 1]. Several MobileFaceNet exports. */
        ZERO_TO_ONE,

        /**
         * Raw pixel values [0, 255], no scaling or mean subtraction. OpenCV
         * SFace: FaceRecognizerSF feeds blobFromImage with scale 1.0 and zero
         * mean, so the input scaling is baked into the model weights.
         */
        RAW_0_255,
    }

    companion object {
        /**
         * ArcFace w600k_mbf (MobileFaceNet backbone, InsightFace). Research-only
         * weights (WebFace600K). Highest accuracy; not F-Droid friendly. This
         * spec exists only so an imported bring-your-own container using ArcFace
         * runs with the right parameters and license label — Eidora never offers
         * or fetches it.
         */
        val ARCFACE =
            EmbeddingModelSpec(
                id = "arcface_w600k_mbf",
                filename = "arcface_w600k_mbf_float32.tflite",
                inputSize = 112,
                embeddingDim = 512,
                normalization = Normalization.SIGNED_UNIT,
                isFree = false,
                license =
                    ModelLicense(
                        isFree = false,
                        effectiveNameRes = org.eidora.R.string.license_research_name,
                        reasonRes = org.eidora.R.string.license_reason_arcface,
                    ),
                defaultThresholds =
                    Thresholds(
                        edge = 0.50f,
                        clusterMatch = 0.55f,
                        individualMatch = 0.50f,
                    ),
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
         * VERIFIED against OpenCV's reference (opencv/face_recognition_sface):
         *  - Apache-2.0 weights; ONNX sha256 0ba9fbfa...4e79.
         *  - input 112x112, ONNX input "data" [1,3,112,112].
         *  - embeddingDim 128 (ONNX output "fc1" [1,128]).
         *  - normalization RAW_0_255: raw pixels reproduce OpenCV's
         *    FaceRecognizerSF embeddings with cosine 1.00000; ZERO_TO_ONE and
         *    SIGNED_UNIT give ~0.03-0.14 (garbage). This is why the free
         *    container's manifest declares normalization: raw_0_255.
         *  - OpenCV's cosine-similarity threshold is 0.363, useful when tuning
         *    the clustering thresholds for this model.
         */
        val SFACE_FREE =
            EmbeddingModelSpec(
                id = "sface_opencv",
                filename = "sface_opencv_float32.tflite",
                inputSize = 112,
                embeddingDim = 128,
                normalization = Normalization.RAW_0_255,
                isFree = true,
                license =
                    ModelLicense(
                        isFree = true,
                        effectiveNameRes = org.eidora.R.string.license_free_name,
                        reasonRes = org.eidora.R.string.license_reason_sface,
                    ),
                defaultThresholds =
                    Thresholds(
                        edge = 0.64f,
                        clusterMatch = 0.68f,
                        individualMatch = 0.64f,
                    ),
            )

        /** All embedding specs the app knows about. */
        val ALL = listOf(ARCFACE, SFACE_FREE)

        /**
         * The default when the user has not chosen. SFACE_FREE is
         * Apache-2.0-licensed and fully verified (dim 128, RAW_0_255
         * normalization confirmed against OpenCV's reference), so F-Droid users
         * get a fully-free default. The ArcFace spec below exists only so that a
         * container someone builds and imports themselves (bring your own model)
         * runs with the right parameters and license label — Eidora never offers
         * or fetches it.
         */
        val DEFAULT = SFACE_FREE

        fun byId(id: String?): EmbeddingModelSpec = ALL.firstOrNull { it.id == id } ?: DEFAULT
    }
}
