package dev.rigspecs.ui.profile

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rigspecs.data.DocumentEntity
import dev.rigspecs.data.PartEntity
import dev.rigspecs.data.SpecItemEntity
import dev.rigspecs.data.VehicleEntity
import dev.rigspecs.domain.ProfileTab
import dev.rigspecs.domain.copyText
import dev.rigspecs.domain.description
import dev.rigspecs.domain.displayValue
import dev.rigspecs.domain.vinDecodeDetails
import dev.rigspecs.ui.common.ConfirmDialog
import dev.rigspecs.ui.common.EmptyState
import dev.rigspecs.ui.common.LabeledFab
import dev.rigspecs.ui.common.icon
import dev.rigspecs.ui.common.rememberCopier
import dev.rigspecs.ui.theme.TouchTarget
import dev.rigspecs.ui.theme.ValueStyle
import dev.rigspecs.ui.theme.WarnColor
import kotlinx.coroutines.launch

@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    onBack: () -> Unit,
    onEditVehicle: () -> Unit,
    onAddSpec: (ProfileTab) -> Unit,
    onEditSpec: (String) -> Unit,
    onAddPart: () -> Unit,
    onEditPart: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copier = rememberCopier()
    val copy: (String, String) -> Unit = { text, what ->
        if (text.isNotBlank()) {
            copier.copy(text)
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar("Copied $what: $text")
            }
        }
    }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var docDialog by remember { mutableStateOf<DocumentEntity?>(null) }
    var docDialogOpen by remember { mutableStateOf(false) }
    var docToDelete by remember { mutableStateOf<DocumentEntity?>(null) }

    // Deleted here or by another phone through sync: there's nothing left to show.
    LaunchedEffect(state.loading, state.vehicle) { if (!state.loading && state.vehicle == null) onBack() }
    val vehicle = state.vehicle ?: return

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(vehicle.unitNumber) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (state.canEdit) {
                        IconButton(onClick = onEditVehicle) { Icon(Icons.Filled.Edit, contentDescription = "Edit rig") }
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Delete rig", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    menuOpen = false
                                    confirmDelete = true
                                },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (state.canEdit) {
                val (label, action) = when (state.tab) {
                    ProfileTab.PARTS -> "Add part" to onAddPart
                    ProfileTab.DOCS -> "Add document" to { docDialog = null; docDialogOpen = true }
                    else -> "Add spec" to { onAddSpec(state.tab) }
                }
                LabeledFab(label, Icons.Filled.Add, action)
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Header(vehicle, onCopyVin = { copy(vehicle.vin, "VIN") })
            PrimaryTabRow(selectedTabIndex = state.tab.ordinal) {
                ProfileTab.entries.forEach { tab ->
                    // The content overload skips the text overload's 16dp side padding, so all five
                    // labels fit on a phone without scrolling.
                    Tab(
                        selected = state.tab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        modifier = Modifier.heightIn(min = TouchTarget),
                        selectedContentColor = MaterialTheme.colorScheme.primary,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ) {
                        Text(tab.label, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
                    }
                }
            }
            when (state.tab) {
                ProfileTab.OVERVIEW -> OverviewTab(state, copy, onEditSpec)
                ProfileTab.FLUIDS, ProfileTab.TORQUE -> {
                    val specs = state.specsFor(state.tab)
                    if (specs.isEmpty()) {
                        EmptyState(
                            "No ${state.tab.label.lowercase()} specs yet",
                            if (state.canEdit) "Add one with the button below." else "An editor hasn't entered any yet.",
                        )
                    } else {
                        LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                            specRows(specs, state.documentsById, state.canEdit, copy, onEditSpec)
                        }
                    }
                }
                ProfileTab.PARTS -> PartsTab(state, copy, onEditPart)
                ProfileTab.DOCS -> DocsTab(state) { docDialog = it; docDialogOpen = true }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete ${vehicle.unitNumber}?",
            text = "Its specs, parts and documents go too, on every phone once it syncs.",
            confirmLabel = "Delete",
            onConfirm = {
                confirmDelete = false
                viewModel.deleteVehicle(onBack)
            },
            onDismiss = { confirmDelete = false },
        )
    }
    if (docDialogOpen) {
        val existing = docDialog
        DocumentDialog(
            existing = existing,
            onSave = { title, kind, page ->
                viewModel.saveDocument(existing?.id.orEmpty(), title, kind, page)
                docDialogOpen = false
            },
            onDelete = existing?.let { { docDialogOpen = false; docToDelete = it } },
            onDismiss = { docDialogOpen = false },
        )
    }
    docToDelete?.let { doc ->
        ConfirmDialog(
            title = "Delete ${doc.title}?",
            text = "Specs and parts that cite it keep their values but lose the source link.",
            confirmLabel = "Delete",
            onConfirm = {
                viewModel.deleteDocument(doc.id)
                docToDelete = null
            },
            onDismiss = { docToDelete = null },
        )
    }
}

@Composable
private fun Header(vehicle: VehicleEntity, onCopyVin: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(vehicle.type.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            val line = listOf(vehicle.description(), vehicle.type.label, vehicle.station).filter { it.isNotBlank() }
            Text(line.joinToString(" · "), style = MaterialTheme.typography.bodyLarge)
            if (vehicle.vin.isNotBlank()) {
                Text(
                    "VIN ${vehicle.vin}",
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable(onClick = onCopyVin).padding(vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun OverviewTab(state: ProfileState, copy: (String, String) -> Unit, onEditSpec: (String) -> Unit) {
    val v = state.vehicle ?: return
    val details = listOf(
        "VIN" to v.vin,
        "Year" to (v.year?.toString() ?: ""),
        "Make" to v.make,
        "Model" to v.model,
        "Engine" to v.engine,
        "Chassis" to v.chassisMake,
        "Body builder" to v.bodyBuilder,
        "Builder job / serial" to v.builderJobNumber,
        "Station" to v.station,
    ).filter { it.second.isNotBlank() }
    val specs = state.specsFor(ProfileTab.OVERVIEW)
    val decodeDetails = remember(v.vinDecodeJson) { vinDecodeDetails(v.vinDecodeJson) }
    val blanks = state.specs.count { it.value.isBlank() } +
        state.parts.count { it.oemNumber.isBlank() && it.aftermarketNumbers.isBlank() }
    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
        if (blanks > 0 && state.canEdit) {
            item(key = "blanks") {
                Text(
                    "$blanks ${if (blanks == 1) "field is" else "fields are"} still blank. Fill them in from the Fluids, Torque and Parts tabs.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
        items(details, key = { "d-" + it.first }) { (label, value) ->
            CopyRow(label = label, value = value, onClick = { copy(value, label) })
        }
        if (decodeDetails.isNotEmpty()) {
            item(key = "decode") { SectionLabel("From the VIN decode") }
            items(decodeDetails, key = { "v-" + it.first }) { (label, value) ->
                CopyRow(label = label, value = value, onClick = { copy(value, label) })
            }
        }
        if (v.notes.isNotBlank()) {
            item(key = "notes") {
                Column(Modifier.padding(16.dp)) {
                    Text("Notes", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(v.notes, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        if (specs.isNotEmpty()) {
            item(key = "other-specs") { SectionLabel("Other specs") }
            specRows(specs, state.documentsById, state.canEdit, copy, onEditSpec)
        }
    }
}

/** Specs grouped under their component ("Engine oil": capacity, type, …). */
private fun LazyListScope.specRows(
    specs: List<SpecItemEntity>,
    docs: Map<String, DocumentEntity>,
    canEdit: Boolean,
    copy: (String, String) -> Unit,
    onEdit: (String) -> Unit,
) {
    specs.groupBy { it.component.trim() }.forEach { (component, group) ->
        item(key = "c-$component-${group.first().id}") { SectionLabel(component) }
        items(group, key = { it.id }) { spec ->
            // A blank has nothing to copy, so tapping it goes straight to filling it in.
            val tap = if (spec.value.isBlank() && canEdit) ({ onEdit(spec.id) }) else ({ copy(spec.displayValue(), spec.name) })
            SpecRow(spec, spec.sourceDocId?.let(docs::get), canEdit, onCopy = tap, onEdit = { onEdit(spec.id) })
        }
    }
}

@Composable
private fun SpecRow(spec: SpecItemEntity, source: DocumentEntity?, canEdit: Boolean, onCopy: () -> Unit, onEdit: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = TouchTarget).clickable(onClick = onCopy).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(spec.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (spec.safetyCritical) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Filled.Warning, contentDescription = "Safety-critical", tint = WarnColor, modifier = Modifier.size(18.dp))
                }
            }
            if (spec.value.isBlank()) {
                Text(
                    if (spec.unit.isBlank()) "Not filled in" else "Not filled in (${spec.unit})",
                    style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(spec.displayValue(), style = ValueStyle)
            }
            if (spec.notes.isNotBlank()) Text(spec.notes, style = MaterialTheme.typography.bodyMedium)
            if (spec.safetyCritical) {
                Text("Verify against the manual before use", style = MaterialTheme.typography.bodyMedium, color = WarnColor)
            }
            source?.let {
                Text("Source: ${it.citation()}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
            }
        }
        if (spec.value.isNotBlank()) {
            Icon(Icons.Filled.ContentCopy, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        if (canEdit) {
            IconButton(onClick = onEdit, modifier = Modifier.size(TouchTarget)) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit ${spec.component} ${spec.name}")
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
    }
    HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun PartsTab(state: ProfileState, copy: (String, String) -> Unit, onEditPart: (String) -> Unit) {
    if (state.parts.isEmpty()) {
        EmptyState("No parts yet", if (state.canEdit) "Add filters, belts, pads and their numbers." else "An editor hasn't entered any yet.")
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
        state.parts.groupBy { it.system }.entries.sortedBy { it.key.ordinal }.forEach { (system, group) ->
            item(key = "s-$system") { SectionLabel(system.label) }
            items(group, key = { it.id }) { part ->
                PartRow(
                    part,
                    part.sourceDocId?.let(state.documentsById::get),
                    state.canEdit,
                    onCopy = if (part.copyText().isBlank() && state.canEdit) ({ onEditPart(part.id) }) else ({ copy(part.copyText(), part.component) }),
                    onEdit = { onEditPart(part.id) },
                )
            }
        }
    }
}

@Composable
private fun PartRow(part: PartEntity, source: DocumentEntity?, canEdit: Boolean, onCopy: () -> Unit, onEdit: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = TouchTarget).clickable(onClick = onCopy).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            val qty = part.qtyPerVehicle?.let { " ×$it" }.orEmpty()
            Text(part.component + qty, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (part.oemNumber.isNotBlank()) {
                Text(part.oemNumber, style = ValueStyle.copy(fontFamily = FontFamily.Monospace))
            } else if (part.aftermarketNumbers.isBlank()) {
                Text(
                    "No part number yet",
                    style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            part.aftermarketNumbers.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach {
                Text(it, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace))
            }
            if (part.supplier.isNotBlank()) Text("Supplier: ${part.supplier}", style = MaterialTheme.typography.bodyMedium)
            if (part.notes.isNotBlank()) Text(part.notes, style = MaterialTheme.typography.bodyMedium)
            source?.let {
                Text("Source: ${it.citation()}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
            }
        }
        if (part.copyText().isNotBlank()) {
            Icon(Icons.Filled.ContentCopy, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        if (canEdit) {
            IconButton(onClick = onEdit, modifier = Modifier.size(TouchTarget)) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit ${part.component}")
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
    }
    HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun DocsTab(state: ProfileState, onOpen: (DocumentEntity) -> Unit) {
    if (state.documents.isEmpty()) {
        EmptyState(
            "No documents yet",
            "List the manuals and build sheets here so specs and parts can point to the page they came from.",
        )
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
        items(state.documents, key = { it.id }) { doc ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = TouchTarget)
                    .clickable(enabled = state.canEdit) { onOpen(doc) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Description, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(doc.title, style = MaterialTheme.typography.bodyLarge)
                    val sub = listOf(doc.kind.label, doc.pageHint).filter { it.isNotBlank() }.joinToString(" · ")
                    Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun CopyRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = TouchTarget).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1.6f))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}
