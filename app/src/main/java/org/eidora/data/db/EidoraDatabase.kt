package org.eidora.data.db

import androidx.room.Database
import androidx.room.RoomDatabase


val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE photos ADD COLUMN pending_xmp_write INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE face_regions ADD COLUMN quality_score REAL")
    }
}

@Database(
    entities = [
        PhotoEntity::class,
        PersonEntity::class,
        FaceRegionEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class EidoraDatabase : RoomDatabase() {
    abstract fun photoDao(): PhotoDao
    abstract fun personDao(): PersonDao
    abstract fun faceRegionDao(): FaceRegionDao
}
