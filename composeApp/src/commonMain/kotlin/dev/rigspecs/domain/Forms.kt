package dev.rigspecs.domain

import dev.rigspecs.data.PartEntity
import dev.rigspecs.data.SpecItemEntity
import dev.rigspecs.data.VehicleEntity

/** Field name -> message. Empty means the form can be saved. */
typealias FormErrors = Map<String, String>

fun validateVehicle(v: VehicleEntity, currentYear: Int): FormErrors = buildMap {
    if (v.unitNumber.isBlank()) put("unitNumber", "Unit number is required")
    v.year?.let { if (it < 1900 || it > currentYear + 2) put("year", "Year looks wrong") }
}

/** Plain warnings that don't block saving. */
fun vehicleWarnings(v: VehicleEntity): List<String> = buildList {
    val vin = v.vin.trim()
    // Pre-1981 and some imported chassis have shorter serials, so a short VIN only warns.
    if (vin.isNotEmpty() && vin.length != 17) add("VINs are usually 17 characters (this one is ${vin.length})")
    if (vin.any { it in "IOQioq" }) add("VINs never use the letters I, O or Q; check for 1 and 0")
}

fun validateSpec(s: SpecItemEntity): FormErrors = buildMap {
    if (s.component.isBlank()) put("component", "What is this for? e.g. Engine oil, Lug nuts")
    if (s.name.isBlank()) put("name", "Which property? e.g. Capacity, Torque, Type")
    if (s.value.isBlank()) put("value", "Value is required")
    if (s.safetyCritical && s.sourceDocId == null) {
        put("source", "Safety-critical specs need a source document")
    }
}

fun validatePart(p: PartEntity): FormErrors = buildMap {
    if (p.component.isBlank()) put("component", "What part is this? e.g. Oil filter")
    p.qtyPerVehicle?.let { if (it < 1) put("qty", "Quantity must be at least 1") }
}

/** Text parsed from an optional whole-number field: blank -> null, junk -> error. */
fun parseOptionalInt(text: String): Result<Int?> {
    val t = text.trim()
    if (t.isEmpty()) return Result.success(null)
    return t.toIntOrNull()?.let { Result.success(it) } ?: Result.failure(IllegalArgumentException(t))
}

/** "450 ft-lb", "24 qt", "15W-40". */
fun SpecItemEntity.displayValue(): String =
    if (unit.isBlank()) value.trim() else "${value.trim()} ${unit.trim()}"

/** What copying a part puts on the clipboard: the OEM number, else the first cross-reference. */
fun PartEntity.copyText(): String =
    oemNumber.trim().ifEmpty { aftermarketNumbers.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: "" }

/** "2019 Pierce Enforcer", falling back to whatever parts are filled. */
fun VehicleEntity.description(): String =
    listOfNotNull(year?.toString(), make.trim().ifEmpty { null }, model.trim().ifEmpty { null })
        .joinToString(" ")
