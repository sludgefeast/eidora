package de.sebastian.faces.data.db

import android.content.Context
import androidx.room.Room

fun FacesDatabase.Companion.getInstance(context: Context): FacesDatabase =
    Room.databaseBuilder(
        context.applicationContext,
        FacesDatabase::class.java,
        "faces.db"
    )
        .fallbackToDestructiveMigration()
        .build()

// Singleton holder
private var _instance: FacesDatabase? = null

fun FacesDatabase.Companion.getOrCreate(context: Context): FacesDatabase {
    return _instance ?: synchronized(FacesDatabase::class.java) {
        _instance ?: getInstance(context).also { _instance = it }
    }
}
