package dev.rigspecs.data

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import dev.rigspecs.data.importing.KnownField
import dev.rigspecs.domain.CandidateRow
import dev.rigspecs.domain.DefaultTemplates
import dev.rigspecs.domain.EntityType
import dev.rigspecs.domain.FieldChange
import dev.rigspecs.domain.Ids
import dev.rigspecs.domain.ImportMatch
import dev.rigspecs.domain.PartSystem
import dev.rigspecs.domain.Session
import dev.rigspecs.domain.SourceKind
import dev.rigspecs.domain.SourcedConflict
import dev.rigspecs.domain.SourcedResult
import dev.rigspecs.domain.SpecCategory
import dev.rigspecs.domain.TemplateItem
import dev.rigspecs.domain.TemplateJson
import dev.rigspecs.domain.VehicleType
import dev.rigspecs.domain.VerificationStatus
import dev.rigspecs.domain.diffFields
import dev.rigspecs.domain.displayValue
import dev.rigspecs.domain.isSafetyCritical
import dev.rigspecs.domain.loggedFields
import dev.rigspecs.domain.matchImportRow
import dev.rigspecs.domain.matchKey
import dev.rigspecs.domain.stampPartSave
import dev.rigspecs.domain.stampSpecSave
import dev.rigspecs.domain.toImportRow
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
    private val templates = db.templateDao()
    private val imports = db.importDao()

    fun observeVehicles(): Flow<List<VehicleEntity>> = vehicles.observeAll()
    fun observeVehicle(id: String): Flow<VehicleEntity?> = vehicles.observe(id)
    fun observeSpecs(vehicleId: String): Flow<List<SpecItemEntity>> = specs.observeForVehicle(vehicleId)
    fun observeParts(vehicleId: String): Flow<List<PartEntity>> = parts.observeForVehicle(vehicleId)
    fun observeDocuments(vehicleId: String): Flow<List<DocumentEntity>> = docs.observeForVehicle(vehicleId)
    fun observeHistory(entityId: String): Flow<List<ChangeLogEntity>> = changeLog.observeFor(entityId)
    fun observeReviewCounts(): Flow<List<ReviewCount>> = vehicles.observeReviewCounts()
    fun observeUnverifiedSpecs(vehicleId: String): Flow<List<SpecItemEntity>> = specs.observeUnverified(vehicleId)
    fun observeUnverifiedParts(vehicleId: String): Flow<List<PartEntity>> = parts.observeUnverified(vehicleId)
    fun observeSearchableSpecs(): Flow<List<SpecItemEntity>> = specs.observeAllFilled()
    fun observeSearchableParts(): Flow<List<PartEntity>> = parts.observeAllNumbered()

    suspend fun vehicle(id: String) = vehicles.get(id)
    suspend fun spec(id: String) = specs.get(id)
    suspend fun part(id: String) = parts.get(id)
    suspend fun document(id: String) = docs.get(id)
    suspend fun documents(vehicleId: String) = docs.listForVehicle(vehicleId)

    /** The profile's field names, for extraction to reuse: spec component and property, part component. */
    suspend fun fieldNames(vehicleId: String): List<KnownField> =
        (specs.listForVehicle(vehicleId).map { KnownField("spec", it.component, it.name) } +
            parts.listForVehicle(vehicleId).map { KnownField("part", it.component) })
            .distinctBy { Triple(it.kind, matchKey(it.component), matchKey(it.name)) }

    /** The stored vPIC JSON for [vin] from any rig, used as the VIN-decode cache. */
    suspend fun storedDecode(vin: String): String? = vehicles.storedDecode(vin)

    /** The department's template for [type], seeding the built-in one the first time. */
    suspend fun templateItems(type: VehicleType): List<TemplateItem> {
        val id = DefaultTemplates.id(type)
        templates.get(id)?.takeIf { !it.deleted }?.let { return TemplateJson.decode(it.itemsJson) }
        val items = DefaultTemplates.forType(type)
        write {
            if (templates.get(id) == null) {
                templates.upsert(
                    TemplateEntity(
                        id = id,
                        departmentId = session.departmentId,
                        vehicleType = type,
                        name = DefaultTemplates.name(type),
                        itemsJson = TemplateJson.encode(items),
                        updatedAt = ids.now(),
                        updatedBy = session.userId,
                    ),
                )
            }
        }
        return items
    }

    /**
     * A blank id means a new row. Returns the saved row's id. A new rig also gets a blank spec or
     * part for each of [template]'s items, in the same transaction.
     */
    suspend fun saveVehicle(draft: VehicleEntity, template: List<TemplateItem> = emptyList()): String = write {
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
        if (old == null) template.forEachIndexed { i, item -> insertTemplateItem(row, item, i) }
        row.id
    }

    private suspend fun insertTemplateItem(v: VehicleEntity, item: TemplateItem, sortOrder: Int) {
        when (item.kind) {
            TemplateItem.Kind.SPEC -> {
                val row = SpecItemEntity(
                    id = ids.newId(), departmentId = v.departmentId, vehicleId = v.id,
                    category = item.category ?: SpecCategory.OTHER, component = item.component, name = item.name,
                    value = "", unit = item.unit, safetyCritical = item.safetyCritical, sortOrder = sortOrder,
                    updatedAt = ids.now(), updatedBy = session.userId,
                )
                specs.upsert(row)
                log(EntityType.SPEC, row.id, diffFields(null, row.loggedFields()))
            }
            TemplateItem.Kind.PART -> {
                val row = PartEntity(
                    id = ids.newId(), departmentId = v.departmentId, vehicleId = v.id,
                    system = item.system ?: PartSystem.OTHER, component = item.component, sortOrder = sortOrder,
                    updatedAt = ids.now(), updatedBy = session.userId,
                )
                parts.upsert(row)
                log(EntityType.PART, row.id, diffFields(null, row.loggedFields()))
            }
        }
    }

    /**
     * Saves the editor's form. [confirm] false keeps an UNVERIFIED row unverified ("Save without
     * confirming"); otherwise what an editor saves counts as checked by them.
     */
    suspend fun saveSpec(draft: SpecItemEntity, confirm: Boolean = true): String = write {
        val old = draft.id.takeIf { it.isNotEmpty() }?.let { specs.get(it) }
        val row = stampSpecSave(old, draft, confirm, session.userId, ids.now()).copy(
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

    suspend fun savePart(draft: PartEntity, confirm: Boolean = true): String = write {
        val old = draft.id.takeIf { it.isNotEmpty() }?.let { parts.get(it) }
        val row = stampPartSave(old, draft, confirm, session.userId, ids.now()).copy(
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

    /** An editor checked the value against its source: VERIFIED, stamped with their name. */
    suspend fun confirmSpec(id: String) = write {
        val old = specs.get(id) ?: return@write
        if (old.status == VerificationStatus.VERIFIED) return@write
        val row = old.copy(
            status = VerificationStatus.VERIFIED, verifiedBy = session.userId, verifiedAt = ids.now(),
            updatedAt = ids.now(), updatedBy = session.userId, dirty = true,
        )
        specs.upsert(row)
        log(EntityType.SPEC, id, diffFields(old.loggedFields(), row.loggedFields()))
    }

    suspend fun confirmPart(id: String) = write {
        val old = parts.get(id) ?: return@write
        if (old.status == VerificationStatus.VERIFIED) return@write
        val row = old.copy(
            status = VerificationStatus.VERIFIED, verifiedBy = session.userId, verifiedAt = ids.now(),
            updatedAt = ids.now(), updatedBy = session.userId, dirty = true,
        )
        parts.upsert(row)
        log(EntityType.PART, id, diffFields(old.loggedFields(), row.loggedFields()))
    }

    /**
     * Writes outside rows (an API source, a spreadsheet) into a profile as UNVERIFIED. A row fills
     * a matching blank (usually a template field) instead of adding a duplicate, and a row that
     * agrees with the profile is skipped. A row that disagrees is reported and left out, so the
     * value an editor entered wins, unless [replaceConflicts] says the import should replace it.
     */
    suspend fun addSourcedRows(
        vehicleId: String,
        sourceKind: SourceKind,
        rows: List<CandidateRow>,
        replaceConflicts: Boolean = false,
    ): SourcedResult = write {
        val vehicle = vehicles.get(vehicleId) ?: return@write SourcedResult(0, 0, 0, emptyList())
        var added = 0
        var filled = 0
        var same = 0
        var replaced = 0
        val conflicts = mutableListOf<SourcedConflict>()
        rows.filter { it.isUsable }.forEach { c ->
            val probe = c.toImportRow(id = "", departmentId = vehicle.departmentId, jobId = "", vehicleId = vehicleId, now = 0, userId = "")
            when (val m = matchImportRow(probe, specs.listForVehicle(vehicleId), parts.listForVehicle(vehicleId))) {
                ImportMatch.New -> {
                    insertSourced(vehicle, sourceKind, c)
                    added++
                }
                is ImportMatch.FillsBlank -> {
                    fillSourced(m.targetId, c, sourceKind)
                    filled++
                }
                is ImportMatch.Same -> same++
                is ImportMatch.Conflict -> if (replaceConflicts) {
                    fillSourced(m.targetId, c, sourceKind)
                    replaced++
                } else {
                    conflicts += SourcedConflict(
                        what = listOf(c.component, c.name).filter { it.isNotBlank() }.joinToString(" · "),
                        profileValue = m.current,
                        sourceValue = probe.displayValue(),
                    )
                }
            }
        }
        SourcedResult(added, filled, same, conflicts, replaced)
    }

    /**
     * Confirms a rig's unverified values in one go, leaving out safety-critical ones: brakes,
     * wheels, aerial and pump values still have to be checked one at a time. Returns how many.
     */
    suspend fun confirmAllUnverified(vehicleId: String): Int = write {
        var n = 0
        specs.listForVehicle(vehicleId).filter { it.status == VerificationStatus.UNVERIFIED && !it.safetyCritical }.forEach { old ->
            val row = old.copy(status = VerificationStatus.VERIFIED, verifiedBy = session.userId, verifiedAt = ids.now(), updatedAt = ids.now(), updatedBy = session.userId, dirty = true)
            specs.upsert(row)
            log(EntityType.SPEC, row.id, diffFields(old.loggedFields(), row.loggedFields()))
            n++
        }
        parts.listForVehicle(vehicleId).filter { it.status == VerificationStatus.UNVERIFIED }.forEach { old ->
            val row = old.copy(status = VerificationStatus.VERIFIED, verifiedBy = session.userId, verifiedAt = ids.now(), updatedAt = ids.now(), updatedBy = session.userId, dirty = true)
            parts.upsert(row)
            log(EntityType.PART, row.id, diffFields(old.loggedFields(), row.loggedFields()))
            n++
        }
        n
    }

    private suspend fun insertSourced(v: VehicleEntity, sourceKind: SourceKind, c: CandidateRow) {
        when (c.kind) {
            ImportRowKind.SPEC -> {
                val row = SpecItemEntity(
                    id = ids.newId(), departmentId = v.departmentId, vehicleId = v.id,
                    category = c.category ?: SpecCategory.OTHER, component = c.component.trim(), name = c.name.trim(),
                    value = c.value.trim(), unit = c.unit.trim(), notes = c.notes.trim(),
                    safetyCritical = c.safetyCritical || isSafetyCritical(c.component, c.name),
                    status = VerificationStatus.UNVERIFIED, sourceKind = sourceKind, sourceUrl = c.sourceUrl,
                    sortOrder = specs.nextSortOrder(v.id), updatedAt = ids.now(), updatedBy = session.userId,
                )
                specs.upsert(row)
                log(EntityType.SPEC, row.id, diffFields(null, row.loggedFields()))
            }
            ImportRowKind.PART -> {
                val row = PartEntity(
                    id = ids.newId(), departmentId = v.departmentId, vehicleId = v.id,
                    system = c.system ?: PartSystem.OTHER, component = c.component.trim(), oemNumber = c.oemNumber.trim(),
                    aftermarketNumbers = c.aftermarketNumbers.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n"),
                    qtyPerVehicle = c.qtyPerVehicle, notes = c.notes.trim(),
                    status = VerificationStatus.UNVERIFIED, sourceKind = sourceKind, sourceUrl = c.sourceUrl,
                    sortOrder = parts.nextSortOrder(v.id), updatedAt = ids.now(), updatedBy = session.userId,
                )
                parts.upsert(row)
                log(EntityType.PART, row.id, diffFields(null, row.loggedFields()))
            }
        }
    }

    private suspend fun fillSourced(targetId: String, c: CandidateRow, sourceKind: SourceKind) {
        when (c.kind) {
            ImportRowKind.SPEC -> {
                val old = specs.get(targetId) ?: return
                val row = old.copy(
                    value = c.value.trim(), unit = c.unit.trim().ifEmpty { old.unit }, notes = old.notes.ifBlank { c.notes.trim() },
                    safetyCritical = old.safetyCritical || c.safetyCritical || isSafetyCritical(c.component, c.name),
                    status = VerificationStatus.UNVERIFIED, sourceKind = sourceKind, sourceUrl = c.sourceUrl,
                    verifiedBy = "", verifiedAt = null, updatedAt = ids.now(), updatedBy = session.userId, dirty = true,
                )
                specs.upsert(row)
                log(EntityType.SPEC, row.id, diffFields(old.loggedFields(), row.loggedFields()))
            }
            ImportRowKind.PART -> {
                val old = parts.get(targetId) ?: return
                val row = old.copy(
                    oemNumber = c.oemNumber.trim(),
                    aftermarketNumbers = c.aftermarketNumbers.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n"),
                    qtyPerVehicle = old.qtyPerVehicle ?: c.qtyPerVehicle,
                    status = VerificationStatus.UNVERIFIED, sourceKind = sourceKind, sourceUrl = c.sourceUrl,
                    verifiedBy = "", verifiedAt = null, updatedAt = ids.now(), updatedBy = session.userId, dirty = true,
                )
                parts.upsert(row)
                log(EntityType.PART, row.id, diffFields(old.loggedFields(), row.loggedFields()))
            }
        }
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
        imports.tombstoneForVehicle(id, ids.now(), session.userId)
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
        // Staged rows can't be checked against a document that's gone.
        imports.dropPendingForDocument(id, ids.now(), session.userId)
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

    internal suspend fun log(entityType: String, entityId: String, changes: List<FieldChange>) {
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

    /** One immediate transaction; other repositories share it so their writes log the same way. */
    internal suspend fun <T> write(block: suspend () -> T): T =
        db.useWriterConnection { it.immediateTransaction { block() } }
}
