package dev.rigspecs.data

import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import java.io.File

/** %APPDATA%\RigSpecs on Windows, ~/.local/share/RigSpecs elsewhere. */
fun appDataDir(): File {
    val base = System.getenv("APPDATA")?.let(::File)
        ?: File(System.getProperty("user.home"), ".local/share")
    return File(base, "RigSpecs").apply { mkdirs() }
}

fun buildDatabase(dir: File = appDataDir()): AppDatabase =
    Room.databaseBuilder<AppDatabase>(name = File(dir, AppDatabase.FILE_NAME).absolutePath)
        .configure(Dispatchers.IO)
