package de.sebastian.faces.data.db

import androidx.room.*
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

    @Query("SELECT path FROM photos")
    suspend fun getAllPaths(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(photo: PhotoEntity)

    @Query("UPDATE photos SET modifiedAt = :modifiedAt, takenAt = :takenAt, analyzed = :analyzed WHERE id = :id")
    suspend fun update(id: String, modifiedAt: Long, takenAt: Long?, analyzed: Boolean)

    @Query("UPDATE photos SET modifiedAt = :modifiedAt WHERE id = :id")
    suspend fun updateModifiedAt(id: String, modifiedAt: Long)

    @Query("UPDATE photos SET analyzed = :analyzed WHERE id = :id")
    suspend fun updateAnalyzed(id: String, analyzed: Boolean)

    @Query("DELETE FROM photos WHERE path = :path")
    suspend fun deleteByPath(path: String)

    @Query("DELETE FROM photos WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM photos")
    fun observeAll(): Flow<List<PhotoEntity>>
}

// ---------------------------------------------------------------------------
// PersonDao
// ---------------------------------------------------------------------------

@Dao
interface PersonDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(person: PersonEntity): Long

    // For inserting suggestion persons with null name (bypasses unique index on name)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWithNullableName(person: PersonEntity)

    @Query("SELECT * FROM persons WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): PersonEntity?

    @Query("SELECT * FROM persons WHERE id = :id")
    suspend fun findById(id: String): PersonEntity?

    @Query("SELECT * FROM persons WHERE name IS NOT NULL")
    suspend fun getAll(): List<PersonEntity>

    @Query("SELECT * FROM persons WHERE name IS NOT NULL")
    fun observeAll(): Flow<List<PersonEntity>>

    @Update
    suspend fun update(person: PersonEntity)

    @Query("UPDATE persons SET representativeFaceId = :faceId WHERE id = :id")
    suspend fun updateRepresentativeFace(id: String, faceId: String?)

    @Query("UPDATE persons SET name = :name WHERE id = :id")
    suspend fun updateName(id: String, name: String)

    @Query("DELETE FROM persons WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("""
        DELETE FROM persons 
        WHERE id NOT IN (
            SELECT DISTINCT personId FROM face_regions WHERE personId IS NOT NULL
        )
    """)
    suspend fun deleteOrphaned()

    @Query("""
        SELECT p.*, COUNT(CASE WHEN f.name IS NOT NULL AND f.ignored = 0 THEN 1 END) as confirmedCount
        FROM persons p
        LEFT JOIN face_regions f ON f.personId = p.id
        WHERE p.name IS NOT NULL
        GROUP BY p.id
        ORDER BY confirmedCount DESC, p.name ASC
    """)
    fun observeAllWithConfirmedCount(): Flow<List<PersonWithCount>>

    // Suggestion persons: have personId set on faces but no name yet
    @Query("""
        SELECT p.*
        FROM persons p
        WHERE p.name IS NULL
        AND EXISTS (
            SELECT 1 FROM face_regions f WHERE f.personId = p.id
        )
    """)
    fun observeSuggestions(): Flow<List<PersonEntity>>

    @Query("""
        SELECT MAX(ph.takenAt) 
        FROM face_regions f 
        JOIN photos ph ON ph.id = f.photoId 
        WHERE f.personId = :personId
    """)
    suspend fun getNewestPhotoTakenAt(personId: String): Long?
}

data class PersonWithCount(
    @Embedded val person: PersonEntity,
    val confirmedCount: Int
)

// ---------------------------------------------------------------------------
// FaceRegionDao
// ---------------------------------------------------------------------------

@Dao
interface FaceRegionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(faceRegion: FaceRegionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(faceRegions: List<FaceRegionEntity>)

    @Query("SELECT * FROM face_regions WHERE id = :id")
    suspend fun findById(id: String): FaceRegionEntity?

    @Query("SELECT * FROM face_regions WHERE photoId = :photoId")
    suspend fun findByPhotoId(photoId: String): List<FaceRegionEntity>

    @Query("SELECT * FROM face_regions WHERE personId = :personId")
    suspend fun findByPersonId(personId: String): List<FaceRegionEntity>

    @Query("SELECT * FROM face_regions WHERE embedding IS NULL AND ignored = 0")
    suspend fun findWithoutEmbedding(): List<FaceRegionEntity>

    @Query("SELECT * FROM face_regions WHERE personId IS NULL AND ignored = 0")
    suspend fun findUnclusteredAndNotIgnored(): List<FaceRegionEntity>

    @Query("UPDATE face_regions SET embedding = :embedding WHERE id = :id")
    suspend fun updateEmbedding(id: String, embedding: ByteArray)

    @Query("UPDATE face_regions SET personId = :personId WHERE id = :id")
    suspend fun updatePersonId(id: String, personId: String?)

    @Query("UPDATE face_regions SET personId = :personId, name = :name WHERE id = :id")
    suspend fun updatePersonAndName(id: String, personId: String?, name: String?)

    @Query("UPDATE face_regions SET ignored = 1, personId = NULL, name = NULL WHERE id = :id")
    suspend fun setIgnored(id: String)

    @Query("UPDATE face_regions SET ignored = 0 WHERE id = :id")
    suspend fun setNotIgnored(id: String)

    @Query("DELETE FROM face_regions WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM face_regions WHERE photoId = :photoId")
    suspend fun deleteByPhotoId(photoId: String)

    @Query("""
        SELECT f.*, ph.takenAt as photoTakenAt
        FROM face_regions f
        JOIN photos ph ON ph.id = f.photoId
        WHERE f.personId = :personId
        ORDER BY 
            CASE WHEN f.name IS NULL THEN 0 ELSE 1 END ASC,
            ph.takenAt DESC
    """)
    fun observeByPersonId(personId: String): Flow<List<FaceRegionWithPhoto>>

    @Query("""
        SELECT f.*, ph.takenAt as photoTakenAt
        FROM face_regions f
        JOIN photos ph ON ph.id = f.photoId
        WHERE f.personId IS NULL AND f.ignored = 0
        ORDER BY ph.takenAt DESC
    """)
    fun observeUnknown(): Flow<List<FaceRegionWithPhoto>>

    @Query("""
        SELECT f.*, ph.takenAt as photoTakenAt
        FROM face_regions f
        JOIN photos ph ON ph.id = f.photoId
        WHERE f.ignored = 1
        ORDER BY ph.takenAt DESC
    """)
    fun observeIgnored(): Flow<List<FaceRegionWithPhoto>>

    @Query("UPDATE face_regions SET name = :name WHERE personId = :personId AND name IS NOT NULL")
    suspend fun updateConfirmedNamesForPerson(personId: String, name: String)

    @Query("UPDATE face_regions SET personId = :targetPersonId WHERE personId = :sourcePersonId")
    suspend fun reassignPerson(sourcePersonId: String, targetPersonId: String)

    @Query("SELECT COUNT(*) FROM face_regions WHERE personId IS NULL AND ignored = 0")
    fun observeUnknownCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM face_regions WHERE ignored = 1")
    fun observeIgnoredCount(): Flow<Int>

    // Fix 5: reactive flow of all face regions for a single photo
    @Query("SELECT * FROM face_regions WHERE photoId = :photoId")
    fun observeByPhotoId(photoId: String): Flow<List<FaceRegionEntity>>
}

data class FaceRegionWithPhoto(
    @Embedded val faceRegion: FaceRegionEntity,
    val photoTakenAt: Long?
)
