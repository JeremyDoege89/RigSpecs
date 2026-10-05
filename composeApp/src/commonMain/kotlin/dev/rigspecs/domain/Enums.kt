package dev.rigspecs.domain

/** Kinds of rig in the fleet. Stored by name, so renaming a constant needs a migration. */
enum class VehicleType(val label: String) {
    ENGINE("Engine"),
    LADDER("Ladder / aerial"),
    TANKER("Tanker"),
    RESCUE("Rescue"),
    BRUSH("Brush"),
    AMBULANCE("Ambulance"),
    CAR("Car / SUV"),
    OTHER("Other"),
}

/** Which profile tab a spec lands on is decided by [ProfileTab.categories]. */
enum class SpecCategory(val label: String) {
    FLUID("Fluid"),
    CAPACITY("Capacity"),
    TORQUE("Torque"),
    ELECTRICAL("Electrical"),
    TIRE("Tire"),
    OTHER("Other"),
}

enum class PartSystem(val label: String) {
    ENGINE("Engine"),
    TRANSMISSION("Transmission"),
    BRAKES("Brakes"),
    SUSPENSION("Suspension / steering"),
    ELECTRICAL("Electrical"),
    HVAC("HVAC"),
    PUMP("Pump"),
    AERIAL("Aerial"),
    BODY("Body"),
    CHASSIS("Chassis"),
    OTHER("Other"),
}

enum class DocumentKind(val label: String) {
    MANUAL("Manual"),
    BUILD_SHEET("Build sheet"),
    INVOICE("Invoice"),
    PHOTO("Photo"),
    OTHER("Other"),
}

enum class ProfileTab(val label: String, val categories: Set<SpecCategory>) {
    OVERVIEW("Overview", setOf(SpecCategory.ELECTRICAL, SpecCategory.TIRE, SpecCategory.OTHER)),
    FLUIDS("Fluids", setOf(SpecCategory.FLUID, SpecCategory.CAPACITY)),
    TORQUE("Torque", setOf(SpecCategory.TORQUE)),
    PARTS("Parts", emptySet()),
    DOCS("Docs", emptySet()),
}

/** The category a new spec gets when it is added from [tab]. */
fun ProfileTab.defaultCategory(): SpecCategory = when (this) {
    ProfileTab.FLUIDS -> SpecCategory.FLUID
    ProfileTab.TORQUE -> SpecCategory.TORQUE
    else -> SpecCategory.OTHER
}

/**
 * The trust model: anything a person didn't enter or confirm themselves (an API lookup, a
 * model's extraction) is UNVERIFIED until an editor confirms it.
 */
enum class VerificationStatus { VERIFIED, UNVERIFIED }

/** Where a spec or part value came from. Stored by name. */
enum class SourceKind(val label: String) {
    MANUAL("Entered by hand"),
    VPIC("NHTSA VIN decode"),
    OPENLABOR("Open Labor Project"),
    IMPORT("Imported from a document"),
}

enum class ImportJobStatus { QUEUED, RUNNING, REVIEW, DONE, FAILED, CANCELLED }

/** A staged row from an import: nothing reaches the profile until it is CONFIRMED. */
enum class ImportRowState {
    PENDING,
    CONFIRMED,
    REJECTED,

    /** Same as a row already confirmed into the profile; hidden from review. */
    DUPLICATE,

    /** Replaced by a later re-run of the same document. */
    SUPERSEDED,
}

/** Entity names used in [dev.rigspecs.data.ChangeLogEntity.entityType]. */
object EntityType {
    const val VEHICLE = "vehicle"
    const val SPEC = "spec"
    const val PART = "part"
    const val DOCUMENT = "document"
    const val SETTING = "setting"
}
