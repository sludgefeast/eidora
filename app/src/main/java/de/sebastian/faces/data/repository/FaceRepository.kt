package de.sebastian.faces.data.repository

import android.content.Context
import de.sebastian.faces.data.db.*
import de.sebastian.faces.ml.FaceNetModel
import de.sebastian.faces.util.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.Flow

class FaceRepository(
    private val context: Context,
    private val db: FacesDatabase
) {
    private val photoDao = db.photoDao()
    private val personDao = db.personDao()
    private val faceDao = db.faceRegionDao()

    // -----------------------------------------------------------------------
    // Confirm face → person
    // -----------------------------------------------------------------------

    suspend fun confirmFace(faceRegionId: String, personId: String) {
        val face = faceDao.findById(faceRegionId) ?: return
        val person = personDao.findById(personId) ?: return
        faceDao.updatePersonAndName(faceRegionId, personId, person.name)
        rewriteXmpForPhoto(face.photoId)
        recomputeCentroid(personId)
    }

    // -----------------------------------------------------------------------
    // Ignore face
    // -----------------------------------------------------------------------

    suspend fun ignoreFace(faceRegionId: String) {
        val face = faceDao.findById(faceRegionId) ?: return
        val previousPersonId = face.personId
        faceDao.setIgnored(faceRegionId)
        previousPersonId?.let { recomputeCentroid(it) }
    }

    suspend fun unignoreFace(faceRegionId: String) {
        val face = faceDao.findById(faceRegionId) ?: return
        faceDao.setNotIgnored(faceRegionId)
        face.personId?.let { recomputeCentroid(it) }
    }

    // -----------------------------------------------------------------------
    // Remove face from person
    // -----------------------------------------------------------------------

    suspend fun removeFaceFromPerson(faceRegionId: String) {
        val face = faceDao.findById(faceRegionId) ?: return
        val previousPersonId = face.personId ?: return
        faceDao.updatePersonAndName(faceRegionId, null, null)
        rewriteXmpForPhoto(face.photoId)
        recomputeCentroid(previousPersonId)
        deletePersonIfOrphaned(previousPersonId)
    }

    // -----------------------------------------------------------------------
    // Assign face to person (or new person)
    // -----------------------------------------------------------------------

    suspend fun assignFaceToPerson(faceRegionId: String, personId: String) {
        val face = faceDao.findById(faceRegionId) ?: return
        val previousPersonId = face.personId
        val person = personDao.findById(personId) ?: return
        faceDao.updatePersonAndName(faceRegionId, personId, person.name)
        rewriteXmpForPhoto(face.photoId)
        previousPersonId?.let { recomputeCentroid(it) }
        recomputeCentroid(personId)
        previousPersonId?.let { deletePersonIfOrphaned(it) }
    }

    suspend fun assignFaceToNewPerson(faceRegionId: String, name: String): PersonEntity {
        val existing = personDao.findByName(name)
        val person = existing ?: PersonEntity(UUID.randomUUID().toString(), name).also {
            personDao.insert(it)
        }
        assignFaceToPerson(faceRegionId, person.id)
        return person
    }

    // -----------------------------------------------------------------------
    // Rename person
    // -----------------------------------------------------------------------

    suspend fun renamePerson(personId: String, newName: String) {
        personDao.updateName(personId, newName)
        faceDao.updateConfirmedNamesForPerson(personId, newName)
        // Rewrite XMP for all photos of this person
        val faces = faceDao.findByPersonId(personId).filter { it.name != null }
        val photoIds = faces.map { it.photoId }.distinct()
        photoIds.forEach { rewriteXmpForPhoto(it) }
    }

    // -----------------------------------------------------------------------
    // Merge persons
    // -----------------------------------------------------------------------

    suspend fun mergePersons(sourceIds: List<String>, winnerId: String) {
        val winner = personDao.findById(winnerId) ?: return
        sourceIds.filter { it != winnerId }.forEach { sourceId ->
            // Reassign all faces
            faceDao.reassignPerson(sourceId, winnerId)
            // Update confirmed names
            faceDao.updateConfirmedNamesForPerson(winnerId, winner.name)
            personDao.deleteById(sourceId)
        }
        recomputeCentroid(winnerId)
        // Rewrite XMP for all affected photos
        val faces = faceDao.findByPersonId(winnerId)
        val photoIds = faces.map { it.photoId }.distinct()
        photoIds.forEach { rewriteXmpForPhoto(it) }
    }

    // -----------------------------------------------------------------------
    // Reset photo (re-detect faces)
    // -----------------------------------------------------------------------

    suspend fun resetPhotoFaces(photoId: String) {
        val photo = photoDao.findById(photoId) ?: return
        val file = File(photo.path)

        // Delete all face regions and thumbnails
        val faces = faceDao.findByPhotoId(photoId)
        faces.forEach { ThumbnailHelper.deleteThumbnail(context, it.id) }
        faceDao.deleteByPhotoId(photoId)

        // Clear XMP face data
        XmpHelper.clearFaceData(file)
        photoDao.updateModifiedAt(photoId, file.lastModified())
        photoDao.updateAnalyzed(photoId, false)
    }

    // -----------------------------------------------------------------------
    // Observe
    // -----------------------------------------------------------------------

    fun observePersonsWithCount(): Flow<List<PersonWithCount>> =
        personDao.observeAllWithConfirmedCount()

    fun observeFacesByPerson(personId: String): Flow<List<FaceRegionWithPhoto>> =
        faceDao.observeByPersonId(personId)

    fun observeUnknownFaces(): Flow<List<FaceRegionWithPhoto>> =
        faceDao.observeUnknown()

    fun observeIgnoredFaces(): Flow<List<FaceRegionWithPhoto>> =
        faceDao.observeIgnored()

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private suspend fun rewriteXmpForPhoto(photoId: String) {
        val photo = photoDao.findById(photoId) ?: return
        val file = File(photo.path)
        if (!file.exists()) return

        val faces = faceDao.findByPhotoId(photoId)
        val xmpRegions = faces.map { face ->
            XmpFaceRegion(
                name = face.name,
                coords = face.regionJson.toFaceRegionCoords()
            )
        }
        XmpHelper.writeFaceRegions(file, xmpRegions)
        photoDao.updateModifiedAt(photoId, file.lastModified())
    }

    private suspend fun recomputeCentroid(personId: String) {
        val allFaces = faceDao.findByPersonId(personId).filter { !it.ignored && it.embedding != null }
        if (allFaces.isEmpty()) {
            personDao.updateRepresentativeFace(personId, null)
            return
        }
        val confirmedFaces = allFaces.filter { it.name != null }
        val basisFaces = confirmedFaces.ifEmpty { allFaces }
        val embeddings = basisFaces.map { FaceNetModel.bytesToFloatArray(it.embedding!!) }
        val centroid = FaceNetModel.centroid(embeddings)
        val representative = basisFaces.minByOrNull { face ->
            FaceNetModel.cosineDistance(FaceNetModel.bytesToFloatArray(face.embedding!!), centroid)
        }
        personDao.updateRepresentativeFace(personId, representative?.id)
    }

    private suspend fun deletePersonIfOrphaned(personId: String) {
        val remaining = faceDao.findByPersonId(personId)
        if (remaining.isEmpty()) {
            personDao.deleteById(personId)
        }
    }
}
