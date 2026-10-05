package dev.rigspecs.domain

import dev.rigspecs.data.ImportRowEntity
import dev.rigspecs.data.ImportRowKind
import dev.rigspecs.data.PartEntity
import dev.rigspecs.data.SpecItemEntity
import dev.rigspecs.data.VehicleEntity
import dev.rigspecs.data.importing.formatPageRanges
import dev.rigspecs.data.importing.parsePageRanges
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun spec(
    value: String = "450",
    unit: String = "ft-lb",
    status: VerificationStatus = VerificationStatus.VERIFIED,
    kind: SourceKind = SourceKind.MANUAL,
    component: String = "Lug nuts",
    name: String = "Torque",
) = SpecItemEntity(
    id = "s", departmentId = "d", vehicleId = "v", category = SpecCategory.TORQUE, component = component, name = name,
    value = value, unit = unit, status = status, sourceKind = kind, sourceUrl = if (kind == SourceKind.OPENLABOR) "https://x" else "",
    verifiedBy = "old", verifiedAt = 1, updatedAt = 0, updatedBy = "u",
)

private fun part(oem: String = "", refs: String = "", component: String = "Oil filter", status: VerificationStatus = VerificationStatus.VERIFIED) =
    PartEntity(
        id = "p", departmentId = "d", vehicleId = "v", system = PartSystem.ENGINE, component = component,
        oemNumber = oem, aftermarketNumbers = refs, status = status, updatedAt = 0, updatedBy = "u",
    )

private fun staged(kind: ImportRowKind, component: String, name: String = "", value: String = "", unit: String = "", oem: String = "", refs: String = "") =
    ImportRowEntity(
        id = "r", departmentId = "d", jobId = "j", vehicleId = "v", kind = kind, component = component, name = name,
        value = value, unit = unit, oemNumber = oem, aftermarketNumbers = refs, updatedAt = 0, updatedBy = "u",
    )

class VerificationRulesTest {
    @Test
    fun typingAValueStampsTheEditor() {
        val saved = stampSpecSave(spec(), spec(value = "475"), confirm = true, userId = "mech", now = 99)
        assertEquals(VerificationStatus.VERIFIED, saved.status)
        assertEquals("mech" to 99L, saved.verifiedBy to saved.verifiedAt)
    }

    @Test
    fun notesOnlyKeepTheOriginalStamp() {
        val saved = stampSpecSave(spec(), spec().copy(notes = "dry threads"), confirm = true, userId = "mech", now = 99)
        assertEquals("old" to 1L, saved.verifiedBy to saved.verifiedAt)
    }

    @Test
    fun unverifiedStaysUnlessConfirmed() {
        val sourced = spec(status = VerificationStatus.UNVERIFIED, kind = SourceKind.OPENLABOR)
        val kept = stampSpecSave(sourced, sourced.copy(notes = "?"), confirm = false, userId = "mech", now = 99)
        assertEquals(VerificationStatus.UNVERIFIED, kept.status)
        assertEquals(SourceKind.OPENLABOR, kept.sourceKind)

        val confirmed = stampSpecSave(sourced, sourced, confirm = true, userId = "mech", now = 99)
        assertEquals(VerificationStatus.VERIFIED, confirmed.status)
        assertEquals("mech", confirmed.verifiedBy)
        // Confirming as-is keeps the provenance.
        assertEquals(SourceKind.OPENLABOR, confirmed.sourceKind)
        assertEquals("https://x", confirmed.sourceUrl)
    }

    @Test
    fun changingASourcedValueMakesItManual() {
        val sourced = spec(status = VerificationStatus.UNVERIFIED, kind = SourceKind.OPENLABOR)
        val fixed = stampSpecSave(sourced, sourced.copy(value = "480"), confirm = true, userId = "mech", now = 99)
        assertEquals(SourceKind.MANUAL, fixed.sourceKind)
        assertEquals("", fixed.sourceUrl)
        // A correction read off an imported page keeps citing the page.
        val imported = spec(kind = SourceKind.IMPORT).copy(sourceDocId = "doc", pageHint = "p. 4")
        val corrected = stampSpecSave(imported, imported.copy(value = "480"), true, "mech", 99)
        assertEquals(SourceKind.IMPORT, corrected.sourceKind)
        assertEquals("doc", corrected.sourceDocId)
    }

    @Test
    fun blanksCarryNoStamp() {
        val blank = stampSpecSave(null, spec(value = ""), confirm = true, userId = "mech", now = 99)
        assertEquals("" to null, blank.verifiedBy to blank.verifiedAt)
        val partBlank = stampPartSave(null, part(), true, "mech", 99)
        assertEquals("" to null, partBlank.verifiedBy to partBlank.verifiedAt)
        val partFilled = stampPartSave(part(), part(oem = "LF9009"), true, "mech", 99)
        assertEquals("mech" to 99L, partFilled.verifiedBy to partFilled.verifiedAt)
    }
}

class ImportMatchTest {
    private val specs = listOf(
        spec(value = "", component = "Lug nuts", name = "Torque").copy(id = "blank"),
        spec(value = "24", unit = "qt", component = "Engine oil", name = "Capacity").copy(id = "oil"),
    )

    @Test
    fun specMatching() {
        assertEquals(ImportMatch.FillsBlank("blank"), matchImportRow(staged(ImportRowKind.SPEC, "lug nuts", "torque", "450", "ft-lb"), specs, emptyList()))
        assertEquals(ImportMatch.Same("oil"), matchImportRow(staged(ImportRowKind.SPEC, "Engine Oil", "Capacity", "24", "QT"), specs, emptyList()))
        val conflict = matchImportRow(staged(ImportRowKind.SPEC, "Engine oil", "Capacity", "26", "qt"), specs, emptyList())
        assertEquals(ImportMatch.Conflict("oil", "24 qt"), conflict)
        assertEquals(ImportMatch.New, matchImportRow(staged(ImportRowKind.SPEC, "Coolant", "Capacity", "12", "gal"), specs, emptyList()))
    }

    @Test
    fun partMatching() {
        val parts = listOf(part(component = "Oil filter").copy(id = "blank"), part(oem = "LF-9009", refs = "NAPA 1792", component = "Fuel filter").copy(id = "fuel"))
        assertEquals(ImportMatch.FillsBlank("blank"), matchImportRow(staged(ImportRowKind.PART, "Oil Filter", oem = "LF9009"), emptyList(), parts))
        assertEquals(ImportMatch.Same("fuel"), matchImportRow(staged(ImportRowKind.PART, "Fuel filter", oem = "lf9009"), emptyList(), parts))
        assertIs<ImportMatch.Conflict>(matchImportRow(staged(ImportRowKind.PART, "Fuel filter", oem = "FS1022"), emptyList(), parts))
    }

    @Test
    fun contentKeysIgnoreFormatting() {
        val a = staged(ImportRowKind.SPEC, "Lug nuts", "Torque", "450", "ft-lb")
        assertEquals(a.contentKey(), a.copy(component = "LUG NUTS", unit = "ft lb", page = 9).contentKey())
        assertTrue(a.contentKey() != a.copy(value = "475").contentKey())
        val p = staged(ImportRowKind.PART, "Oil filter", oem = "LF9009", refs = "NAPA 1792\nWix 51792")
        assertEquals(p.contentKey(), p.copy(aftermarketNumbers = "WIX-51792\nnapa 1792").contentKey())
    }

    @Test
    fun safetyKeywordsMatchWordStarts() {
        assertTrue(isSafetyCritical("Front brake chamber"))
        assertTrue(isSafetyCritical("Lug nuts", "Torque"))
        assertTrue(isSafetyCritical("Aerial turntable bolts"))
        assertTrue(isSafetyCritical("Fire pump", "Packing"))
        assertFalse(isSafetyCritical("Fuel filter, primary"))
        assertFalse(isSafetyCritical("Trim panel"))
        assertFalse(isSafetyCritical("Engine oil", "Capacity"))
    }

    @Test
    fun displayAndPageHints() {
        assertEquals("450 ft-lb", staged(ImportRowKind.SPEC, "Lug nuts", "Torque", "450", "ft-lb").displayValue())
        assertEquals("LF9009 · NAPA 1792", staged(ImportRowKind.PART, "Oil filter", oem = "LF9009", refs = "NAPA 1792").displayValue())
        assertEquals("p. 212", pageHint(212))
        assertEquals("", pageHint(null))
    }
}

class PageRangeTest {
    @Test
    fun parsing() {
        assertEquals(listOf(1, 2, 3), parsePageRanges("", 3))
        assertEquals(listOf(1, 2, 3, 7, 210, 211), parsePageRanges(" 7, 1-3; 210 – 211, 2", 300))
        assertNull(parsePageRanges("5-3", 10))
        assertNull(parsePageRanges("0", 10))
        assertNull(parsePageRanges("11", 10))
        assertNull(parsePageRanges("abc", 10))
    }

    @Test
    fun formatting() {
        assertEquals("1-5, 7, 210-211", formatPageRanges(listOf(1, 2, 3, 4, 5, 7, 210, 211)))
        assertEquals("4", formatPageRanges(listOf(4)))
        assertEquals("", formatPageRanges(emptyList()))
    }
}

class PortalsTest {
    private fun rig(
        make: String = "",
        chassis: String = "",
        engine: String = "",
        esn: String = "",
        job: String = "",
        vin: String = "",
        decode: String = "",
    ) = VehicleEntity(
        id = "v", departmentId = "d", unitNumber = "E2", type = VehicleType.ENGINE, make = make, chassisMake = chassis,
        engine = engine, engineSerial = esn, builderJobNumber = job, vin = vin, vinDecodeJson = decode,
        updatedAt = 0, updatedBy = "u",
    )

    @Test
    fun whichPortalsApply() {
        val pierce = rig(make = "Pierce", engine = "Cummins X12 500 hp")
        assertEquals(setOf(Portal.CUMMINS, Portal.PIERCE), Portal.entries.filter { it.appliesTo(pierce) }.toSet())
        val ambulance = rig(make = "Ford", chassis = "Ford")
        assertTrue(Portal.entries.none { it.appliesTo(ambulance) })
        val freightliner = rig(make = "Freightliner", engine = "Detroit DD13")
        assertEquals(listOf(Portal.FREIGHTLINER), Portal.entries.filter { it.appliesTo(freightliner) })
        // Engine family alone, or from the VIN decode.
        assertTrue(Portal.CUMMINS.appliesTo(rig(engine = "ISL 9")))
        assertTrue(Portal.CUMMINS.appliesTo(rig(decode = """{"EngineModel":"ISL","EngineManufacturer":""}""")))
        assertFalse(Portal.CUMMINS.appliesTo(rig(engine = "Ford 6.7L Power Stroke")))
    }

    @Test
    fun identifiersAndTemplates() {
        val v = rig(make = "Pierce", esn = "79012323", job = "SO 34567", vin = "4P1CT02S5KA000000")
        assertEquals("79012323", Portal.CUMMINS.identifier(v))
        assertEquals("SO 34567", Portal.PIERCE.identifier(v))
        assertEquals("https://p.example/bom?job=SO%2034567&x={nope}", expandPortalUrl("https://p.example/bom?job={job}&x={nope}", v))
        assertEquals("https://q.example/esn/79012323", expandPortalUrl(" https://q.example/esn/{esn} ", v))
        assertEquals("%C3%A9-._~a1", urlEncode("é-._~a1"))
    }

    @Test
    fun onlyWebLinks() {
        assertTrue(isAllowedPortalUrl("https://quickserve.cummins.com/"))
        assertFalse(isAllowedPortalUrl("file:///etc/passwd"))
        assertFalse(isAllowedPortalUrl("intent://scan#Intent;end"))
        assertFalse(isAllowedPortalUrl("https://bad url"))
    }
}
