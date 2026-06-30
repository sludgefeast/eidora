package de.sebastian.faces.ml

import kotlin.random.Random

/**
 * Chinese Whispers graph clustering for face embeddings.
 *
 * Each node = one FaceRegion (identified by its DB id).
 * Edges are created between all pairs whose cosine distance < threshold.
 * The algorithm iteratively updates node labels until convergence.
 */
object ChineseWhispers {

    private const val EDGE_THRESHOLD = 0.30f  // cosine distance; lower = more similar, fewer false positives
    private const val MAX_ITERATIONS = 100

    data class ClusterResult(
        val faceRegionId: String,
        val clusterId: Int
    )

    /**
     * @param nodes List of (faceRegionId, embedding)
     * @return List of ClusterResult assigning each node to a cluster id
     */
    fun cluster(nodes: List<Pair<String, FloatArray>>): List<ClusterResult> {
        if (nodes.isEmpty()) return emptyList()
        if (nodes.size == 1) return listOf(ClusterResult(nodes[0].first, 0))

        // Build adjacency list with weights
        val n = nodes.size
        val labels = IntArray(n) { it }  // each node starts in its own cluster
        val edges = Array(n) { mutableListOf<Pair<Int, Float>>() }

        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val dist = FaceNetModel.cosineDistance(nodes[i].second, nodes[j].second)
                if (dist < EDGE_THRESHOLD) {
                    val weight = 1f - dist  // similarity
                    edges[i].add(Pair(j, weight))
                    edges[j].add(Pair(i, weight))
                }
            }
        }

        // Iterative label propagation
        val indices = (0 until n).toMutableList()
        repeat(MAX_ITERATIONS) {
            var changed = false
            indices.shuffle(Random)
            for (i in indices) {
                if (edges[i].isEmpty()) continue
                // Find label with highest total weight among neighbors
                val weightByLabel = mutableMapOf<Int, Float>()
                for ((neighbor, weight) in edges[i]) {
                    weightByLabel[labels[neighbor]] = (weightByLabel[labels[neighbor]] ?: 0f) + weight
                }
                val bestLabel = weightByLabel.maxByOrNull { it.value }?.key ?: continue
                if (labels[i] != bestLabel) {
                    labels[i] = bestLabel
                    changed = true
                }
            }
            if (!changed) return@repeat
        }

        return nodes.mapIndexed { index, (id, _) ->
            ClusterResult(faceRegionId = id, clusterId = labels[index])
        }
    }
}
