package de.sebastian.eidora.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import androidx.work.WorkInfo
import de.sebastian.eidora.data.db.DatabaseProvider
import de.sebastian.eidora.data.db.EidoraDatabase
import de.sebastian.eidora.data.db.PersonEntity
import de.sebastian.eidora.ml.ChineseWhispers
import de.sebastian.eidora.ml.EmbeddingModel
import java.util.UUID

private const val TAG = "ClusteringWorker"

class ClusteringWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val db = DatabaseProvider.getInstance(applicationContext)
        val faceDao = db.faceRegionDao()
        val personDao = db.personDao()

        try {
            // ----- Mutual exclusion: wait for active sync to finish -----
            val wm = WorkManager.getInstance(applicationContext)
            val syncStates = setOf(WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED)
            var waited = false
            while (true) {
                val syncRunning = wm.getWorkInfosForUniqueWork(SyncPipeline.UNIQUE_SYNC_NAME).get()
                    ?.any { it.state in syncStates } == true
                if (!syncRunning) break
                if (!waited) {
                    waited = true
                    Log.i(TAG, "Sync is active – clustering waiting")
                    try {
                        setForeground(NotificationHelper.clusteringForegroundInfo(
                            applicationContext, 0,
                            applicationContext.getString(de.sebastian.eidora.R.string.notif_waiting_for_sync),
                            cancelIntent = cancelPendingIntent(applicationContext)
                        ))
                    } catch (t: Throwable) { /* ignore */ }
                }
                if (isStopped) return Result.success()
                kotlinx.coroutines.delay(5_000)
            }

            reportProgress(0, applicationContext.getString(de.sebastian.eidora.R.string.notif_preparing))

            // ----- Pre-cleaning (from input data) -----
            val rejectSuggestions = inputData.getBoolean(KEY_REJECT_SUGGESTIONS, false)
            val removeUnconfirmed = inputData.getBoolean(KEY_REMOVE_UNCONFIRMED, false)
            if (rejectSuggestions || removeUnconfirmed) {
                val repo = de.sebastian.eidora.data.repository.FaceRepository(applicationContext, db)
                if (rejectSuggestions) {
                    Log.i(TAG, "Pre-clustering: rejecting all suggestions")
                    repo.rejectAllSuggestions()
                }
                if (removeUnconfirmed) {
                    Log.i(TAG, "Pre-clustering: removing unconfirmed faces from persons")
                    personDao.getAll().forEach { person ->
                        repo.removeUnconfirmedFaces(person.id)
                    }
                }
            }

            val config = try {
                de.sebastian.eidora.data.settings.SettingsProvider.get(applicationContext)
                    .getClusteringConfig()
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to load clustering config, using defaults", t)
                de.sebastian.eidora.data.settings.ClusteringConfig(
                    edgeThreshold = 0.30f,
                    clusterMatchThreshold = 0.30f,
                    individualMatchThreshold = 0.25f,
                    minClusterSize = 2,
                    timeWeight = 1.0f
                )
            }

            val powerGate = PowerGate(applicationContext)
            val powerConfig = try {
                de.sebastian.eidora.data.settings.SettingsProvider.get(applicationContext).getPowerConfig()
            } catch (t: Throwable) {
                de.sebastian.eidora.data.settings.PowerConfig(
                    minBatteryPercent = 20,
                    maxBatteryTempCelsius = 40.0f
                )
            }
            powerGate.awaitOk(powerConfig.minBatteryPercent, powerConfig.maxBatteryTempCelsius) { reason ->
                try {
                    setForeground(NotificationHelper.clusteringForegroundInfo(
                        applicationContext, 0, reason,
                        cancelIntent = cancelPendingIntent(applicationContext)
                    ))
                } catch (t: Throwable) { /* ignore */ }
            }

            val pendingEmbeddings = faceDao.findWithoutEmbedding()
            if (pendingEmbeddings.isNotEmpty()) {
                Log.w(TAG, "${pendingEmbeddings.size} faces still missing embeddings – retrying later")
                return Result.retry()
            }

            val timeWeight = config.timeWeight
            val unknownFacesAll = faceDao.findUnclusteredWithDate()
                .filter { it.faceRegion.embedding != null }

            // personCentroids: centroid + median takenAt for temporal penalty
            data class PersonData(val centroid: FloatArray, val medianTakenAt: Long?)
            val personData: Map<String, PersonData> = personDao.getAll()
                .filter { it.name != null }
                .mapNotNull { person ->
                    val allFaces = faceDao.findByPersonIdWithDate(person.id)
                        .filter { !it.faceRegion.ignored && it.faceRegion.embedding != null }
                    if (allFaces.isEmpty()) null
                    else {
                        // Weight confirmed faces (name != null) higher than clustered ones
                        val centroid = EmbeddingModel.weightedCentroid(
                            allFaces.map {
                                val quality = it.faceRegion.qualityScore ?: 0.5f
                                val confirmBoost = if (it.faceRegion.name != null) 1.5f else 1.0f
                                EmbeddingModel.bytesToFloatArray(it.faceRegion.embedding!!) to (quality * confirmBoost)
                            }
                        )
                        val dates = allFaces.mapNotNull { it.photoTakenAt }.sorted()
                        val median = if (dates.isEmpty()) null else dates[dates.size / 2]
                        person.id to PersonData(centroid, median)
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
                        powerGate.awaitOk(powerConfig.minBatteryPercent, powerConfig.maxBatteryTempCelsius) { reason ->
                            try {
                                setForeground(NotificationHelper.clusteringForegroundInfo(applicationContext, (index * 30) / unknownFacesAll.size, reason))
                            } catch (t: Throwable) { /* ignore */ }
                        }
                    }
                    try {
                        val embedding = EmbeddingModel.bytesToFloatArray(face.faceRegion.embedding!!)
                        var bestId: String? = null
                        var bestDist = config.individualMatchThreshold
                        personData.forEach { (personId, pd) ->
                            val cosD = EmbeddingModel.cosineDistance(embedding, pd.centroid)
                            val penalty = de.sebastian.eidora.ml.TemporalDistance.penalty(
                                face.photoTakenAt, pd.medianTakenAt, timeWeight, config.individualMatchThreshold
                            )
                            val d = cosD + penalty
                            if (d < bestDist) { bestDist = d; bestId = personId }
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
                        reportProgress(phaseProgress, applicationContext.getString(de.sebastian.eidora.R.string.notif_matching_persons, index + 1, unknownFacesAll.size))
                    }
                }
                Log.i(TAG, "Individually assigned ${individuallyAssigned.size} faces to existing persons")
            }
            reportProgress(30, applicationContext.getString(de.sebastian.eidora.R.string.notif_matching_persons_done))

            val candidates: List<Pair<String, FloatArray>> = unknownFacesAll
                .filter { it.faceRegion.id !in individuallyAssigned }
                .map { face -> Pair(face.faceRegion.id, EmbeddingModel.bytesToFloatArray(face.faceRegion.embedding!!)) }
            // takenAt lookup for ChineseWhispers temporal penalty
            val candidateTakenAt: Map<String, Long?> = unknownFacesAll
                .filter { it.faceRegion.id !in individuallyAssigned }
                .associate { it.faceRegion.id to it.photoTakenAt }

            if (candidates.isEmpty()) {
                reportProgress(80, applicationContext.getString(de.sebastian.eidora.R.string.notif_updating_centroids))
                try { recomputeAllCentroids(db) { p -> reportProgress(80 + p * 20 / 100, applicationContext.getString(de.sebastian.eidora.R.string.notif_updating_centroids)) } }
                catch (t: Throwable) { Log.e(TAG, "Failed to recompute centroids", t) }
                reportProgress(100, applicationContext.getString(de.sebastian.eidora.R.string.notif_done))
                return Result.success()
            }

            // ----- Phase 2: Chinese Whispers (30-40%) -----
            reportProgress(30, applicationContext.getString(de.sebastian.eidora.R.string.notif_grouping, candidates.size))
            val clusterResults = try {
                ChineseWhispers.cluster(candidates, config.edgeThreshold, candidateTakenAt, timeWeight)
            } catch (t: Throwable) {
                Log.e(TAG, "Clustering algorithm failed", t)
                return Result.failure()
            }
            reportProgress(40, applicationContext.getString(de.sebastian.eidora.R.string.notif_grouping_done))

            // ----- Phase 3: Cluster assignment (40-80%) -----
            // Pre-load existing suggestions (unnamed persons) with their centroids
            // so new clusters can be merged into them instead of creating duplicates.
            data class SuggestionData(val person: PersonEntity, val centroid: FloatArray, val medianTakenAt: Long?)
            val existingSuggestions: List<SuggestionData> = try {
                personDao.getSuggestions().mapNotNull { suggestion ->
                    val faces = faceDao.findByPersonIdWithDate(suggestion.id)
                        .filter { !it.faceRegion.ignored && it.faceRegion.embedding != null }
                    if (faces.isEmpty()) null
                    else {
                        val centroid = EmbeddingModel.weightedCentroid(
                            faces.map {
                                EmbeddingModel.bytesToFloatArray(it.faceRegion.embedding!!) to (it.faceRegion.qualityScore ?: 0.5f)
                            }
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
                    val memberPairs: List<Pair<FloatArray, Float>> = members.mapNotNull { result ->
                        candidates.find { it.first == result.faceRegionId }
                            ?.let { (_, emb) ->
                                // quality is stored in faceDao but not in the candidates list;
                                // fall back to equal weight for clustering pass
                                emb to 0.5f
                            }
                    }
                    val clusterCentroid = EmbeddingModel.weightedCentroid(memberPairs)

                    var bestPerson: PersonEntity? = null
                    var bestDistance = config.clusterMatchThreshold
                    val clusterMedian = members.mapNotNull { candidateTakenAt[it.faceRegionId] }
                        .sorted().let { if (it.isEmpty()) null else it[it.size / 2] }

                    personData.forEach { (personId, pd) ->
                        try {
                            val cosD = EmbeddingModel.cosineDistance(clusterCentroid, pd.centroid)
                            val penalty = de.sebastian.eidora.ml.TemporalDistance.penalty(
                                clusterMedian, pd.medianTakenAt, timeWeight, config.clusterMatchThreshold
                            )
                            val d = cosD + penalty
                            if (d < bestDistance) {
                                bestDistance = d
                                bestPerson = personDao.findById(personId)
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "Error comparing person $personId", t)
                        }
                    }

                    val targetPerson: PersonEntity = bestPerson ?: run {
                        // No named person matched – check existing suggestions before
                        // creating a new one. This avoids duplicate suggestion clusters.
                        var bestSuggestion: PersonEntity? = null
                        var bestSuggestionDist = config.clusterMatchThreshold
                        existingSuggestions.forEach { sd ->
                            try {
                                val cosD = EmbeddingModel.cosineDistance(clusterCentroid, sd.centroid)
                                val penalty = de.sebastian.eidora.ml.TemporalDistance.penalty(
                                    clusterMedian, sd.medianTakenAt, timeWeight, config.clusterMatchThreshold
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
                        try { faceDao.updatePersonId(result.faceRegionId, targetPerson.id) }
                        catch (t: Throwable) { Log.w(TAG, "Failed to assign face ${result.faceRegionId}", t) }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to process cluster, skipping", t)
                }

                val phaseProgress = 40 + ((index + 1) * 40) / totalClusters.coerceAtLeast(1)
                reportProgress(phaseProgress, applicationContext.getString(de.sebastian.eidora.R.string.notif_assigning, index + 1, totalClusters))
            }

            // ----- Phase 4: Centroid recompute (80-100%) -----
            reportProgress(80, applicationContext.getString(de.sebastian.eidora.R.string.notif_updating_centroids))
            try {
                recomputeAllCentroids(db) { p ->
                    reportProgress(80 + p * 20 / 100, applicationContext.getString(de.sebastian.eidora.R.string.notif_updating_centroids))
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to recompute centroids", t)
            }

            reportProgress(100, applicationContext.getString(de.sebastian.eidora.R.string.notif_done))
            return Result.success()
        } catch (t: Throwable) {
            Log.e(TAG, "Unhandled error in ClusteringWorker", t)
            return Result.failure()
        } finally {
            try {
                androidx.core.app.NotificationManagerCompat.from(applicationContext)
                    .cancel(NotificationHelper.NOTIFICATION_ID_CLUSTERING)
            } catch (t: Throwable) { /* ignore */ }
        }
    }

    private suspend fun reportProgress(percent: Int, message: String) {
        try {
            setProgress(workDataOf(
                PhotoSyncWorker.KEY_PROGRESS to percent,
                PhotoSyncWorker.KEY_STATUS to message
            ))
            setForeground(NotificationHelper.clusteringForegroundInfo(
                applicationContext, percent, message,
                cancelIntent = cancelPendingIntent(applicationContext)
            ))
        } catch (t: Throwable) { /* ignore */ }
    }

    private suspend fun recomputeAllCentroids(
        db: EidoraDatabase,
        onProgress: suspend (Int) -> Unit = {}
    ) {
        val personDao = db.personDao()
        val faceDao = db.faceRegionDao()

        val persons = personDao.getAll()
        persons.forEachIndexed { index, person ->
            try {
                val allFaces = faceDao.findByPersonId(person.id)
                    .filter { !it.ignored && it.embedding != null }
                if (allFaces.isNotEmpty()) {
                    val basisFaces = allFaces.filter { it.name != null }.ifEmpty { allFaces }
                    val centroid = EmbeddingModel.weightedCentroid(
                        basisFaces.map {
                            EmbeddingModel.bytesToFloatArray(it.embedding!!) to (it.qualityScore ?: 0.5f)
                        }
                    )

                    val representative = basisFaces.minByOrNull { face ->
                        EmbeddingModel.cosineDistance(
                            EmbeddingModel.bytesToFloatArray(face.embedding!!),
                            centroid
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

    companion object {
        const val KEY_REJECT_SUGGESTIONS = "reject_suggestions"
        const val KEY_REMOVE_UNCONFIRMED = "remove_unconfirmed"

        fun buildRequest(
            rejectSuggestions: Boolean = false,
            removeUnconfirmed: Boolean = false
        ): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<ClusteringWorker>()
                .setInputData(workDataOf(
                    KEY_REJECT_SUGGESTIONS to rejectSuggestions,
                    KEY_REMOVE_UNCONFIRMED to removeUnconfirmed
                ))
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30_000L, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build()

        /** PendingIntent that cancels the clustering work – used in notification. */
        fun cancelPendingIntent(context: Context): android.app.PendingIntent {
            val intent = android.content.Intent(context, CancelClusteringReceiver::class.java)
            return android.app.PendingIntent.getBroadcast(
                context, 0, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
