package dev.rigspecs.domain

import dev.rigspecs.data.PartEntity
import dev.rigspecs.data.SpecItemEntity
import dev.rigspecs.data.VehicleEntity
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun vehicle(
    unit: String,
    type: VehicleType = VehicleType.ENGINE,
    station: String = "",
    make: String = "",
    vin: String = "",
) = VehicleEntity(
    id = unit, departmentId = "d", unitNumber = unit, type = type, station = station, make = make, vin = vin,
    updatedAt = 0, updatedBy = "u",
)

private fun spec(value: String = "450", unit: String = "ft-lb", critical: Boolean = false, source: String? = null) =
    SpecItemEntity(
        id = "s", departmentId = "d", vehicleId = "v", category = SpecCategory.TORQUE, component = "Lug nuts",
        name = "Torque", value = value, unit = unit, safetyCritical = critical, sourceDocId = source,
        updatedAt = 0, updatedBy = "u",
    )

class FleetFilterTest {
    private val fleet = listOf(
        vehicle("Engine 2", station = "Station 1", make = "Pierce"),
        vehicle("Medic 3", VehicleType.AMBULANCE, station = "Station 2", make = "Ford"),
        vehicle("Ladder 1", VehicleType.LADDER, station = "Station 1", make = "E-One"),
        vehicle("Car 10", VehicleType.CAR, station = "Station 10", vin = "1FTFW1E50PFA12345"),
    )

    @Test
    fun wordsMatchInAnyOrderAndField() {
        assertEquals(listOf("Medic 3"), fleet.filterFleet(FleetFilter("3 medic")).map { it.unitNumber })
        assertEquals(listOf("Medic 3"), fleet.filterFleet(FleetFilter("ford station 2")).map { it.unitNumber })
        assertEquals(listOf("Car 10"), fleet.filterFleet(FleetFilter("pfa123")).map { it.unitNumber })
    }

    @Test
    fun typeAndStationFiltersCombine() {
        val f = FleetFilter(types = setOf(VehicleType.ENGINE, VehicleType.LADDER), stations = setOf("Station 1"))
        assertEquals(listOf("Engine 2", "Ladder 1"), fleet.filterFleet(f).map { it.unitNumber })
        assertEquals(4, fleet.filterFleet(FleetFilter("  ")).size)
    }

    @Test
    fun stationsSortNaturally() {
        assertEquals(listOf("Station 1", "Station 2", "Station 10"), fleet.stations())
        assertEquals(
            listOf("Engine 2", "Engine 10", "Medic 3"),
            listOf("Engine 10", "Medic 3", "Engine 2").sortedWith(naturalOrder),
        )
    }
}

class ChangesTest {
    @Test
    fun newRowLogsOnlyFilledFields() {
        val changes = diffFields(null, spec().loggedFields())
        val fields = changes.map { it.field }.toSet()
        assertEquals(setOf("category", "component", "name", "value", "unit", "safetyCritical"), fields)
        assertTrue(changes.all { it.oldValue == null })
    }

    @Test
    fun editLogsOldAndNewValue() {
        val changes = diffFields(spec().loggedFields(), spec(value = "475").loggedFields())
        assertEquals(listOf(FieldChange("value", "450", "475")), changes)
    }

    @Test
    fun blankAndNullAreEqual() {
        val a = spec(unit = "").loggedFields()
        val b = a + ("unit" to null)
        assertEquals(emptyList(), diffFields(a, b))
    }

    @Test
    fun tombstoneLogsDeleted() {
        val v = vehicle("Engine 2")
        val changes = diffFields(v.loggedFields(), v.copy(deleted = true).loggedFields())
        assertEquals(listOf(FieldChange("deleted", "false", "true")), changes)
    }
}

class FormsTest {
    @Test
    fun safetyCriticalSpecNeedsSource() {
        assertEquals(setOf("source"), validateSpec(spec(critical = true)).keys)
        assertTrue(validateSpec(spec(critical = true, source = "doc")).isEmpty())
        assertTrue(validateSpec(spec()).isEmpty())
    }

    @Test
    fun specNeedsComponentNameAndValue() {
        val blank = spec(value = " ").copy(component = "", name = "")
        assertEquals(setOf("component", "name", "value"), validateSpec(blank).keys)
    }

    @Test
    fun vehicleRules() {
        assertEquals(setOf("unitNumber"), validateVehicle(vehicle(" "), 2026).keys)
        assertEquals(setOf("year"), validateVehicle(vehicle("E2").copy(year = 2030), 2026).keys)
        assertTrue(validateVehicle(vehicle("E2").copy(year = 2027), 2026).isEmpty())
        assertEquals(2, vehicleWarnings(vehicle("E2", vin = "1FTOW")).size)
        assertTrue(vehicleWarnings(vehicle("E2", vin = "1FTFW1E50PFA12345")).isEmpty())
    }

    @Test
    fun optionalInt() {
        assertEquals(null, parseOptionalInt(" ").getOrThrow())
        assertEquals(4, parseOptionalInt(" 4 ").getOrThrow())
        assertTrue(parseOptionalInt("4x").isFailure)
    }

    @Test
    fun displayAndCopyText() {
        assertEquals("450 ft-lb", spec().displayValue())
        assertEquals("15W-40", spec(value = "15W-40 ", unit = "").displayValue())
        val part = PartEntity(
            id = "p", departmentId = "d", vehicleId = "v", system = PartSystem.ENGINE, component = "Oil filter",
            aftermarketNumbers = "\n NAPA 1792\nWix 51792", updatedAt = 0, updatedBy = "u",
        )
        assertEquals("NAPA 1792", part.copyText())
        assertEquals("LF9009", part.copy(oemNumber = " LF9009 ").copyText())
        assertEquals("2019 Pierce", vehicle("E2", make = "Pierce").copy(year = 2019).description())
    }

    @Test
    fun timestampFormat() {
        // 2026-10-05T14:07:00Z
        assertEquals("Oct 5, 2026 14:07", formatTimestamp(1_791_209_220_000, TimeZone.UTC))
    }
}
