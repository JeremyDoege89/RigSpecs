package dev.rigspecs.domain

import dev.rigspecs.data.ImportRowEntity
import dev.rigspecs.data.ImportRowKind
import dev.rigspecs.data.VehicleEntity

/**
 * One value from outside the app (an API lookup or a document extraction) before anyone has
 * checked it. Sources produce these; the repository turns them into UNVERIFIED profile rows
 * (API sources) or staged import rows (documents).
 */
data class CandidateRow(
    val kind: ImportRowKind,
    val component: String,
    val name: String = "",
    val value: String = "",
    val unit: String = "",
    val category: SpecCategory? = null,
    val system: PartSystem? = null,
    val oemNumber: String = "",
    val aftermarketNumbers: List<String> = emptyList(),
    val qtyPerVehicle: Int? = null,
    val notes: String = "",
    /** 1-based page of the source document, for extractions. */
    val page: Int? = null,
    /** The words the value was read from. */
    val evidence: String = "",
    /** The page or API call behind the value, for API sources. */
    val sourceUrl: String = "",
    val safetyCritical: Boolean = false,
) {
    /** Usable at all: a spec needs a component, a property and a value; a part needs a number. */
    val isUsable: Boolean
        get() = component.isNotBlank() && when (kind) {
            ImportRowKind.SPEC -> name.isNotBlank() && value.isNotBlank()
            ImportRowKind.PART -> oemNumber.isNotBlank() || aftermarketNumbers.any { it.isNotBlank() }
        }
}

/**
 * An outside database that can fill specs for a rig. Adding a source (Open Labor Project now,
 * MOTOR or Mitchell1 later) is a new implementation of this, not a change to the app.
 */
interface SpecSource {
    val kind: SourceKind
    val label: String

    /** Whether the source covers this rig at all (Open Labor covers cars and light trucks). */
    fun appliesTo(vehicle: VehicleEntity): Boolean

    /** Everything the source knows about the rig. Every row lands UNVERIFIED. */
    suspend fun lookup(vehicle: VehicleEntity): List<CandidateRow>
}

/** What adding a source's rows did to a profile. */
data class SourcedResult(
    val added: Int,
    val filled: Int,
    val alreadyThere: Int,
    /** Rows that disagree with a value already in the profile; the profile value is kept. */
    val conflicts: List<SourcedConflict>,
)

data class SourcedConflict(val what: String, val profileValue: String, val sourceValue: String)

fun CandidateRow.toImportRow(
    id: String,
    departmentId: String,
    jobId: String,
    vehicleId: String,
    now: Long,
    userId: String,
    sortOrder: Int = 0,
) = ImportRowEntity(
    id = id,
    departmentId = departmentId,
    jobId = jobId,
    vehicleId = vehicleId,
    kind = kind,
    category = if (kind == ImportRowKind.SPEC) category ?: SpecCategory.OTHER else null,
    system = if (kind == ImportRowKind.PART) system ?: PartSystem.OTHER else null,
    component = component.trim(),
    name = name.trim(),
    value = value.trim(),
    unit = unit.trim(),
    oemNumber = oemNumber.trim(),
    aftermarketNumbers = aftermarketNumbers.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n"),
    qtyPerVehicle = qtyPerVehicle,
    notes = notes.trim(),
    page = page,
    evidence = evidence.trim(),
    safetyCritical = safetyCritical || isSafetyCritical(component, name),
    sortOrder = sortOrder,
    updatedAt = now,
    updatedBy = userId,
)
