// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.ml

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("EmbeddingModel math")
class EmbeddingMathTest {
    private val eps = 1e-4f

    private fun v(vararg xs: Float) = floatArrayOf(*xs)

    @Nested
    @DisplayName("cosineDistance")
    inner class Cosine {
        @Test
        @DisplayName("identical vectors have distance 0")
        fun identical() {
            assertEquals(0f, EmbeddingModel.cosineDistance(v(1f, 2f, 3f), v(1f, 2f, 3f)), eps)
        }

        @Test
        @DisplayName("a scaled vector is still distance 0 (magnitude-independent)")
        fun scaled() {
            assertEquals(0f, EmbeddingModel.cosineDistance(v(1f, 0f, 0f), v(5f, 0f, 0f)), eps)
        }

        @Test
        @DisplayName("orthogonal vectors have distance 1")
        fun orthogonal() {
            assertEquals(1f, EmbeddingModel.cosineDistance(v(1f, 0f), v(0f, 1f)), eps)
        }

        @Test
        @DisplayName("opposite vectors have distance 2")
        fun opposite() {
            assertEquals(2f, EmbeddingModel.cosineDistance(v(1f, 0f), v(-1f, 0f)), eps)
        }

        @Test
        @DisplayName("a zero vector yields the max-distance fallback of 1")
        fun zeroVector() {
            assertEquals(1f, EmbeddingModel.cosineDistance(v(0f, 0f, 0f), v(1f, 2f, 3f)), eps)
        }

        @Test
        @DisplayName("distance is symmetric")
        fun symmetric() {
            val a = v(0.3f, 0.7f, 0.1f)
            val b = v(0.5f, 0.2f, 0.9f)
            assertEquals(
                EmbeddingModel.cosineDistance(a, b),
                EmbeddingModel.cosineDistance(b, a),
                eps,
            )
        }
    }

    @Nested
    @DisplayName("weightedCentroid")
    inner class Centroid {
        @Test
        @DisplayName("empty input yields an empty array")
        fun empty() {
            assertEquals(0, EmbeddingModel.weightedCentroid(emptyList()).size)
        }

        @Test
        @DisplayName("a single embedding is its own centroid")
        fun single() {
            val c = EmbeddingModel.weightedCentroid(listOf(v(1f, 2f, 3f) to 1f))
            assertEquals(1f, c[0], eps)
            assertEquals(2f, c[1], eps)
            assertEquals(3f, c[2], eps)
        }

        @Test
        @DisplayName("equal weights give the arithmetic mean")
        fun equalWeights() {
            val c =
                EmbeddingModel.weightedCentroid(
                    listOf(v(0f, 0f) to 1f, v(2f, 4f) to 1f),
                )
            assertEquals(1f, c[0], eps)
            assertEquals(2f, c[1], eps)
        }

        @Test
        @DisplayName("higher weight pulls the centroid toward that embedding")
        fun weighted() {
            // Weight 3 on (0,0) vs weight 1 on (4,4): mean = (1,1).
            val c =
                EmbeddingModel.weightedCentroid(
                    listOf(v(0f, 0f) to 3f, v(4f, 4f) to 1f),
                )
            assertEquals(1f, c[0], eps)
            assertEquals(1f, c[1], eps)
        }

        @Test
        @DisplayName("all-zero weights fall back to equal weighting (not divide-by-zero)")
        fun zeroWeightsFallback() {
            val c =
                EmbeddingModel.weightedCentroid(
                    listOf(v(0f, 0f) to 0f, v(2f, 6f) to 0f),
                )
            // Falls back to the plain mean.
            assertEquals(1f, c[0], eps)
            assertEquals(3f, c[1], eps)
        }
    }
}
