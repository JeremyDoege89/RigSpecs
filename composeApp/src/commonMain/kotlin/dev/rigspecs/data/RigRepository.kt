package dev.rigspecs.data

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import dev.rigspecs.domain.EntityType
import dev.rigspecs.domain.FieldChange
import dev.rigspecs.domain.Ids
import dev.rigspecs.domain.Session
import dev.rigspecs.domain.diffFields
import dev.rigspecs.domain.loggedFields
import kotlinx.coroutines.flow.Flow

/**
 * Every write goes through here: it stamps department, updatedAt/updatedBy and dirty, and records
 * the field changes in the change log inside the same transaction. Screens read only from Room,
 * so the app behaves the same with or without signal.
 */
class RigRepository(
    private val db: AppDatabase,
    private val session: Session,
    private val ids: Ids,
) {
    private val vehicles = db.vehicleDao()
    private val specs = db.specItemDao()
    private val parts = db.partDao()
    private val docs = db.documentDao()
    private val changeLog = db.changeLogDao()

    fun observeVehicles(): Flow<List<VehicleEntity>> = vehicles.observeAll()
    fun observeVehicle(id: String): Flow<VehicleEntity?> = vehicles.observe(id)
    fun observeSpecs(vehicleId: String): Flow<List<SpecItemEntity>> = specs.observeForVehicle(vehicleId)
    fun observeParts(vehicleId: String): Flow<List<PartEntity>> = parts.observeForVehicle(vehicleId)
    fun observeDocuments(vehicleId: String): Flow<List<DocumentEntity>> = docs.observeForVehicle(vehicleId)
    fun observeHistory(entityId: String): Flow<List<ChangeLogEntity>> = changeLog.observeFor(entityId)

    suspend fun vehicle(id: String) = vehicles.get(id)
    suspend fun spec(id: String) = specs.get(id)
    suspend fun part(id: String) = parts.get(id)
    suspend fun document(id: String) = docs.get(id)
    suspend fun documents(vehicleId: String) = docs.listForVehicle(vehicleId)

    /** A blank id means a new row. Returns the saved row's id. */
    suspend fun saveVehicle(draft: VehicleEntity): String = write {
        val old = draft.id.takeIf { it.isNotEmpty() }?.let { vehicles.get(it) }
        val row = draft.copy(
            id = old?.id ?: ids.newId(),
            departmentId = old?.departmentId ?: session.departmentId,
            updatedAt = ids.now(),
            updatedBy = session.userId,
            dirty = true,
        )
        vehicles.upsert(row)
        log(EntityType.VEHICLE, row.id, diffFields(old?.loggedFields(), row.loggedFields()))
        row.id
    }

    suspend fun saveSpec(draft: SpecItemEntity): String = write {
        val old = draft.id.takeIf { it.isNotEmpty() }?.let { specs.get(it) }
        val row = draft.copy(
            id = old?.id ?: ids.newId(),
            departmentId = old?.departmentId ?: session.departmentId,
            sortOrder = old?.sortOrder ?: specs.nextSortOrder(draft.vehicleId),
            updatedAt = ids.now(),
            updatedBy = session.userId,
            dirty = true,
        )
        specs.upsert(row)
        log(EntityType.SPEC, row.id, diffFields(old?.loggedFields(), row.loggedFields()))
        row.id
    }

    suspend fun savePart(draft: PartEntity): String = write {
        val old = draft.id.takeIf { it.isNotEmpty() }?.let { parts.get(it) }
        val row = draft.copy(
            id = old?.id ?: ids.newId(),
            departmentId = old?.departmentId ?: session.departmentId,
            sortOrder = old?.sortOrder ?: parts.nextSortOrder(draft.vehicleId),
            updatedAt = ids.now(),
            updatedBy = session.userId,
            dirty = true,
        )
        parts.upsert(row)
        log(EntityType.PART, row.id, diffFields(old?.loggedFields(), row.loggedFields()))
        row.id
    }

    suspend fun saveDocument(draft: DocumentEntity): String = write {
        val old = draft.id.takeIf { it.isNotEmpty() }?.let { docs.get(it) }
        val row = draft.copy(
            id = old?.id ?: ids.newId(),
            departmentId = old?.departmentId ?: session.departmentId,
            updatedAt = ids.now(),
            updatedBy = session.userId,
            dirty = true,
        )
        docs.upsert(row)
        log(EntityType.DOCUMENT, row.id, diffFields(old?.loggedFields(), row.loggedFields()))
        row.id
    }

    /** Tombstones the rig and everything on it, so the delete reaches other phones on sync. */
    suspend fun deleteVehicle(id: String) = write {
        val v = vehicles.get(id) ?: return@write
        specs.listForVehicle(id).forEach { tombstoneSpec(it) }
        parts.listForVehicle(id).forEach { tombstonePart(it) }
        docs.listForVehicle(id).forEach { tombstoneDocument(it) }
        val row = v.copy(deleted = true, updatedAt = ids.now(), updatedBy = session.userId, dirty = true)
        vehicles.upsert(row)
        log(EntityType.VEHICLE, id, diffFields(v.loggedFields(), row.loggedFields()))
    }

    suspend fun deleteSpec(id: String) = write { specs.get(id)?.let { tombstoneSpec(it) } }
    suspend fun deletePart(id: String) = write { parts.get(id)?.let { tombstonePart(it) } }

    /** Specs and parts that cited the document keep their values but lose the dangling source. */
    suspend fun deleteDocument(id: String) = write {
        val doc = docs.get(id) ?: return@write
        specs.listForVehicle(doc.vehicleId).filter { it.sourceDocId == id }.forEach {
            val row = it.copy(sourceDocId = null, updatedAt = ids.now(), updatedBy = session.userId, dirty = true)
            specs.upsert(row)
            log(EntityType.SPEC, it.id, diffFields(it.loggedFields(), row.loggedFields()))
        }
        parts.listForVehicle(doc.vehicleId).filter { it.sourceDocId == id }.forEach {
            val row = it.copy(sourceDocId = null, updatedAt = ids.now(), updatedBy = session.userId, dirty = true)
            parts.upsert(row)
            log(EntityType.PART, it.id, diffFields(it.loggedFields(), row.loggedFields()))
        }
        tombstoneDocument(doc)
    }

    private suspend fun tombstoneSpec(old: SpecItemEntity) {
        val row = old.copy(deleted = true, updatedAt = ids.now(), updatedBy = session.userId, dirty = true)
        specs.upsert(row)
        log(EntityType.SPEC, old.id, diffFields(old.loggedFields(), row.loggedFields()))
    }

    private suspend fun tombstonePart(old: PartEntity) {
        val row = old.copy(deleted = true, updatedAt = ids.now(), updatedBy = session.userId, dirty = true)
        parts.upsert(row)
        log(EntityType.PART, old.id, diffFields(old.loggedFields(), row.loggedFields()))
    }

    private suspend fun tombstoneDocument(old: DocumentEntity) {
        val row = old.copy(deleted = true, updatedAt = ids.now(), updatedBy = session.userId, dirty = true)
        docs.upsert(row)
        log(EntityType.DOCUMENT, old.id, diffFields(old.loggedFields(), row.loggedFields()))
    }

    private suspend fun log(entityType: String, entityId: String, changes: List<FieldChange>) {
        if (changes.isEmpty()) return
        val now = ids.now()
        changeLog.insertAll(changes.map {
            ChangeLogEntity(
                id = ids.newId(),
                departmentId = session.departmentId,
                entityType = entityType,
                entityId = entityId,
                field = it.field,
                oldValue = it.oldValue,
                newValue = it.newValue,
                userId = session.userId,
                timestamp = now,
            )
        })
    }

    private suspend fun <T> write(block: suspend () -> T): T =
        db.useWriterConnection { it.immediateTransaction { block() } }
}
