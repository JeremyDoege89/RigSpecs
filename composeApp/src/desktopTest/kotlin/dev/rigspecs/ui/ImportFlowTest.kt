package dev.rigspecs.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.room.Room
import dev.rigspecs.App
import dev.rigspecs.data.AppDatabase
import dev.rigspecs.data.DocumentEntity
import dev.rigspecs.data.ImportRowKind
import dev.rigspecs.data.RigRepository
import dev.rigspecs.data.SettingsRepository
import dev.rigspecs.data.VehicleEntity
import dev.rigspecs.data.configure
import dev.rigspecs.data.importing.DesktopDocumentFiles
import dev.rigspecs.data.importing.DesktopDocumentPages
import dev.rigspecs.data.importing.DocumentFiles
import dev.rigspecs.data.importing.DocumentPages
import dev.rigspecs.data.importing.ExtractInput
import dev.rigspecs.data.importing.ExtractOutput
import dev.rigspecs.data.importing.ExtractVehicle
import dev.rigspecs.data.importing.ExtractionService
import dev.rigspecs.data.importing.PingResult
import dev.rigspecs.di.sharedModule
import dev.rigspecs.domain.CandidateRow
import dev.rigspecs.domain.DocumentKind
import dev.rigspecs.domain.PartSystem
import dev.rigspecs.domain.SourceKind
import dev.rigspecs.domain.SpecCategory
import dev.rigspecs.domain.VehicleType
import dev.rigspecs.ui.UiTestKit.clickButton
import dev.rigspecs.ui.UiTestKit.shot
import dev.rigspecs.ui.UiTestKit.waitForText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** Assisted import end to end, with a fake extraction service and a real PDF on disk. */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class ImportFlowTest {

    /** Answers like the server would, from what each page "says". */
    private class FakeExtractor : ExtractionService {
        val pagesSent = mutableListOf<List<Int>>()
        override suspend fun extract(vehicle: ExtractVehicle, input: ExtractInput, dense: Boolean): ExtractOutput {
            pagesSent += input.pageNumbers
            val rows = input.pageNumbers.flatMap { page ->
                when (page) {
                    2 -> listOf(
                        CandidateRow(ImportRowKind.SPEC, "Lug nuts", "Torque", "450", "ft-lb", category = SpecCategory.TORQUE, page = 2, evidence = "Wheel nuts: 450 ft-lb, dry threads"),
                        CandidateRow(ImportRowKind.SPEC, "Engine oil", "Capacity", "26", "qt", category = SpecCategory.CAPACITY, page = 2, evidence = "Oil capacity with filter 26 qt"),
                    )
                    3 -> listOf(
                        CandidateRow(ImportRowKind.PART, "Oil filter", oemNumber = "LF9O09", aftermarketNumbers = listOf("NAPA 1792"), system = PartSystem.ENGINE, page = 3, evidence = "Oil filter LF9009 / NAPA 1792"),
                        CandidateRow(ImportRowKind.SPEC, "Front axle", "Hub oil", "SAE 80W-90", category = SpecCategory.FLUID, page = 3, evidence = "Hub oil SAE 80W-90"),
                    )
                    else -> emptyList()
                }
            }
            return ExtractOutput(rows, "fake-model")
        }
        override suspend fun ping() = PingResult(true, "Ready")
    }

    @Test
    fun importReviewAndConfirm() {
        val dir = Files.createTempDirectory("rigspecs-import-ui").toFile()
        val db = Room.inMemoryDatabaseBuilder<AppDatabase>().configure(Dispatchers.IO)
        val extractor = FakeExtractor()
        val koin = startKoin {
            modules(
                sharedModule,
                module {
                    single { db }
                    single<DocumentPages> { DesktopDocumentPages() }
                    single<DocumentFiles> { DesktopDocumentFiles(dir) }
                },
                module { single<ExtractionService> { extractor } },
            )
        }.koin
        try {
            // A rig with its template, a 4-page manual on disk, a value already entered and one unverified API value.
            runBlocking {
                val rigs = koin.get<RigRepository>()
                val settings = koin.get<SettingsRepository>()
                settings.set(SettingsRepository.Keys.SERVER_URL, "https://example.supabase.co")
                settings.set(SettingsRepository.Keys.SERVER_KEY, "anon")
                val vid = rigs.saveVehicle(
                    VehicleEntity(id = "", departmentId = "", unitNumber = "Engine 2", type = VehicleType.ENGINE, make = "Pierce", updatedAt = 0, updatedBy = ""),
                    rigs.templateItems(VehicleType.ENGINE),
                )
                val oil = rigs.observeSpecs(vid).first().first { it.component == "Engine oil" && it.name == "Capacity" }
                rigs.saveSpec(oil.copy(value = "24", unit = "qt"))
                rigs.saveDocument(
                    DocumentEntity(id = "", departmentId = "", vehicleId = vid, title = "Pierce delivery manual", kind = DocumentKind.MANUAL, localPath = manual(dir).absolutePath, updatedAt = 0, updatedBy = ""),
                )
                rigs.addSourcedRows(vid, SourceKind.OPENLABOR, listOf(CandidateRow(ImportRowKind.SPEC, "Coolant", "Capacity", "12", "gal", category = SpecCategory.CAPACITY, sourceUrl = "https://openlaborproject.com/x")))
            }
            Dispatchers.setMain(UnconfinedTestDispatcher())
            runDesktopComposeUiTest(width = 412, height = 900) {
                setContent { App() }
                try {
                    flow()
                } catch (e: Throwable) {
                    e.printStackTrace()
                    runCatching { shot("import-failure") }
                    throw e
                }
            }
            assertEquals(listOf(listOf(2, 3)), extractor.pagesSent)
        } finally {
            Dispatchers.resetMain()
            stopKoin()
            db.close()
        }
    }

    private fun ComposeUiTest.flow() {
        waitForText("Engine 2")
        waitForText("1 to review")
        onNodeWithText("Engine 2").performClick()

        // The unverified API value already shows up top.
        waitForText("1 unverified to review")
        shot("i1-profile-unverified")

        // Start an import from the manual: only pages 2 and 3.
        onNodeWithText("Docs").performClick()
        onNodeWithText("Pierce delivery manual").performClick()
        onNodeWithText("Import specs from it").performClick()
        waitForText("4 pages")
        onNode(hasText("Pages to read") and androidx.compose.ui.test.hasSetTextAction()).performTextInput("2-3")
        waitForText("2 of 4 pages")
        shot("i2-import-setup")
        clickButton("Start import")

        // Review: rows wait here, none in the profile yet.
        waitForText("Nothing here is in the profile yet", timeoutMs = 10_000)
        waitForText("Wheel nuts: 450 ft-lb")
        waitForText("The profile says 24 qt")
        shot("i3-review")

        // The page viewer shows the real page.
        onAllNodesWithText("Page 2").filter(hasClickAction()).onFirst().performClick()
        // The page is rendered from the real PDF before it shows.
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("Page 2").fetchSemanticsNodes().isNotEmpty() }
        shot("i4-page-viewer")
        onNodeWithText("Close").performClick()

        // Lug nuts: confirm as read. Engine oil: the manual wins over what was typed.
        rowAction("Wheel nuts: 450 ft-lb", "Confirm")
        rowAction("Oil capacity with filter 26 qt", "Replace")
        // Oil filter: the model misread 0 as O; fix it while confirming.
        rowAction("Oil filter LF9009", "Edit")
        val oem = onNode(hasText("OEM part number") and androidx.compose.ui.test.hasSetTextAction())
        oem.performTextClearance()
        oem.performTextInput("LF9009")
        onNodeWithText("Confirm with changes").performClick()
        // Hub oil: not on this rig.
        rowAction("Hub oil SAE 80W-90", "Reject")
        // The Open Labor value: checked against the manual.
        onNode(hasScrollToIndexAction()).performScrollToNode(hasText("From Open Labor Project"))
        onAllNodesWithText("Confirm").filter(hasClickAction()).onLast().performClick()
        waitForText("Nothing to review")
        shot("i5-review-done")

        // Back on the profile: values cite the manual's page.
        onNodeWithContentDescription("Back").performClick()
        onNodeWithText("Torque").performClick()
        waitForText("450 ft-lb")
        waitForText("Source: Pierce delivery manual, p. 2")
        onNodeWithText("Parts").performClick()
        waitForText("LF9009")
        waitForText("Source: Pierce delivery manual, p. 3")
        onNodeWithText("Fluids").performClick()
        waitForText("26 qt")
        shot("i6-profile-after-import")

        // The document records the run.
        onNodeWithText("Docs").performClick()
        waitForText("rows kept")

        // Settings: the portal and server fields.
        onNodeWithContentDescription("Back").performClick()
        onNodeWithContentDescription("Settings").performClick()
        waitForText("Parts portals")
        shot("i7-settings")
    }

    /** Taps [action] on the review card whose evidence or text contains [cardText]. */
    private fun ComposeUiTest.rowAction(cardText: String, action: String) {
        val list = onNode(hasScrollToIndexAction())
        list.performScrollToNode(hasText(cardText, substring = true))
        // Every card has the same buttons; the card's own text tells them apart, so act on the
        // first button of that name below the card's text.
        val cardTop = onAllNodesWithText(cardText, substring = true).onFirst().fetchSemanticsNode().boundsInRoot.top
        val buttons = onAllNodesWithText(action).filter(hasClickAction())
        val nodes = buttons.fetchSemanticsNodes()
        val index = nodes.withIndex().filter { it.value.boundsInRoot.top > cardTop }.minByOrNull { it.value.boundsInRoot.top }?.index
            ?: error("No $action button under \"$cardText\"")
        buttons[index].performClick()
        waitForIdle()
    }

    /** A 4-page manual: page 2 has the lug nut torque, page 3 the oil filter. */
    private fun manual(dir: File): File = File(dir, "pierce.pdf").also { file ->
        PDDocument().use { doc ->
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            val lines = mapOf(
                1 to "Pierce Enforcer delivery manual",
                2 to "Wheel nuts: 450 ft-lb, dry threads. Oil capacity with filter 26 qt.",
                3 to "Oil filter LF9009 / NAPA 1792. Hub oil SAE 80W-90.",
                4 to "Warranty",
            )
            for (n in 1..4) {
                val page = PDPage(PDRectangle.LETTER)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(font, 14f)
                    cs.newLineAtOffset(72f, 700f)
                    cs.showText("Page $n. ${lines.getValue(n)}")
                    cs.endText()
                }
            }
            doc.save(file)
        }
    }
}
