// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.worker

import android.content.Context
import android.util.Log
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

class ClusteringWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!org.eidora.util.PermissionChecker.hasWorkerPermissions(applicationContext)) {
            Log.w(TAG, "Missing media/all-files permission – aborting clustering")
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
                Log.w(TAG, "${pendingEmbeddings.size} faces still missing embeddings (attempt $runAttemptCount)")
                if (runAttemptCount < MAX_EMBEDDING_WAIT_ATTEMPTS) {
                    SyncPipeline.enqueue(applicationContext)
                    return Result.retry()
                }
                Log.w(TAG, "Proceeding with clustering despite ${pendingEmbeddings.size} missing embeddings")
            }

            val timeWeight = config.timeWeight
            val unknownFacesAll =
                faceDao
                    .findUnclusteredWithDate()
                    .filter { it.faceRegion.embedding != null }

            // PersonData: all embeddings with metadata for weighted nearest-neighbour matching.
            // Keeping individual embeddings instead of a centroid preserves age-related variation.
            data class PersonEmbedding(
                val embedding: FloatArray,
                val takenAt: Long?,
                val quality: Float,
                val isConfirmed: Boolean,
            )

            data class PersonData(
                val faces: List<PersonEmbedding>,
            )

            val personData: Map<String, PersonData> =
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

            // ----- Phase 1: Individual matching (0-30%) -----
            val individuallyAssigned = mutableSetOf<String>()
            if (personData.isNotEmpty() && unknownFacesAll.isNotEmpty()) {
                for ((index, face) in unknownFacesAll.withIndex()) {
                    if (isStopped) {
                        Log.i(TAG, "Clustering was cancelled at index $index, exiting")
                        return Result.failure()
                    }
                    if (index % 50 == 0 && index > 0) {
                        powerGate
                            .awaitOk(powerConfig, isStopped = {
                                isStopped
                            }) { reason ->
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
                                    // ignore
                                }
                            }
                    }
                    try {
                        val embedding = EmbeddingModel.bytesToFloatArray(face.faceRegion.embedding!!)
                        var bestId: String? = null
                        var bestDist = config.individualMatchThreshold
                        personData.forEach { (personId, pd) ->
                            // Weighted nearest-neighbour: find the best matching face
                            // in this person's history, boosted by temporal proximity.
                            val bestFaceDist =
                                pd.faces.minOfOrNull { pf ->
                                    val cosD = EmbeddingModel.cosineDistance(embedding, pf.embedding)
                                    // Temporal bonus: reduce distance for temporally close faces
                                    val temporalBonus =
                                        temporalBonus(
                                            face.photoTakenAt,
                                            pf.takenAt,
                                            timeWeight,
                                        )
                                    // Quality and confirm boost as weight on the bonus
                                    val boost =
                                        (pf.quality * if (pf.isConfirmed) 1.5f else 1.0f)
                                            .coerceAtMost(1.0f)
                                    cosD - temporalBonus * boost
                                } ?: return@forEach
                            if (bestFaceDist < bestDist) {
                                bestDist = bestFaceDist
                                bestId = personId
                            }
                        }
                        bestId?.let { personId ->
                            faceDao.updatePersonId(face.faceRegion.id, personId)
                            individuallyAssigned.add(face.faceRegion.id)
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "Individual match failed for face ${face.faceRegion.id}", t)
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
                Log.i(TAG, "Individually assigned ${individuallyAssigned.size} faces to existing persons")
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
                    Log.e(TAG, "Failed to recompute centroids", t)
                }
                reportProgress(100, applicationContext.getString(org.eidora.R.string.notif_done))
                return Result.success()
            }

            // ----- Phase 2: Chinese Whispers (30-40%) -----
            reportProgress(30, applicationContext.getString(org.eidora.R.string.notif_grouping, candidates.size))
            val clusterResults =
                try {
                    ChineseWhispers.cluster(candidates, config.edgeThreshold, candidateTakenAt, timeWeight)
                } catch (t: Throwable) {
                    Log.e(TAG, "Clustering algorithm failed", t)
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
                    Log.w(TAG, "Failed to load existing suggestions", t)
                    emptyList()
                }
            Log.i(TAG, "Loaded ${existingSuggestions.size} existing suggestions for merge check")

            val clusterGroups = clusterResults.groupBy { it.clusterId }
            val totalClusters = clusterGroups.size
            clusterGroups.entries.forEachIndexed { index, (_, members) ->
                if (members.isEmpty()) return@forEachIndexed

                if (members.size < config.minClusterSize) {
                    Log.d(TAG, "Skipping singleton cluster (${members.size} face)")
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
                                pd.faces.minOfOrNull { pf ->
                                    val cosD = EmbeddingModel.cosineDistance(clusterCentroid, pf.embedding)
                                    val temporalBonus =
                                        temporalBonus(
                                            clusterMedian,
                                            pf.takenAt,
                                            timeWeight,
                                        )
                                    val boost =
                                        (pf.quality * if (pf.isConfirmed) 1.5f else 1.0f)
                                            .coerceAtMost(1.0f)
                                    cosD - temporalBonus * boost
                                } ?: return@forEach
                            if (bestFaceDist < bestDistance) {
                                bestDistance = bestFaceDist
                                bestPerson = personDao.findById(personId)
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "Error comparing person $personId", t)
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
                                    Log.w(TAG, "Error comparing suggestion ${sd.person.id}", t)
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
                            Log.w(TAG, "Failed to assign face ${result.faceRegionId}", t)
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to process cluster, skipping", t)
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
                Log.e(TAG, "Failed to recompute centroids", t)
            }

            reportProgress(100, applicationContext.getString(org.eidora.R.string.notif_done))
            return Result.success()
        } catch (t: Throwable) {
            Log.e(TAG, "Unhandled error in ClusteringWorker", t)
            return Result.failure()
        } finally {
            try {
                androidx.core.app.NotificationManagerCompat
                    .from(applicationContext)
                    .cancel(NotificationHelper.NOTIFICATION_ID_CLUSTERING)
            } catch (t: Throwable) {
                // ignore
            }
        }
    }

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
                Log.i(TAG, "Sync is active – clustering waiting")
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
                    // ignore
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
            Log.i(TAG, "Pre-clustering: rejecting all suggestions")
            repo.rejectAllSuggestions()
        }
        if (removeUnconfirmed) {
            Log.i(TAG, "Pre-clustering: removing unconfirmed faces from persons")
            personDao.getAll().forEach { person -> repo.removeUnconfirmedFaces(person.id) }
        }
    }

    private suspend fun loadClusteringConfig(): org.eidora.data.settings.ClusteringConfig =
        try {
            org.eidora.data.settings.SettingsProvider.get(applicationContext).getClusteringConfig()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to load clustering config, using defaults", t)
            org.eidora.data.settings.ClusteringConfig(
                edgeThreshold = 0.30f,
                clusterMatchThreshold = 0.30f,
                individualMatchThreshold = 0.25f,
                minClusterSize = 2,
                timeWeight = 1.0f,
            )
        }

    private suspend fun loadPowerConfig(): org.eidora.data.settings.PowerConfig =
        try {
            org.eidora.data.settings.SettingsProvider.get(applicationContext).getPowerConfig()
        } catch (t: Throwable) {
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
        ) { reason ->
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
                // ignore
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
                    PhotoSyncWorker.KEY_PROGRESS to percent,
                    PhotoSyncWorker.KEY_STATUS to message,
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
            // ignore
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
                    val centroid =
                        EmbeddingModel.weightedCentroid(
                            basisFaces.map {
                                EmbeddingModel.bytesToFloatArray(it.embedding!!) to (it.qualityScore ?: 0.5f)
                            },
                        )

                    val representative =
                        basisFaces.minByOrNull { face ->
                            EmbeddingModel.cosineDistance(
                                EmbeddingModel.bytesToFloatArray(face.embedding!!),
                                centroid,
                            )
                        }
                    personDao.updateRepresentativeFace(person.id, representative?.id)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to recompute centroid for person ${person.id}", t)
            }
            onProgress(((index + 1) * 100) / persons.size.coerceAtLeast(1))
        }
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
        fun cancelPendingIntent(context: Context): android.app.PendingIntent {
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
