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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rigspecs.data.VehicleEntity
import dev.rigspecs.domain.description
import dev.rigspecs.ui.common.EmptyState
import dev.rigspecs.ui.common.LabeledFab
import dev.rigspecs.ui.common.icon
import dev.rigspecs.ui.theme.TouchTarget
import dev.rigspecs.ui.theme.WarnColor

@Composable
fun FleetScreen(
    viewModel: FleetViewModel,
    onOpenVehicle: (String) -> Unit,
    onAddVehicle: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
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
                placeholder = { Text("Search rigs") },
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
                state.vehicles.isEmpty() -> EmptyState(
                    title = "Nothing matches",
                    body = "Try fewer words or clear the filters.",
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.vehicles, key = { it.id }) { vehicle ->
                        VehicleCard(vehicle, toReview = state.toReview[vehicle.id] ?: 0, onClick = { onOpenVehicle(vehicle.id) })
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
