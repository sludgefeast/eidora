// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        PhotoEntity::class,
        PersonEntity::class,
        FaceRegionEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class EidoraDatabase : RoomDatabase() {
    abstract fun photoDao(): PhotoDao

    abstract fun personDao(): PersonDao

    abstract fun faceRegionDao(): FaceRegionDao
}
