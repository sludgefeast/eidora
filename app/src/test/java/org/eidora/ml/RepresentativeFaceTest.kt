// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.ml

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("EmbeddingModel.representativeFaceIndex")
class RepresentativeFaceTest {
    private fun v(vararg xs: Float) = floatArrayOf(*xs)

    @Nested
    @DisplayName("degenerate sizes")
    inner class Sizes {
        @Test
        @DisplayName("empty list returns -1")
        fun empty() {
            assertEquals(-1, EmbeddingModel.representativeFaceIndex(emptyList()))
        }

        @Test
        @DisplayName("a single face is always its own representative (first-run/XMP case)")
        fun singleFace() {
            // Regression guard: on first run a person often has exactly one face
            // imported from XMP metadata. Its representative (and thus the
            // avatar) must be that face, not unset.
            val one = listOf(v(0.1f, 0.9f, 0.2f) to 0.5f)
            assertEquals(0, EmbeddingModel.representativeFaceIndex(one))
        }

        @Test
        @DisplayName("a single face with zero quality still returns index 0")
        fun singleFaceZeroQuality() {
            val one = listOf(v(1f, 0f, 0f) to 0f)
            assertEquals(0, EmbeddingModel.representativeFaceIndex(one))
        }
    }

    @Nested
    @DisplayName("multiple faces")
    inner class Multiple {
        @Test
        @DisplayName("picks the face nearest the centroid")
        fun nearestCentroid() {
            // Two faces cluster tightly near (1,0,...), one is an outlier. The
            // centroid sits among the cluster, so an in-cluster face wins over
            // the outlier.
            val faces =
                listOf(
                    v(1f, 0f, 0f) to 1f, // index 0 – in cluster
                    v(0.99f, 0.01f, 0f) to 1f, // index 1 – in cluster
                    v(0f, 1f, 0f) to 1f, // index 2 – outlier
                )
            val idx = EmbeddingModel.representativeFaceIndex(faces)
            assertEquals(true, idx == 0 || idx == 1, "expected an in-cluster face, got $idx")
        }

        @Test
        @DisplayName("does not pick the lone outlier")
        fun notOutlier() {
            val faces =
                listOf(
                    v(1f, 0f, 0f) to 1f,
                    v(1f, 0.02f, 0f) to 1f,
                    v(0.98f, 0f, 0.01f) to 1f,
                    v(-1f, 0f, 0f) to 1f, // opposite direction – far from centroid
                )
            val idx = EmbeddingModel.representativeFaceIndex(faces)
            assertEquals(true, idx != 3, "outlier at index 3 must not be representative")
        }

        @Test
        @DisplayName("two identical faces return the first")
        fun identical() {
            val faces =
                listOf(
                    v(0.5f, 0.5f, 0.5f) to 1f,
                    v(0.5f, 0.5f, 0.5f) to 1f,
                )
            assertEquals(0, EmbeddingModel.representativeFaceIndex(faces))
        }
    }
}
