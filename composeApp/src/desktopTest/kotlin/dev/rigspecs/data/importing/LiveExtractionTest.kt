package dev.rigspecs.data.importing

import dev.rigspecs.data.ImportRowKind
import dev.rigspecs.data.Settings
import dev.rigspecs.domain.Portal
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A real extraction against the department's deployed function. Costs a few cents, so it runs
 * only with RIGSPECS_LIVE_EXTRACT=1 plus RIGSPECS_LIVE_URL, RIGSPECS_LIVE_KEY and
 * RIGSPECS_LIVE_SECRET.
 */
class LiveExtractionTest {
    private val pages = mapOf(
        12 to listOf(
            "LUBRICATION AND CAPACITIES - ENGINE 2 (Cummins X12)",
            "Engine oil: SAE 15W-40, API CK-4. Capacity with filter change: 40 qt (37.9 L).",
            "Coolant: Fleetguard ES Compleat OAT, 50/50 premix. Cooling system capacity: 12.5 gal.",
            "Transmission: Allison 3000 EVS. Fluid: TranSynd TES 668. Capacity: 18 qt.",
            "Fire pump gearbox oil: SAE 80W-90 GL-5. Capacity: 4.5 qt.",
        ),
        13 to listOf(
            "TORQUE VALUES AND SERVICE PARTS",
            "Wheel lug nuts (M22 x 1.5): 450-500 ft-lb. Tighten in a star pattern; re-check after 50 miles.",
            "Front axle U-bolts: 300 ft-lb.",
            "Oil filter: Fleetguard LF9009 (cross reference NAPA 1792, Wix 51792). Quantity 1.",
            "Fuel filter, primary: Fleetguard FS1098.",
            "Air filter: Donaldson P608667.",
        ),
    )

    @Test
    fun readsARealManualExcerpt() {
        if (System.getenv("RIGSPECS_LIVE_EXTRACT") != "1") return
        val settings = Settings(
            portalUrls = Portal.entries.associateWith { it.defaultUrl },
            portalEnabled = Portal.entries.associateWith { true },
            importEnabled = true,
            serverUrl = System.getenv("RIGSPECS_LIVE_URL"),
            serverKey = System.getenv("RIGSPECS_LIVE_KEY"),
            importSecret = System.getenv("RIGSPECS_LIVE_SECRET"),
        )
        val service = ServerExtractionService(importHttpClient()) { settings }
        // Field names as an engine's template has them, so rows should reuse these.
        val fields = listOf(
            "Engine oil" to "Type", "Engine oil" to "Capacity", "Coolant" to "Type", "Coolant" to "Capacity",
            "Transmission fluid" to "Type", "Transmission fluid" to "Capacity", "Pump gearbox oil" to "Type",
            "Pump gearbox oil" to "Capacity", "Lug nuts" to "Torque",
        ).map { (c, n) -> KnownField("spec", c, n) } +
            listOf("Oil filter", "Fuel filter, primary", "Air filter").map { KnownField("part", it) }
        val vehicle = ExtractVehicle("Engine 2", "Engine", 2019, "Pierce", "Enforcer", "Cummins X12", "Pierce", fields = fields)

        val routine = runBlocking { service.extract(vehicle, ExtractInput.Pdf(pdf(12, 13), listOf(12, 13)), dense = false) }
        println("MODEL ${routine.model}")
        routine.rows.forEach { println("ROW p${it.page} ${it.kind} ${it.category ?: it.system} | ${it.component} | ${it.name} | ${it.value} ${it.unit} | ${it.oemNumber} ${it.aftermarketNumbers} | ${it.evidence}") }
        assertTrue(routine.rows.all { it.page in setOf(12, 13) }, "pages map back to the manual's numbering")
        // Rows reuse the profile's names, so they fill the template's blanks.
        assertTrue(routine.rows.any { it.kind == ImportRowKind.SPEC && it.component == "Lug nuts" && it.name == "Torque" && "450" in it.value && it.page == 13 })
        assertTrue(routine.rows.any { it.kind == ImportRowKind.SPEC && it.component == "Coolant" && it.name == "Capacity" && "12.5" in it.value })
        assertTrue(routine.rows.any { it.kind == ImportRowKind.SPEC && it.component == "Engine oil" && it.name == "Capacity" && it.value.startsWith("40") && it.page == 12 })
        // Part numbers come without the brand, ready to paste into a parts search.
        assertTrue(routine.rows.any { it.kind == ImportRowKind.PART && it.component == "Oil filter" && it.oemNumber == "LF9009" && it.aftermarketNumbers.any { r -> "1792" in r } })

        val dense = runBlocking { service.extract(vehicle, ExtractInput.Pdf(pdf(13), listOf(13)), dense = true) }
        println("DENSE MODEL ${dense.model}: ${dense.rows.size} rows")
        assertTrue("opus" in dense.model, "dense pages use the careful model")
        assertTrue(dense.rows.any { it.oemNumber == "P608667" })
    }

    private fun pdf(vararg pageNumbers: Int): ByteArray = PDDocument().use { doc ->
        val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
        for (n in pageNumbers) {
            val page = PDPage(PDRectangle.LETTER)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(font, 11f)
                cs.setLeading(18f)
                cs.newLineAtOffset(54f, 720f)
                pages.getValue(n).forEach { line ->
                    cs.showText(line)
                    cs.newLine()
                }
                cs.newLineAtOffset(240f, -400f)
                cs.showText("Page $n")
                cs.endText()
            }
        }
        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
    }
}
