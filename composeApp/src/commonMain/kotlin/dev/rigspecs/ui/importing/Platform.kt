package dev.rigspecs.ui.importing

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap

/** A file the user picked or photographed, already copied into app storage. */
data class PickedDocument(val path: String, val name: String, val isPdf: Boolean)

interface DocumentPicker {
    /** A PDF or a picture from the device's files. */
    fun pickFile()

    /** Whether this device can take a photo of a page (phones, not the desktop). */
    val canTakePhoto: Boolean
    fun takePhoto()
}

/** [onError] gets a sentence for the user when a file can't be read or copied. */
@Composable
expect fun rememberDocumentPicker(onPicked: (PickedDocument) -> Unit, onError: (String) -> Unit): DocumentPicker

/** Decodes a JPEG or PNG for display. */
expect fun decodeImage(bytes: ByteArray): ImageBitmap
