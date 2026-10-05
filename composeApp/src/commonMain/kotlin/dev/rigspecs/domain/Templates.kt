package dev.rigspecs.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One starter field. A new rig gets a blank spec or part for each, so a mechanic fills in
 * values instead of deciding what to record. Stored as JSON in TemplateEntity.itemsJson, so
 * fields here must stay backward compatible (add with defaults, never rename).
 */
@Serializable
data class TemplateItem(
    val kind: Kind,
    val component: String,
    /** Specs only: "Type", "Capacity", "Torque". */
    val name: String = "",
    val unit: String = "",
    val category: SpecCategory? = null,
    val system: PartSystem? = null,
    val safetyCritical: Boolean = false,
) {
    @Serializable
    enum class Kind { SPEC, PART }
}

object TemplateJson {
    private val json = Json { ignoreUnknownKeys = true }
    fun encode(items: List<TemplateItem>): String = json.encodeToString(items)
    fun decode(text: String): List<TemplateItem> = runCatching { json.decodeFromString<List<TemplateItem>>(text) }.getOrDefault(emptyList())
}

/**
 * Built-in templates, seeded once per department under a fixed id per type so every phone
 * shares the same template row after sync instead of each creating its own.
 */
object DefaultTemplates {
    fun id(type: VehicleType) = "default-${type.name.lowercase()}"
    fun name(type: VehicleType) = "${type.label} starter"

    fun forType(type: VehicleType): List<TemplateItem> = when (type) {
        VehicleType.ENGINE -> heavy(pump = true)
        VehicleType.TANKER -> heavy(pump = true)
        VehicleType.BRUSH -> heavy(pump = true)
        VehicleType.LADDER -> heavy(pump = true, aerial = true)
        VehicleType.RESCUE -> heavy()
        VehicleType.AMBULANCE -> ambulance()
        VehicleType.CAR -> car()
        VehicleType.OTHER -> basic()
    }

    private fun spec(category: SpecCategory, component: String, name: String, unit: String = "", critical: Boolean = false) =
        TemplateItem(TemplateItem.Kind.SPEC, component, name, unit, category = category, safetyCritical = critical)

    private fun part(system: PartSystem, component: String) =
        TemplateItem(TemplateItem.Kind.PART, component, system = system)

    private fun fluid(component: String, unit: String) = listOf(
        spec(SpecCategory.FLUID, component, "Type"),
        spec(SpecCategory.CAPACITY, component, "Capacity", unit),
    )

    private fun tires() = listOf(
        spec(SpecCategory.TIRE, "Front tires", "Size"),
        spec(SpecCategory.TIRE, "Front tires", "Pressure", "psi"),
        spec(SpecCategory.TIRE, "Rear tires", "Size"),
        spec(SpecCategory.TIRE, "Rear tires", "Pressure", "psi"),
    )

    private fun lugNuts() = listOf(
        spec(SpecCategory.TORQUE, "Lug nuts", "Torque", "ft-lb", critical = true),
        spec(SpecCategory.TORQUE, "Lug nuts", "Socket size"),
    )

    private fun wipers() = listOf(
        part(PartSystem.BODY, "Wiper blade, driver"),
        part(PartSystem.BODY, "Wiper blade, passenger"),
    )

    /** Fire apparatus on a heavy chassis: diesel, DEF, air brakes. */
    private fun heavy(pump: Boolean = false, aerial: Boolean = false): List<TemplateItem> = buildList {
        addAll(fluid("Engine oil", "qt"))
        addAll(fluid("Coolant", "gal"))
        addAll(fluid("Transmission fluid", "qt"))
        addAll(fluid("DEF", "gal"))
        add(spec(SpecCategory.FLUID, "Power steering fluid", "Type"))
        add(spec(SpecCategory.CAPACITY, "Fuel tank", "Capacity", "gal"))
        if (pump) addAll(fluid("Pump gearbox oil", "qt"))
        if (aerial) addAll(fluid("Aerial hydraulic oil", "gal"))
        addAll(lugNuts())
        if (aerial) add(spec(SpecCategory.TORQUE, "Turntable bolts", "Torque", "ft-lb", critical = true))
        addAll(tires())
        add(spec(SpecCategory.ELECTRICAL, "Batteries", "Group size"))
        add(spec(SpecCategory.ELECTRICAL, "Batteries", "Count"))
        add(part(PartSystem.ENGINE, "Oil filter"))
        add(part(PartSystem.ENGINE, "Fuel filter, primary"))
        add(part(PartSystem.ENGINE, "Fuel filter, secondary"))
        add(part(PartSystem.ENGINE, "Air filter"))
        add(part(PartSystem.ENGINE, "Coolant filter"))
        add(part(PartSystem.ENGINE, "Serpentine belt"))
        add(part(PartSystem.ENGINE, "DEF filter"))
        add(part(PartSystem.TRANSMISSION, "Transmission filter"))
        add(part(PartSystem.BRAKES, "Brake shoes, front"))
        add(part(PartSystem.BRAKES, "Brake shoes, rear"))
        add(part(PartSystem.HVAC, "Cab air filter"))
        addAll(wipers())
    }

    /** Box body on a Ford or Freightliner chassis: hydraulic brakes, often gas. */
    private fun ambulance(): List<TemplateItem> = buildList {
        addAll(fluid("Engine oil", "qt"))
        addAll(fluid("Coolant", "gal"))
        addAll(fluid("Transmission fluid", "qt"))
        add(spec(SpecCategory.FLUID, "Brake fluid", "Type"))
        add(spec(SpecCategory.FLUID, "Power steering fluid", "Type"))
        add(spec(SpecCategory.CAPACITY, "DEF", "Capacity", "gal"))
        add(spec(SpecCategory.CAPACITY, "Fuel tank", "Capacity", "gal"))
        addAll(lugNuts())
        addAll(tires())
        add(spec(SpecCategory.ELECTRICAL, "Chassis batteries", "Group size"))
        add(spec(SpecCategory.ELECTRICAL, "Module batteries", "Group size"))
        add(part(PartSystem.ENGINE, "Oil filter"))
        add(part(PartSystem.ENGINE, "Air filter"))
        add(part(PartSystem.ENGINE, "Fuel filter"))
        add(part(PartSystem.ENGINE, "Serpentine belt"))
        add(part(PartSystem.BRAKES, "Brake pads, front"))
        add(part(PartSystem.BRAKES, "Brake pads, rear"))
        add(part(PartSystem.HVAC, "Cabin air filter"))
        add(part(PartSystem.HVAC, "Module A/C filter"))
        addAll(wipers())
    }

    private fun car(): List<TemplateItem> = buildList {
        addAll(fluid("Engine oil", "qt"))
        addAll(fluid("Coolant", "qt"))
        addAll(fluid("Transmission fluid", "qt"))
        add(spec(SpecCategory.FLUID, "Brake fluid", "Type"))
        addAll(lugNuts())
        addAll(tires())
        add(spec(SpecCategory.ELECTRICAL, "Battery", "Group size"))
        add(part(PartSystem.ENGINE, "Oil filter"))
        add(part(PartSystem.ENGINE, "Engine air filter"))
        add(part(PartSystem.HVAC, "Cabin air filter"))
        add(part(PartSystem.BRAKES, "Brake pads, front"))
        add(part(PartSystem.BRAKES, "Brake pads, rear"))
        addAll(wipers())
    }

    private fun basic(): List<TemplateItem> = buildList {
        addAll(fluid("Engine oil", "qt"))
        addAll(fluid("Coolant", "gal"))
        addAll(lugNuts())
        addAll(tires())
        add(part(PartSystem.ENGINE, "Oil filter"))
        add(part(PartSystem.ENGINE, "Air filter"))
        addAll(wipers())
    }
}
