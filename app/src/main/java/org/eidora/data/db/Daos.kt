// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

// ---------------------------------------------------------------------------
// PhotoDao
// ---------------------------------------------------------------------------

@Dao
interface PhotoDao {
    @Query("SELECT * FROM photos WHERE path = :path")
    suspend fun findByPath(path: String): PhotoEntity?

    @Query("SELECT * FROM photos WHERE id = :id")
    suspend fun findById(id: String): PhotoEntity?

    @Query("SELECT * FROM photos")
    suspend fun getAll(): List<PhotoEntity>

    @Query("SELECT * FROM photos WHERE folder IN (:folders)")
    suspend fun getInFolders(folders: List<String>): List<PhotoEntity>

    /**
     * IDs of photos that have NO confirmed (named) face — i.e. no face the user
     * has assigned to a person. Used when switching detectors: photos with at
     * least one confirmed face are kept, the rest are re-detected.
     */
    @Query(
        """
        SELECT id FROM photos
        WHERE id NOT IN (
            SELECT DISTINCT photoId FROM face_regions
            WHERE name IS NOT NULL AND ignored = 0
        )
        """,
    )
    suspend fun idsWithoutConfirmedFace(): List<String>

    @Query("SELECT * FROM photos WHERE pending_xmp_write = 1")
    suspend fun getPendingXmpWrites(): List<PhotoEntity>

    @Query("UPDATE photos SET pending_xmp_write = 1 WHERE id = :id")
    suspend fun markPendingXmpWrite(id: String)

    @Query("UPDATE photos SET pending_xmp_write = 0, modifiedAt = :modifiedAt WHERE id = :id")
    suspend fun clearPendingXmpWrite(
        id: String,
        modifiedAt: Long,
    )

    @Query("SELECT id, path, folder, modifiedAt, stage FROM photos")
    suspend fun getAllPathsWithModified(): List<PathModified>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(photo: PhotoEntity)

    @Query("UPDATE photos SET modifiedAt = :modifiedAt, takenAt = :takenAt, stage = :stage WHERE id = :id")
    suspend fun update(
        id: String,
        modifiedAt: Long,
        takenAt: Long?,
        stage: Int,
    )

    @Query("UPDATE photos SET modifiedAt = :modifiedAt WHERE id = :id")
    suspend fun updateModifiedAt(
        id: String,
        modifiedAt: Long,
    )

    @Query("UPDATE photos SET takenAt = :takenAt WHERE id = :id")
    suspend fun updateTakenAt(
        id: String,
        takenAt: Long,
    )

    @Query("UPDATE photos SET stage = :stage WHERE id = :id")
    suspend fun updateStage(
        id: String,
        stage: Int,
    )

    /** Set photos in the given folders to one stage (folder-scoped re-analyze). */
    @Query("UPDATE photos SET stage = :stage WHERE folder IN (:folders)")
    suspend fun updateStagesInFolders(
        folders: List<String>,
        stage: Int,
    )

    /**
     * Back-compat helper mirroring the old boolean flag: `true` means the photo
     * is fully processed (stage DONE), `false` resets it to the start (stage NEW)
     * so a later run re-processes it. Callers that need the intermediate
     * NEEDS_DETECTION stage use [updateStage] directly.
     */
    suspend fun updateAnalyzed(
        id: String,
        analyzed: Boolean,
    ) = updateStage(id, if (analyzed) PhotoStage.DONE else PhotoStage.NEW)

    @Query("SELECT * FROM photos WHERE stage = :stage")
    suspend fun getByStage(stage: Int): List<PhotoEntity>

    @Query("SELECT COUNT(*) FROM photos WHERE stage = :stage")
    suspend fun countByStage(stage: Int): Int

    @Query("DELETE FROM photos WHERE path = :path")
    suspend fun deleteByPath(path: String)

    @Query("DELETE FROM photos WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query(
        """
        SELECT * FROM photos
        WHERE folder IN (:folders)
        ORDER BY CASE WHEN takenAt IS NULL THEN 1 ELSE 0 END, takenAt DESC
    """,
    )
    fun observeAllSortedByDate(folders: List<String>): Flow<List<PhotoEntity>>

    @Query("SELECT COUNT(*) FROM photos")
    suspend fun count(): Int

    @Query("UPDATE photos SET folder = :folder WHERE id = :id")
    suspend fun updateFolder(
        id: String,
        folder: String,
    )

    /**
     * Normalizes photos stored under a subfolder of [root] (folder = "root/…") to
     * [root] itself, so folder-scoped queries that match the whitelist exactly
     * also cover subfolders. One-off repair for photos scanned before folder
     * normalization existed. Uses a GLOB-safe LIKE with the '/' separator.
     */
    @Query(
        "UPDATE photos SET folder = :root " +
            "WHERE folder = :root OR folder LIKE :root || '/%'",
    )
    suspend fun normalizeFolderToRoot(root: String)

    @Query("DELETE FROM photos WHERE folder NOT IN (:folders)")
    suspend fun deleteNotInFolders(folders: List<String>)
}

// ---------------------------------------------------------------------------
// PersonDao
// ---------------------------------------------------------------------------

@Dao
interface PersonDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(person: PersonEntity): Long

    // Suggestion persons have a null name. Kept as a separate entry point for
    // clarity; the name index is non-unique so no special handling is needed.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWithNullableName(person: PersonEntity)

    @Query("SELECT * FROM persons WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): PersonEntity?

    /**
     * Named persons sharing [name] that have at least one non-ignored face in
     * the given folders (i.e. currently visible). Excludes [excludePersonId]
     * (the person being renamed). Ordered by visible face count descending.
     */
    @Query(
        """
        SELECT p.* FROM persons p
        WHERE p.name = :name AND p.id != :excludePersonId
        AND EXISTS (
            SELECT 1 FROM face_regions f
            JOIN photos ph ON ph.id = f.photoId
            WHERE f.personId = p.id AND f.ignored = 0 AND ph.folder IN (:folders)
        )
        ORDER BY (
            SELECT COUNT(*) FROM face_regions f
            JOIN photos ph ON ph.id = f.photoId
            WHERE f.personId = p.id AND f.ignored = 0 AND ph.folder IN (:folders)
        ) DESC
    """,
    )
    suspend fun findVisibleNamesakes(
        name: String,
        excludePersonId: String,
        folders: List<String>,
    ): List<PersonEntity>

    @Query("SELECT * FROM persons WHERE id = :id")
    suspend fun findById(id: String): PersonEntity?

    @Query("SELECT * FROM persons WHERE name IS NOT NULL")
    suspend fun getAll(): List<PersonEntity>

    @Query("SELECT id FROM persons")
    suspend fun allIds(): List<String>

    @Query("SELECT * FROM persons WHERE name IS NULL")
    suspend fun getSuggestions(): List<PersonEntity>

    @Query("DELETE FROM persons")
    suspend fun deleteAll()

    @Update
    suspend fun update(person: PersonEntity)

    @Query("UPDATE persons SET representativeFaceId = :faceId WHERE id = :id")
    suspend fun updateRepresentativeFace(
        id: String,
        faceId: String?,
    )

    @Query("UPDATE persons SET name = :name WHERE id = :id")
    suspend fun updateName(
        id: String,
        name: String,
    )

    @Query("DELETE FROM persons WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query(
        """
        DELETE FROM persons 
        WHERE id NOT IN (
            SELECT DISTINCT personId FROM face_regions WHERE personId IS NOT NULL
        )
    """,
    )
    suspend fun deleteOrphaned()

    @Query(
        """
        SELECT p.*, 
               COUNT(CASE WHEN f.name IS NOT NULL AND f.ignored = 0 THEN 1 END) as confirmedCount,
               COUNT(CASE WHEN f.name IS NULL AND f.ignored = 0 THEN 1 END) as unconfirmedCount
        FROM persons p
        LEFT JOIN face_regions f ON f.personId = p.id
            AND f.photoId IN (SELECT id FROM photos WHERE folder IN (:folders))
        WHERE p.name IS NOT NULL
        GROUP BY p.id
        HAVING COUNT(f.id) > 0
        ORDER BY confirmedCount DESC, p.name ASC
    """,
    )
    fun observeAllWithConfirmedCount(folders: List<String>): Flow<List<PersonWithCount>>

    // Suggestion persons: have personId set on faces but no name yet
    @Query(
        """
        SELECT p.*
        FROM persons p
        WHERE p.name IS NULL
        AND EXISTS (
            SELECT 1 FROM face_regions f
            JOIN photos ph ON ph.id = f.photoId
            WHERE f.personId = p.id AND ph.folder IN (:folders)
        )
        ORDER BY (
            SELECT COUNT(*) FROM face_regions f
            JOIN photos ph ON ph.id = f.photoId
            WHERE f.personId = p.id AND f.ignored = 0 AND ph.folder IN (:folders)
        ) DESC
    """,
    )
    fun observeSuggestions(folders: List<String>): Flow<List<PersonEntity>>
}

data class PersonWithCount(
    @Embedded val person: PersonEntity,
    val confirmedCount: Int,
    val unconfirmedCount: Int = 0,
)

// ---------------------------------------------------------------------------
// FaceRegionDao
// ---------------------------------------------------------------------------

@Dao
interface FaceRegionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(faceRegion: FaceRegionEntity)

    @Query("SELECT * FROM face_regions WHERE id = :id")
    suspend fun findById(id: String): FaceRegionEntity?

    @Query(
        """
        SELECT f.id FROM face_regions f
        JOIN photos ph ON ph.id = f.photoId
        WHERE ph.folder NOT IN (:folders)
    """,
    )
    suspend fun faceIdsInPhotosNotInFolders(folders: List<String>): List<String>

    @Query("SELECT id FROM face_regions")
    suspend fun allIds(): List<String>

    @Query("SELECT * FROM face_regions WHERE photoId = :photoId")
    suspend fun findByPhotoId(photoId: String): List<FaceRegionEntity>

    @Query(
        "SELECT f.id, f.photoId, f.personId, f.name, f.regionJson, f.embedding, f.ignored, f.quality_score, f.embedding_failed, ph.takenAt AS photoTakenAt FROM face_regions f JOIN photos ph ON f.photoId = ph.id WHERE f.personId = :personId",
    )
    suspend fun findByPersonIdWithDate(personId: String): List<FaceRegionForClustering>

    @Query(
        "SELECT f.id, f.photoId, f.personId, f.name, f.regionJson, f.embedding, f.ignored, f.quality_score, f.embedding_failed, ph.takenAt AS photoTakenAt FROM face_regions f JOIN photos ph ON f.photoId = ph.id WHERE f.personId IS NULL AND f.ignored = 0 AND f.embedding IS NOT NULL",
    )
    suspend fun findUnclusteredWithDate(): List<FaceRegionForClustering>

    @Query(
        """
        SELECT DISTINCT ph.id, ph.path, ph.folder, ph.modifiedAt, ph.takenAt, ph.stage, ph.pending_xmp_write
        FROM photos ph
        JOIN face_regions f ON f.photoId = ph.id
        WHERE f.personId = :personId
          AND f.name IS NOT NULL
          AND f.ignored = 0
          AND ph.folder IN (:folders)
        ORDER BY CASE WHEN ph.takenAt IS NULL THEN 1 ELSE 0 END, ph.takenAt DESC
    """,
    )
    fun observeConfirmedPhotosForPerson(
        personId: String,
        folders: List<String>,
    ): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM face_regions WHERE personId = :personId")
    suspend fun findByPersonId(personId: String): List<FaceRegionEntity>

    /** Face ids of a person whose photos are OUTSIDE the given folders. */
    @Query(
        """
        SELECT f.id FROM face_regions f
        JOIN photos ph ON ph.id = f.photoId
        WHERE f.personId = :personId AND ph.folder NOT IN (:folders)
    """,
    )
    suspend fun faceIdsOutsideFolders(
        personId: String,
        folders: List<String>,
    ): List<String>

    /** Number of a person's faces whose photos are INSIDE the given folders. */
    @Query(
        """
        SELECT COUNT(*) FROM face_regions f
        JOIN photos ph ON ph.id = f.photoId
        WHERE f.personId = :personId AND ph.folder IN (:folders)
    """,
    )
    suspend fun countInsideFolders(
        personId: String,
        folders: List<String>,
    ): Int

    @Query("UPDATE face_regions SET personId = :newPersonId WHERE id IN (:faceIds)")
    suspend fun reassignFaces(
        faceIds: List<String>,
        newPersonId: String,
    )

    @Query("SELECT * FROM face_regions WHERE embedding IS NULL AND ignored = 0 AND embedding_failed = 0")
    suspend fun findWithoutEmbedding(): List<FaceRegionEntity>

    @Query("UPDATE face_regions SET embedding_failed = 1 WHERE id = :id")
    suspend fun markEmbeddingFailed(id: String)

    @Query("UPDATE face_regions SET quality_score = :score WHERE id = :id")
    suspend fun updateQualityScore(
        id: String,
        score: Float,
    )

    @Query("UPDATE face_regions SET name = NULL WHERE id = :id")
    suspend fun clearName(id: String)

    @Query("UPDATE face_regions SET regionJson = :regionJson WHERE id = :id")
    suspend fun updateRegionJson(
        id: String,
        regionJson: String,
    )

    @Query("SELECT * FROM face_regions")
    suspend fun getAll(): List<FaceRegionEntity>

    @Query("DELETE FROM face_regions")
    suspend fun deleteAll()

    @Query("UPDATE face_regions SET embedding = :embedding WHERE id = :id")
    suspend fun updateEmbedding(
        id: String,
        embedding: ByteArray,
    )

    /**
     * Clears every stored embedding and the permanent-failure flag, so the
     * embedding worker recomputes all of them. Used when the embedding model
     * changes: vectors from different models are not comparable.
     */
    @Query("UPDATE face_regions SET embedding = NULL, embedding_failed = 0")
    suspend fun clearAllEmbeddings()

    @Query("UPDATE face_regions SET personId = :personId WHERE id = :id")
    suspend fun updatePersonId(
        id: String,
        personId: String?,
    )

    @Query("UPDATE face_regions SET personId = :personId, name = :name WHERE id = :id")
    suspend fun updatePersonAndName(
        id: String,
        personId: String?,
        name: String?,
    )

    @Query("UPDATE face_regions SET ignored = 1, personId = NULL, name = NULL WHERE id = :id")
    suspend fun setIgnored(id: String)

    @Query("UPDATE face_regions SET ignored = 0 WHERE id = :id")
    suspend fun setNotIgnored(id: String)

    @Query("DELETE FROM face_regions WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM face_regions WHERE photoId = :photoId")
    suspend fun deleteByPhotoId(photoId: String)

    @Query(
        """
        SELECT f.id, f.photoId, f.personId, f.name,
               f.ignored, f.quality_score, f.embedding_failed,
               ph.takenAt AS photoTakenAt
        FROM face_regions f
        JOIN photos ph ON ph.id = f.photoId
        WHERE f.personId = :personId AND ph.folder IN (:folders)
        ORDER BY 
            CASE WHEN f.name IS NULL THEN 0 ELSE 1 END ASC,
            ph.takenAt DESC
        LIMIT 10000
    """,
    )
    fun observeByPersonId(
        personId: String,
        folders: List<String>,
    ): Flow<List<FaceRegionWithPhoto>>

    @Query(
        """
        SELECT f.id, f.photoId, f.personId, f.name,
               f.ignored, f.quality_score, f.embedding_failed,
               ph.takenAt AS photoTakenAt
        FROM face_regions f
        JOIN photos ph ON ph.id = f.photoId
        WHERE f.personId IS NULL AND f.ignored = 0 AND f.embedding_failed = 0 AND ph.folder IN (:folders)
        ORDER BY ph.takenAt DESC
        LIMIT 10000
    """,
    )
    fun observeUnknown(folders: List<String>): Flow<List<FaceRegionWithPhoto>>

    @Query(
        """
        SELECT f.id, f.photoId, f.personId, f.name,
               f.ignored, f.quality_score, f.embedding_failed,
               ph.takenAt AS photoTakenAt
        FROM face_regions f
        JOIN photos ph ON ph.id = f.photoId
        WHERE f.ignored = 1 AND ph.folder IN (:folders)
        ORDER BY ph.takenAt DESC
        LIMIT 10000
    """,
    )
    fun observeIgnored(folders: List<String>): Flow<List<FaceRegionWithPhoto>>

    @Query("UPDATE face_regions SET name = :name WHERE personId = :personId AND name IS NOT NULL")
    suspend fun updateConfirmedNamesForPerson(
        personId: String,
        name: String,
    )

    @Query("UPDATE face_regions SET personId = :targetPersonId WHERE personId = :sourcePersonId")
    suspend fun reassignPerson(
        sourcePersonId: String,
        targetPersonId: String,
    )

    @Query(
        """
        SELECT COUNT(*) FROM face_regions f
        JOIN photos ph ON ph.id = f.photoId
        WHERE f.personId IS NULL AND f.ignored = 0 AND f.embedding_failed = 0 AND ph.folder IN (:folders)
    """,
    )
    fun observeUnknownCount(folders: List<String>): Flow<Int>

    @Query(
        """
        SELECT COUNT(*) FROM face_regions f
        JOIN photos ph ON ph.id = f.photoId
        WHERE f.ignored = 1 AND ph.folder IN (:folders)
    """,
    )
    fun observeIgnoredCount(folders: List<String>): Flow<Int>

    // Fix 5: reactive flow of all face regions for a single photo
    @Query("SELECT * FROM face_regions WHERE photoId = :photoId")
    fun observeByPhotoId(photoId: String): Flow<List<FaceRegionEntity>>
}

/**
 * Face row WITH the embedding blob, for the clustering worker which needs it.
 * Kept separate from [FaceRegionWithPhoto] (the UI projection) so UI list
 * queries never pull the blob into a CursorWindow. These run as suspend calls
 * in the background, not as observed flows bound to the UI.
 */
data class FaceRegionForClustering(
    @Embedded val faceRegion: FaceRegionEntity,
    val photoTakenAt: Long?,
)

/**
 * Face row for UI lists, WITHOUT the embedding blob. The embedding is 128–512
 * floats per row; selecting it for a person with hundreds of faces overflowed
 * the SQLite CursorWindow (~2 MB) and crashed observed queries with "Couldn't
 * read row N from CursorWindow". Room populates these scalar columns directly
 * (no @Embedded blob), so the window holds far more rows.
 *
 * [faceRegion] rebuilds a FaceRegionEntity with embedding = null so existing
 * call sites that read face.faceRegion.id / .name / .ignored / .photoId /
 * .qualityScore keep working unchanged. Consumers needing the embedding (the
 * clustering worker) use FaceRegionForClustering instead.
 */
data class FaceRegionWithPhoto(
    val id: String,
    val photoId: String,
    val personId: String?,
    val name: String?,
    val ignored: Boolean,
    @ColumnInfo(name = "quality_score") val qualityScore: Float?,
    @ColumnInfo(name = "embedding_failed") val embeddingFailed: Boolean,
    val photoTakenAt: Long?,
)

/**
 * Rebuilds a FaceRegionEntity from the lightweight projection so existing call
 * sites that read face.faceRegion.id / .name / .ignored / .photoId /
 * .qualityScore keep working unchanged. Defined as an extension property, NOT a
 * member, so Room's KSP processor does not mistake it for a column to map.
 *
 * embedding and regionJson are set to empty/null: this projection deliberately
 * omits both (embedding is a large blob, regionJson a large string) to keep the
 * SQLite CursorWindow small enough for people with thousands of faces. No
 * FaceRegionWithPhoto consumer reads them; the fullscreen editor uses
 * FaceRegionEntity via a per-photo query instead.
 */
val FaceRegionWithPhoto.faceRegion: FaceRegionEntity
    get() =
        FaceRegionEntity(
            id = id,
            photoId = photoId,
            personId = personId,
            name = name,
            regionJson = "",
            embedding = null,
            ignored = ignored,
            qualityScore = qualityScore,
            embeddingFailed = embeddingFailed,
        )

data class PathModified(
    val id: String,
    val path: String,
    val folder: String,
    val modifiedAt: Long,
    val stage: Int,
)
