package de.sebastian.faces.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        PhotoEntity::class,
        PersonEntity::class,
        FaceRegionEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class FacesDatabase : RoomDatabase() {
    abstract fun photoDao(): PhotoDao
    abstract fun personDao(): PersonDao
    abstract fun faceRegionDao(): FaceRegionDao
}
