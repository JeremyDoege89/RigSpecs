package dev.rigspecs.domain

import dev.rigspecs.data.DocumentEntity
import dev.rigspecs.data.ImportRowKind
import dev.rigspecs.data.PartEntity
import dev.rigspecs.data.SpecItemEntity
import dev.rigspecs.data.VehicleEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun rig(id: String, unit: String, type: VehicleType = VehicleType.AMBULANCE, make: String = "Ford", station: String = "") =
    VehicleEntity(id = id, departmentId = "d", unitNumber = unit, type = type, make = make, station = station, updatedAt = 0, updatedBy = "u")

private fun spec(id: String, rig: String, component: String, name: String, value: String, unit: String = "", category: SpecCategory = SpecCategory.TORQUE, status: VerificationStatus = VerificationStatus.VERIFIED) =
    SpecItemEntity(id = id, departmentId = "d", vehicleId = rig, category = category, component = component, name = name, value = value, unit = unit, status = status, updatedAt = 0, updatedBy = "u")

private fun part(id: String, rig: String, component: String, oem: String, refs: String = "") =
    PartEntity(id = id, departmentId = "d", vehicleId = rig, system = PartSystem.ENGINE, component = component, oemNumber = oem, aftermarketNumbers = refs, updatedAt = 0, updatedBy = "u")

class SearchTest {
    private val rigs = listOf(
        rig("m3", "Medic 3", station = "Station 2"),
        rig("m30", "Medic 30"),
        rig("e2", "Engine 2", VehicleType.ENGINE, make = "Pierce", station = "Station 1"),
    )
    private val specs = listOf(
        spec("s1", "m3", "Lug nuts", "Torque", "165", "ft-lb"),
        spec("s2", "e2", "Lug nuts", "Torque", "450-500", "ft-lb", status = VerificationStatus.UNVERIFIED),
        spec("s3", "e2", "Engine oil", "Capacity", "40", "qt", SpecCategory.CAPACITY),
        spec("blank", "m30", "Lug nuts", "Torque", ""),
    )
    private val parts = listOf(
        part("p1", "m3", "Oil filter", "FL-2051S", "NAPA 1515"),
        part("p2", "m30", "Oil filter", "FL-2051S"),
        part("p3", "e2", "Oil filter", "LF9009", "NAPA 1792\nWix 51792"),
    )
    private val index = SearchIndex(rigs, specs, parts)

    private fun hits(q: String) = index.search(q).flatMap { g -> g.hits.map { g.vehicle.unitNumber to (it.spec?.id ?: it.part?.id) } }

    @Test
    fun rigAndItemWordsCombine() {
        // "3" matches Medic 3 exactly, not Medic 30.
        assertEquals(listOf("Medic 3" to "p1"), hits("oil filter medic 3"))
        assertEquals(listOf("Medic 3" to "p1"), hits("medic 3 filter"))
    }

    @Test
    fun valuesAcrossEveryRig() {
        val torque = hits("lug nut torque")
        assertEquals(setOf("Medic 3" to "s1", "Engine 2" to "s2"), torque.toSet())
        // Blanks are never results.
        assertTrue(torque.none { it.second == "blank" })
    }

    @Test
    fun partNumbersIgnorePunctuationAndCase() {
        assertEquals(listOf("Engine 2" to "p3"), hits("lf9009"))
        assertEquals(listOf("Engine 2" to "p3"), hits("LF-9009"))
        assertEquals(setOf("Medic 3" to "p1", "Medic 30" to "p2"), hits("fl2051s").toSet())
        assertEquals(listOf("Engine 2" to "p3"), hits("wix 51792"))
    }

    @Test
    fun aRigNameAloneFindsTheRigNotEveryField() {
        val groups = index.search("medic 3")
        assertEquals(1, groups.size)
        assertTrue(groups.single().rigMatches)
        assertTrue(groups.single().hits.isEmpty())
        assertTrue(index.search("   ").isEmpty())
        assertTrue(index.search("nothing like this").isEmpty())
    }

    @Test
    fun aQueryThatNamesARigStaysOnThatRig() {
        // "engine" is also in Engine oil and the Engine parts system, and Medic 3 is at Station 2.
        val named = index.search("engine 2")
        assertEquals(listOf("e2"), named.map { it.vehicle.id })
        assertTrue(named.single().rigMatches)
        assertTrue(named.single().hits.isEmpty())
        assertEquals(listOf("Engine 2" to "s3"), hits("engine 2 oil capacity"))
        assertEquals(listOf("Engine 2" to "p3"), hits("filter engine2"))
        // Not a unit number, so it's an ordinary search across rigs.
        assertEquals(setOf("Medic 3" to "p1", "Medic 30" to "p2", "Engine 2" to "p3"), hits("oil filter").toSet())
        // A unit number without a digit doesn't take over: a rig called Air, and "air filter".
        val withAir = SearchIndex(
            rigs + rig("air", "Air"),
            specs,
            parts + part("p4", "air", "Air filter", "P608667") + part("p5", "e2", "Air filter", "P181064"),
        )
        assertEquals(setOf("air", "e2"), withAir.search("air filter").map { it.vehicle.id }.toSet())
    }

    @Test
    fun unverifiedHitsAreFlagged() {
        val e2 = index.search("lug torque").single { it.vehicle.id == "e2" }
        assertTrue(e2.hits.single().isUnverified)
        assertEquals(ProfileTab.TORQUE, e2.hits.single().spec!!.profileTab())
        assertEquals(ProfileTab.FLUIDS, specs.single { it.id == "s3" }.profileTab())
    }

    @Test
    fun tokensKeepBothSpellings() {
        assertEquals(setOf("lf9009", "lf", "9009"), searchTokens("LF-9009"))
        assertEquals(listOf("15w40", "oil"), queryWords("15W-40  oil"))
    }
}

class CsvTest {
    @Test
    fun parsesQuotesCommasNewlinesAndExcelQuirks() {
        val text = "﻿a,b,c\r\n\"x, y\",\"say \"\"hi\"\"\",\"two\nlines\"\r\n1,,3\r\n\r\n"
        assertEquals(listOf(listOf("a", "b", "c"), listOf("x, y", "say \"hi\"", "two\nlines"), listOf("1", "", "3")), parseCsv(text))
        assertEquals(listOf(listOf("lone", "cr")), parseCsv("lone,cr\r"))
    }

    @Test
    fun writeThenParseRoundTrips() {
        val rows = listOf(listOf("plain", "with, comma", "with \"quote\"", "multi\nline", ""))
        assertEquals(rows, parseCsv(writeCsv(rows)))
        assertTrue(writeCsv(rows).endsWith("\r\n"))
    }

    @Test
    fun exportIncludesBlanksStatusAndSources() {
        val doc = DocumentEntity(id = "doc", departmentId = "d", vehicleId = "v", title = "Pierce manual", kind = DocumentKind.MANUAL, updatedAt = 0, updatedBy = "u")
        val csv = exportRigCsv(
            listOf(
                spec("s1", "v", "Lug nuts", "Torque", "450", "ft-lb").copy(safetyCritical = true, sourceDocId = "doc", pageHint = "p. 4", verifiedBy = "mech", verifiedAt = 0),
                spec("s2", "v", "Coolant", "Capacity", "", "gal", SpecCategory.CAPACITY).copy(sortOrder = 1),
            ),
            listOf(part("p1", "v", "Oil filter", "LF9009", "NAPA 1792\nWix 51792")),
            mapOf("doc" to doc),
        )
        val rows = parseCsv(csv)
        assertEquals(RigCsvColumns, rows[0])
        assertEquals(
            listOf("spec", "TORQUE", "", "Lug nuts", "Torque", "450", "ft-lb", "", "", "", "", "yes", "verified", "Pierce manual", "p. 4", "mech", "1970-01-01T00:00:00Z"),
            rows[1],
        )
        assertEquals("", rows[2][5]) // the blank is exported so it can be filled in
        assertEquals(listOf("part", "", "ENGINE", "Oil filter"), rows[3].take(4))
        assertEquals("NAPA 1792; Wix 51792", rows[3][8])
        assertEquals("Engine-2-specs.csv", csvFileName(" Engine 2 "))
    }

    @Test
    fun importReadsOurExportAndOtherSheets() {
        // An exported sheet with two blanks filled in and one left blank.
        val ours = writeCsv(
            listOf(
                RigCsvColumns,
                listOf("spec", "TORQUE", "", "Lug nuts", "Torque", "450", "ft-lb", "", "", "", "", "yes", "verified", "", "", "", ""),
                listOf("spec", "CAPACITY", "", "Coolant", "Capacity", "", "gal", "", "", "", "", "", "verified", "", "", "", ""),
                listOf("part", "", "ENGINE", "Oil filter", "", "", "", "LF9009", "NAPA 1792; Wix 51792", "1", "", "", "", "", "", "", ""),
            ),
        )
        val parsed = parseRigCsv(ours)
        assertNull(parsed.fileError)
        assertEquals(2, parsed.rows.size)
        assertEquals(1, parsed.blanks)
        val lug = parsed.rows[0].row
        assertEquals(Triple("Lug nuts", "450", true), Triple(lug.component, lug.value, lug.safetyCritical))
        assertEquals(SpecCategory.TORQUE, lug.category)
        val filter = parsed.rows[1].row
        assertEquals(ImportRowKind.PART, filter.kind)
        assertEquals(listOf("NAPA 1792", "Wix 51792"), filter.aftermarketNumbers)
        assertEquals(1, filter.qtyPerVehicle)

        // Someone's own sheet: other headers, no kind column, category labels.
        val theirs = "Item,Name,Value,Units,Category,OEM,Qty\nEngine oil,Capacity,40,qt,Capacity,,\nAir filter,,,,,P608667,1\n"
        val other = parseRigCsv(theirs)
        assertEquals(listOf(ImportRowKind.SPEC, ImportRowKind.PART), other.rows.map { it.row.kind })
        assertEquals(SpecCategory.CAPACITY, other.rows[0].row.category)
        assertEquals("P608667", other.rows[1].row.oemNumber)
    }

    @Test
    fun importErrorsNameTheLine() {
        val bad = "component,property,value,category,quantity,kind\n,Torque,5,,,\nLug nuts,,450,,,\nLug nuts,Torque,450,Bolts,,\nFilter,,,,two,part\nX,Y,1,,,widget\n"
        val parsed = parseRigCsv(bad)
        assertEquals(listOf(2, 3, 4, 5, 6), parsed.errors.map { it.line })
        assertTrue(parsed.errors[2].message.contains("Bolts"))
        assertIs<CsvImport>(parsed)
        assertEquals("No component column. The first row must name the columns, like an exported rig.", parseRigCsv("a,b\n1,2\n").fileError)
        assertEquals("The file is empty.", parseRigCsv("").fileError)
    }
}
