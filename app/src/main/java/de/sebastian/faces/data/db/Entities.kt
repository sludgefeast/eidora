package de.sebastian.faces.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "photos")
data class PhotoEntity(
    @PrimaryKey val id: String,
    val path: String,
    val modifiedAt: Long,
    val takenAt: Long?,
    val analyzed: Boolean = false
)

@Entity(
    tableName = "persons",
    indices = [Index(value = ["name"], unique = true)]
)
data class PersonEntity(
    @PrimaryKey val id: String,
    val name: String,
    val representativeFaceId: String? = null
)

@Entity(
    tableName = "face_regions",
    foreignKeys = [
        ForeignKey(
            entity = PhotoEntity::class,
            parentColumns = ["id"],
            childColumns = ["photoId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["id"],
            childColumns = ["personId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index("photoId"),
        Index("personId")
    ]
)
data class FaceRegionEntity(
    @PrimaryKey val id: String,
    val photoId: String,
    val personId: String? = null,
    val name: String? = null,
    val regionJson: String,
    val embedding: ByteArray? = null,
    val ignored: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FaceRegionEntity) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
