package dev.rigspecs.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.rigspecs.data.DocumentEntity
import dev.rigspecs.domain.DocumentKind
import dev.rigspecs.ui.common.ChoiceField
import dev.rigspecs.ui.common.FormField

/**
 * Adds or edits a document entry. Attaching the actual PDF or photo comes with file storage
 * (milestone 4); until then an entry names the source so specs can cite it.
 */
@Composable
fun DocumentDialog(
    existing: DocumentEntity?,
    onSave: (title: String, kind: DocumentKind, pageHint: String) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    var kind by remember { mutableStateOf(existing?.kind ?: DocumentKind.MANUAL) }
    var pageHint by remember { mutableStateOf(existing?.pageHint.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add document" else "Edit document") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FormField(title, { title = it; error = null }, "Title *", error = error, supporting = "e.g. Pierce delivery manual")
                ChoiceField("Kind", kind, DocumentKind.entries, { it.label }, { kind = it }, Modifier.fillMaxWidth())
                FormField(pageHint, { pageHint = it }, "Where in it", supporting = "e.g. p. 212, Section 4")
                Text(
                    "Attaching the file itself arrives with sync.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (title.isBlank()) error = "Title is required" else onSave(title, kind, pageHint)
            }) { Text("Save") }
        },
        dismissButton = {
            if (onDelete != null) {
                TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            } else {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** "Pierce delivery manual, p. 212". */
fun DocumentEntity.citation(): String = if (pageHint.isBlank()) title else "$title, ${pageHint.trim()}"
