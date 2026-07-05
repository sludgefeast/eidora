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

        return try {
            try {
                setForeground(NotificationHelper.clusteringForegroundInfo(applicationContext))
            } catch (t: Throwable) {
                Log.w(TAG, "setForeground failed", t)
            }
            setProgress(workDataOf(PhotoSyncWorker.KEY_STATUS to "Clustering faces…"))

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

            // Fix 4: abort if any faces still lack embeddings – they haven't been
            // processed by EmbeddingWorker yet. Retry later instead of clustering
            // on incomplete data.
            val pendingEmbeddings = faceDao.findWithoutEmbedding()
            if (pendingEmbeddings.isNotEmpty()) {
                Log.w(TAG, "${pendingEmbeddings.size} faces still missing embeddings – retrying later")
                return Result.retry()
            }

            // Pre-clustering step: try to match each unknown face individually
            // against the centroids of confirmed faces per named person.
            // Uses a stricter threshold than the cluster-level match.
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

            val individuallyAssigned = mutableSetOf<String>()
            if (personCentroids.isNotEmpty()) {
                unknownFacesAll.forEach { face ->
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
                }
                Log.i(TAG, "Individually assigned ${individuallyAssigned.size} faces to existing persons")
            }

            // Remaining candidates for clustering
            val candidates: List<Pair<String, FloatArray>> = unknownFacesAll
                .filter { it.id !in individuallyAssigned }
                .map { face -> Pair(face.id, FaceNetModel.bytesToFloatArray(face.embedding!!)) }

            if (candidates.isEmpty()) {
                try { recomputeAllCentroids(db) } catch (t: Throwable) {
                    Log.e(TAG, "Failed to recompute centroids", t)
                }
                return Result.success()
            }

            val clusterResults = try {
                ChineseWhispers.cluster(candidates, config.edgeThreshold)
            } catch (t: Throwable) {
                Log.e(TAG, "Clustering algorithm failed", t)
                return Result.failure()
            }

            clusterResults.groupBy { it.clusterId }.forEach { (_, members) ->
                if (members.isEmpty()) return@forEach

                // Fix 2: skip singleton clusters – leave them as Unknown until
                // more evidence accumulates in future syncs
                if (members.size < config.minClusterSize) {
                    Log.d(TAG, "Skipping singleton cluster (${members.size} face)")
                    return@forEach
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
            }

            try { recomputeAllCentroids(db) } catch (t: Throwable) {
                Log.e(TAG, "Failed to recompute centroids", t)
            }

            Result.success()
        } catch (t: Throwable) {
            Log.e(TAG, "Unhandled error in ClusteringWorker", t)
            Result.failure()
        }
    }

    private suspend fun recomputeAllCentroids(db: EidoraDatabase) {
        val personDao = db.personDao()
        val faceDao = db.faceRegionDao()

        personDao.getAll().forEach { person ->
            try {
                val allFaces = faceDao.findByPersonId(person.id)
                    .filter { !it.ignored && it.embedding != null }
                if (allFaces.isEmpty()) return@forEach

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
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to recompute centroid for person ${person.id}", t)
            }
        }
    }

    companion object {
        fun buildRequest(): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<ClusteringWorker>()
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30_000L, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build()
    }
}
