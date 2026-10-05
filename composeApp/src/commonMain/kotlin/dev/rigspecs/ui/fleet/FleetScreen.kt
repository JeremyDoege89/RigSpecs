package dev.rigspecs.ui.fleet

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import dev.rigspecs.data.VehicleEntity
import dev.rigspecs.domain.ProfileTab
import dev.rigspecs.domain.SearchHit
import dev.rigspecs.domain.copyText
import dev.rigspecs.domain.description
import dev.rigspecs.domain.displayValue
import dev.rigspecs.domain.profileTab
import dev.rigspecs.ui.common.EmptyState
import dev.rigspecs.ui.common.LabeledFab
import dev.rigspecs.ui.common.icon
import dev.rigspecs.ui.common.rememberCopier
import dev.rigspecs.ui.theme.TouchTarget
import dev.rigspecs.ui.theme.ValueStyle
import dev.rigspecs.ui.theme.WarnColor

@Composable
fun FleetScreen(
    viewModel: FleetViewModel,
    onOpenVehicle: (String) -> Unit,
    onAddVehicle: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTab: (vehicleId: String, tab: ProfileTab) -> Unit,
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
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Fleet") },
                actions = {
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                },
            )
        },
        floatingActionButton = {
            if (state.canEdit) {
                LabeledFab("Add rig", Icons.Filled.Add, onAddVehicle)
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.filter.query,
                onValueChange = viewModel::setQuery,
                placeholder = { Text("Search rigs, specs and part numbers") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.filter.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setQuery("") }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (state.typesInFleet.size > 1 || state.stations.size > 1) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (state.typesInFleet.size > 1) {
                        items(state.typesInFleet) { type ->
                            FilterChip(
                                selected = type in state.filter.types,
                                onClick = { viewModel.toggleType(type) },
                                label = { Text(type.label) },
                                leadingIcon = { Icon(type.icon(), contentDescription = null, Modifier.size(18.dp)) },
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                        }
                    }
                    if (state.stations.size > 1) {
                        items(state.stations) { station ->
                            FilterChip(
                                selected = station in state.filter.stations,
                                onClick = { viewModel.toggleStation(station) },
                                label = { Text(station) },
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                        }
                    }
                }
            }
            when {
                state.loading -> Unit
                state.totalCount == 0 -> EmptyState(
                    title = "No rigs yet",
                    body = if (state.canEdit) "Add the first rig with the button below." else "An editor hasn't added any rigs yet.",
                )
                state.vehicles.isEmpty() && state.searchGroups.isEmpty() -> EmptyState(
                    title = "Nothing matches",
                    body = "Try fewer words or clear the filters.",
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (state.searching && state.vehicles.isNotEmpty()) item(key = "rigs-head") { SearchHeading("Rigs") }
                    items(state.vehicles, key = { it.id }) { vehicle ->
                        VehicleCard(vehicle, toReview = state.toReview[vehicle.id] ?: 0, onClick = { onOpenVehicle(vehicle.id) })
                    }
                    if (state.searchGroups.isNotEmpty()) {
                        item(key = "hits-head") {
                            SearchHeading("Specs and parts · ${state.hitCount}")
                        }
                        state.searchGroups.forEach { group ->
                            item(key = "g-${group.vehicle.id}") { HitGroupHeader(group.vehicle) }
                            items(group.hits, key = { "h-" + (it.spec?.id ?: it.part?.id) }) { hit ->
                                SearchHitRow(
                                    hit,
                                    onCopy = {
                                        hit.spec?.let { copy(it.displayValue(), it.name) }
                                        hit.part?.let { copy(it.copyText(), it.component) }
                                    },
                                    onOpen = { onOpenTab(group.vehicle.id, hit.spec?.profileTab() ?: ProfileTab.PARTS) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VehicleCard(vehicle: VehicleEntity, toReview: Int, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget + 16.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                vehicle.type.icon(),
                contentDescription = vehicle.type.label,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(vehicle.unitNumber, style = MaterialTheme.typography.titleLarge)
                // The icon already shows the type; spell it out only when there's nothing else to say.
                val line = vehicle.description().ifEmpty { vehicle.type.label }
                Text(
                    line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (toReview > 0) {
                    Text("$toReview to review", style = MaterialTheme.typography.labelLarge, color = WarnColor)
                }
            }
            if (vehicle.station.isNotBlank()) {
                Spacer(Modifier.width(12.dp))
                Text(
                    vehicle.station,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}

@Composable
private fun SearchHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun HitGroupHeader(vehicle: VehicleEntity) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(vehicle.type.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(vehicle.unitNumber, style = MaterialTheme.typography.titleMedium)
        if (vehicle.station.isNotBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(vehicle.station, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A matching spec or part: tap to copy its value, the arrow opens it on the rig. */
@Composable
private fun SearchHitRow(hit: SearchHit, onCopy: () -> Unit, onOpen: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget).clickable(onClick = onCopy),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.onSurface),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val spec = hit.spec
                val part = hit.part
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (spec != null) "${spec.component} · ${spec.name}" else part?.component.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (spec?.safetyCritical == true) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Filled.Warning, contentDescription = "Safety-critical", tint = WarnColor, modifier = Modifier.size(16.dp))
                    }
                }
                if (spec != null) Text(spec.displayValue(), style = ValueStyle)
                if (part != null) {
                    if (part.oemNumber.isNotBlank()) Text(part.oemNumber, style = ValueStyle.copy(fontFamily = FontFamily.Monospace))
                    val refs = part.aftermarketNumbers.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    if (refs.isNotEmpty()) Text(refs.joinToString(" · "), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
                }
                if (hit.isUnverified) Text("Unverified", style = MaterialTheme.typography.labelLarge, color = WarnColor)
            }
            IconButton(onClick = onOpen, modifier = Modifier.size(TouchTarget)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Open on the rig")
            }
        }
    }
}
