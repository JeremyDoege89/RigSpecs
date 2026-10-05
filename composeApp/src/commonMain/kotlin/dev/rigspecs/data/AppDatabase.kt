package dev.rigspecs.data

import androidx.room.AutoMigration
import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.CoroutineDispatcher

@Database(
    entities = [
        VehicleEntity::class,
        SpecItemEntity::class,
        PartEntity::class,
        DocumentEntity::class,
        TemplateEntity::class,
        ChangeLogEntity::class,
    ],
    version = 2,
    autoMigrations = [
        // v2: vehicle.vinDecodeJson
        AutoMigration(from = 1, to = 2),
    ],
)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun vehicleDao(): VehicleDao
    abstract fun specItemDao(): SpecItemDao
    abstract fun partDao(): PartDao
    abstract fun documentDao(): DocumentDao
    abstract fun templateDao(): TemplateDao
    abstract fun changeLogDao(): ChangeLogDao

    companion object {
        const val FILE_NAME = "rigspecs.db"
    }
}

// Room's KSP generates the actual for each target.
@Suppress("KotlinNoActualForExpect")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}

/** The same SQLite build on every platform, so FTS and SQL behave identically everywhere. */
fun RoomDatabase.Builder<AppDatabase>.configure(io: CoroutineDispatcher): AppDatabase =
    setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(io)
        .build()
