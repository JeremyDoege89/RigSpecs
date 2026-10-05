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
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import java.io.File
import javax.imageio.ImageIO

/** Shared steps for the desktop UI flow tests. */
@OptIn(ExperimentalTestApi::class)
internal object UiTestKit {
    val shots = File("build/screenshots").apply { mkdirs() }

    fun ComposeUiTest.waitForText(text: String, timeoutMs: Long = 5_000) {
        waitUntil(timeoutMillis = timeoutMs) { onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    fun ComposeUiTest.type(label: String, text: String) {
        val field = onNode(hasText(label) and hasSetTextAction())
        runCatching { field.performScrollTo() } // dialogs don't scroll
        field.performTextInput(text)
    }

    /** The full-width button at the bottom of a form, not a same-named title or FAB. */
    fun ComposeUiTest.clickButton(label: String) {
        onAllNodesWithText(label).filter(hasClickAction()).onFirst().performScrollTo().performClick()
    }

    /** Captures the topmost root: the screen, or the dialog over it. */
    fun ComposeUiTest.shot(name: String) {
        waitForIdle()
        val image = onAllNodes(isRoot()).onLast().captureToImage()
        ImageIO.write(image.toAwtImage(), "png", File(shots, "$name.png"))
    }
}
