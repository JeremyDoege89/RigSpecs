package dev.rigspecs.domain

import dev.rigspecs.data.DocumentEntity
import dev.rigspecs.data.PartEntity
import dev.rigspecs.data.SpecItemEntity
import dev.rigspecs.data.VehicleEntity

/** One user-visible field that differs between two versions of a row. */
data class FieldChange(val field: String, val oldValue: String?, val newValue: String?)

/**
 * The fields worth recording in the change log, keyed by a stable name. Sync bookkeeping
 * (updatedAt, updatedBy, dirty) and sortOrder are left out on purpose.
 */
fun VehicleEntity.loggedFields(): Map<String, String?> = mapOf(
    "unitNumber" to unitNumber,
    "type" to type.name,
    "vin" to vin,
    "year" to year?.toString(),
    "make" to make,
    "model" to model,
    "chassisMake" to chassisMake,
    "bodyBuilder" to bodyBuilder,
    "builderJobNumber" to builderJobNumber,
    "engine" to engine,
    "engineSerial" to engineSerial,
    "station" to station,
    "photoPath" to photoPath,
    "notes" to notes,
    "deleted" to deleted.toString(),
)

fun SpecItemEntity.loggedFields(): Map<String, String?> = mapOf(
    "category" to category.name,
    "component" to component,
    "name" to name,
    "value" to value,
    "unit" to unit,
    "notes" to notes,
    "sourceDocId" to sourceDocId,
    "pageHint" to pageHint,
    "safetyCritical" to safetyCritical.toString(),
    "status" to status.name,
    "sourceKind" to sourceKind.name,
    "sourceUrl" to sourceUrl,
    "deleted" to deleted.toString(),
)

fun PartEntity.loggedFields(): Map<String, String?> = mapOf(
    "system" to system.name,
    "component" to component,
    "oemNumber" to oemNumber,
    "aftermarketNumbers" to aftermarketNumbers,
    "supplier" to supplier,
    "qtyPerVehicle" to qtyPerVehicle?.toString(),
    "notes" to notes,
    "sourceDocId" to sourceDocId,
    "pageHint" to pageHint,
    "status" to status.name,
    "sourceKind" to sourceKind.name,
    "sourceUrl" to sourceUrl,
    "deleted" to deleted.toString(),
)

fun DocumentEntity.loggedFields(): Map<String, String?> = mapOf(
    "title" to title,
    "kind" to kind.name,
    "fileUrl" to fileUrl,
    "pageHint" to pageHint,
    "deleted" to deleted.toString(),
)

private val NewRowDefaults = setOf("deleted" to "false", "status" to "VERIFIED", "sourceKind" to "MANUAL")

/**
 * Fields that changed from [old] to [new]. A brand-new row ([old] null) logs every non-blank
 * field, so the history also says who entered a value first. Blank and null count as equal.
 */
fun diffFields(old: Map<String, String?>?, new: Map<String, String?>): List<FieldChange> {
    fun norm(v: String?) = v?.takeIf { it.isNotEmpty() }
    return new.mapNotNull { (field, value) ->
        val before = norm(old?.get(field))
        val after = norm(value)
        when {
            before == after -> null
            // A new row's defaults say nothing: not deleted, verified, entered by hand.
            old == null && (field to after) in NewRowDefaults -> null
            else -> FieldChange(field, before, after)
        }
    }
}
