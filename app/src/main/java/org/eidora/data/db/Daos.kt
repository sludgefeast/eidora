package org.eidora.data.db

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

    @Query("SELECT * FROM photos WHERE pending_xmp_write = 1")
    suspend fun getPendingXmpWrites(): List<PhotoEntity>

    @Query("UPDATE photos SET pending_xmp_write = 1 WHERE id = :id")
    suspend fun markPendingXmpWrite(id: String)

    @Query("UPDATE photos SET pending_xmp_write = 0, modifiedAt = :modifiedAt WHERE id = :id")
    suspend fun clearPendingXmpWrite(
        id: String,
        modifiedAt: Long,
    )

    @Query("SELECT path, modifiedAt, analyzed FROM photos")
    suspend fun getAllPathsWithModified(): List<PathModified>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(photo: PhotoEntity)

    @Query("UPDATE photos SET modifiedAt = :modifiedAt, takenAt = :takenAt, analyzed = :analyzed WHERE id = :id")
    suspend fun update(
        id: String,
        modifiedAt: Long,
        takenAt: Long?,
        analyzed: Boolean,
    )

    @Query("UPDATE photos SET modifiedAt = :modifiedAt WHERE id = :id")
    suspend fun updateModifiedAt(
        id: String,
        modifiedAt: Long,
    )

    @Query("UPDATE photos SET analyzed = :analyzed WHERE id = :id")
    suspend fun updateAnalyzed(
        id: String,
        analyzed: Boolean,
    )

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
     * Number of a person's (non-ignored) faces whose photos are in the given
     * folders. Zero means the person is currently hidden by the folder filter.
     */
    @Query(
        """
        SELECT COUNT(*) FROM face_regions f
        JOIN photos ph ON ph.id = f.photoId
        WHERE f.personId = :personId AND f.ignored = 0 AND ph.folder IN (:folders)
    """,
    )
    suspend fun countFacesInFolders(
        personId: String,
        folders: List<String>,
    ): Int

    @Query("SELECT * FROM persons WHERE id = :id")
    suspend fun findById(id: String): PersonEntity?

    @Query("SELECT * FROM persons WHERE name IS NOT NULL")
    suspend fun getAll(): List<PersonEntity>

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

    @Query("SELECT * FROM face_regions WHERE photoId = :photoId")
    suspend fun findByPhotoId(photoId: String): List<FaceRegionEntity>

    @Query(
        "SELECT f.*, ph.takenAt as photoTakenAt FROM face_regions f JOIN photos ph ON f.photoId = ph.id WHERE f.personId = :personId",
    )
    suspend fun findByPersonIdWithDate(personId: String): List<FaceRegionWithPhoto>

    @Query(
        "SELECT f.*, ph.takenAt as photoTakenAt FROM face_regions f JOIN photos ph ON f.photoId = ph.id WHERE f.personId IS NULL AND f.ignored = 0 AND f.embedding IS NOT NULL",
    )
    suspend fun findUnclusteredWithDate(): List<FaceRegionWithPhoto>

    @Query(
        """
        SELECT DISTINCT ph.id, ph.path, ph.modifiedAt, ph.takenAt, ph.analyzed, ph.pending_xmp_write
        FROM photos ph
        JOIN face_regions f ON f.photoId = ph.id
        WHERE f.personId = :personId
          AND f.name IS NOT NULL
          AND f.ignored = 0
        ORDER BY CASE WHEN ph.takenAt IS NULL THEN 1 ELSE 0 END, ph.takenAt DESC
    """,
    )
    fun observeConfirmedPhotosForPerson(personId: String): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM face_regions WHERE personId = :personId")
    suspend fun findByPersonId(personId: String): List<FaceRegionEntity>

    @Query("SELECT * FROM face_regions WHERE embedding IS NULL AND ignored = 0")
    suspend fun findWithoutEmbedding(): List<FaceRegionEntity>

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
        SELECT f.*, ph.takenAt as photoTakenAt
        FROM face_regions f
        JOIN photos ph ON ph.id = f.photoId
        WHERE f.personId = :personId AND ph.folder IN (:folders)
        ORDER BY 
            CASE WHEN f.name IS NULL THEN 0 ELSE 1 END ASC,
            ph.takenAt DESC
    """,
    )
    fun observeByPersonId(
        personId: String,
        folders: List<String>,
    ): Flow<List<FaceRegionWithPhoto>>

    @Query(
        """
        SELECT f.*, ph.takenAt as photoTakenAt
        FROM face_regions f
        JOIN photos ph ON ph.id = f.photoId
        WHERE f.personId IS NULL AND f.ignored = 0 AND ph.folder IN (:folders)
        ORDER BY ph.takenAt DESC
    """,
    )
    fun observeUnknown(folders: List<String>): Flow<List<FaceRegionWithPhoto>>

    @Query(
        """
        SELECT f.*, ph.takenAt as photoTakenAt
        FROM face_regions f
        JOIN photos ph ON ph.id = f.photoId
        WHERE f.ignored = 1 AND ph.folder IN (:folders)
        ORDER BY ph.takenAt DESC
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
        WHERE f.personId IS NULL AND f.ignored = 0 AND ph.folder IN (:folders)
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

data class FaceRegionWithPhoto(
    @Embedded val faceRegion: FaceRegionEntity,
    val photoTakenAt: Long?,
)

data class PathModified(
    val path: String,
    val modifiedAt: Long,
    val analyzed: Boolean,
)
