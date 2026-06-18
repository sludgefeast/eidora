package de.sebastian.faces.data.db

import android.content.Context
import androidx.room.Room

object DatabaseProvider {
    @Volatile
    private var instance: FacesDatabase? = null

    fun getInstance(context: Context): FacesDatabase =
        instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                FacesDatabase::class.java,
                "faces.db"
            )
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
}
