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

    /**
     * Rows needing a person's attention per rig: unverified specs and parts in the profile plus
     * imported rows waiting for review.
     */
    @Query(
        """
        SELECT v.id AS vehicleId,
          (SELECT COUNT(*) FROM spec_item s WHERE s.vehicleId = v.id AND s.deleted = 0 AND s.status = 'UNVERIFIED') +
          (SELECT COUNT(*) FROM part p WHERE p.vehicleId = v.id AND p.deleted = 0 AND p.status = 'UNVERIFIED') AS unverified,
          (SELECT COUNT(*) FROM import_row r WHERE r.vehicleId = v.id AND r.deleted = 0 AND r.state = 'PENDING') AS pendingImport
        FROM vehicle v WHERE v.deleted = 0
        """,
    )
    fun observeReviewCounts(): Flow<List<ReviewCount>>

    /** A stored vPIC decode for [vin] from any rig, deleted or not: the VIN-decode cache. */
    @Query("SELECT vinDecodeJson FROM vehicle WHERE vin = :vin AND vinDecodeJson != '' ORDER BY updatedAt DESC LIMIT 1")
    suspend fun storedDecode(vin: String): String?
}

data class ReviewCount(val vehicleId: String, val unverified: Int, val pendingImport: Int) {
    val total: Int get() = unverified + pendingImport
}

@Dao
interface SpecItemDao {
    @Query("SELECT * FROM spec_item WHERE vehicleId = :vehicleId AND deleted = 0 ORDER BY sortOrder, component COLLATE NOCASE, name COLLATE NOCASE")
    fun observeForVehicle(vehicleId: String): Flow<List<SpecItemEntity>>

    @Query("SELECT * FROM spec_item WHERE id = :id")
    suspend fun get(id: String): SpecItemEntity?

    @Query("SELECT * FROM spec_item WHERE vehicleId = :vehicleId AND deleted = 0")
    suspend fun listForVehicle(vehicleId: String): List<SpecItemEntity>

    @Query("SELECT * FROM spec_item WHERE vehicleId = :vehicleId AND deleted = 0 AND status = 'UNVERIFIED' ORDER BY sortOrder")
    fun observeUnverified(vehicleId: String): Flow<List<SpecItemEntity>>

    /** Every rig's filled-in specs, for search. */
    @Query("SELECT * FROM spec_item WHERE deleted = 0 AND value != ''")
    fun observeAllFilled(): Flow<List<SpecItemEntity>>

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

    @Query("SELECT * FROM part WHERE vehicleId = :vehicleId AND deleted = 0 AND status = 'UNVERIFIED' ORDER BY sortOrder")
    fun observeUnverified(vehicleId: String): Flow<List<PartEntity>>

    /** Every rig's parts that have a number, for search. */
    @Query("SELECT * FROM part WHERE deleted = 0 AND (oemNumber != '' OR aftermarketNumbers != '')")
    fun observeAllNumbered(): Flow<List<PartEntity>>

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
interface ImportDao {
    @Upsert
    suspend fun upsertJob(job: ImportJobEntity)

    @Query("SELECT * FROM import_job WHERE id = :id")
    suspend fun job(id: String): ImportJobEntity?

    @Query("SELECT * FROM import_job WHERE id = :id")
    fun observeJob(id: String): Flow<ImportJobEntity?>

    @Query("SELECT * FROM import_job WHERE vehicleId = :vehicleId AND deleted = 0 ORDER BY createdAt DESC")
    fun observeJobs(vehicleId: String): Flow<List<ImportJobEntity>>

    @Query("SELECT * FROM import_job WHERE documentId = :documentId AND deleted = 0 ORDER BY createdAt DESC")
    suspend fun jobsForDocument(documentId: String): List<ImportJobEntity>

    @Upsert
    suspend fun upsertRow(row: ImportRowEntity)

    @Query("SELECT * FROM import_row WHERE id = :id")
    suspend fun row(id: String): ImportRowEntity?

    @Query("SELECT * FROM import_row WHERE jobId = :jobId AND deleted = 0 ORDER BY sortOrder")
    suspend fun rowsForJob(jobId: String): List<ImportRowEntity>

    @Query("SELECT * FROM import_row WHERE vehicleId = :vehicleId AND deleted = 0 AND state = 'PENDING' ORDER BY jobId, sortOrder")
    fun observePending(vehicleId: String): Flow<List<ImportRowEntity>>

    /** Rows from every run over [documentId], for re-run matching. */
    @Query(
        "SELECT r.* FROM import_row r JOIN import_job j ON j.id = r.jobId " +
            "WHERE j.documentId = :documentId AND r.deleted = 0",
    )
    suspend fun rowsForDocument(documentId: String): List<ImportRowEntity>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM import_row WHERE jobId = :jobId")
    suspend fun nextSortOrder(jobId: String): Int

    @Query("UPDATE import_job SET deleted = 1, dirty = 1, updatedAt = :now, updatedBy = :userId WHERE vehicleId = :vehicleId")
    suspend fun tombstoneJobsForVehicle(vehicleId: String, now: Long, userId: String)

    @Query("UPDATE import_row SET deleted = 1, dirty = 1, updatedAt = :now, updatedBy = :userId WHERE vehicleId = :vehicleId")
    suspend fun tombstoneRowsForVehicle(vehicleId: String, now: Long, userId: String)

    @Query(
        "UPDATE import_row SET state = 'SUPERSEDED', dirty = 1, updatedAt = :now, updatedBy = :userId " +
            "WHERE state = 'PENDING' AND jobId IN (SELECT id FROM import_job WHERE documentId = :documentId)",
    )
    suspend fun supersedePendingForDocument(documentId: String, now: Long, userId: String)
}

suspend fun ImportDao.tombstoneForVehicle(vehicleId: String, now: Long, userId: String) {
    tombstoneJobsForVehicle(vehicleId, now, userId)
    tombstoneRowsForVehicle(vehicleId, now, userId)
}

suspend fun ImportDao.dropPendingForDocument(documentId: String, now: Long, userId: String) =
    supersedePendingForDocument(documentId, now, userId)

@Dao
interface SettingDao {
    @Query("SELECT * FROM setting")
    fun observeAll(): Flow<List<SettingEntity>>

    @Query("SELECT * FROM setting WHERE `key` = :key")
    suspend fun get(key: String): SettingEntity?

    @Upsert
    suspend fun upsert(setting: SettingEntity)

    @Query("DELETE FROM setting WHERE `key` = :key")
    suspend fun delete(key: String)
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
