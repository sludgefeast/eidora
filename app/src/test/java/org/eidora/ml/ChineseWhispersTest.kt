// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.ml

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ChineseWhispers.cluster")
class ChineseWhispersTest {
    private fun v(vararg xs: Float) = floatArrayOf(*xs)

    /** Maps faceRegionId -> clusterId for easy assertions. */
    private fun clusterMap(results: List<ChineseWhispers.ClusterResult>) =
        results.associate { it.faceRegionId to it.clusterId }

    @Test
    @DisplayName("empty input yields no clusters")
    fun empty() {
        assertTrue(ChineseWhispers.cluster(emptyList()).isEmpty())
    }

    @Test
    @DisplayName("a single node forms one cluster")
    fun single() {
        val result = ChineseWhispers.cluster(listOf("a" to v(1f, 0f, 0f)))
        assertEquals(1, result.size)
        assertEquals("a", result[0].faceRegionId)
    }

    @Test
    @DisplayName("two tight groups end up in two different clusters")
    fun twoGroups() {
        // Group A near (1,0,0), group B near (0,1,0). Intra-group distance ~0,
        // inter-group ~0.96 — well above the 0.30 edge threshold, so the two
        // groups cannot be linked regardless of iteration order.
        val nodes =
            listOf(
                "a1" to v(1f, 0f, 0f),
                "a2" to v(0.99f, 0.01f, 0f),
                "a3" to v(0.98f, 0.02f, 0f),
                "b1" to v(0f, 1f, 0f),
                "b2" to v(0.01f, 0.99f, 0f),
                "b3" to v(0.02f, 0.98f, 0f),
            )
        val m = clusterMap(ChineseWhispers.cluster(nodes, edgeThreshold = 0.30f))

        // All of A share one cluster id; all of B share another; the two differ.
        assertEquals(m["a1"], m["a2"])
        assertEquals(m["a1"], m["a3"])
        assertEquals(m["b1"], m["b2"])
        assertEquals(m["b1"], m["b3"])
        assertTrue(m["a1"] != m["b1"], "the two groups must not merge")
        assertEquals(2, m.values.toSet().size, "expected exactly two clusters")
    }

    @Test
    @DisplayName("identical embeddings collapse into one cluster")
    fun identicalCollapse() {
        val nodes =
            listOf(
                "x" to v(0.5f, 0.5f, 0.5f),
                "y" to v(0.5f, 0.5f, 0.5f),
                "z" to v(0.5f, 0.5f, 0.5f),
            )
        val m = clusterMap(ChineseWhispers.cluster(nodes, edgeThreshold = 0.30f))
        assertEquals(1, m.values.toSet().size, "identical faces should form one cluster")
    }

    @Test
    @DisplayName("orthogonal embeddings stay in separate clusters")
    fun orthogonalSeparate() {
        // Pairwise distance is 1.0, above the threshold: no edges, so each node
        // remains its own cluster.
        val nodes =
            listOf(
                "x" to v(1f, 0f, 0f),
                "y" to v(0f, 1f, 0f),
                "z" to v(0f, 0f, 1f),
            )
        val m = clusterMap(ChineseWhispers.cluster(nodes, edgeThreshold = 0.30f))
        assertEquals(3, m.values.toSet().size, "unrelated faces should not merge")
    }

    @Test
    @DisplayName("every input node appears exactly once in the result")
    fun coversAllNodes() {
        val nodes =
            listOf(
                "a1" to v(1f, 0f, 0f),
                "a2" to v(0.99f, 0.01f, 0f),
                "b1" to v(0f, 1f, 0f),
            )
        val result = ChineseWhispers.cluster(nodes, edgeThreshold = 0.30f)
        assertEquals(nodes.size, result.size)
        assertEquals(nodes.map { it.first }.toSet(), result.map { it.faceRegionId }.toSet())
    }
}
