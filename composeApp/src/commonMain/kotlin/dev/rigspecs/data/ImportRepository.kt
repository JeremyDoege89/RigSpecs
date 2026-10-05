package dev.rigspecs.data

import dev.rigspecs.domain.CandidateRow
import dev.rigspecs.domain.EntityType
import dev.rigspecs.domain.Ids
import dev.rigspecs.domain.ImportJobStatus
import dev.rigspecs.domain.ImportMatch
import dev.rigspecs.domain.ImportRowState
import dev.rigspecs.domain.PartSystem
import dev.rigspecs.domain.Session
import dev.rigspecs.domain.SourceKind
import dev.rigspecs.domain.SpecCategory
import dev.rigspecs.domain.VerificationStatus
import dev.rigspecs.domain.contentKey
import dev.rigspecs.domain.diffFields
import dev.rigspecs.domain.loggedFields
import dev.rigspecs.domain.matchImportRow
import dev.rigspecs.domain.pageHint
import dev.rigspecs.domain.toImportRow
import kotlinx.coroutines.flow.Flow

/** The editor's changes to a staged row before confirming it. Null fields stay as extracted. */
data class RowEdit(
    val component: String? = null,
    val name: String? = null,
    val value: String? = null,
    val unit: String? = null,
    val category: SpecCategory? = null,
    val system: PartSystem? = null,
    val oemNumber: String? = null,
    val aftermarketNumbers: String? = null,
    val safetyCritical: Boolean? = null,
)

sealed interface ConfirmResult {
    data class Confirmed(val itemId: String) : ConfirmResult

    /** The profile already has a different value; the editor must choose Replace or Keep. */
    data class NeedsChoice(val current: String) : ConfirmResult

    /** The row was already handled (on this or another device). */
    data object Gone : ConfirmResult
}

/**
 * Assisted import's staging area. Extracted rows wait here; nothing reaches the profile until an
 * editor confirms it, and confirmed rows are never overwritten by a re-run.
 */
class ImportRepository(
    private val db: AppDatabase,
    private val rigs: RigRepository,
    private val session: Session,
    private val ids: Ids,
) {
    private val dao = db.importDao()
    private val specs = db.specItemDao()
    private val parts = db.partDao()

    fun observeJob(id: String): Flow<ImportJobEntity?> = dao.observeJob(id)
    fun observeJobs(vehicleId: String): Flow<List<ImportJobEntity>> = dao.observeJobs(vehicleId)
    fun observePending(vehicleId: String): Flow<List<ImportRowEntity>> = dao.observePending(vehicleId)
    suspend fun job(id: String) = dao.job(id)
    suspend fun row(id: String) = dao.row(id)
    suspend fun jobsForDocument(documentId: String) = dao.jobsForDocument(documentId)

    /**
     * Starts a run over a document. Rows still waiting from earlier runs of the same document are
     * superseded (the new run replaces them); confirmed and rejected rows stay as they are.
     */
    suspend fun startJob(vehicleId: String, documentId: String, pages: String, pagesTotal: Int, dense: Boolean): String = rigs.write {
        val vehicle = db.vehicleDao().get(vehicleId) ?: error("No rig $vehicleId")
        dao.supersedePendingForDocument(documentId, ids.now(), session.userId)
        val job = ImportJobEntity(
            id = ids.newId(),
            departmentId = vehicle.departmentId,
            vehicleId = vehicleId,
            documentId = documentId,
            status = ImportJobStatus.QUEUED,
            pages = pages,
            pagesTotal = pagesTotal,
            dense = dense,
            createdAt = ids.now(),
            updatedAt = ids.now(),
            updatedBy = session.userId,
        )
        dao.upsertJob(job)
        job.id
    }

    suspend fun updateJob(id: String, change: (ImportJobEntity) -> ImportJobEntity) = rigs.write {
        val job = dao.job(id) ?: return@write
        dao.upsertJob(change(job).copy(updatedAt = ids.now(), updatedBy = session.userId, dirty = true))
    }

    /**
     * Stages one batch of extracted rows. A row that repeats one confirmed in an earlier run, or
     * that the profile already holds, is marked DUPLICATE; one an editor rejected before stays
     * REJECTED; a repeat within this run is a DUPLICATE. Returns the number now waiting for review.
     */
    suspend fun stageRows(jobId: String, rows: List<CandidateRow>): Int = rigs.write {
        val job = dao.job(jobId) ?: return@write 0
        val earlier = dao.rowsForDocument(job.documentId).filter { it.jobId != jobId }
        val confirmed = earlier.filter { it.state == ImportRowState.CONFIRMED }.map { it.contentKey() }.toSet()
        val rejected = earlier.filter { it.state == ImportRowState.REJECTED }.map { it.contentKey() }.toSet()
        val seen = dao.rowsForJob(jobId).map { it.contentKey() }.toMutableSet()
        val profileSpecs = specs.listForVehicle(job.vehicleId)
        val profileParts = parts.listForVehicle(job.vehicleId)
        var order = dao.nextSortOrder(jobId)
        var usable = 0
        var pending = 0
        rows.filter { it.isUsable }.forEach { c ->
            val row = c.toImportRow(ids.newId(), job.departmentId, jobId, job.vehicleId, ids.now(), session.userId, order++)
            val key = row.contentKey()
            val state = when {
                key in seen || key in confirmed -> ImportRowState.DUPLICATE
                key in rejected -> ImportRowState.REJECTED
                matchImportRow(row, profileSpecs, profileParts) is ImportMatch.Same -> ImportRowState.DUPLICATE
                else -> ImportRowState.PENDING
            }
            seen += key
            dao.upsertRow(row.copy(state = state))
            usable++
            if (state == ImportRowState.PENDING) pending++
        }
        dao.upsertJob(job.copy(rowsExtracted = job.rowsExtracted + usable, updatedAt = ids.now(), dirty = true))
        pending
    }

    /**
     * Copies a staged row into the profile as VERIFIED, citing the document and page: as a new
     * row, by filling a matching blank, or (with [replace]) over a different existing value.
     */
    suspend fun confirmRow(rowId: String, edit: RowEdit? = null, replace: Boolean = false): ConfirmResult = rigs.write {
        val original = dao.row(rowId)?.takeIf { it.state == ImportRowState.PENDING && !it.deleted }
            ?: return@write ConfirmResult.Gone
        val row = edit?.let { original.applying(it) } ?: original
        val job = dao.job(row.jobId) ?: return@write ConfirmResult.Gone
        val match = matchImportRow(row, specs.listForVehicle(row.vehicleId), parts.listForVehicle(row.vehicleId))
        if (match is ImportMatch.Conflict && !replace) return@write ConfirmResult.NeedsChoice(match.current)
        val itemId = when (row.kind) {
            ImportRowKind.SPEC -> confirmSpec(row, job, match)
            ImportRowKind.PART -> confirmPart(row, job, match)
        }
        val edited = row != original
        dao.upsertRow(
            row.copy(state = ImportRowState.CONFIRMED, resultId = itemId, edited = edited, updatedAt = ids.now(), updatedBy = session.userId, dirty = true),
        )
        dao.upsertJob(job.copy(rowsConfirmed = job.rowsConfirmed + 1, updatedAt = ids.now(), dirty = true))
        finishIfReviewed(job.id)
        ConfirmResult.Confirmed(itemId)
    }

    suspend fun rejectRow(rowId: String) = rigs.write {
        val row = dao.row(rowId)?.takeIf { it.state == ImportRowState.PENDING } ?: return@write
        dao.upsertRow(row.copy(state = ImportRowState.REJECTED, updatedAt = ids.now(), updatedBy = session.userId, dirty = true))
        finishIfReviewed(row.jobId)
    }

    /** A job whose rows are all decided is done; one still reading pages is left alone. */
    private suspend fun finishIfReviewed(jobId: String) {
        val job = dao.job(jobId) ?: return
        if (job.status != ImportJobStatus.REVIEW) return
        if (dao.rowsForJob(jobId).none { it.state == ImportRowState.PENDING }) {
            dao.upsertJob(job.copy(status = ImportJobStatus.DONE, updatedAt = ids.now(), dirty = true))
        }
    }

    /** Called when a job has read its last page. */
    suspend fun markRead(jobId: String) = rigs.write {
        val job = dao.job(jobId) ?: return@write
        val anyPending = dao.rowsForJob(jobId).any { it.state == ImportRowState.PENDING }
        val status = if (anyPending) ImportJobStatus.REVIEW else ImportJobStatus.DONE
        dao.upsertJob(job.copy(status = status, error = "", updatedAt = ids.now(), updatedBy = session.userId, dirty = true))
    }

    private suspend fun confirmSpec(row: ImportRowEntity, job: ImportJobEntity, match: ImportMatch): String {
        val now = ids.now()
        val provenance = { s: SpecItemEntity ->
            s.copy(
                sourceDocId = job.documentId, pageHint = pageHint(row.page), sourceKind = SourceKind.IMPORT, sourceUrl = "",
                status = VerificationStatus.VERIFIED, verifiedBy = session.userId, verifiedAt = now,
                updatedAt = now, updatedBy = session.userId, dirty = true,
            )
        }
        return when (match) {
            ImportMatch.New -> {
                val created = provenance(
                    SpecItemEntity(
                        id = ids.newId(), departmentId = job.departmentId, vehicleId = row.vehicleId,
                        category = row.category ?: SpecCategory.OTHER, component = row.component, name = row.name,
                        value = row.value, unit = row.unit, notes = row.notes, safetyCritical = row.safetyCritical,
                        sortOrder = specs.nextSortOrder(row.vehicleId), updatedAt = now, updatedBy = session.userId,
                    ),
                )
                specs.upsert(created)
                rigs.log(EntityType.SPEC, created.id, diffFields(null, created.loggedFields()))
                created.id
            }
            is ImportMatch.FillsBlank, is ImportMatch.Conflict -> {
                val targetId = if (match is ImportMatch.FillsBlank) match.targetId else (match as ImportMatch.Conflict).targetId
                val old = specs.get(targetId) ?: error("Gone: $targetId")
                val updated = provenance(
                    old.copy(
                        value = row.value, unit = row.unit.ifBlank { old.unit }, notes = old.notes.ifBlank { row.notes },
                        safetyCritical = old.safetyCritical || row.safetyCritical,
                    ),
                )
                specs.upsert(updated)
                rigs.log(EntityType.SPEC, updated.id, diffFields(old.loggedFields(), updated.loggedFields()))
                updated.id
            }
            is ImportMatch.Same -> {
                // The value already matches; add the citation if the row had none.
                val old = specs.get(match.targetId) ?: error("Gone: ${match.targetId}")
                val updated = if (old.sourceDocId == null || old.status == VerificationStatus.UNVERIFIED) {
                    provenance(old).copy(
                        sourceDocId = old.sourceDocId ?: job.documentId,
                        pageHint = if (old.sourceDocId == null) pageHint(row.page) else old.pageHint,
                        sourceKind = if (old.sourceDocId == null) SourceKind.IMPORT else old.sourceKind,
                    )
                } else {
                    old
                }
                if (updated != old) {
                    specs.upsert(updated)
                    rigs.log(EntityType.SPEC, updated.id, diffFields(old.loggedFields(), updated.loggedFields()))
                }
                old.id
            }
        }
    }

    private suspend fun confirmPart(row: ImportRowEntity, job: ImportJobEntity, match: ImportMatch): String {
        val now = ids.now()
        val provenance = { p: PartEntity ->
            p.copy(
                sourceDocId = job.documentId, pageHint = pageHint(row.page), sourceKind = SourceKind.IMPORT, sourceUrl = "",
                status = VerificationStatus.VERIFIED, verifiedBy = session.userId, verifiedAt = now,
                updatedAt = now, updatedBy = session.userId, dirty = true,
            )
        }
        return when (match) {
            ImportMatch.New -> {
                val created = provenance(
                    PartEntity(
                        id = ids.newId(), departmentId = job.departmentId, vehicleId = row.vehicleId,
                        system = row.system ?: PartSystem.OTHER, component = row.component, oemNumber = row.oemNumber,
                        aftermarketNumbers = row.aftermarketNumbers, qtyPerVehicle = row.qtyPerVehicle, notes = row.notes,
                        sortOrder = parts.nextSortOrder(row.vehicleId), updatedAt = now, updatedBy = session.userId,
                    ),
                )
                parts.upsert(created)
                rigs.log(EntityType.PART, created.id, diffFields(null, created.loggedFields()))
                created.id
            }
            is ImportMatch.FillsBlank, is ImportMatch.Conflict -> {
                val targetId = if (match is ImportMatch.FillsBlank) match.targetId else (match as ImportMatch.Conflict).targetId
                val old = parts.get(targetId) ?: error("Gone: $targetId")
                val updated = provenance(
                    old.copy(
                        oemNumber = row.oemNumber, aftermarketNumbers = row.aftermarketNumbers,
                        qtyPerVehicle = row.qtyPerVehicle ?: old.qtyPerVehicle, notes = old.notes.ifBlank { row.notes },
                    ),
                )
                parts.upsert(updated)
                rigs.log(EntityType.PART, updated.id, diffFields(old.loggedFields(), updated.loggedFields()))
                updated.id
            }
            is ImportMatch.Same -> {
                val old = parts.get(match.targetId) ?: error("Gone: ${match.targetId}")
                val updated = if (old.sourceDocId == null || old.status == VerificationStatus.UNVERIFIED) {
                    provenance(old).copy(
                        sourceDocId = old.sourceDocId ?: job.documentId,
                        pageHint = if (old.sourceDocId == null) pageHint(row.page) else old.pageHint,
                        sourceKind = if (old.sourceDocId == null) SourceKind.IMPORT else old.sourceKind,
                    )
                } else {
                    old
                }
                if (updated != old) {
                    parts.upsert(updated)
                    rigs.log(EntityType.PART, updated.id, diffFields(old.loggedFields(), updated.loggedFields()))
                }
                old.id
            }
        }
    }
}

private fun ImportRowEntity.applying(e: RowEdit) = copy(
    component = e.component?.trim() ?: component,
    name = e.name?.trim() ?: name,
    value = e.value?.trim() ?: value,
    unit = e.unit?.trim() ?: unit,
    category = e.category ?: category,
    system = e.system ?: system,
    oemNumber = e.oemNumber?.trim() ?: oemNumber,
    aftermarketNumbers = e.aftermarketNumbers?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.joinToString("\n") ?: aftermarketNumbers,
    safetyCritical = e.safetyCritical ?: safetyCritical,
)
