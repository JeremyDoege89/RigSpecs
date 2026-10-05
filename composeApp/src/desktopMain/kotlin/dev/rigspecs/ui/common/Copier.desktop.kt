package dev.rigspecs.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

@Composable
actual fun rememberCopier(): Copier = remember {
    Copier { text ->
        // The clipboard can be briefly owned by another app, or absent on a headless JVM.
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
    }
}
