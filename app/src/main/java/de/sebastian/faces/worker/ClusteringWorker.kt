package de.sebastian.faces.worker

import android.content.Context
import androidx.work.*
import de.sebastian.faces.data.db.FacesDatabase
import de.sebastian.faces.data.db.PersonEntity
import de.sebastian.faces.ml.ChineseWhispers
import de.sebastian.faces.ml.FaceNetModel
import java.util.UUID

class ClusteringWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val db = FacesDatabase.getInstance(applicationContext)
        val faceDao = db.faceRegionDao()
        val personDao = db.personDao()

        return try {
            setProgress(workDataOf(PhotoSyncWorker.KEY_STATUS to "Clustering faces…"))

            // Only cluster faces without personId, not ignored, with embeddings
            val candidates = faceDao.findUnclusteredAndNotIgnored()
                .filter { it.embedding != null }
                .map { face ->
                    Pair(face.id, FaceNetModel.bytesToFloatArray(face.embedding!!))
                }

            if (candidates.isEmpty()) return Result.success()

            val clusterResults = ChineseWhispers.cluster(candidates)

            // Group by clusterId
            val clusters = clusterResults.groupBy { it.clusterId }

            clusters.forEach { (_, members) ->
                if (members.isEmpty()) return@forEach

                // Find the best matching existing person using centroid comparison
                val memberEmbeddings = members.mapNotNull { result ->
                    candidates.find { it.first == result.faceRegionId }?.second
                }
                val clusterCentroid = FaceNetModel.centroid(memberEmbeddings)

                // Compare with centroids of existing persons
                val existingPersons = personDao.getAll()
                var bestPerson: PersonEntity? = null
                var bestDistance = ChineseWhispers.EDGE_THRESHOLD_PUBLIC

                existingPersons.forEach { person ->
                    val personFaces = faceDao.findByPersonId(person.id)
                        .filter { it.embedding != null && !it.ignored }
                    if (personFaces.isEmpty()) return@forEach

                    val personEmbeddings = personFaces.map { FaceNetModel.bytesToFloatArray(it.embedding!!) }
                    val personCentroid = FaceNetModel.centroid(personEmbeddings)
                    val dist = FaceNetModel.cosineDistance(clusterCentroid, personCentroid)
                    if (dist < bestDistance) {
                        bestDistance = dist
                        bestPerson = person
                    }
                }

                val targetPerson = bestPerson ?: run {
                    // Create a new unnamed person (will be named by user)
                    val newPerson = PersonEntity(
                        id = UUID.randomUUID().toString(),
                        name = "Person_${UUID.randomUUID().toString().take(4)}"
                    )
                    personDao.insert(newPerson)
                    newPerson
                }

                // Assign faces to person as suggestions (name stays null)
                members.forEach { result ->
                    faceDao.updatePersonId(result.faceRegionId, targetPerson.id)
                }
            }

            // Recompute centroids and representative faces for all affected persons
            recomputeAllCentroids(db)

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private suspend fun recomputeAllCentroids(db: FacesDatabase) {
        val personDao = db.personDao()
        val faceDao = db.faceRegionDao()

        personDao.getAll().forEach { person ->
            val allFaces = faceDao.findByPersonId(person.id).filter { !it.ignored && it.embedding != null }
            if (allFaces.isEmpty()) return@forEach

            val confirmedFaces = allFaces.filter { it.name != null }
            val basisFaces = confirmedFaces.ifEmpty { allFaces }

            val embeddings = basisFaces.map { FaceNetModel.bytesToFloatArray(it.embedding!!) }
            val centroid = FaceNetModel.centroid(embeddings)

            // Find face closest to centroid
            val representative = basisFaces.minByOrNull { face ->
                FaceNetModel.cosineDistance(FaceNetModel.bytesToFloatArray(face.embedding!!), centroid)
            }
            representative?.let {
                personDao.updateRepresentativeFace(person.id, it.id)
            }
        }
    }

    companion object {
        const val WORK_NAME = "clustering"

        fun buildRequest(): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<ClusteringWorker>().build()
    }
}

// Make threshold accessible for ClusteringWorker
val ChineseWhispers.Companion.EDGE_THRESHOLD_PUBLIC get() = 0.40f
