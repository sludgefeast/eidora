package de.sebastian.eidora.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import de.sebastian.eidora.data.db.DatabaseProvider
import de.sebastian.eidora.data.db.EidoraDatabase
import de.sebastian.eidora.data.db.PersonEntity
import de.sebastian.eidora.ml.ChineseWhispers
import de.sebastian.eidora.ml.FaceNetModel
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
            reportProgress(0, applicationContext.getString(de.sebastian.eidora.R.string.notif_preparing))

            val config = try {
                de.sebastian.eidora.data.settings.SettingsProvider.get(applicationContext)
                    .getClusteringConfig()
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to load clustering config, using defaults", t)
                de.sebastian.eidora.data.settings.ClusteringConfig(
                    edgeThreshold = 0.30f,
                    clusterMatchThreshold = 0.30f,
                    individualMatchThreshold = 0.25f,
                    minClusterSize = 2
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
                    setForeground(NotificationHelper.clusteringForegroundInfo(applicationContext, 0, reason))
                } catch (t: Throwable) { /* ignore */ }
            }

            val pendingEmbeddings = faceDao.findWithoutEmbedding()
            if (pendingEmbeddings.isNotEmpty()) {
                Log.w(TAG, "${pendingEmbeddings.size} faces still missing embeddings – retrying later")
                return Result.retry()
            }

            val unknownFacesAll = faceDao.findUnclusteredAndNotIgnored()
                .filter { it.embedding != null }

            val personCentroids: Map<String, FloatArray> = personDao.getAll()
                .filter { it.name != null }
                .mapNotNull { person ->
                    val confirmedEmbeddings = faceDao.findByPersonId(person.id)
                        .filter { it.name != null && !it.ignored && it.embedding != null }
                        .map { FaceNetModel.bytesToFloatArray(it.embedding!!) }
                    if (confirmedEmbeddings.isEmpty()) null
                    else person.id to FaceNetModel.centroid(confirmedEmbeddings)
                }.toMap()

            // ----- Phase 1: Individual matching (0-30%) -----
            val individuallyAssigned = mutableSetOf<String>()
            if (personCentroids.isNotEmpty() && unknownFacesAll.isNotEmpty()) {
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
                        val embedding = FaceNetModel.bytesToFloatArray(face.embedding!!)
                        var bestId: String? = null
                        var bestDist = config.individualMatchThreshold
                        personCentroids.forEach { (personId, centroid) ->
                            val d = FaceNetModel.cosineDistance(embedding, centroid)
                            if (d < bestDist) { bestDist = d; bestId = personId }
                        }
                        bestId?.let { personId ->
                            faceDao.updatePersonId(face.id, personId)
                            individuallyAssigned.add(face.id)
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "Individual match failed for face ${face.id}", t)
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
                .filter { it.id !in individuallyAssigned }
                .map { face -> Pair(face.id, FaceNetModel.bytesToFloatArray(face.embedding!!)) }

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
                ChineseWhispers.cluster(candidates, config.edgeThreshold)
            } catch (t: Throwable) {
                Log.e(TAG, "Clustering algorithm failed", t)
                return Result.failure()
            }
            reportProgress(40, applicationContext.getString(de.sebastian.eidora.R.string.notif_grouping_done))

            // ----- Phase 3: Cluster assignment (40-80%) -----
            val clusterGroups = clusterResults.groupBy { it.clusterId }
            val totalClusters = clusterGroups.size
            clusterGroups.entries.forEachIndexed { index, (_, members) ->
                if (members.isEmpty()) return@forEachIndexed

                if (members.size < config.minClusterSize) {
                    Log.d(TAG, "Skipping singleton cluster (${members.size} face)")
                    return@forEachIndexed
                }

                try {
                    val memberEmbeddings: List<FloatArray> = members.mapNotNull { result ->
                        candidates.find { it.first == result.faceRegionId }?.second
                    }
                    val clusterCentroid = FaceNetModel.centroid(memberEmbeddings)

                    var bestPerson: PersonEntity? = null
                    var bestDistance = config.clusterMatchThreshold

                    personDao.getAll().forEach { person ->
                        try {
                            val personFaces = faceDao.findByPersonId(person.id)
                                .filter { it.embedding != null && !it.ignored }
                            if (personFaces.isEmpty()) return@forEach
                            val personEmbeddings: List<FloatArray> = personFaces
                                .map { FaceNetModel.bytesToFloatArray(it.embedding!!) }
                            val dist = FaceNetModel.cosineDistance(
                                clusterCentroid,
                                FaceNetModel.centroid(personEmbeddings)
                            )
                            if (dist < bestDistance) { bestDistance = dist; bestPerson = person }
                        } catch (t: Throwable) {
                            Log.w(TAG, "Error comparing person ${person.id}", t)
                        }
                    }

                    val targetPerson: PersonEntity = bestPerson ?: run {
                        val newPerson = PersonEntity(id = UUID.randomUUID().toString(), name = null)
                        personDao.insertWithNullableName(newPerson)
                        newPerson
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
            setForeground(NotificationHelper.clusteringForegroundInfo(applicationContext, percent, message))
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
                    val embeddings: List<FloatArray> = basisFaces
                        .map { FaceNetModel.bytesToFloatArray(it.embedding!!) }
                    val centroid = FaceNetModel.centroid(embeddings)

                    val representative = basisFaces.minByOrNull { face ->
                        FaceNetModel.cosineDistance(
                            FaceNetModel.bytesToFloatArray(face.embedding!!),
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
        fun buildRequest(): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<ClusteringWorker>()
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30_000L, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build()
    }
}
