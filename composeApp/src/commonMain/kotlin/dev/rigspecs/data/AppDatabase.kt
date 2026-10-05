package dev.rigspecs.data

import androidx.room.AutoMigration
import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
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
        ImportJobEntity::class,
        ImportRowEntity::class,
        SettingEntity::class,
    ],
    version = 3,
    autoMigrations = [
        // v2: vehicle.vinDecodeJson
        AutoMigration(from = 1, to = 2),
        // v3: trust fields on spec_item and part, vehicle.engineSerial, import_job, import_row, setting
        AutoMigration(from = 2, to = 3, spec = Migration2To3::class),
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
    abstract fun importDao(): ImportDao
    abstract fun settingDao(): SettingDao

    companion object {
        const val FILE_NAME = "rigspecs.db"
    }
}

/**
 * Everything typed in before v3 was entered by a person, so it counts as verified by whoever
 * last saved it. Blanks stay unattributed.
 */
class Migration2To3 : AutoMigrationSpec {
    override fun onPostMigrate(connection: SQLiteConnection) {
        connection.execSQL("UPDATE spec_item SET verifiedBy = updatedBy, verifiedAt = updatedAt WHERE value != ''")
        connection.execSQL(
            "UPDATE part SET verifiedBy = updatedBy, verifiedAt = updatedAt WHERE oemNumber != '' OR aftermarketNumbers != ''",
        )
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
