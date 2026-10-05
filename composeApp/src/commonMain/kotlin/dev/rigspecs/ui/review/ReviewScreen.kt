package dev.rigspecs.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rigspecs.data.ImportRowEntity
import dev.rigspecs.data.ImportRowKind
import dev.rigspecs.data.PartEntity
import dev.rigspecs.data.SpecItemEntity
import dev.rigspecs.domain.ImportJobStatus
import dev.rigspecs.domain.ImportMatch
import dev.rigspecs.domain.copyText
import dev.rigspecs.domain.displayValue
import dev.rigspecs.domain.isAllowedPortalUrl
import dev.rigspecs.ui.common.EmptyState
import dev.rigspecs.ui.importing.PageViewerDialog
import dev.rigspecs.ui.theme.TouchTarget
import dev.rigspecs.ui.theme.ValueStyle
import dev.rigspecs.ui.theme.WarnColor

@Composable
fun ReviewScreen(
    viewModel: ReviewViewModel,
    onBack: () -> Unit,
    onEditSpec: (String) -> Unit,
    onEditPart: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<ImportRowEntity?>(null) }
    var viewing by remember { mutableStateOf<Pair<String, Int>?>(null) }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Review" + (state.vehicle?.let { " · ${it.unitNumber}" } ?: "")) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (state.loading) return@Scaffold
        if (state.isEmpty && state.jobs.isEmpty()) {
            EmptyState(
                "Nothing to review",
                "Every value on this rig was entered or confirmed by a person.",
                Modifier.padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.jobs, key = { "job-" + it.job.id }) { line ->
                JobCard(line, onResume = { viewModel.resume(line.job.id) }, onCancel = { viewModel.cancel(line.job.id) })
            }
            if (state.items.isNotEmpty()) {
                item(key = "imports-intro") {
                    Text(
                        "Check each value against the page before confirming. Nothing here is in the profile yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            state.items.groupBy { it.document?.title ?: "Imported document" }.forEach { (title, group) ->
                item(key = "doc-$title") { Heading(title) }
                items(group.sortedWith(compareBy({ it.row.page ?: 0 }, { it.row.sortOrder })), key = { it.row.id }) { item ->
                    ImportRowCard(
                        item,
                        onConfirm = { viewModel.confirm(item.row.id) },
                        onReplace = { viewModel.confirm(item.row.id, replace = true) },
                        onReject = { viewModel.reject(item.row.id) },
                        onEdit = { editing = item.row },
                        onViewPage = item.document?.localPath?.takeIf { it.isNotBlank() }?.let { path ->
                            item.row.page?.let { page -> { viewing = path to page } }
                        },
                    )
                }
            }
            if (state.unverifiedSpecs.isNotEmpty() || state.unverifiedParts.isNotEmpty()) {
                item(key = "unverified") { Heading("Unverified in the profile") }
                items(state.unverifiedSpecs, key = { "s-" + it.id }) { spec ->
                    UnverifiedSpecCard(spec, onConfirm = { viewModel.confirmSpec(spec.id) }, onEdit = { onEditSpec(spec.id) }, onDelete = { viewModel.deleteSpec(spec.id) })
                }
                items(state.unverifiedParts, key = { "p-" + it.id }) { part ->
                    UnverifiedPartCard(part, onConfirm = { viewModel.confirmPart(part.id) }, onEdit = { onEditPart(part.id) }, onDelete = { viewModel.deletePart(part.id) })
                }
            }
        }
    }

    editing?.let { row ->
        EditRowDialog(
            row = row,
            onConfirm = { edit ->
                // No silent overwrite: if the edit now clashes with the profile, the editor gets asked.
                viewModel.confirm(row.id, edit)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
    viewing?.let { (path, page) -> PageViewerDialog(path, page, onDismiss = { viewing = null }) }
}

@Composable
private fun JobCard(line: JobLine, onResume: () -> Unit, onCancel: () -> Unit) {
    val job = line.job
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.onSurface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val title = when {
                line.running -> "Reading ${line.documentTitle}: page ${minOf(job.pagesDone + 1, job.pagesTotal)} of ${job.pagesTotal}"
                line.interrupted -> "${line.documentTitle}: paused at page ${job.pagesDone} of ${job.pagesTotal}"
                job.status == ImportJobStatus.FAILED -> "${line.documentTitle}: stopped at page ${job.pagesDone} of ${job.pagesTotal}"
                else -> "${line.documentTitle}: waiting to start"
            }
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (job.pagesTotal > 0) {
                LinearProgressIndicator(progress = { job.pagesDone.toFloat() / job.pagesTotal }, modifier = Modifier.fillMaxWidth())
            }
            if (job.status == ImportJobStatus.FAILED && job.error.isNotBlank()) {
                Text(job.error, color = WarnColor, style = MaterialTheme.typography.bodyMedium)
            }
            if (line.interrupted) {
                Text("The app closed while reading. Resume to read the rest.", style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (line.running) {
                    OutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = TouchTarget)) { Text("Stop") }
                } else {
                    Button(onClick = onResume, modifier = Modifier.heightIn(min = TouchTarget)) { Text("Resume") }
                }
            }
        }
    }
}

@Composable
private fun ImportRowCard(
    item: ReviewItem,
    onConfirm: () -> Unit,
    onReplace: () -> Unit,
    onReject: () -> Unit,
    onEdit: () -> Unit,
    onViewPage: (() -> Unit)?,
) {
    val row = item.row
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.onSurface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val kind = when (row.kind) {
                    ImportRowKind.SPEC -> row.category?.label ?: "Spec"
                    ImportRowKind.PART -> "Part · " + (row.system?.label ?: "Other")
                }
                Text(kind, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                if (row.safetyCritical) {
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Filled.Warning, contentDescription = "Safety-critical", tint = WarnColor, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.weight(1f))
                row.page?.let { page ->
                    if (onViewPage != null) TextButton(onClick = onViewPage) { Text("Page $page") }
                    else Text("Page $page", style = MaterialTheme.typography.labelLarge)
                }
            }
            Text(listOf(row.component, row.name).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodyLarge)
            Text(row.displayValue(), style = if (row.kind == ImportRowKind.PART) ValueStyle.copy(fontFamily = FontFamily.Monospace) else ValueStyle)
            if (row.notes.isNotBlank()) Text(row.notes, style = MaterialTheme.typography.bodyMedium)
            if (row.evidence.isNotBlank()) {
                Text(
                    "“${row.evidence}”",
                    style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (val m = item.match) {
                ImportMatch.New -> Text("Adds a new row", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                is ImportMatch.FillsBlank -> Text("Fills a blank field", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                is ImportMatch.Same -> Text("Matches the profile", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                is ImportMatch.Conflict -> Text("The profile says ${m.current}", style = MaterialTheme.typography.bodyLarge, color = WarnColor)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                if (item.match is ImportMatch.Conflict) {
                    Button(onClick = onReplace, modifier = Modifier.weight(1f).heightIn(min = TouchTarget), contentPadding = TightButton) { OneLine("Replace") }
                    OutlinedButton(onClick = onReject, modifier = Modifier.weight(1f).heightIn(min = TouchTarget), contentPadding = TightButton) { OneLine("Keep profile") }
                } else {
                    Button(onClick = onConfirm, modifier = Modifier.weight(1.2f).heightIn(min = TouchTarget), contentPadding = TightButton) { OneLine("Confirm") }
                    OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f).heightIn(min = TouchTarget), contentPadding = TightButton) { OneLine("Edit") }
                    OutlinedButton(onClick = onReject, modifier = Modifier.weight(1f).heightIn(min = TouchTarget), contentPadding = TightButton) { OneLine("Reject") }
                }
            }
        }
    }
}

@Composable
private fun UnverifiedSpecCard(spec: SpecItemEntity, onConfirm: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) =
    UnverifiedCard(
        title = listOf(spec.component, spec.name).filter { it.isNotBlank() }.joinToString(" · "),
        value = spec.displayValue(),
        source = spec.sourceKind.label,
        sourceUrl = spec.sourceUrl,
        safetyCritical = spec.safetyCritical,
        onConfirm = onConfirm, onEdit = onEdit, onDelete = onDelete,
    )

@Composable
private fun UnverifiedPartCard(part: PartEntity, onConfirm: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) =
    UnverifiedCard(
        title = part.component,
        value = part.copyText(),
        source = part.sourceKind.label,
        sourceUrl = part.sourceUrl,
        safetyCritical = false,
        onConfirm = onConfirm, onEdit = onEdit, onDelete = onDelete,
    )

@Composable
private fun UnverifiedCard(
    title: String,
    value: String,
    source: String,
    sourceUrl: String,
    safetyCritical: Boolean,
    onConfirm: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val uri = LocalUriHandler.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.onSurface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("From $source", style = MaterialTheme.typography.labelLarge, color = WarnColor)
                if (safetyCritical) {
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Filled.Warning, contentDescription = "Safety-critical", tint = WarnColor, modifier = Modifier.size(18.dp))
                }
            }
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(value, style = ValueStyle)
            if (isAllowedPortalUrl(sourceUrl)) {
                TextButton(onClick = { runCatching { uri.openUri(sourceUrl) } }, contentPadding = PaddingValues(0.dp)) { Text("Open the source") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = onConfirm, modifier = Modifier.weight(1.2f).heightIn(min = TouchTarget), contentPadding = TightButton) { OneLine("Confirm") }
                OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f).heightIn(min = TouchTarget), contentPadding = TightButton) { OneLine("Edit") }
                OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f).heightIn(min = TouchTarget), contentPadding = TightButton) { OneLine("Delete") }
            }
        }
    }
}

/** Three buttons share a phone-width row, so they get less side padding than the default. */
private val TightButton = PaddingValues(horizontal = 10.dp, vertical = 8.dp)

@Composable
private fun OneLine(text: String) = Text(text, maxLines = 1, softWrap = false)

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}
