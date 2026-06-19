package de.sebastian.faces.worker

import android.content.Context
import androidx.work.*
import de.sebastian.faces.data.db.DatabaseProvider
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
        val db = DatabaseProvider.getInstance(applicationContext)
        val faceDao = db.faceRegionDao()
        val personDao = db.personDao()

        return try {
            setProgress(workDataOf(PhotoSyncWorker.KEY_STATUS to "Clustering faces…"))

            val candidates: List<Pair<String, FloatArray>> = faceDao
                .findUnclusteredAndNotIgnored()
                .filter { it.embedding != null }
                .map { face -> Pair(face.id, FaceNetModel.bytesToFloatArray(face.embedding!!)) }

            if (candidates.isEmpty()) return Result.success()

            val clusterResults = ChineseWhispers.cluster(candidates)
            val clusters = clusterResults.groupBy { it.clusterId }

            clusters.forEach { (_, members) ->
                if (members.isEmpty()) return@forEach

                val memberEmbeddings: List<FloatArray> = members.mapNotNull { result ->
                    candidates.find { it.first == result.faceRegionId }?.second
                }
                val clusterCentroid = FaceNetModel.centroid(memberEmbeddings)

                val existingPersons = personDao.getAll()
                var bestPerson: PersonEntity? = null
                var bestDistance = CLUSTER_MATCH_THRESHOLD

                existingPersons.forEach { person ->
                    val personFaces = faceDao.findByPersonId(person.id)
                        .filter { it.embedding != null && !it.ignored }
                    if (personFaces.isEmpty()) return@forEach

                    val personEmbeddings: List<FloatArray> = personFaces
                        .map { FaceNetModel.bytesToFloatArray(it.embedding!!) }
                    val personCentroid = FaceNetModel.centroid(personEmbeddings)
                    val dist = FaceNetModel.cosineDistance(clusterCentroid, personCentroid)
                    if (dist < bestDistance) {
                        bestDistance = dist
                        bestPerson = person
                    }
                }

                // Fix 3: new persons get NO name – they are pure suggestions (name = null)
                val targetPerson: PersonEntity = bestPerson ?: run {
                    val newPerson = PersonEntity(
                        id = UUID.randomUUID().toString(),
                        name = null  // will be set by user when confirming
                    )
                    personDao.insertWithNullableName(newPerson)
                    newPerson
                }

                members.forEach { result ->
                    faceDao.updatePersonId(result.faceRegionId, targetPerson.id)
                }
            }

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
            val allFaces = faceDao.findByPersonId(person.id)
                .filter { !it.ignored && it.embedding != null }
            if (allFaces.isEmpty()) return@forEach

            val confirmedFaces = allFaces.filter { it.name != null }
            val basisFaces = confirmedFaces.ifEmpty { allFaces }

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
    }

    companion object {
        const val WORK_NAME = "clustering"
        const val CLUSTER_MATCH_THRESHOLD = 0.40f

        fun buildRequest(): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<ClusteringWorker>().build()
    }
}
