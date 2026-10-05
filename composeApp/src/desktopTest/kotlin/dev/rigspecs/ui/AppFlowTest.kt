package dev.rigspecs.ui

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.room.Room
import dev.rigspecs.App
import dev.rigspecs.data.AppDatabase
import dev.rigspecs.data.configure
import dev.rigspecs.data.importing.DesktopDocumentFiles
import dev.rigspecs.data.importing.DesktopDocumentPages
import dev.rigspecs.data.importing.DocumentFiles
import dev.rigspecs.data.importing.DocumentPages
import dev.rigspecs.data.vin.VpicFixtures
import dev.rigspecs.data.vin.VpicRemote
import dev.rigspecs.di.sharedModule
import dev.rigspecs.ui.UiTestKit.clickButton
import dev.rigspecs.ui.UiTestKit.shot
import dev.rigspecs.ui.UiTestKit.type
import dev.rigspecs.ui.UiTestKit.waitForText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Drives the main flows through the real App() against an in-memory database and a fake vPIC,
 * and saves screenshots to build/screenshots for a visual check.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class AppFlowTest {
    @Test
    fun decodeTemplateFillAndFilter() {
        val db = Room.inMemoryDatabaseBuilder<AppDatabase>().configure(Dispatchers.IO)
        val fakeVpic = VpicRemote { vin, _ -> if (vin.startsWith("4EN")) VpicFixtures.EONE else VpicFixtures.NOTHING_DECODED }
        val docsDir = java.nio.file.Files.createTempDirectory("rigspecs-ui-docs").toFile()
        startKoin {
            modules(
                sharedModule,
                module {
                    single { db }
                    single<DocumentPages> { DesktopDocumentPages() }
                    single<DocumentFiles> { DesktopDocumentFiles(docsDir) }
                },
                module { single<VpicRemote> { fakeVpic } },
            )
        }
        try {
            // Navigation and lifecycle insist on the main thread, and ViewModels collect on Main. An
            // unconfined test Main satisfies both from the test thread.
            Dispatchers.setMain(UnconfinedTestDispatcher())
            runDesktopComposeUiTest(width = 412, height = 900) {
                setContent { App() }
                try {
                    flow()
                } catch (e: Throwable) {
                    // Teardown errors can mask the real failure, so log it and keep a screenshot first.
                    e.printStackTrace()
                    runCatching { shot("failure") }
                    throw e
                }
            }
        } finally {
            Dispatchers.resetMain()
            stopKoin()
            db.close()
        }
    }

    private fun ComposeUiTest.flow() {
        waitForText("No rigs yet")
        shot("1-fleet-empty")

        // Add a rig: decode the VIN, confirm, keep the template on.
        onNodeWithContentDescription("Add rig").performClick()
        waitForText("Start with the engine template")
        // A complete VIN decodes by itself.
        type("VIN", "4en6aaa85k1000000")
        waitForText("NHTSA says")
        waitForText("2019 E-One")
        waitForText("Engine: Cummins ISL 8.9L 310 hp")
        waitForText("check digit")
        shot("2-decode-card")
        onNodeWithText("Use these").performClick()
        waitForText("Filled in year, make, engine, chassis make")
        type("Unit number *", "Engine 2")
        type("Station", "Station 1")
        type("Model", "Typhoon")
        type("Builder job / serial number", "SO-34567")
        shot("3-add-rig")
        clickButton("Add rig")

        // Lands on the new profile with the template's blanks and the decode details.
        waitForText("VIN 4EN6AAA85K1000000")
        waitForText("fields are still blank")
        // The Cummins engine from the decode brings up the QuickServe button; no ESN yet, so it asks for one.
        waitForText("Look up in")
        onNodeWithText("Cummins QuickServe").performClick()
        waitForText("Add the ESN first")
        shot("4-profile-overview")
        // Scrolling to a node fails if it doesn't exist, so these double as checks.
        onNode(hasScrollToIndexAction()).performScrollToNode(hasText("From the VIN decode"))
        onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Body class", substring = true))
        waitForText("Fire Apparatus")
        shot("4b-profile-decode")

        // Fill the template's lug-nut torque. It's safety-critical, so it needs a source.
        onNodeWithText("Torque").performClick()
        waitForText("Not filled in (ft-lb)")
        shot("5-torque-blank")
        onNodeWithContentDescription("Edit Lug nuts Torque").performClick()
        type("Value *", "450")
        clickButton("Save")
        waitForText("Safety-critical specs need a source document")
        onNode(hasText("Source document *") and hasClickAction()).performScrollTo().performClick()
        onNodeWithText("Add a document…").performClick()
        type("Title *", "E-One delivery manual")
        type("Where in it", "p. 212")
        // The dialog is its own root, listed after the screen's Save button.
        onAllNodesWithText("Save").filter(hasClickAction()).onLast().performClick()
        waitForText("E-One delivery manual")
        // The row's own page is optional; the citation falls back to the document's.
        waitForText("Where it's printed")
        clickButton("Save")
        waitForText("450 ft-lb")
        waitForText("Source: E-One delivery manual, p. 212")
        waitForText("Verify against the manual before use")
        shot("6-profile-torque")

        // Fill the template's oil filter.
        onNodeWithText("Parts").performClick()
        waitForText("No part number yet")
        onNodeWithContentDescription("Edit Oil filter").performScrollTo().performClick()
        type("OEM part number", "LF9009")
        type("Cross-references", "NAPA 1792")
        clickButton("Save")
        waitForText("LF9009")
        waitForText("NAPA 1792")
        shot("7-profile-parts")

        onNodeWithText("Docs").performClick()
        waitForText("Manual · p. 212")

        // A second rig without a template, typed by hand; the failed decode doesn't block saving.
        onNodeWithContentDescription("Back").performClick()
        waitForText("Engine 2")
        // Leaving a half-filled form asks first; "Keep editing" keeps what was typed.
        onNodeWithContentDescription("Add rig").performClick()
        type("Unit number *", "Scratch")
        onNodeWithContentDescription("Back").performClick()
        waitForText("Discard changes?")
        onNodeWithText("Keep editing").performClick()
        waitForText("Scratch")
        onNodeWithContentDescription("Back").performClick()
        onNodeWithText("Discard").performClick()
        waitForText("Engine 2")
        assertTrue(onAllNodesWithText("Scratch").fetchSemanticsNodes().isEmpty())

        onNodeWithContentDescription("Add rig").performClick()
        // Pasted with spaces and junk on the end: trimmed to the 17 characters a VIN has, then decoded.
        type("VIN", "1FDX E4FN8 LDC00000 extra")
        waitForText("1FDXE4FN8LDC00000")
        assertTrue(onAllNodesWithText("extra", substring = true).fetchSemanticsNodes().isEmpty())
        waitForText("NHTSA doesn't recognize this VIN")
        type("Unit number *", "Medic 3")
        onNode(hasText("Type") and hasClickAction()).performClick()
        onNodeWithText("Ambulance").performClick()
        waitForText("Start with the ambulance template")
        onNodeWithText("Start with the ambulance template").performClick()
        type("Station", "Station 2")
        clickButton("Add rig")
        waitForText("Ambulance")
        onNodeWithText("Torque").performClick()
        waitForText("No torque specs yet")
        onNodeWithContentDescription("Back").performClick()

        waitForText("Medic 3")
        shot("8-fleet-two-rigs")
        onAllNodesWithText("Ambulance").filter(hasClickAction()).onFirst().performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Engine 2").fetchSemanticsNodes().isEmpty() }
        onAllNodesWithText("Ambulance").filter(hasClickAction()).onFirst().performClick()

        onNode(hasSetTextAction()).performTextInput("e-one")
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Medic 3").fetchSemanticsNodes().isEmpty() }
        waitForText("Engine 2")
        shot("9-fleet-search")
    }
}
