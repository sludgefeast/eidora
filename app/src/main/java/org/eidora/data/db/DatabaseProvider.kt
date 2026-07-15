package org.eidora.data.db

import android.content.Context
import androidx.room.Room

object DatabaseProvider {
    @Volatile
    private var instance: EidoraDatabase? = null

    fun getInstance(context: Context): EidoraDatabase =
        instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                EidoraDatabase::class.java,
                "faces.db"
            )
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
}
