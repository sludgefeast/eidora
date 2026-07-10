package de.sebastian.eidora.ml

import kotlin.random.Random

/**
 * Chinese Whispers graph clustering for face embeddings.
 *
 * Memory-optimized:
 * - Uses primitive IntArray/FloatArray for edges (no boxing).
 * - Uses LSH (Locality-Sensitive Hashing) to reduce O(n²) pairwise
 *   comparisons to O(n * bucket_size). For n > LSH_THRESHOLD nodes,
 *   only candidate pairs from LSH buckets are compared.
 */
object ChineseWhispers {

    private const val MAX_ITERATIONS = 100

    // Enable LSH-based candidate generation above this node count.
    private const val LSH_THRESHOLD = 500

    // LSH parameters. K bits per signature → 2^K possible buckets per table.
    // L independent hash tables increase recall.
    private const val LSH_K = 10       // ~1024 buckets per table
    private const val LSH_L = 8        // 8 tables → good recall
    private const val LSH_SEED = 42L   // deterministic between runs
    private const val EMBEDDING_DIM = 512

    data class ClusterResult(
        val faceRegionId: String,
        val clusterId: Int
    )

    fun cluster(nodes: List<Pair<String, FloatArray>>, edgeThreshold: Float = 0.30f): List<ClusterResult> {
        if (nodes.isEmpty()) return emptyList()
        if (nodes.size == 1) return listOf(ClusterResult(nodes[0].first, 0))

        val n = nodes.size
        val labels = IntArray(n) { it }
        val neighborsIdx = arrayOfNulls<IntArray>(n)
        val neighborsWeight = arrayOfNulls<FloatArray>(n)
        val neighborCount = IntArray(n)

        val embeddings = Array(n) { nodes[it].second }

        // Choose candidate-pair strategy based on size.
        if (n < LSH_THRESHOLD) {
            buildEdgesExhaustive(embeddings, edgeThreshold, neighborsIdx, neighborsWeight, neighborCount)
        } else {
            buildEdgesLsh(embeddings, edgeThreshold, neighborsIdx, neighborsWeight, neighborCount)
        }

        // Iterative label propagation
        val indices = IntArray(n) { it }
        val weightByLabel = HashMap<Int, Float>()
        repeat(MAX_ITERATIONS) {
            var changed = false
            shuffleIntArray(indices)
            for (i in indices) {
                val count = neighborCount[i]
                if (count == 0) continue
                weightByLabel.clear()
                val ni = neighborsIdx[i]!!
                val nw = neighborsWeight[i]!!
                for (k in 0 until count) {
                    val label = labels[ni[k]]
                    weightByLabel[label] = (weightByLabel[label] ?: 0f) + nw[k]
                }
                var bestLabel = -1
                var bestWeight = -1f
                for ((label, weight) in weightByLabel) {
                    if (weight > bestWeight) {
                        bestWeight = weight
                        bestLabel = label
                    }
                }
                if (bestLabel != -1 && labels[i] != bestLabel) {
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

    // -------------------------------------------------------------------------
    // Edge construction: exhaustive (small n)
    // -------------------------------------------------------------------------

    private fun buildEdgesExhaustive(
        embeddings: Array<FloatArray>,
        edgeThreshold: Float,
        neighborsIdx: Array<IntArray?>,
        neighborsWeight: Array<FloatArray?>,
        neighborCount: IntArray
    ) {
        val n = embeddings.size
        for (i in 0 until n) {
            val embI = embeddings[i]
            for (j in i + 1 until n) {
                val dist = EmbeddingModel.cosineDistance(embI, embeddings[j])
                if (dist < edgeThreshold) {
                    val weight = 1f - dist
                    addEdge(neighborsIdx, neighborsWeight, neighborCount, i, j, weight)
                    addEdge(neighborsIdx, neighborsWeight, neighborCount, j, i, weight)
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Edge construction: LSH-based (large n)
    // -------------------------------------------------------------------------

    /**
     * Uses L independent hash tables of K random-hyperplane signatures each.
     * Candidate pairs = pairs that collide in at least one table.
     * For each candidate pair, the exact cosine distance is computed.
     * Duplicate candidate pairs are handled by a per-node seen-set.
     */
    private fun buildEdgesLsh(
        embeddings: Array<FloatArray>,
        edgeThreshold: Float,
        neighborsIdx: Array<IntArray?>,
        neighborsWeight: Array<FloatArray?>,
        neighborCount: IntArray
    ) {
        val n = embeddings.size
        val random = Random(LSH_SEED)

        // Generate L * K random hyperplanes (unit vectors).
        val hyperplanes = Array(LSH_L) {
            Array(LSH_K) {
                FloatArray(EMBEDDING_DIM) { random.nextFloat() * 2f - 1f }
            }
        }

        // For each table, bucket = signature → list of node indices.
        // Signatures fit in an Int since K = 10.
        val tables = Array(LSH_L) { HashMap<Int, IntArrayList>() }

        for (i in 0 until n) {
            val emb = embeddings[i]
            for (l in 0 until LSH_L) {
                var sig = 0
                val plane = hyperplanes[l]
                for (k in 0 until LSH_K) {
                    if (dot(emb, plane[k]) > 0f) sig = sig or (1 shl k)
                }
                tables[l].getOrPut(sig) { IntArrayList() }.add(i)
            }
        }

        // Walk each bucket and compute pairwise distances.
        // A "seen" set per source node prevents redundant work when a pair
        // collides in multiple tables.
        val seen = IntArray(n) { -1 }
        for (table in tables) {
            for ((_, bucket) in table) {
                val bucketSize = bucket.size
                if (bucketSize < 2) continue
                for (a in 0 until bucketSize) {
                    val i = bucket.data[a]
                    for (b in a + 1 until bucketSize) {
                        val j = bucket.data[b]
                        // Skip pair if we've already tested it via this source i.
                        // Encode (i, j) with i as source key; use the higher index
                        // as the seen-marker so we don't re-test symmetric pair.
                        val u = if (i < j) i else j
                        val v = if (i < j) j else i
                        if (seen[u] == v) continue
                        seen[u] = v
                        val dist = EmbeddingModel.cosineDistance(embeddings[u], embeddings[v])
                        if (dist < edgeThreshold) {
                            val weight = 1f - dist
                            addEdge(neighborsIdx, neighborsWeight, neighborCount, u, v, weight)
                            addEdge(neighborsIdx, neighborsWeight, neighborCount, v, u, weight)
                        }
                    }
                }
            }
        }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private fun addEdge(
        idx: Array<IntArray?>,
        weights: Array<FloatArray?>,
        counts: IntArray,
        from: Int,
        to: Int,
        weight: Float
    ) {
        var curIdx = idx[from]
        var curWeights = weights[from]
        val count = counts[from]
        if (curIdx == null) {
            curIdx = IntArray(4)
            curWeights = FloatArray(4)
            idx[from] = curIdx
            weights[from] = curWeights
        } else if (count == curIdx.size) {
            val newSize = curIdx.size * 2
            curIdx = curIdx.copyOf(newSize)
            curWeights = curWeights!!.copyOf(newSize)
            idx[from] = curIdx
            weights[from] = curWeights
        }
        curIdx[count] = to
        curWeights!![count] = weight
        counts[from] = count + 1
    }

    private fun shuffleIntArray(array: IntArray) {
        for (i in array.size - 1 downTo 1) {
            val j = Random.nextInt(i + 1)
            val tmp = array[i]
            array[i] = array[j]
            array[j] = tmp
        }
    }

    /**
     * Minimal primitive-int growable array to avoid boxing in LSH buckets.
     */
    private class IntArrayList {
        var data: IntArray = IntArray(4)
        var size: Int = 0

        fun add(value: Int) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = value
        }
    }
}
