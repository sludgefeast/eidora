// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.ml

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

    // Cap on edges kept per node. Bounds graph memory at O(n × MAX_NEIGHBORS);
    // without it a frequently-photographed face collects thousands of neighbours
    // and the graph exhausts the heap. 256 balances two failure modes: too low
    // (128) starved Chinese Whispers of connections and fragmented large groups
    // into many small clusters + singletons; too high risks OOM again. At ~26k
    // faces this is ~100 MB, safely under the 256 MB heap. Relative to the
    // graph, not tuned to any collection.
    private const val MAX_NEIGHBORS = 256

    // Enable LSH-based candidate generation above this node count.
    private const val LSH_THRESHOLD = 500

    // LSH parameters. K bits per signature → 2^K possible buckets per table.
    // L independent hash tables increase recall.
    private const val LSH_K = 10 // ~1024 buckets per table
    private const val LSH_L = 8 // 8 tables → good recall
    private const val LSH_SEED = 42L // deterministic between runs
    private const val EMBEDDING_DIM = 512

    data class ClusterResult(
        val faceRegionId: String,
        val clusterId: Int,
    )

    fun cluster(
        nodes: List<Pair<String, FloatArray>>,
        edgeThreshold: Float = 0.30f,
        takenAt: Map<String, Long?> = emptyMap(),
        timeWeight: Float = 0f,
    ): List<ClusterResult> {
        if (nodes.isEmpty()) return emptyList()
        if (nodes.size == 1) return listOf(ClusterResult(nodes[0].first, 0))

        val n = nodes.size
        val labels = IntArray(n) { it }
        val neighborsIdx = arrayOfNulls<IntArray>(n)
        val neighborsWeight = arrayOfNulls<FloatArray>(n)
        val neighborCount = IntArray(n)

        val embeddings = Array(n) { nodes[it].second }
        val nodeIds = Array(n) { nodes[it].first }

        // Choose candidate-pair strategy based on size.
        if (n < LSH_THRESHOLD) {
            buildEdgesExhaustive(
                embeddings,
                nodeIds,
                edgeThreshold,
                takenAt,
                timeWeight,
                neighborsIdx,
                neighborsWeight,
                neighborCount,
            )
        } else {
            buildEdgesLsh(
                embeddings,
                nodeIds,
                edgeThreshold,
                takenAt,
                timeWeight,
                neighborsIdx,
                neighborsWeight,
                neighborCount,
            )
        }

        // Top-K capping is applied per direction, so an edge u→v can survive
        // while v→u gets dropped (v's list was full of stronger edges). That
        // asymmetry starves Chinese Whispers' label propagation and fragments
        // clusters. Restore symmetry: every edge exists in both directions.
        symmetrizeEdges(neighborsIdx, neighborsWeight, neighborCount, n)

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
        nodeIds: Array<String>,
        edgeThreshold: Float,
        takenAt: Map<String, Long?>,
        timeWeight: Float,
        neighborsIdx: Array<IntArray?>,
        neighborsWeight: Array<FloatArray?>,
        neighborCount: IntArray,
    ) {
        val n = embeddings.size
        for (i in 0 until n) {
            val embI = embeddings[i]
            for (j in i + 1 until n) {
                val cosD = EmbeddingModel.cosineDistance(embI, embeddings[j])
                val penalty =
                    TemporalDistance.penalty(
                        takenAt[nodeIds[i]],
                        takenAt[nodeIds[j]],
                        timeWeight,
                        edgeThreshold,
                    )
                val dist = cosD + penalty
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
        nodeIds: Array<String>,
        edgeThreshold: Float,
        takenAt: Map<String, Long?>,
        timeWeight: Float,
        neighborsIdx: Array<IntArray?>,
        neighborsWeight: Array<FloatArray?>,
        neighborCount: IntArray,
    ) {
        val n = embeddings.size
        val random = Random(LSH_SEED)
        // Dimension comes from the data, so LSH works for any embedding model.
        val dim = if (embeddings.isNotEmpty()) embeddings[0].size else EMBEDDING_DIM

        // Generate L * K random hyperplanes (unit vectors).
        val hyperplanes =
            Array(LSH_L) {
                Array(LSH_K) {
                    FloatArray(dim) { random.nextFloat() * 2f - 1f }
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
                        val cosD = EmbeddingModel.cosineDistance(embeddings[u], embeddings[v])
                        val penalty =
                            TemporalDistance.penalty(
                                takenAt[nodeIds[u]],
                                takenAt[nodeIds[v]],
                                timeWeight,
                                edgeThreshold,
                            )
                        val dist = cosD + penalty
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

    private fun dot(
        a: FloatArray,
        b: FloatArray,
    ): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    /**
     * Ensures the adjacency is symmetric: if u lists v as a neighbour, v also
     * lists u (with the same weight). Per-direction top-K capping can drop one
     * side of an edge; this adds the missing side back. A node may end up with
     * slightly more than MAX_NEIGHBORS edges, which is fine — the cap exists to
     * bound memory against pathological fan-out, and the symmetric completions
     * are limited to edges that already survived capping on the other side, so
     * the overhead is small. Reuses addEdge's growable arrays, temporarily
     * lifting the cap via a direct append so a full node can still receive its
     * missing back-edges.
     */
    private fun symmetrizeEdges(
        idx: Array<IntArray?>,
        weights: Array<FloatArray?>,
        counts: IntArray,
        n: Int,
    ) {
        // Snapshot counts up front: we mutate counts as we add back-edges, but
        // only want to scan the edges that existed before symmetrisation.
        val originalCounts = counts.copyOf()
        for (u in 0 until n) {
            val uCount = originalCounts[u]
            if (uCount == 0) continue
            val uIdx = idx[u]!!
            val uWeights = weights[u]!!
            for (k in 0 until uCount) {
                val v = uIdx[k]
                val w = uWeights[k]
                // Does v already list u (within v's original edges)?
                val vOriginal = originalCounts[v]
                val vIdx = idx[v]
                var found = false
                if (vIdx != null) {
                    for (p in 0 until vOriginal) {
                        if (vIdx[p] == u) {
                            found = true
                            break
                        }
                    }
                }
                if (!found) {
                    appendEdgeUncapped(idx, weights, counts, v, u, w)
                }
            }
        }
    }

    /**
     * Appends an edge with no top-K cap, growing the arrays as needed. Used only
     * by [symmetrizeEdges] to add back-edges a full node would otherwise reject.
     */
    private fun appendEdgeUncapped(
        idx: Array<IntArray?>,
        weights: Array<FloatArray?>,
        counts: IntArray,
        from: Int,
        to: Int,
        weight: Float,
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

    private fun addEdge(
        idx: Array<IntArray?>,
        weights: Array<FloatArray?>,
        counts: IntArray,
        from: Int,
        to: Int,
        weight: Float,
    ) {
        var curIdx = idx[from]
        var curWeights = weights[from]
        val count = counts[from]
        if (curIdx == null) {
            curIdx = IntArray(4)
            curWeights = FloatArray(4)
            idx[from] = curIdx
            weights[from] = curWeights
        } else if (count == curIdx.size && curIdx.size < MAX_NEIGHBORS) {
            // Grow, but never beyond MAX_NEIGHBORS. Capping the per-node
            // adjacency keeps the graph's memory at O(n × MAX_NEIGHBORS) instead
            // of unbounded: with well-aligned embeddings a frequently-photographed
            // face can otherwise collect thousands of neighbours and exhaust the
            // heap (OutOfMemoryError in addEdge). Keeping only the strongest
            // edges also drops weak, noisy ones, which helps cluster quality.
            val newSize = minOf(curIdx.size * 2, MAX_NEIGHBORS)
            curIdx = curIdx.copyOf(newSize)
            curWeights = curWeights!!.copyOf(newSize)
            idx[from] = curIdx
            weights[from] = curWeights
        }
        curWeights = curWeights!!
        if (count < curIdx.size) {
            // Room left: append.
            curIdx[count] = to
            curWeights[count] = weight
            counts[from] = count + 1
        } else {
            // Full at MAX_NEIGHBORS: replace the weakest edge if this one is
            // stronger, so each node keeps its top-MAX_NEIGHBORS strongest edges.
            var minPos = 0
            var minW = curWeights[0]
            for (p in 1 until curIdx.size) {
                if (curWeights[p] < minW) {
                    minW = curWeights[p]
                    minPos = p
                }
            }
            if (weight > minW) {
                curIdx[minPos] = to
                curWeights[minPos] = weight
            }
        }
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
