package dev.rigspecs.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.rigspecs.ui.common.SecretField
import dev.rigspecs.ui.theme.RigSpecsTheme
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class SecretFieldTest {
    /** What the field draws, after masking (the raw input is a separate property). */
    private fun shows(text: String) = SemanticsMatcher("shows \"$text\"") {
        it.config.getOrNull(SemanticsProperties.EditableText)?.text == text
    }

    @Test
    fun passphraseIsMaskedUntilShown() = runDesktopComposeUiTest(width = 412, height = 300) {
        setContent { RigSpecsTheme { SecretField("plain-words-here", {}, "Import passphrase") } }
        onAllNodes(shows("plain-words-here")).assertCountEquals(0)
        onNodeWithContentDescription("Show import passphrase").performClick()
        onAllNodes(shows("plain-words-here")).assertCountEquals(1)
        onNodeWithContentDescription("Hide import passphrase").performClick()
        onAllNodes(shows("plain-words-here")).assertCountEquals(0)
    }
}
