package dev.rigspecs.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.rigspecs.data.DocumentEntity
import dev.rigspecs.data.ImportJobEntity
import dev.rigspecs.domain.ImportJobStatus
import dev.rigspecs.domain.SourceKind
import dev.rigspecs.ui.theme.TouchTarget
import dev.rigspecs.ui.theme.WarnColor

/** Under the header on every tab: imports reading now, and how much waits for a person. */
@Composable
internal fun ReviewBanner(state: ProfileState, onReview: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        state.activeJobs.forEach { job ->
            val title = state.documentsById[job.documentId]?.title ?: "document"
            Text("Reading $title: page ${minOf(job.pagesDone + 1, job.pagesTotal)} of ${job.pagesTotal}", style = MaterialTheme.typography.bodyLarge)
            if (job.pagesTotal > 0) LinearProgressIndicator(progress = { job.pagesDone.toFloat() / job.pagesTotal }, modifier = Modifier.fillMaxWidth())
        }
        if (state.toReview > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val parts = listOfNotNull(
                    state.pendingImport.takeIf { it > 0 }?.let { "$it imported ${if (it == 1) "row" else "rows"}" },
                    state.unverified.takeIf { it > 0 }?.let { "$it unverified" },
                )
                Text("${parts.joinToString(" and ")} to review", color = WarnColor, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Button(onClick = onReview, modifier = Modifier.heightIn(min = TouchTarget)) { Text("Review") }
            }
        }
    }
}

/** "Look up in" buttons for the parts portals that fit this rig. */
@Composable
internal fun PortalRow(links: List<PortalLink>, onPortal: (PortalLink) -> Unit) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
        Text("Look up in", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            links.forEach { link ->
                OutlinedButton(onClick = { onPortal(link) }, modifier = Modifier.heightIn(min = TouchTarget)) {
                    Text(link.portal.label)
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

@Composable
internal fun UnverifiedBadge(source: SourceKind) {
    Text(
        "Unverified · from ${source.from}",
        style = MaterialTheme.typography.labelLarge,
        color = WarnColor,
        modifier = Modifier.padding(top = 2.dp),
    )
}

internal fun importStatusLine(job: ImportJobEntity, running: Boolean): String = when {
    running -> "Importing: page ${minOf(job.pagesDone + 1, job.pagesTotal)} of ${job.pagesTotal}"
    job.status == ImportJobStatus.FAILED -> "Import stopped at page ${job.pagesDone} of ${job.pagesTotal}"
    job.status == ImportJobStatus.RUNNING -> "Import paused at page ${job.pagesDone} of ${job.pagesTotal}"
    job.status == ImportJobStatus.REVIEW -> "Imported: ${job.rowsExtracted} rows found, ${job.rowsConfirmed} confirmed so far"
    job.status == ImportJobStatus.DONE -> "Imported: ${job.rowsConfirmed} of ${job.rowsExtracted} rows kept"
    job.status == ImportJobStatus.CANCELLED -> "Import stopped"
    else -> "Import waiting to start"
}

@Composable
internal fun AddDocumentDialog(
    canTakePhoto: Boolean,
    onPickFile: () -> Unit,
    onTakePhoto: () -> Unit,
    onNameOnly: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add document") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPickFile, modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget)) { Text("Choose a PDF or picture") }
                if (canTakePhoto) {
                    Button(onClick = onTakePhoto, modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget)) { Text("Photograph a page or label") }
                }
                OutlinedButton(onClick = onNameOnly, modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget)) { Text("Just name it (no file)") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun DocumentActionsDialog(
    document: DocumentEntity,
    job: ImportJobEntity?,
    canEdit: Boolean,
    onImport: () -> Unit,
    onReview: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(document.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (document.localPath.isNotBlank() && canEdit) {
                    Button(onClick = onImport, modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget)) {
                        Text(if (job == null) "Import specs from it" else "Import again")
                    }
                }
                if (job != null && job.status != ImportJobStatus.DONE) {
                    OutlinedButton(onClick = onReview, modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget)) { Text("Review the import") }
                }
                OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget)) { Text("Edit details") }
                OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget)) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
