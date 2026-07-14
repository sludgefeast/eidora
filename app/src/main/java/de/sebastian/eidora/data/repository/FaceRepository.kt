package de.sebastian.eidora.data.repository

import android.content.Context
import android.util.Log
import de.sebastian.eidora.data.db.*
import de.sebastian.eidora.ml.EmbeddingModel
import de.sebastian.eidora.util.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.Flow

class FaceRepository(
    private val context: Context,
    private val db: EidoraDatabase
) {
    private val TAG = "FaceRepository"

    private val photoDao = db.photoDao()
    private val personDao = db.personDao()
    private val faceDao = db.faceRegionDao()

    // -----------------------------------------------------------------------
    // Confirm face → person
    // -----------------------------------------------------------------------

    suspend fun confirmFace(faceRegionId: String, personId: String) {
        val face = faceDao.findById(faceRegionId) ?: return
        val person = personDao.findById(personId) ?: return
        val personName = person.name ?: return // can't confirm to unnamed suggestion person
        faceDao.updatePersonAndName(faceRegionId, personId, personName)
        rewriteXmpForPhoto(face.photoId)
        recomputeCentroid(personId)
    }

    // -----------------------------------------------------------------------
    // Reject suggestion (unnamed Person)
    // -----------------------------------------------------------------------

    /**
     * Detaches all faces from an unnamed suggestion Person (personId → null)
     * and deletes the Person. Faces become "Unknown" again.
     */
    suspend fun rejectSuggestion(personId: String) {
        val person = personDao.findById(personId) ?: return
        if (person.name != null) return  // Safety: only reject unnamed suggestions
        val faces = faceDao.findByPersonId(personId)
        faces.forEach { face -> faceDao.updatePersonId(face.id, null) }
        personDao.deleteById(personId)
    }

    /**
     * Deletes a named person: moves all their faces (confirmed + unconfirmed)
     * back to Unknown, clears XMP metadata for every affected photo, and
     * removes the person record.
     */
    suspend fun deletePerson(personId: String) {
        val person = personDao.findById(personId) ?: return
        val faces = faceDao.findByPersonId(personId)

        // Collect affected photo IDs before touching anything
        val affectedPhotoIds = faces.map { it.photoId }.distinct()

        // Detach all faces from this person (confirmed + unconfirmed)
        faces.forEach { face ->
            faceDao.updatePersonId(face.id, null)
            if (face.name != null) {
                faceDao.clearName(face.id)
            }
        }

        personDao.deleteById(personId)

        // Rewrite XMP for each affected photo so DigiKam/Aves no longer
        // show the person's name in the face regions.
        affectedPhotoIds.forEach { photoId ->
            try { rewriteXmpForPhoto(photoId) }
            catch (t: Throwable) { Log.w(TAG, "XMP rewrite failed for $photoId", t) }
        }
    }

    /**
     * Removes all unconfirmed faces (name == null) from the given person.
     * They move back to Unknown and will be re-clustered on the next run.
     */
    suspend fun removeUnconfirmedFaces(personId: String) {
        val unconfirmed = faceDao.findByPersonId(personId)
            .filter { it.name == null }
        unconfirmed.forEach { face ->
            faceDao.updatePersonId(face.id, null)
        }
        recomputeCentroid(personId)
        deletePersonIfOrphaned(personId)
    }

    suspend fun rejectAllSuggestions() {
        val suggestions = personDao.getSuggestions()
        suggestions.forEach { person ->
            val faces = faceDao.findByPersonId(person.id)
            faces.forEach { face -> faceDao.updatePersonId(face.id, null) }
            personDao.deleteById(person.id)
        }
    }

    // -----------------------------------------------------------------------
    // Ignore face
    // -----------------------------------------------------------------------

    suspend fun ignoreFace(faceRegionId: String) {
        val face = faceDao.findById(faceRegionId) ?: return
        val previousPersonId = face.personId
        faceDao.setIgnored(faceRegionId)
        previousPersonId?.let {
            recomputeCentroid(it)
            deletePersonIfOrphaned(it)
        }
    }

    suspend fun unignoreFace(faceRegionId: String) {
        val face = faceDao.findById(faceRegionId) ?: return
        faceDao.setNotIgnored(faceRegionId)
        face.personId?.let { recomputeCentroid(it) }
    }

    // -----------------------------------------------------------------------
    // Permanently delete face (false positive removal)
    // -----------------------------------------------------------------------

    /**
     * Deletes a face region completely: removes the DB row, the thumbnail,
     * and rewrites the photo's XMP so the region no longer appears in DigiKam/Aves.
     * The photo itself stays in the DB as analyzed=true so the sync does not
     * re-detect it. If the face belonged to a person, that person's centroid
     * is recomputed and the person is deleted if it becomes empty.
     */
    suspend fun permanentlyDeleteFace(faceRegionId: String) {
        val face = faceDao.findById(faceRegionId) ?: return
        val previousPersonId = face.personId
        faceDao.deleteById(faceRegionId)
        ThumbnailHelper.deleteThumbnail(context, faceRegionId)
        rewriteXmpForPhoto(face.photoId)
        previousPersonId?.let {
            recomputeCentroid(it)
            deletePersonIfOrphaned(it)
        }
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
        val personName = person.name ?: return
        faceDao.updatePersonAndName(faceRegionId, personId, personName)
        rewriteXmpForPhoto(face.photoId)
        previousPersonId?.let {
            recomputeCentroid(it)
            deletePersonIfOrphaned(it)
        }
        recomputeCentroid(personId)
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
        val faces = faceDao.findByPersonId(personId).filter { it.name != null }
        val photoIds = faces.map { it.photoId }.distinct()
        photoIds.forEach { rewriteXmpForPhoto(it) }
    }

    // -----------------------------------------------------------------------
    // Merge persons
    // -----------------------------------------------------------------------

    suspend fun mergePersons(sourceIds: List<String>, winnerId: String) {
        val winner = personDao.findById(winnerId) ?: return
        val winnerName = winner.name ?: return
        sourceIds.filter { it != winnerId }.forEach { sourceId ->
            faceDao.reassignPerson(sourceId, winnerId)
            personDao.deleteById(sourceId)
        }
        faceDao.updateConfirmedNamesForPerson(winnerId, winnerName)
        recomputeCentroid(winnerId)
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
        val faces = faceDao.findByPhotoId(photoId)
        faces.forEach { ThumbnailHelper.deleteThumbnail(context, it.id) }
        faceDao.deleteByPhotoId(photoId)
        XmpHelper.clearFaceData(file)
        photoDao.updateModifiedAt(photoId, file.lastModified())
        photoDao.updateAnalyzed(photoId, false)
    }

    /**
     * Resets every photo as if it were new: deletes all face regions,
     * thumbnails, persons and XMP face data, then marks all photos as
     * unanalyzed so the next sync re-detects everything from scratch.
     */
    suspend fun resetAllFaces() {
        val allPhotos = photoDao.getAll()

        // Delete all thumbnails
        val allFaces = faceDao.getAll()
        allFaces.forEach { ThumbnailHelper.deleteThumbnail(context, it.id) }

        // Delete all face regions and persons
        faceDao.deleteAll()
        personDao.deleteAll()

        // Clear XMP face data on disk and mark all photos as unanalyzed
        allPhotos.forEach { photo ->
            try {
                val file = File(photo.path)
                if (file.exists()) {
                    XmpHelper.clearFaceData(file)
                    photoDao.updateModifiedAt(photo.id, file.lastModified())
                }
            } catch (t: Throwable) {
                Log.w(TAG, "XMP clear failed for ${photo.path}", t)
            }
            photoDao.updateAnalyzed(photo.id, false)
        }
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
        val newModifiedAt = file.lastModified()
        photoDao.updateModifiedAt(photoId, newModifiedAt)

        // Notify the MediaStore so other apps (Aves, DigiKam) see the
        // updated XMP immediately. We do this AFTER updating our own DB so
        // the next Eidora sync sees db.modifiedAt == file.lastModified()
        // and skips the photo instead of re-analysing it.
        android.media.MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            arrayOf("image/jpeg"),
            null
        )
    }

    suspend fun recomputeCentroid(personId: String) {
        val allFaces = faceDao.findByPersonId(personId)
            .filter { !it.ignored && it.embedding != null }
        if (allFaces.isEmpty()) {
            personDao.updateRepresentativeFace(personId, null)
            return
        }
        val confirmedFaces = allFaces.filter { it.name != null }
        val basisFaces = confirmedFaces.ifEmpty { allFaces }
        val embeddings = basisFaces.map { EmbeddingModel.bytesToFloatArray(it.embedding!!) }
        val centroid = EmbeddingModel.centroid(embeddings)
        val representative = basisFaces.minByOrNull { face ->
            EmbeddingModel.cosineDistance(
                EmbeddingModel.bytesToFloatArray(face.embedding!!),
                centroid
            )
        }
        personDao.updateRepresentativeFace(personId, representative?.id)
    }

    private suspend fun deletePersonIfOrphaned(personId: String) {
        val remaining = faceDao.findByPersonId(personId)
        if (remaining.isEmpty()) personDao.deleteById(personId)
    }
}
