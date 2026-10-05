package dev.rigspecs.ui.importing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.rigspecs.data.importing.DocumentFiles
import org.jetbrains.skia.Image
import org.koin.compose.koinInject
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@Composable
actual fun rememberDocumentPicker(onPicked: (PickedDocument) -> Unit, onError: (String) -> Unit): DocumentPicker {
    val files = koinInject<DocumentFiles>()
    val picked = rememberUpdatedState(onPicked)
    val failed = rememberUpdatedState(onError)
    return remember {
        object : DocumentPicker {
            override fun pickFile() {
                val dialog = FileDialog(null as Frame?, "Choose a manual, parts list or photo", FileDialog.LOAD).apply {
                    setFilenameFilter { _, name -> name.lowercase().substringAfterLast('.') in Accepted }
                    isVisible = true
                }
                val chosen = dialog.files.firstOrNull() ?: return
                runCatching {
                    val ext = chosen.extension.lowercase().let { if (it == "jpeg") "jpg" else it }
                    require(ext in Accepted) { "Pick a PDF or a picture" }
                    val path = files.newPath(ext)
                    chosen.copyTo(File(path))
                    PickedDocument(path, chosen.nameWithoutExtension, ext == "pdf")
                }.onSuccess { picked.value(it) }.onFailure { failed.value("Couldn't open that file: ${it.message}") }
            }
            override val canTakePhoto = false
            override fun takePhoto() = Unit
        }
    }
}

private val Accepted = setOf("pdf", "jpg", "jpeg", "png")

actual fun decodeImage(bytes: ByteArray): ImageBitmap = Image.makeFromEncoded(bytes).toComposeImageBitmap()
