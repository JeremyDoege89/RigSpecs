package dev.rigspecs.ui

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.hasClickAction
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
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.room.Room
import dev.rigspecs.App
import dev.rigspecs.data.AppDatabase
import dev.rigspecs.data.configure
import dev.rigspecs.di.sharedModule
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

/**
 * Drives the milestone 1 flow through the real App() against an in-memory database, and saves
 * screenshots to build/screenshots for a visual check.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class AppFlowTest {
    private val shots = File("build/screenshots").apply { mkdirs() }

    @Test
    fun addRigSpecPartAndFilterFleet() {
        val db = Room.inMemoryDatabaseBuilder<AppDatabase>().configure(Dispatchers.IO)
        startKoin { modules(sharedModule, module { single { db } }) }
        try {
            // Navigation and lifecycle insist on the main thread, and ViewModels collect on Main. An
            // unconfined test Main satisfies both from the test thread.
            Dispatchers.setMain(UnconfinedTestDispatcher())
            runFlow()
        } finally {
            Dispatchers.resetMain()
            stopKoin()
            db.close()
        }
    }

    private fun runFlow() {
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
    }

    private fun ComposeUiTest.flow() {
        run {
                waitForText("No rigs yet")
                shot("1-fleet-empty")

                // Add a rig by hand.
                onNodeWithContentDescription("Add rig").performClick()
                type("Unit number *", "Engine 2")
                type("Station", "Station 1")
                type("VIN", "4P1BAAGF5KA012345")
                type("Year", "2019")
                type("Make", "Pierce")
                type("Model", "Enforcer")
                type("Builder job / serial number", "SO-34567")
                shot("2-add-rig")
                clickButton("Add rig")

                // Lands on the new profile.
                waitForText("VIN ${"4P1BAAGF5KA012345"}")
                waitForText("SO-34567")
                shot("3-profile-overview")

                // A safety-critical torque spec needs a source document.
                onNodeWithText("Torque").performClick()
                waitForText("No torque specs yet")
                onNodeWithContentDescription("Add spec").performClick()
                type("Component *", "Lug nuts")
                type("Property *", "Torque")
                type("Value *", "450")
                onNodeWithText("ft-lb").performClick()
                onNodeWithText("Safety-critical").performScrollTo().performClick()
                clickButton("Save")
                waitForText("Safety-critical specs need a source document")
                shot("4-spec-needs-source")

                onNode(hasText("Source document *") and hasClickAction()).performScrollTo().performClick()
                onNodeWithText("Add a document…").performClick()
                type("Title *", "Pierce delivery manual")
                type("Where in it", "p. 212")
                // The dialog is its own root, listed after the screen's Save button.
                onAllNodesWithText("Save").filter(hasClickAction()).onLast().performClick()
                waitForText("Pierce delivery manual, p. 212")
                clickButton("Save")

                waitForText("450 ft-lb")
                waitForText("Source: Pierce delivery manual, p. 212")
                waitForText("Verify against the manual before use")
                shot("5-profile-torque")

                // A part with an OEM number and a cross-reference.
                onNodeWithText("Parts").performClick()
                onNodeWithContentDescription("Add part").performClick()
                type("Part *", "Oil filter")
                type("OEM part number", "LF9009")
                type("Cross-references", "NAPA 1792")
                clickButton("Save")
                waitForText("LF9009")
                waitForText("NAPA 1792")
                shot("6-profile-parts")

                // Docs tab lists the document added from the spec form.
                onNodeWithText("Docs").performClick()
                waitForText("Manual · p. 212")

                // Back to the fleet, add a second rig of another type, then filter.
                onNodeWithContentDescription("Back").performClick()
                waitForText("Engine 2")
                onNodeWithContentDescription("Add rig").performClick()
                type("Unit number *", "Medic 3")
                onNode(hasText("Type") and hasClickAction()).performClick()
                onNodeWithText("Ambulance").performClick()
                type("Station", "Station 2")
                clickButton("Add rig")
                waitForText("Ambulance")
                onNodeWithContentDescription("Back").performClick()

                waitForText("Medic 3")
                shot("7-fleet-two-rigs")
                onAllNodesWithText("Ambulance").filter(hasClickAction()).onFirst().performClick()
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Engine 2").fetchSemanticsNodes().isEmpty() }
                onAllNodesWithText("Ambulance").filter(hasClickAction()).onFirst().performClick()

                onNode(hasSetTextAction()).performTextInput("pierce")
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Medic 3").fetchSemanticsNodes().isEmpty() }
                waitForText("Engine 2")
                shot("8-fleet-search")
        }
    }

    private fun ComposeUiTest.waitForText(text: String) {
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeUiTest.type(label: String, text: String) {
        val field = onNode(hasText(label) and hasSetTextAction())
        runCatching { field.performScrollTo() } // dialogs don't scroll
        field.performTextInput(text)
    }

    /** The full-width button at the bottom of a form, not a same-named title or FAB. */
    private fun ComposeUiTest.clickButton(label: String) {
        onAllNodesWithText(label).filter(hasClickAction()).onFirst().performScrollTo().performClick()
    }

    private fun ComposeUiTest.shot(name: String) {
        waitForIdle()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(shots, "$name.png"))
    }
}
