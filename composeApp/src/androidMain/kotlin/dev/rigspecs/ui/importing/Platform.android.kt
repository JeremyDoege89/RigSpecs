package dev.rigspecs.ui.importing

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import dev.rigspecs.data.importing.DocumentFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.io.File

@Composable
actual fun rememberDocumentPicker(onPicked: (PickedDocument) -> Unit, onError: (String) -> Unit): DocumentPicker {
    val context = LocalContext.current
    val files = koinInject<DocumentFiles>()
    val scope = rememberCoroutineScope()
    val picked by rememberUpdatedState(onPicked)
    val failed by rememberUpdatedState(onError)
    // Survives the camera app taking over (and possibly this process being recreated).
    var photoPath by rememberSaveable { mutableStateOf<String?>(null) }

    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { copyIn(context, uri, files) } }
            result.onSuccess { picked(it) }.onFailure { failed("Couldn't open that file: ${it.message ?: "unknown error"}") }
        }
    }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val path = photoPath ?: return@rememberLauncherForActivityResult
        photoPath = null
        if (ok && File(path).length() > 0) {
            picked(PickedDocument(path, "Photo", isPdf = false))
        } else {
            files.delete(path)
        }
    }
    return remember {
        object : DocumentPicker {
            override fun pickFile() = openDocument.launch(arrayOf("application/pdf", "image/*"))
            override val canTakePhoto = true
            override fun takePhoto() {
                val path = files.newPath("jpg")
                photoPath = path
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File(path))
                runCatching { takePicture.launch(uri) }.onFailure {
                    files.delete(path)
                    failed("No camera app is available.")
                }
            }
        }
    }
}

private fun copyIn(context: Context, uri: Uri, files: DocumentFiles): PickedDocument {
    val resolver = context.contentResolver
    val mime = resolver.getType(uri).orEmpty()
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: "Document"
    val extension = when {
        mime == "application/pdf" || name.lowercase().endsWith(".pdf") -> "pdf"
        mime == "image/png" -> "png"
        mime == "image/webp" -> "webp"
        mime == "image/heic" || mime == "image/heif" -> "heic"
        mime.startsWith("image/") -> "jpg"
        else -> error("Pick a PDF or a picture")
    }
    val path = files.newPath(extension)
    resolver.openInputStream(uri)?.use { input -> File(path).outputStream().use { input.copyTo(it) } }
        ?: error("The file couldn't be read")
    return PickedDocument(path, name.substringBeforeLast('.').ifBlank { "Document" }, extension == "pdf")
}

actual fun decodeImage(bytes: ByteArray): ImageBitmap =
    (BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Not an image")).asImageBitmap()
