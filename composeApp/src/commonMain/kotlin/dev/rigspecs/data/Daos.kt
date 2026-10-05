package dev.rigspecs.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface VehicleDao {
    @Query("SELECT * FROM vehicle WHERE deleted = 0 ORDER BY unitNumber COLLATE NOCASE")
    fun observeAll(): Flow<List<VehicleEntity>>

    @Query("SELECT * FROM vehicle WHERE id = :id AND deleted = 0")
    fun observe(id: String): Flow<VehicleEntity?>

    @Query("SELECT * FROM vehicle WHERE id = :id")
    suspend fun get(id: String): VehicleEntity?

    @Upsert
    suspend fun upsert(vehicle: VehicleEntity)

    /** A stored vPIC decode for [vin] from any rig, deleted or not: the VIN-decode cache. */
    @Query("SELECT vinDecodeJson FROM vehicle WHERE vin = :vin AND vinDecodeJson != '' ORDER BY updatedAt DESC LIMIT 1")
    suspend fun storedDecode(vin: String): String?
}

@Dao
interface SpecItemDao {
    @Query("SELECT * FROM spec_item WHERE vehicleId = :vehicleId AND deleted = 0 ORDER BY sortOrder, component COLLATE NOCASE, name COLLATE NOCASE")
    fun observeForVehicle(vehicleId: String): Flow<List<SpecItemEntity>>

    @Query("SELECT * FROM spec_item WHERE id = :id")
    suspend fun get(id: String): SpecItemEntity?

    @Query("SELECT * FROM spec_item WHERE vehicleId = :vehicleId AND deleted = 0")
    suspend fun listForVehicle(vehicleId: String): List<SpecItemEntity>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM spec_item WHERE vehicleId = :vehicleId")
    suspend fun nextSortOrder(vehicleId: String): Int

    @Upsert
    suspend fun upsert(item: SpecItemEntity)
}

@Dao
interface PartDao {
    @Query("SELECT * FROM part WHERE vehicleId = :vehicleId AND deleted = 0 ORDER BY sortOrder, component COLLATE NOCASE")
    fun observeForVehicle(vehicleId: String): Flow<List<PartEntity>>

    @Query("SELECT * FROM part WHERE id = :id")
    suspend fun get(id: String): PartEntity?

    @Query("SELECT * FROM part WHERE vehicleId = :vehicleId AND deleted = 0")
    suspend fun listForVehicle(vehicleId: String): List<PartEntity>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM part WHERE vehicleId = :vehicleId")
    suspend fun nextSortOrder(vehicleId: String): Int

    @Upsert
    suspend fun upsert(part: PartEntity)
}

@Dao
interface DocumentDao {
    @Query("SELECT * FROM document WHERE vehicleId = :vehicleId AND deleted = 0 ORDER BY title COLLATE NOCASE")
    fun observeForVehicle(vehicleId: String): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM document WHERE id = :id")
    suspend fun get(id: String): DocumentEntity?

    @Query("SELECT * FROM document WHERE vehicleId = :vehicleId AND deleted = 0")
    suspend fun listForVehicle(vehicleId: String): List<DocumentEntity>

    @Upsert
    suspend fun upsert(document: DocumentEntity)
}

@Dao
interface TemplateDao {
    @Query("SELECT * FROM template WHERE id = :id")
    suspend fun get(id: String): TemplateEntity?

    @Upsert
    suspend fun upsert(template: TemplateEntity)
}

@Dao
interface ChangeLogDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(entries: List<ChangeLogEntity>)

    @Query("SELECT * FROM change_log WHERE entityId = :entityId ORDER BY timestamp DESC")
    fun observeFor(entityId: String): Flow<List<ChangeLogEntity>>

    @Query("SELECT * FROM change_log WHERE entityId = :entityId ORDER BY timestamp DESC")
    suspend fun listFor(entityId: String): List<ChangeLogEntity>
}
