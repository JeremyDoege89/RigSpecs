package dev.rigspecs.ui.csv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@Composable
actual fun rememberCsvFiles(
    onPicked: (name: String, text: String) -> Unit,
    onSaved: (String) -> Unit,
    onError: (String) -> Unit,
): CsvFiles {
    val picked = rememberUpdatedState(onPicked)
    val saved = rememberUpdatedState(onSaved)
    val failed = rememberUpdatedState(onError)
    return remember {
        object : CsvFiles {
            override fun pick() {
                val dialog = FileDialog(null as Frame?, "Choose a CSV file", FileDialog.LOAD).apply {
                    directory = startFolder()
                    file = "*.csv"
                    setFilenameFilter { _, name -> name.lowercase().endsWith(".csv") }
                    isVisible = true
                }
                val chosen = dialog.files.firstOrNull() ?: return
                runCatching { chosen.readText() }
                    .onSuccess { picked.value(chosen.name, it) }
                    .onFailure { failed.value("Couldn't open that file: ${it.message}") }
            }

            override fun save(suggestedName: String, text: String) {
                val dialog = FileDialog(null as Frame?, "Save the CSV", FileDialog.SAVE).apply {
                    directory = startFolder()
                    file = suggestedName
                    isVisible = true
                }
                val name = dialog.file ?: return
                val target = File(dialog.directory, if (name.lowercase().endsWith(".csv")) name else "$name.csv")
                runCatching { target.writeText(text) }
                    .onSuccess { saved.value("Saved to ${target.absolutePath}") }
                    .onFailure { failed.value("Couldn't save the CSV: ${it.message}") }
            }
        }
    }
}

private fun startFolder(): String {
    val home = File(System.getProperty("user.home"))
    return listOf("Documents", "Downloads").map { File(home, it) }.firstOrNull { it.isDirectory }?.absolutePath ?: home.absolutePath
}
