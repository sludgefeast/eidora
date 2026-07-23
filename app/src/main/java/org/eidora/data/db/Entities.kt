// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "photos")
data class PhotoEntity(
    @PrimaryKey val id: String,
    val path: String,
    /** MediaStore relative path of the containing folder, e.g. "DCIM/Camera". */
    val folder: String = "",
    val modifiedAt: Long,
    val takenAt: Long?,
    val analyzed: Boolean = false,
    @ColumnInfo(name = "pending_xmp_write") val pendingXmpWrite: Boolean = false,
)

@Entity(
    tableName = "persons",
    // Non-unique: two different people may share a name. The user resolves
    // same-name collisions explicitly (merge or keep separate).
    indices = [Index(value = ["name"])],
)
data class PersonEntity(
    @PrimaryKey val id: String,
    val name: String?, // null = clustering suggestion, not yet named by user
    val representativeFaceId: String? = null,
)

@Entity(
    tableName = "face_regions",
    foreignKeys = [
        ForeignKey(
            entity = PhotoEntity::class,
            parentColumns = ["id"],
            childColumns = ["photoId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["id"],
            childColumns = ["personId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("photoId"),
        Index("personId"),
    ],
)
data class FaceRegionEntity(
    @PrimaryKey val id: String,
    val photoId: String,
    val personId: String? = null,
    val name: String? = null,
    val regionJson: String,
    val embedding: ByteArray? = null,
    val ignored: Boolean = false,
    @ColumnInfo(name = "quality_score") val qualityScore: Float? = null,
    // True when embedding computation failed permanently (e.g. the face crop
    // could not be prepared). Such faces are excluded from the "missing
    // embedding" set so clustering does not wait for them forever.
    @ColumnInfo(name = "embedding_failed") val embeddingFailed: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FaceRegionEntity) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
