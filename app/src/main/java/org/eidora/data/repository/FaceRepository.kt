// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.data.repository

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import org.eidora.data.db.*
import org.eidora.ml.EmbeddingModel
import org.eidora.util.*
import java.io.File
import java.util.UUID

class FaceRepository(
    private val context: Context,
    private val db: EidoraDatabase,
) {
    private val tag = "FaceRepository"

    private val photoDao = db.photoDao()
    private val personDao = db.personDao()
    private val faceDao = db.faceRegionDao()

    // -----------------------------------------------------------------------
    // Confirm face → person
    // -----------------------------------------------------------------------

    suspend fun confirmFace(
        faceRegionId: String,
        personId: String,
    ) {
        val face = faceDao.findById(faceRegionId) ?: return
        val person = personDao.findById(personId) ?: return
        val personName = person.name ?: return // can't confirm to unnamed suggestion person
        faceDao.updatePersonAndName(faceRegionId, personId, personName)
        markPendingXmpWrite(face.photoId)
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
        if (person.name != null) return // Safety: only reject unnamed suggestions
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

        affectedPhotoIds.forEach { markPendingXmpWrite(it) }
    }

    /**
     * Removes all unconfirmed faces (name == null) from the given person.
     * They move back to Unknown and will be re-clustered on the next run.
     */
    suspend fun removeUnconfirmedFaces(personId: String) {
        val unconfirmed =
            faceDao
                .findByPersonId(personId)
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
        markPendingXmpWrite(face.photoId)
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
        markPendingXmpWrite(face.photoId)
        recomputeCentroid(previousPersonId)
        deletePersonIfOrphaned(previousPersonId)
    }

    // -----------------------------------------------------------------------
    // Assign face to person (or new person)
    // -----------------------------------------------------------------------

    suspend fun assignFaceToPerson(
        faceRegionId: String,
        personId: String,
        confirm: Boolean = true,
    ) {
        val face = faceDao.findById(faceRegionId) ?: return
        val previousPersonId = face.personId
        val person = personDao.findById(personId) ?: return
        if (confirm) {
            val personName = person.name ?: return
            faceDao.updatePersonAndName(faceRegionId, personId, personName)
            markPendingXmpWrite(face.photoId)
        } else {
            // Attach without a name → unconfirmed. No XMP write (nothing named yet).
            faceDao.updatePersonId(faceRegionId, personId)
        }
        previousPersonId?.let {
            recomputeCentroid(it)
            deletePersonIfOrphaned(it)
        }
        recomputeCentroid(personId)
    }

    suspend fun assignFaceToNewPerson(
        faceRegionId: String,
        name: String,
        confirm: Boolean = true,
    ): PersonEntity {
        val existing = personDao.findByName(name)
        val person =
            existing ?: PersonEntity(UUID.randomUUID().toString(), name).also {
                personDao.insert(it)
            }
        assignFaceToPerson(faceRegionId, person.id, confirm = confirm)
        return person
    }

    // -----------------------------------------------------------------------
    // Rename person
    // -----------------------------------------------------------------------

    suspend fun renamePerson(
        personId: String,
        newName: String,
    ) {
        personDao.updateName(personId, newName)
        faceDao.updateConfirmedNamesForPerson(personId, newName)
        val faces = faceDao.findByPersonId(personId).filter { it.name != null }
        val photoIds = faces.map { it.photoId }.distinct()
        photoIds.forEach { markPendingXmpWrite(it) }
    }

    // -----------------------------------------------------------------------
    // Merge persons
    // -----------------------------------------------------------------------

    /**
     * Names an unnamed suggestion person.
     * @param confirm when true, all the suggestion's faces are also named
     *   (confirmed) and their XMP is written. When false, only the person
     *   record gets a name; the faces stay unconfirmed.
     */
    suspend fun nameSuggestion(
        personId: String,
        name: String,
        confirm: Boolean,
    ) {
        personDao.updateName(personId, name)
        if (confirm) {
            faceDao.updateConfirmedNamesForPerson(personId, name)
            val photoIds = faceDao.findByPersonId(personId).map { it.photoId }.distinct()
            photoIds.forEach { markPendingXmpWrite(it) }
        }
        recomputeCentroid(personId)
    }

    /**
     * Merges [sourceIds] into [winnerId].
     * @param confirmFaces when true, all faces of the winner are named (confirmed).
     *   When false, each face keeps its current confirmed/unconfirmed status.
     *   "Merge persons" passes false (preserve); "name/merge suggestion" passes true.
     */
    suspend fun mergePersons(
        sourceIds: List<String>,
        winnerId: String,
        confirmFaces: Boolean = false,
    ) {
        val winner = personDao.findById(winnerId) ?: return
        val winnerName = winner.name ?: return
        sourceIds.filter { it != winnerId }.forEach { sourceId ->
            faceDao.reassignPerson(sourceId, winnerId)
            personDao.deleteById(sourceId)
        }
        if (confirmFaces) {
            faceDao.updateConfirmedNamesForPerson(winnerId, winnerName)
        }
        recomputeCentroid(winnerId)
        val faces = faceDao.findByPersonId(winnerId)
        // Only photos with a confirmed (named) face need an XMP write.
        val photoIds = faces.filter { it.name != null }.map { it.photoId }.distinct()
        photoIds.forEach { markPendingXmpWrite(it) }
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
     * Folder-scoped re-analyze: only re-analyzes photos in [folders] (the
     * currently visible/whitelisted set), leaving photos in other folders
     * untouched. Clears their face data + XMP and marks just those photos
     * NEEDS_DETECTION so detection re-runs on them. Since XMP face metadata is
     * cleared, triage has nothing to import and the pipeline starts at detection.
     */
    suspend fun resetFoldersForRedetection(folders: List<String>) {
        if (folders.isEmpty()) {
            Log.w(tag, "resetFoldersForRedetection called with no folders; nothing to do")
            return
        }
        val photos = photoDao.getInFolders(folders)
        Log.i(tag, "resetFoldersForRedetection: ${photos.size} photos in $folders")
        var xmpCleared = 0
        photos.forEach { photo ->
            try {
                // Remove this photo's face regions + their thumbnails.
                faceDao.findByPhotoId(photo.id).forEach { face ->
                    ThumbnailHelper.deleteThumbnail(context, face.id)
                }
                faceDao.deleteByPhotoId(photo.id)
                val file = File(photo.path)
                if (file.exists()) {
                    XmpHelper.clearFaceData(file)
                    photoDao.updateModifiedAt(photo.id, file.lastModified())
                    xmpCleared++
                }
            } catch (t: Throwable) {
                Log.w(tag, "Reset failed for ${photo.path}", t)
            }
        }
        // Remove any persons left without a face (orphaned by the deletions).
        personDao.deleteOrphaned()
        // Straight to detection: no metadata left for triage to import.
        photoDao.updateStagesInFolders(folders, org.eidora.data.db.PhotoStage.NEEDS_DETECTION)
        Log.i(tag, "resetFoldersForRedetection done: XMP cleared on $xmpCleared files → NEEDS_DETECTION")
    }

    private suspend fun resetEverything() {
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
                Log.w(tag, "XMP clear failed for ${photo.path}", t)
            }
            photoDao.updateAnalyzed(photo.id, false)
        }
    }

    // -----------------------------------------------------------------------
    // Observe
    // -----------------------------------------------------------------------

    /**
     * Ensures every person contains either only visible (in-folder) or only
     * hidden (out-of-folder) faces. Any person that currently mixes both is
     * split: its hidden faces are moved to a new person that copies the name,
     * and both persons' centroids/representatives are recomputed.
     *
     * Call after the folder whitelist changes. Returns the number of new
     * persons created by splitting.
     */
    suspend fun splitPersonsByVisibility(folders: List<String>): Int {
        if (folders.isEmpty()) return 0
        var created = 0
        val personIds = personDao.allIds()
        for (personId in personIds) {
            val hiddenFaceIds = faceDao.faceIdsOutsideFolders(personId, folders)
            if (hiddenFaceIds.isEmpty()) continue // all visible → nothing to do
            val visibleCount = faceDao.countInsideFolders(personId, folders)
            if (visibleCount == 0) continue // all hidden → nothing to do
            // Mixed: move the hidden faces into a new person with the same name.
            val original = personDao.findById(personId) ?: continue
            val newPerson =
                PersonEntity(
                    id = UUID.randomUUID().toString(),
                    name = original.name,
                )
            personDao.insertWithNullableName(newPerson)
            faceDao.reassignFaces(hiddenFaceIds, newPerson.id)
            recomputeCentroid(personId)
            recomputeCentroid(newPerson.id)
            created++
        }
        return created
    }

    /**
     * Purges photos (and, via FK cascade, their face regions) whose folder is
     * not in the current whitelist, then removes any persons left without faces.
     * Use after the user narrows the folder selection to reclaim space.
     * Returns the number of photos removed.
     */
    suspend fun cleanupFoldersNotIn(folders: List<String>): Int {
        val before = photoDao.count()
        // Delete the thumbnail files first: the DB removes face rows via
        // ON DELETE CASCADE, but that only touches the database — the thumbnail
        // JPEGs on disk would otherwise be orphaned and accumulate.
        faceDao.faceIdsInPhotosNotInFolders(folders).forEach { faceId ->
            try {
                ThumbnailHelper.deleteThumbnail(context, faceId)
            } catch (t: Throwable) {
                // best-effort; a missing file is fine
            }
        }
        photoDao.deleteNotInFolders(folders)
        personDao.deleteOrphaned()
        val after = photoDao.count()
        return (before - after).coerceAtLeast(0)
    }

    /**
     * Discards all embeddings and clustered persons so the pipeline recomputes
     * them with a newly chosen embedding model. Confirmed names survive: they
     * live on the face rows (and in photo XMP) and are re-imported, so the user
     * does not lose their labelling — only the machine-generated grouping is
     * rebuilt. Detection results (face crops/thumbnails) are kept; only the
     * embedding step and everything downstream of it is redone.
     */
    suspend fun resetForEmbeddingModelChange() {
        // Drop clustered persons (centroids are in the old vector space).
        personDao.deleteAll()
        // Null out every embedding and clear the permanent-failure flag so the
        // embedding worker recomputes all of them with the new model.
        faceDao.clearAllEmbeddings()
    }

    /**
     * What to do with existing embeddings when the embedding model changes.
     * KEEP_EMBEDDINGS is only safe when the embedding space is unchanged — e.g.
     * re-importing the SAME model, not switching to a different one, since
     * embeddings from different models are not comparable.
     */
    enum class EmbeddingChangeStrategy {
        /** Keep existing embeddings and clusters untouched (same-model reimport). */
        KEEP_EMBEDDINGS,

        /** Recompute all embeddings; drops clustered persons (different model). */
        RECOMPUTE_ALL,
    }

    /**
     * Applies [strategy] when the embedding model is (re)activated. KEEP_EMBEDDINGS
     * leaves everything in place — use it only when the vector space is unchanged.
     * RECOMPUTE_ALL behaves like [resetForEmbeddingModelChange].
     */
    suspend fun resetForEmbeddingModelChange(strategy: EmbeddingChangeStrategy) {
        when (strategy) {
            EmbeddingChangeStrategy.KEEP_EMBEDDINGS -> {
                // Nothing to reset; existing embeddings stay valid.
            }
            EmbeddingChangeStrategy.RECOMPUTE_ALL -> resetForEmbeddingModelChange()
        }
    }

    /** What to do with existing faces when the detection model changes. */
    enum class DetectionChangeStrategy {
        /** Keep all detected faces; the new detector only applies to future scans. */
        KEEP_ALL,

        /**
         * Keep photos that have at least one confirmed (named) face untouched;
         * re-detect all other photos. Preserves the user's naming work while
         * letting the new detector re-scan the unconfirmed rest.
         */
        KEEP_CONFIRMED,

        /** Re-detect everything — drops all faces and persons. */
        REDETECT_ALL,
    }

    /**
     * Applies [strategy] when the detection model changes. For KEEP_ALL nothing
     * is reset here. For KEEP_CONFIRMED only photos without a confirmed face are
     * reset (their faces/thumbnails/XMP cleared, marked unanalyzed), then
     * orphaned persons are purged. For REDETECT_ALL everything is reset.
     */
    suspend fun resetForDetectionModelChange(strategy: DetectionChangeStrategy) {
        when (strategy) {
            DetectionChangeStrategy.KEEP_ALL -> {
                // Nothing to reset; the new detector applies to future scans.
            }
            DetectionChangeStrategy.KEEP_CONFIRMED -> {
                val toReset = photoDao.idsWithoutConfirmedFace()
                for (photoId in toReset) {
                    resetPhotoFaces(photoId)
                }
                // Persons whose faces were all on re-detected photos are now
                // empty; drop them. Persons keeping ≥1 confirmed face survive.
                personDao.deleteOrphaned()
            }
            DetectionChangeStrategy.REDETECT_ALL -> {
                resetEverything()
            }
        }
    }

    fun observePersonsWithCount(folders: List<String>): Flow<List<PersonWithCount>> =
        personDao.observeAllWithConfirmedCount(folders)

    fun observeUnknownFaces(folders: List<String>): Flow<List<FaceRegionWithPhoto>> = faceDao.observeUnknown(folders)

    fun observeIgnoredFaces(folders: List<String>): Flow<List<FaceRegionWithPhoto>> = faceDao.observeIgnored(folders)

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Marks a photo as having pending XMP metadata to write,
     * then enqueues the background [XmpWriteWorker].
     * The actual file write happens asynchronously – UI returns immediately.
     */
    private suspend fun markPendingXmpWrite(photoId: String) {
        photoDao.markPendingXmpWrite(photoId)
        org.eidora.worker.XmpWriteWorker
            .enqueue(context)
    }

    suspend fun recomputeCentroid(personId: String) {
        val allFaces =
            faceDao
                .findByPersonId(personId)
                .filter { !it.ignored && it.embedding != null }
        if (allFaces.isEmpty()) {
            personDao.updateRepresentativeFace(personId, null)
            return
        }
        val confirmedFaces = allFaces.filter { it.name != null }
        val basisFaces = confirmedFaces.ifEmpty { allFaces }
        val embeddings = basisFaces.map { EmbeddingModel.bytesToFloatArray(it.embedding!!) }
        val centroid = EmbeddingModel.centroid(embeddings)
        val representative =
            basisFaces.minByOrNull { face ->
                EmbeddingModel.cosineDistance(
                    EmbeddingModel.bytesToFloatArray(face.embedding!!),
                    centroid,
                )
            }
        personDao.updateRepresentativeFace(personId, representative?.id)
    }

    private suspend fun deletePersonIfOrphaned(personId: String) {
        val remaining = faceDao.findByPersonId(personId)
        if (remaining.isEmpty()) personDao.deleteById(personId)
    }
}
