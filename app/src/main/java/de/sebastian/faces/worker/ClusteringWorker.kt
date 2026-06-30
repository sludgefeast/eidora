package de.sebastian.faces.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import de.sebastian.faces.data.db.DatabaseProvider
import de.sebastian.faces.data.db.FacesDatabase
import de.sebastian.faces.data.db.PersonEntity
import de.sebastian.faces.ml.ChineseWhispers
import de.sebastian.faces.ml.FaceNetModel
import java.util.UUID

private const val TAG = "ClusteringWorker"

// Fix 1: lower threshold for fewer false positives
private const val CLUSTER_MATCH_THRESHOLD = 0.30f

// Fix 2: minimum cluster size to create a suggestion
private const val MIN_CLUSTER_SIZE = 2

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

            // Fix 4: abort if any faces still lack embeddings – they haven't been
            // processed by EmbeddingWorker yet. Retry later instead of clustering
            // on incomplete data.
            val pendingEmbeddings = faceDao.findWithoutEmbedding()
            if (pendingEmbeddings.isNotEmpty()) {
                Log.w(TAG, "${pendingEmbeddings.size} faces still missing embeddings – retrying later")
                return Result.retry()
            }

            val candidates: List<Pair<String, FloatArray>> = faceDao
                .findUnclusteredAndNotIgnored()
                .filter { it.embedding != null }
                .map { face -> Pair(face.id, FaceNetModel.bytesToFloatArray(face.embedding!!)) }

            if (candidates.isEmpty()) return Result.success()

            val clusterResults = try {
                ChineseWhispers.cluster(candidates)
            } catch (t: Throwable) {
                Log.e(TAG, "Clustering algorithm failed", t)
                return Result.failure()
            }

            clusterResults.groupBy { it.clusterId }.forEach { (_, members) ->
                if (members.isEmpty()) return@forEach

                // Fix 2: skip singleton clusters – leave them as Unknown until
                // more evidence accumulates in future syncs
                if (members.size < MIN_CLUSTER_SIZE) {
                    Log.d(TAG, "Skipping singleton cluster (${members.size} face)")
                    return@forEach
                }

                try {
                    val memberEmbeddings: List<FloatArray> = members.mapNotNull { result ->
                        candidates.find { it.first == result.faceRegionId }?.second
                    }
                    val clusterCentroid = FaceNetModel.centroid(memberEmbeddings)

                    var bestPerson: PersonEntity? = null
                    var bestDistance = CLUSTER_MATCH_THRESHOLD

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

    private suspend fun recomputeAllCentroids(db: FacesDatabase) {
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
