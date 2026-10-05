package dev.rigspecs.data

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.Dispatchers

fun buildDatabase(context: Context): AppDatabase {
    val app = context.applicationContext
    return Room.databaseBuilder<AppDatabase>(app, app.getDatabasePath(AppDatabase.FILE_NAME).absolutePath)
        .configure(Dispatchers.IO)
}
