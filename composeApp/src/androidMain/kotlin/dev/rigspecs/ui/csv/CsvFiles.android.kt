package dev.rigspecs.ui.csv

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
actual fun rememberCsvFiles(
    onPicked: (name: String, text: String) -> Unit,
    onSaved: (String) -> Unit,
    onError: (String) -> Unit,
): CsvFiles {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picked = rememberUpdatedState(onPicked)
    val saved = rememberUpdatedState(onSaved)
    val failed = rememberUpdatedState(onError)
    // The text waiting for the user to choose where it goes.
    val pending = remember { arrayOfNulls<String>(1) }

    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val resolver = context.contentResolver
                    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    } ?: "spreadsheet.csv"
                    val text = resolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: error("The file couldn't be read")
                    name to text
                }
            }.onSuccess { (name, text) -> picked.value(name, text) }
                .onFailure { failed.value("Couldn't open that file: ${it.message ?: "unknown error"}") }
        }
    }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        val text = pending[0]
        pending[0] = null
        if (uri == null || text == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.encodeToByteArray()) } ?: error("The file couldn't be written")
                }
            }.onSuccess { saved.value("Saved the CSV") }
                .onFailure { failed.value("Couldn't save the CSV: ${it.message ?: "unknown error"}") }
        }
    }
    return remember {
        object : CsvFiles {
            override fun pick() = open.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/csv", "application/vnd.ms-excel"))
            override fun save(suggestedName: String, text: String) {
                pending[0] = text
                create.launch(suggestedName)
            }
        }
    }
}
