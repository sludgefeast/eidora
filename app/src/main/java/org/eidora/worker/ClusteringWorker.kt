// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.worker

import android.content.Context
import org.eidora.util.EidoraLog
import androidx.work.*
import androidx.work.WorkInfo
import org.eidora.data.db.DatabaseProvider
import org.eidora.data.db.EidoraDatabase
import org.eidora.data.db.PersonEntity
import org.eidora.ml.ChineseWhispers
import org.eidora.ml.EmbeddingModel
import java.util.UUID

private const val TAG = "ClusteringWorker"

// How many times clustering retries while embeddings are still pending before
// proceeding anyway. With WorkManager's default backoff this spans several
// minutes, enough for the embedding phase to finish on a large library.
private const val MAX_EMBEDDING_WAIT_ATTEMPTS = 10

/**
 * How strongly a scattered k-nearest-neighbourhood penalises a match, as a
 * fraction of the intra-neighbour spread. This is a *relative* factor (0..1),
 * not an absolute distance, so it is model- and collection-independent: 0 would
 * disable the consistency check (pure nearest-neighbour), 1 would add the full
 * spread. A moderate value discounts lone-outlier matches without changing
 * consistent ones. Kept conservative so it never overrides the model's tuned
 * threshold on its own.
 */
private const val CONSISTENCY_PENALTY_FRACTION = 0.5f

/**
 * How far above the (strict) auto-assign threshold a face may still be offered
 * as an unconfirmed *suggestion* for a named person, as a fraction of that
 * threshold, is now a user-adjustable setting: ClusteringConfig.suggestMargin
 * (default DEFAULT_SUGGEST_MARGIN = 0.10). It stays a relative margin, not a
 * calibrated absolute, so it remains model- and collection-independent. At 0.25
 * the suggest threshold reached deep into the uncertain band (e.g. SFace auto
 * 0.64 → suggest 0.80) and mixed faces; 0.10 keeps suggestions just past the
 * auto threshold (→ ~0.70 for SFace) for cleaner groups.
 */

/**
 * Maximum silhouette-like ratio (internal spread ÷ distance to nearest other
 * cluster) for a cluster to count as "pure". Below this, the cluster is much
 * tighter than its separation from other clusters, so it's unlikely to mix two
 * people. Relative and threshold-free w.r.t. absolute distances.
 */
private const val PURITY_RATIO_MAX = 0.6f

/**
 * Fraction of currently-unknown faces the largest pure clusters should cover as
 * suggestions in one run. The rest wait for later runs. Relative, so it scales
 * with library size and with each round's progress.
 */
private const val SUGGEST_COVERAGE = 0.5f

/**
 * Safety cap: above this many clusters the O(n²) purity check is skipped (all
 * eligible clusters treated as pure) to avoid a slow pass on the device.
 */
private const val MAX_CLUSTERS_FOR_PURITY = 4000

/**
 * One stored embedding of a named person, with the metadata used for weighted
 * nearest-neighbour matching. Individual embeddings are kept (rather than a
 * single centroid) so age- and lighting-related variation is preserved.
 */
private class PersonEmbedding(
    val embedding: FloatArray,
    val takenAt: Long?,
    val quality: Float,
    val isConfirmed: Boolean,
)

/** All stored embeddings of one named person. */
private class PersonData(
    val name: String?,
    val faces: List<PersonEmbedding>,
)

class ClusteringWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!org.eidora.util.PermissionChecker.hasWorkerPermissions(applicationContext)) {
            EidoraLog.w(TAG, "Missing media/all-files permission – aborting clustering")
            return Result.failure()
        }
        val db = DatabaseProvider.getInstance(applicationContext)
        val faceDao = db.faceRegionDao()
        val personDao = db.personDao()

        try {
            if (!awaitSyncToFinish()) return Result.success() // stopped while waiting

            reportProgress(0, applicationContext.getString(org.eidora.R.string.notif_preparing))

            runPreCleaning(db, personDao)

            val config = loadClusteringConfig()
            val powerConfig = loadPowerConfig()
            val powerGate = PowerGate(applicationContext)

            gateOnPower(powerGate, powerConfig)
            if (isStopped) return Result.success()

            val pendingEmbeddings = faceDao.findWithoutEmbedding()
            if (pendingEmbeddings.isNotEmpty()) {
                // Faces that can still get an embedding are outstanding. Make
                // sure the embedding worker is actually running, then retry –
                // but only a bounded number of times, so a genuinely stuck
                // embedding phase can't make clustering retry forever. Faces
                // whose embedding failed permanently are already excluded by
                // findWithoutEmbedding (embedding_failed = 1).
                EidoraLog.w(TAG, "${pendingEmbeddings.size} faces still missing embeddings (attempt $runAttemptCount)")
                if (runAttemptCount < MAX_EMBEDDING_WAIT_ATTEMPTS) {
                    SyncPipeline.enqueue(applicationContext)
                    return Result.retry()
                }
                EidoraLog.w(TAG, "Proceeding with clustering despite ${pendingEmbeddings.size} missing embeddings")
            }

            val timeWeight = config.timeWeight
            val unknownFacesAll =
                faceDao
                    .findUnclusteredWithDate()
                    .filter { it.faceRegion.embedding != null }

            val personData: Map<String, PersonData> = loadPersonData(faceDao, personDao)

            // ----- Phase 1: Individual matching (0-30%) -----
            val individuallyAssigned = mutableSetOf<String>()
            // Diagnostics: collect the best k-NN distance per unknown face so we
            // can pick the threshold from real data. Bucketed to keep the log
            // compact; also track matched vs. just-missed near the threshold.
            val distBuckets = IntArray(10) // 0.0-0.1, 0.1-0.2, … 0.9-1.0
            var matchedCount = 0
            var nearMissCount = 0 // within 0.05 above the threshold
            if (personData.isNotEmpty() && unknownFacesAll.isNotEmpty()) {
                for ((index, face) in unknownFacesAll.withIndex()) {
                    if (isStopped) {
                        EidoraLog.i(TAG, "Clustering was cancelled at index $index, exiting")
                        return Result.failure()
                    }
                    if (index % 50 == 0 && index > 0) {
                        powerGate
                            .awaitOk(powerConfig, isStopped = {
                                isStopped
                            }) { reason, _ ->
                                try {
                                    setForeground(
                                        NotificationHelper.clusteringForegroundInfo(
                                            applicationContext,
                                            (index * 30) / unknownFacesAll.size,
                                            reason,
                                            gateBlocked = true,
                                        ),
                                    )
                                } catch (t: Throwable) {
                                    EidoraLog.w(TAG, ") failed: ${t.message}")
                                }
                            }
                    }
                    try {
                        val embedding = EmbeddingModel.bytesToFloatArray(face.faceRegion.embedding!!)
                        val autoThreshold = config.individualMatchThreshold
                        val suggestThreshold = autoThreshold * (1f + config.suggestMargin)
                        var bestId: String? = null // best within auto threshold
                        var bestName: String? = null
                        var bestDist = autoThreshold
                        var suggestId: String? = null // best within suggest threshold
                        var suggestDist = suggestThreshold
                        var bestDistAny = Float.MAX_VALUE // best distance ignoring threshold, for diagnostics
                        personData.forEach { (personId, pd) ->
                            // Weighted k-NN: nearest distance to this person's faces,
                            // boosted by temporal proximity, with a consistency penalty.
                            val bestFaceDist =
                                bestDistanceToPerson(
                                    embedding,
                                    face.photoTakenAt,
                                    pd,
                                    timeWeight,
                                ) ?: return@forEach
                            if (bestFaceDist < bestDistAny) bestDistAny = bestFaceDist
                            if (bestFaceDist < bestDist) {
                                bestDist = bestFaceDist
                                bestId = personId
                                bestName = pd.name
                            }
                            // Track the best candidate in the wider suggestion band.
                            if (bestFaceDist < suggestDist) {
                                suggestDist = bestFaceDist
                                suggestId = personId
                            }
                        }
                        // Diagnostics: bucket the best distance to any person.
                        if (bestDistAny != Float.MAX_VALUE) {
                            val b = (bestDistAny * 10f).toInt().coerceIn(0, 9)
                            distBuckets[b]++
                            if (bestId != null) {
                                matchedCount++
                            } else if (suggestId != null) {
                                nearMissCount++
                            }
                        }
                        val matchedId = bestId
                        val matchedName = bestName
                        val suggestedId = suggestId
                        val assigned =
                            when {
                                // Below the strict threshold: assign AND confirm.
                                matchedId != null -> {
                                    faceDao.updatePersonAndName(face.faceRegion.id, matchedId, matchedName)
                                    true
                                }
                                // In the suggestion band: assign but leave unconfirmed,
                                // so it shows up in that person's PersonDetail for the
                                // user to accept or reject (name stays null).
                                suggestedId != null -> {
                                    faceDao.updatePersonId(face.faceRegion.id, suggestedId)
                                    true
                                }
                                else -> false
                            }
                        if (assigned) individuallyAssigned.add(face.faceRegion.id)
                    } catch (t: Throwable) {
                        EidoraLog.w(TAG, "Individual match failed for face ${face.faceRegion.id}", t)
                    }
                    if (index % 10 == 0) {
                        val phaseProgress = (index * 30) / unknownFacesAll.size
                        reportProgress(
                            phaseProgress,
                            applicationContext.getString(
                                org.eidora.R.string.notif_matching_persons,
                                index + 1,
                                unknownFacesAll.size,
                            ),
                        )
                    }
                }
                EidoraLog.i(TAG, "Individually assigned ${individuallyAssigned.size} faces to existing persons")
                // Diagnostics for threshold tuning: distribution of the best
                // k-NN distance to any named person, over all unknown faces.
                // autoMatched = assigned+confirmed below the auto threshold;
                // suggested = assigned as unconfirmed suggestions in the band
                // between the auto and suggest thresholds.
                val histogram =
                    (0 until 10).joinToString(" ") { b ->
                        "${b / 10f}-${(b + 1) / 10f}:${distBuckets[b]}"
                    }
                val suggestThr = config.individualMatchThreshold * (1f + config.suggestMargin)
                EidoraLog.i(
                    TAG,
                    "kNN distance histogram (auto=${config.individualMatchThreshold}, " +
                        "suggest=$suggestThr): $histogram | " +
                        "autoMatched=$matchedCount suggested=$nearMissCount",
                )
            }
            reportProgress(30, applicationContext.getString(org.eidora.R.string.notif_matching_persons_done))

            val candidates: List<Pair<String, FloatArray>> =
                unknownFacesAll
                    .filter { it.faceRegion.id !in individuallyAssigned }
                    .map { face ->
                        Pair(face.faceRegion.id, EmbeddingModel.bytesToFloatArray(face.faceRegion.embedding!!))
                    }
            // takenAt lookup for ChineseWhispers temporal penalty
            val candidateTakenAt: Map<String, Long?> =
                unknownFacesAll
                    .filter { it.faceRegion.id !in individuallyAssigned }
                    .associate { it.faceRegion.id to it.photoTakenAt }

            if (candidates.isEmpty()) {
                reportProgress(80, applicationContext.getString(org.eidora.R.string.notif_updating_centroids))
                try {
                    recomputeAllCentroids(
                        db,
                    ) { p ->
                        reportProgress(
                            80 + p * 20 / 100,
                            applicationContext.getString(org.eidora.R.string.notif_updating_centroids),
                        )
                    }
                } catch (
                    t: Throwable,
                ) {
                    EidoraLog.e(TAG, "Failed to recompute centroids", t)
                }
                reportProgress(100, applicationContext.getString(org.eidora.R.string.notif_done))
                return Result.success()
            }

            // ----- Phase 2: Chinese Whispers (30-40%) -----
            reportProgress(30, applicationContext.getString(org.eidora.R.string.notif_grouping, candidates.size))
            val clusterResults =
                try {
                    ChineseWhispers.cluster(
                        candidates,
                        config.edgeThreshold,
                        candidateTakenAt,
                        timeWeight,
                    ) { round, total ->
                        // Heartbeat during the long label-propagation phase so the
                        // notification doesn't look frozen. Keeps progress at 30
                        // (this phase's start); only the text moves.
                        NotificationHelper.updateClusteringNotification(
                            applicationContext,
                            30,
                            applicationContext.getString(
                                org.eidora.R.string.notif_grouping_round,
                                round,
                                total,
                            ),
                        )
                    }
                } catch (t: Throwable) {
                    EidoraLog.e(TAG, "Clustering algorithm failed", t)
                    return Result.failure()
                }
            reportProgress(40, applicationContext.getString(org.eidora.R.string.notif_grouping_done))

            // ----- Phase 3: Cluster assignment (40-80%) -----
            // Pre-load existing suggestions (unnamed persons) with their centroids
            // so new clusters can be merged into them instead of creating duplicates.
            data class SuggestionData(
                val person: PersonEntity,
                val centroid: FloatArray,
                val medianTakenAt: Long?,
            )
            val existingSuggestions: List<SuggestionData> =
                try {
                    personDao.getSuggestions().mapNotNull { suggestion ->
                        val faces =
                            faceDao
                                .findByPersonIdWithDate(suggestion.id)
                                .filter { !it.faceRegion.ignored && it.faceRegion.embedding != null }
                        if (faces.isEmpty()) {
                            null
                        } else {
                            val centroid =
                                EmbeddingModel.weightedCentroid(
                                    faces.map {
                                        EmbeddingModel.bytesToFloatArray(it.faceRegion.embedding!!) to
                                            (it.faceRegion.qualityScore ?: 0.5f)
                                    },
                                )
                            val dates = faces.mapNotNull { it.photoTakenAt }.sorted()
                            val median = if (dates.isEmpty()) null else dates[dates.size / 2]
                            SuggestionData(suggestion, centroid, median)
                        }
                    }
                } catch (t: Throwable) {
                    EidoraLog.w(TAG, "Failed to load existing suggestions", t)
                    emptyList()
                }
            EidoraLog.i(TAG, "Loaded ${existingSuggestions.size} existing suggestions for merge check")

            val clusterGroups = clusterResults.groupBy { it.clusterId }
            val totalClusters = clusterGroups.size

            // ----- Cluster pre-selection: only surface large AND pure clusters -----
            // Iterative approach: rather than turning every cluster above
            // minClusterSize into a suggestion (which produced thousands of small,
            // often-mixed suggestions), we surface only the clusters that are both
            // large and internally consistent. The rest wait for later runs, when
            // more confirmed persons let Phase 1 absorb their faces. All criteria
            // are relative, so this stays valid for any collection and model.
            val eligible =
                clusterGroups.entries.filter { (_, m) -> m.size >= config.minClusterSize }
            val skippedBelowMin = clusterGroups.size - eligible.size

            // Embedding + centroid per eligible cluster (centroid computed once).
            data class ClusterInfo(
                val clusterId: Int,
                val size: Int,
                val centroid: FloatArray,
                val spread: Float,
            )
            val infos =
                eligible.mapNotNull { (clusterId, members) ->
                    val embs =
                        members.mapNotNull { r -> candidates.find { it.first == r.faceRegionId }?.second }
                    if (embs.size < config.minClusterSize) return@mapNotNull null
                    ClusterInfo(
                        clusterId = clusterId,
                        size = embs.size,
                        centroid = EmbeddingModel.centroid(embs),
                        spread = EmbeddingModel.clusterSpread(embs),
                    )
                }

            // Purity via a silhouette-like ratio: a cluster is pure when its
            // internal spread (a) is small relative to the distance to the nearest
            // OTHER cluster's centroid (b). ratio = a / b; low ratio = well
            // separated = pure. This is threshold-free (no calibrated constant).
            // O(clusters²) on centroids only; capped for safety on huge counts.
            val pureClusterIds: Set<Int> =
                if (infos.size > MAX_CLUSTERS_FOR_PURITY) {
                    EidoraLog.w(TAG, "Too many clusters (${infos.size}) for purity check; skipping it")
                    infos.map { it.clusterId }.toSet()
                } else {
                    infos
                        .filter { ci ->
                            if (ci.spread <= 1e-6f) return@filter true // single-tight cluster
                            var nearestOther = Float.MAX_VALUE
                            infos.forEach { other ->
                                if (other.clusterId != ci.clusterId) {
                                    val d = EmbeddingModel.cosineDistance(ci.centroid, other.centroid)
                                    if (d < nearestOther) nearestOther = d
                                }
                            }
                            if (nearestOther == Float.MAX_VALUE) return@filter true // only one cluster
                            (ci.spread / nearestOther) < PURITY_RATIO_MAX
                        }.map { it.clusterId }
                        .toSet()
                }

            // Top-N by size covering SUGGEST_COVERAGE of unknown faces: sort pure
            // clusters largest-first, accumulate until we've covered that fraction
            // of the currently-unknown faces. Only these become suggestions this
            // run; the rest wait. Coverage is relative, so it scales with library
            // size and shrinks the effective min size as unknowns decrease.
            val unknownTotal = unknownFacesAll.size.coerceAtLeast(1)
            val coverageTarget = (unknownTotal * SUGGEST_COVERAGE).toInt().coerceAtLeast(1)
            val selectedIds = mutableSetOf<Int>()
            var covered = 0
            infos
                .filter { it.clusterId in pureClusterIds }
                .sortedByDescending { it.size }
                .forEach { ci ->
                    if (covered < coverageTarget) {
                        selectedIds.add(ci.clusterId)
                        covered += ci.size
                    }
                }
            EidoraLog.i(
                TAG,
                "Cluster selection: ${infos.size} eligible, ${pureClusterIds.size} pure, " +
                    "${selectedIds.size} selected covering $covered/$unknownTotal unknown " +
                    "(target ${(SUGGEST_COVERAGE * 100).toInt()}%)",
            )

            clusterGroups.entries.forEachIndexed { index, (clusterId, members) ->
                if (members.isEmpty()) return@forEachIndexed

                // Only process clusters selected this run (large + pure + within
                // the coverage target). The others wait for a later run.
                if (clusterId !in selectedIds) {
                    return@forEachIndexed
                }

                try {
                    val memberPairs: List<Pair<FloatArray, Float>> =
                        members.mapNotNull { result ->
                            candidates
                                .find { it.first == result.faceRegionId }
                                ?.let { (_, emb) ->
                                    // quality is stored in faceDao but not in the candidates list;
                                    // fall back to equal weight for clustering pass
                                    emb to 0.5f
                                }
                        }
                    val clusterCentroid = EmbeddingModel.weightedCentroid(memberPairs)

                    var bestPerson: PersonEntity? = null
                    var bestDistance = config.clusterMatchThreshold
                    val clusterMedian =
                        members
                            .mapNotNull { candidateTakenAt[it.faceRegionId] }
                            .sorted()
                            .let { if (it.isEmpty()) null else it[it.size / 2] }

                    personData.forEach { (personId, pd) ->
                        try {
                            // Compare cluster centroid against each person embedding (NN)
                            val bestFaceDist =
                                bestDistanceToPerson(
                                    clusterCentroid,
                                    clusterMedian,
                                    pd,
                                    timeWeight,
                                ) ?: return@forEach
                            if (bestFaceDist < bestDistance) {
                                bestDistance = bestFaceDist
                                bestPerson = personDao.findById(personId)
                            }
                        } catch (t: Throwable) {
                            EidoraLog.w(TAG, "Error comparing person $personId", t)
                        }
                    }

                    val targetPerson: PersonEntity =
                        bestPerson ?: run {
                            // No named person matched – check existing suggestions before
                            // creating a new one. This avoids duplicate suggestion clusters.
                            var bestSuggestion: PersonEntity? = null
                            var bestSuggestionDist = config.clusterMatchThreshold
                            existingSuggestions.forEach { sd ->
                                try {
                                    val cosD = EmbeddingModel.cosineDistance(clusterCentroid, sd.centroid)
                                    val penalty =
                                        org.eidora.ml.TemporalDistance.penalty(
                                            clusterMedian,
                                            sd.medianTakenAt,
                                            timeWeight,
                                            config.clusterMatchThreshold,
                                        )
                                    val d = cosD + penalty
                                    if (d < bestSuggestionDist) {
                                        bestSuggestionDist = d
                                        bestSuggestion = sd.person
                                    }
                                } catch (t: Throwable) {
                                    EidoraLog.w(TAG, "Error comparing suggestion ${sd.person.id}", t)
                                }
                            }
                            bestSuggestion ?: run {
                                val newPerson = PersonEntity(id = UUID.randomUUID().toString(), name = null)
                                personDao.insertWithNullableName(newPerson)
                                newPerson
                            }
                        }

                    members.forEach { result ->
                        try {
                            faceDao.updatePersonId(result.faceRegionId, targetPerson.id)
                        } catch (
                            t: Throwable,
                        ) {
                            EidoraLog.w(TAG, "Failed to assign face ${result.faceRegionId}", t)
                        }
                    }
                } catch (t: Throwable) {
                    EidoraLog.e(TAG, "Failed to process cluster, skipping", t)
                }

                val phaseProgress = 40 + ((index + 1) * 40) / totalClusters.coerceAtLeast(1)
                reportProgress(
                    phaseProgress,
                    applicationContext.getString(
                        org.eidora.R.string.notif_assigning,
                        index + 1,
                        totalClusters,
                    ),
                )
            }

            if (skippedBelowMin > 0) {
                EidoraLog.i(
                    TAG,
                    "Skipped $skippedBelowMin clusters below minimum size " +
                        "(${config.minClusterSize}) — mostly singletons that didn't group",
                )
            }

            // ----- Phase 4: Centroid recompute (80-100%) -----
            reportProgress(80, applicationContext.getString(org.eidora.R.string.notif_updating_centroids))
            try {
                recomputeAllCentroids(db) { p ->
                    reportProgress(
                        80 + p * 20 / 100,
                        applicationContext.getString(org.eidora.R.string.notif_updating_centroids),
                    )
                }
            } catch (t: Throwable) {
                EidoraLog.e(TAG, "Failed to recompute centroids", t)
            }

            // ----- Enforce global suggestion cap -----
            // Keep at most config.maxSuggestions suggestions overall (not per
            // run): after generating this run's suggestions, dissolve the
            // smallest ones (fewest faces) so only the largest remain. A later
            // run whose clusters are bigger than older suggestions thus displaces
            // them; its faces go back to Unknown and can regroup.
            if (config.limitSuggestions) {
                try {
                    enforceSuggestionCap(db, config.maxSuggestions)
                } catch (t: Throwable) {
                    EidoraLog.w(TAG, "Failed to enforce suggestion cap", t)
                }
            }

            reportProgress(100, applicationContext.getString(org.eidora.R.string.notif_done))
            return Result.success()
        } catch (t: Throwable) {
            t.rethrowIfCancellation()
            EidoraLog.e(TAG, "Unhandled error in ClusteringWorker", t)
            return Result.failure()
        } finally {
            try {
                androidx.core.app.NotificationManagerCompat
                    .from(applicationContext)
                    .cancel(NotificationHelper.NOTIFICATION_ID_CLUSTERING)
            } catch (t: Throwable) {
                EidoraLog.w(TAG, ".cancel(NotificationHelper.NOTIFICATION_ failed: ${t.message}")
            }
        }
    }

    /**
     * Loads every named person's stored embeddings (with metadata) for
     * nearest-neighbour matching. Persons whose faces are all ignored or lack an
     * embedding are skipped. Extracted from doWork to keep the phases readable.
     */
    /**
     * Enforces a global cap on the number of suggestions. Suggestions
     * (unnamed persons) are ranked by face count; everything past the top
     * [maxSuggestions] is dissolved via rejectSuggestion (faces back to Unknown,
     * the empty suggestion person removed). Keeps the largest suggestions, so a
     * new run's bigger clusters displace older, smaller suggestions.
     */
    private suspend fun enforceSuggestionCap(
        db: org.eidora.data.db.EidoraDatabase,
        maxSuggestions: Int,
    ) {
        val personDao = db.personDao()
        val counts = personDao.getSuggestionFaceCounts() // already largest-first
        if (counts.size <= maxSuggestions) return
        val repo = org.eidora.data.repository.FaceRepository(applicationContext, db)
        val toDissolve = counts.drop(maxSuggestions)
        toDissolve.forEach { sc ->
            try {
                repo.rejectSuggestion(sc.personId)
            } catch (t: Throwable) {
                EidoraLog.w(TAG, "Failed to dissolve suggestion ${sc.personId}", t)
            }
        }
        EidoraLog.i(
            TAG,
            "Suggestion cap: kept $maxSuggestions, dissolved ${toDissolve.size} " +
                "(smallest by face count)",
        )
    }

    private suspend fun loadPersonData(
        faceDao: org.eidora.data.db.FaceRegionDao,
        personDao: org.eidora.data.db.PersonDao,
    ): Map<String, PersonData> =
        personDao
            .getAll()
            .filter { it.name != null }
            .mapNotNull { person ->
                val allFaces =
                    faceDao
                        .findByPersonIdWithDate(person.id)
                        .filter { !it.faceRegion.ignored && it.faceRegion.embedding != null }
                if (allFaces.isEmpty()) {
                    null
                } else {
                    person.id to
                        PersonData(
                            name = person.name,
                            faces =
                                allFaces.map {
                                    PersonEmbedding(
                                        embedding = EmbeddingModel.bytesToFloatArray(it.faceRegion.embedding!!),
                                        takenAt = it.photoTakenAt,
                                        quality = it.faceRegion.qualityScore ?: 0.5f,
                                        isConfirmed = it.faceRegion.name != null,
                                    )
                                },
                        )
                }
            }.toMap()

    /**
     * Waits until any active sync finishes, so sync and clustering don't run
     * concurrently. Shows a "waiting" notification. Returns false if the worker
     * was stopped while waiting.
     */
    private suspend fun awaitSyncToFinish(): Boolean {
        val wm = WorkManager.getInstance(applicationContext)
        val syncStates = setOf(WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED)
        var waited = false
        while (true) {
            val syncRunning =
                wm
                    .getWorkInfosForUniqueWork(SyncPipeline.UNIQUE_SYNC_NAME)
                    .get()
                    ?.any { it.state in syncStates } == true
            if (!syncRunning) return true
            if (!waited) {
                waited = true
                EidoraLog.i(TAG, "Sync is active – clustering waiting")
                try {
                    setForeground(
                        NotificationHelper.clusteringForegroundInfo(
                            applicationContext,
                            0,
                            applicationContext.getString(org.eidora.R.string.notif_waiting_for_sync),
                            cancelIntent = cancelPendingIntent(applicationContext),
                        ),
                    )
                } catch (t: Throwable) {
                    EidoraLog.w(TAG, ") failed: ${t.message}")
                }
            }
            if (isStopped) return false
            kotlinx.coroutines.delay(5_000)
        }
    }

    /** Optional pre-clustering cleanup requested via input data. */
    private suspend fun runPreCleaning(
        db: org.eidora.data.db.EidoraDatabase,
        personDao: org.eidora.data.db.PersonDao,
    ) {
        val rejectSuggestions = inputData.getBoolean(KEY_REJECT_SUGGESTIONS, false)
        val removeUnconfirmed = inputData.getBoolean(KEY_REMOVE_UNCONFIRMED, false)
        if (!rejectSuggestions && !removeUnconfirmed) return
        val repo = org.eidora.data.repository.FaceRepository(applicationContext, db)
        if (rejectSuggestions) {
            EidoraLog.i(TAG, "Pre-clustering: rejecting all suggestions")
            repo.rejectAllSuggestions()
        }
        if (removeUnconfirmed) {
            EidoraLog.i(TAG, "Pre-clustering: removing unconfirmed faces from persons")
            personDao.getAll().forEach { person -> repo.removeUnconfirmedFaces(person.id) }
        }
    }

    private suspend fun loadClusteringConfig(): org.eidora.data.settings.ClusteringConfig =
        try {
            org.eidora.data.settings.SettingsProvider.get(applicationContext).getClusteringConfig()
        } catch (t: Throwable) {
            t.rethrowIfCancellation()
            EidoraLog.w(TAG, "Failed to load clustering config, using defaults", t)
            org.eidora.data.settings.ClusteringConfig(
                edgeThreshold = 0.30f,
                clusterMatchThreshold = 0.30f,
                individualMatchThreshold = 0.25f,
                minClusterSize = 2,
                timeWeight = 1.0f,
                suggestMargin = 0.10f,
                limitSuggestions = true,
                maxSuggestions = 20,
            )
        }

    private suspend fun loadPowerConfig(): org.eidora.data.settings.PowerConfig =
        try {
            org.eidora.data.settings.SettingsProvider.get(applicationContext).getPowerConfig()
        } catch (t: Throwable) {
            t.rethrowIfCancellation()
            org.eidora.data.settings.PowerConfig(
                minBatteryPercent = org.eidora.data.settings.SettingsRepository.DEFAULT_MIN_BATTERY_PERCENT,
                maxBatteryTempCelsius = org.eidora.data.settings.SettingsRepository.DEFAULT_MAX_BATTERY_TEMP,
                resumeBatteryPercent = org.eidora.data.settings.SettingsRepository.DEFAULT_RESUME_BATTERY_PERCENT,
                resumeBatteryTempCelsius = org.eidora.data.settings.SettingsRepository.DEFAULT_RESUME_BATTERY_TEMP,
            )
        }

    /** Suspends until battery/thermal conditions allow work, updating the notification. */
    private suspend fun gateOnPower(
        powerGate: PowerGate,
        powerConfig: org.eidora.data.settings.PowerConfig,
    ) {
        powerGate.awaitOk(
            powerConfig,
            isStopped = { isStopped },
        ) { reason, _ ->
            try {
                setForeground(
                    NotificationHelper.clusteringForegroundInfo(
                        applicationContext,
                        0,
                        reason,
                        cancelIntent = cancelPendingIntent(applicationContext),
                        gateBlocked = true,
                    ),
                )
            } catch (t: Throwable) {
                EidoraLog.w(TAG, ") failed: ${t.message}")
            }
        }
    }

    private suspend fun reportProgress(
        percent: Int,
        message: String,
    ) {
        try {
            setProgress(
                workDataOf(
                    NotificationHelper.KEY_PROGRESS to percent,
                    NotificationHelper.KEY_STATUS to message,
                ),
            )
            setForeground(
                NotificationHelper.clusteringForegroundInfo(
                    applicationContext,
                    percent,
                    message,
                    cancelIntent = cancelPendingIntent(applicationContext),
                ),
            )
        } catch (t: Throwable) {
            EidoraLog.w(TAG, ") failed: ${t.message}")
        }
    }

    private suspend fun recomputeAllCentroids(
        db: EidoraDatabase,
        onProgress: suspend (Int) -> Unit = {},
    ) {
        val personDao = db.personDao()
        val faceDao = db.faceRegionDao()

        val persons = personDao.getAll()
        persons.forEachIndexed { index, person ->
            try {
                val allFaces =
                    faceDao
                        .findByPersonId(person.id)
                        .filter { !it.ignored && it.embedding != null }
                if (allFaces.isNotEmpty()) {
                    val basisFaces = allFaces.filter { it.name != null }.ifEmpty { allFaces }
                    val embeddingsWithWeights =
                        basisFaces.map {
                            EmbeddingModel.bytesToFloatArray(it.embedding!!) to (it.qualityScore ?: 0.5f)
                        }
                    val repIdx = EmbeddingModel.representativeFaceIndex(embeddingsWithWeights)
                    val representative = basisFaces.getOrNull(repIdx)
                    personDao.updateRepresentativeFace(person.id, representative?.id)
                }
            } catch (t: Throwable) {
                EidoraLog.w(TAG, "Failed to recompute centroid for person ${person.id}", t)
            }
            onProgress(((index + 1) * 100) / persons.size.coerceAtLeast(1))
        }
    }

    /**
     * Smallest adjusted distance between a query embedding and any of a person's
     * stored faces. Each candidate face's cosine distance is reduced by a
     * temporal bonus (closer in time = more likely the same person), weighted by
     * the face's quality and a confirm boost. Returns null if the person has no
     * faces. This is the shared nearest-neighbour rule used by both Phase 1
     * (individual matching) and Phase 3 (cluster assignment), so the two stay
     * consistent.
     */
    private fun bestDistanceToPerson(
        queryEmbedding: FloatArray,
        queryTakenAt: Long?,
        person: PersonData,
        timeWeight: Float,
    ): Float? {
        if (person.faces.isEmpty()) return null
        // Adjusted cosine distance to each of the person's stored faces (lower =
        // more similar). The temporal bonus and quality/confirm boost lower the
        // distance for close-in-time, high-quality, confirmed faces.
        val adjusted =
            person.faces
                .map { pf ->
                    val cosD = EmbeddingModel.cosineDistance(queryEmbedding, pf.embedding)
                    val bonus = temporalBonus(queryTakenAt, pf.takenAt, timeWeight)
                    val boost =
                        (pf.quality * if (pf.isConfirmed) 1.5f else 1.0f).coerceAtMost(1.0f)
                    cosD - bonus * boost
                }.sorted()

        // Primary criterion: the single nearest distance, compared against the
        // model's established (LFW-calibrated, pairwise) threshold. Keeping the
        // minimum here means the tuned per-model thresholds stay valid for
        // everyone — we do NOT introduce a new absolute value that would depend
        // on a particular collection's spread.
        val nearest = adjusted.first()

        // k-NN consistency penalty: a *relative* check that needs no calibrated
        // constant, so it stays valid across collections and models. If the
        // nearest face is a lone outlier — its k nearest neighbours disagree by
        // being much farther away — the match is less trustworthy, so we nudge
        // the effective distance up. When the k nearest are all similarly close
        // (a consistent match), the penalty is ~0 and behaviour equals the old
        // minimum. k adapts to history size.
        val k = adaptiveK(person.faces.size)
        if (k <= 1 || adjusted.size < 2) return nearest
        val knn = adjusted.take(k)
        val spread = (knn.last() - knn.first()).coerceAtLeast(0f)
        // Penalty is a fraction of the intra-neighbour spread, not an absolute
        // number: consistent neighbourhoods (small spread) barely move, scattered
        // ones (large spread) get pushed away from a match.
        val penalty = CONSISTENCY_PENALTY_FRACTION * spread
        return nearest + penalty
    }

    /**
     * Neighbour count for the k-NN consistency check, scaled to the person's
     * history size: 1-2 faces → 1 (no check possible), 3-5 → 2, 6-10 → 3,
     * more → 5.
     */
    private fun adaptiveK(historySize: Int): Int =
        when {
            historySize <= 2 -> 1
            historySize <= 5 -> 2
            historySize <= 10 -> 3
            else -> 5
        }

    /**
     * Returns a temporal bonus in [0..maxBonus] that is highest when the two
     * timestamps are close together. Uses a Gaussian with half-width = 3 years.
     * Subtracting this from cosine distance makes temporally close faces
     * effectively "more similar".
     */
    private fun temporalBonus(
        takenAtA: Long?,
        takenAtB: Long?,
        weight: Float,
        maxBonus: Float = 0.15f,
    ): Float {
        if (weight <= 0f || takenAtA == null || takenAtB == null) return 0f
        if (takenAtA <= 0L || takenAtB <= 0L) return 0f
        val deltaMs = kotlin.math.abs(takenAtA - takenAtB).toFloat()
        val deltaYears = deltaMs / (365.25f * 24 * 3600 * 1000)
        val sigma = 3.0f // Gaussian half-width in years
        val gaussian = kotlin.math.exp(-(deltaYears * deltaYears) / (2f * sigma * sigma))
        return maxBonus * weight * gaussian
    }

    companion object {
        const val KEY_REJECT_SUGGESTIONS = "reject_suggestions"
        const val KEY_REMOVE_UNCONFIRMED = "remove_unconfirmed"

        fun buildRequest(
            rejectSuggestions: Boolean = false,
            removeUnconfirmed: Boolean = false,
        ): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<ClusteringWorker>()
                .setInputData(
                    workDataOf(
                        KEY_REJECT_SUGGESTIONS to rejectSuggestions,
                        KEY_REMOVE_UNCONFIRMED to removeUnconfirmed,
                    ),
                ).setBackoffCriteria(BackoffPolicy.LINEAR, 30_000L, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build()

        /** PendingIntent that cancels the clustering work – used in notification. */
        private fun cancelPendingIntent(context: Context): android.app.PendingIntent {
            val intent = android.content.Intent(context, CancelClusteringReceiver::class.java)
            return android.app.PendingIntent.getBroadcast(
                context,
                0,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
