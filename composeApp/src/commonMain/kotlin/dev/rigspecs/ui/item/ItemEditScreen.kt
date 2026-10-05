package dev.rigspecs.ui.item

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rigspecs.data.DocumentEntity
import dev.rigspecs.domain.CommonUnits
import dev.rigspecs.domain.PartSystem
import dev.rigspecs.domain.SourceKind
import dev.rigspecs.domain.SpecCategory
import dev.rigspecs.domain.VerificationStatus
import dev.rigspecs.domain.isAllowedPortalUrl
import dev.rigspecs.domain.formatTimestamp
import dev.rigspecs.ui.common.ChoiceField
import dev.rigspecs.ui.common.ConfirmDialog
import dev.rigspecs.ui.common.FormField
import dev.rigspecs.ui.profile.DocumentDialog
import dev.rigspecs.ui.theme.TouchTarget
import dev.rigspecs.ui.theme.WarnColor

/** Sentinel entries in the source picker. */
private sealed interface SourceChoice {
    data object None : SourceChoice
    data object AddNew : SourceChoice
    data class Doc(val doc: DocumentEntity) : SourceChoice
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ItemEditScreen(viewModel: ItemEditViewModel, onClose: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) onClose() }
    var confirmDelete by remember { mutableStateOf(false) }
    var addDocument by remember { mutableStateOf(false) }
    val isSpec = state.kind == ItemKind.SPEC
    val noun = if (isSpec) "spec" else "part"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isNew) "Add $noun" else "Edit $noun") },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (!state.isNew) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete $noun")
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (state.loading) return@Scaffold
        val f = state.form
        val unverified = state.status == VerificationStatus.UNVERIFIED
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (unverified) {
                UnverifiedNotice(state.sourceKind, state.sourceUrl)
            }
            if (isSpec) {
                ChoiceField("Category", f.category, SpecCategory.entries, { it.label }, { c -> viewModel.edit { it.copy(category = c) } }, Modifier.fillMaxWidth())
                FormField(
                    f.component, { v -> viewModel.edit { it.copy(component = v) } }, "Component *",
                    error = state.errors["component"], supporting = "Engine oil, Lug nuts, Front axle",
                    capitalization = KeyboardCapitalization.Sentences,
                )
                FormField(
                    f.name, { v -> viewModel.edit { it.copy(name = v) } }, "Property *",
                    error = state.errors["name"], supporting = "Capacity, Torque, Type, Pressure",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FormField(
                        f.value, { v -> viewModel.edit { it.copy(value = v) } }, "Value *",
                        error = state.errors["value"], modifier = Modifier.weight(2f),
                    )
                    FormField(
                        f.unit, { v -> viewModel.edit { it.copy(unit = v) } }, "Unit",
                        capitalization = KeyboardCapitalization.None, modifier = Modifier.weight(1f),
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CommonUnits.forEach { u -> AssistChip(onClick = { viewModel.edit { it.copy(unit = u) } }, label = { Text(u) }) }
                }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = TouchTarget)
                        .toggleable(f.safetyCritical, role = Role.Switch, onValueChange = viewModel::setSafetyCritical),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Safety-critical", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Brakes, wheels, aerial, pump. Shows a verify-against-the-manual warning and needs a source.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = f.safetyCritical, onCheckedChange = null)
                }
            } else {
                ChoiceField("System", f.system, PartSystem.entries, { it.label }, { s -> viewModel.edit { it.copy(system = s) } }, Modifier.fillMaxWidth())
                FormField(
                    f.component, { v -> viewModel.edit { it.copy(component = v) } }, "Part *",
                    error = state.errors["component"], supporting = "Oil filter, Front brake pads",
                )
                FormField(
                    f.oemNumber, { v -> viewModel.edit { it.copy(oemNumber = v) } }, "OEM part number",
                    capitalization = KeyboardCapitalization.Characters,
                )
                FormField(
                    f.aftermarketNumbers, { v -> viewModel.edit { it.copy(aftermarketNumbers = v) } }, "Cross-references",
                    supporting = "One per line: NAPA 1792, Wix 51792", singleLine = false,
                    capitalization = KeyboardCapitalization.Characters,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FormField(
                        f.supplier, { v -> viewModel.edit { it.copy(supplier = v) } }, "Supplier",
                        capitalization = KeyboardCapitalization.Words, modifier = Modifier.weight(2f),
                    )
                    FormField(
                        f.qty, { v -> viewModel.edit { it.copy(qty = v.filter(Char::isDigit).take(4)) } }, "Qty",
                        error = state.errors["qty"], keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f),
                    )
                }
            }
            FormField(f.notes, { v -> viewModel.edit { it.copy(notes = v) } }, "Notes", singleLine = false)

            val choices = listOf(SourceChoice.None) + state.documents.map { SourceChoice.Doc(it) } + SourceChoice.AddNew
            val selected = state.documents.firstOrNull { it.id == f.sourceDocId }?.let { SourceChoice.Doc(it) } ?: SourceChoice.None
            ChoiceField(
                label = if (isSpec && f.safetyCritical) "Source document *" else "Source document",
                selected = selected,
                options = choices,
                optionLabel = {
                    when (it) {
                        SourceChoice.None -> "None"
                        SourceChoice.AddNew -> "Add a document…"
                        is SourceChoice.Doc -> it.doc.title
                    }
                },
                onSelect = { choice ->
                    when (choice) {
                        SourceChoice.None -> viewModel.edit { it.copy(sourceDocId = null) }
                        SourceChoice.AddNew -> addDocument = true
                        is SourceChoice.Doc -> viewModel.edit { it.copy(sourceDocId = choice.doc.id) }
                    }
                },
                error = state.errors["source"],
                supporting = "The manual or build sheet this came from",
                modifier = Modifier.fillMaxWidth(),
            )
            if (f.sourceDocId != null) {
                FormField(f.pageHint, { v -> viewModel.edit { it.copy(pageHint = v) } }, "Page", supporting = "Where it's printed: p. 212, Section 4")
            }
            if (isSpec && f.safetyCritical) {
                Text("Verify against the manual before saving.", color = WarnColor, style = MaterialTheme.typography.bodyMedium)
            }
            state.verifiedAt?.takeIf { !unverified }?.let { at ->
                Text(
                    "Checked by ${state.verifiedBy} on ${formatTimestamp(at)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            state.lastChangedAt?.let { at ->
                Text(
                    "Last changed ${formatTimestamp(at)} by ${state.lastChangedBy}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (unverified) {
                Button(
                    onClick = { viewModel.save(confirm = true) },
                    enabled = !state.saving,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    contentPadding = PaddingValues(vertical = 16.dp),
                ) { Text("Confirm: I checked it") }
                OutlinedButton(
                    onClick = { viewModel.save(confirm = false) },
                    enabled = !state.saving,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    contentPadding = PaddingValues(vertical = 16.dp),
                ) { Text("Save without confirming") }
            } else {
                Button(
                    onClick = { viewModel.save() },
                    enabled = !state.saving,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    contentPadding = PaddingValues(vertical = 16.dp),
                ) { Text("Save") }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete this $noun?",
            text = "It is removed from every phone once it syncs. The change history keeps the old value.",
            confirmLabel = "Delete",
            onConfirm = {
                confirmDelete = false
                viewModel.delete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
    if (addDocument) {
        DocumentDialog(
            existing = null,
            onSave = { title, kind, page ->
                viewModel.addDocument(title, kind, page)
                addDocument = false
            },
            onDelete = null,
            onDismiss = { addDocument = false },
        )
    }
}

@Composable
private fun UnverifiedNotice(source: SourceKind, url: String) {
    val uri = LocalUriHandler.current
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp)).padding(12.dp),
    ) {
        Text("Unverified · from ${source.label}", color = WarnColor, style = MaterialTheme.typography.titleMedium)
        Text(
            "Nobody has checked this against the manual yet. Confirm it once you have.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (isAllowedPortalUrl(url)) {
            TextButton(onClick = { runCatching { uri.openUri(url) } }, contentPadding = PaddingValues(0.dp)) { Text("Open the source") }
        }
    }
}
