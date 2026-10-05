package dev.rigspecs.ui.vehicle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rigspecs.domain.VehicleType
import dev.rigspecs.ui.common.ChoiceField
import dev.rigspecs.ui.common.FormField
import dev.rigspecs.ui.theme.WarnColor

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VehicleEditScreen(
    viewModel: VehicleEditViewModel,
    onBack: () -> Unit,
    onSaved: (id: String, isNew: Boolean) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.savedId) { state.savedId?.let { onSaved(it, state.isNew) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isNew) "Add rig" else "Edit rig") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        if (state.loading) return@Scaffold
        val f = state.form
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Section("Identity")
            FormField(
                f.unitNumber, { v -> viewModel.edit { it.copy(unitNumber = v) } }, "Unit number *",
                error = state.errors["unitNumber"], supporting = "What the crew calls it: Engine 2, Medic 3",
                capitalization = KeyboardCapitalization.Words,
            )
            ChoiceField(
                label = "Type",
                selected = f.type,
                options = VehicleType.entries,
                optionLabel = { it.label },
                onSelect = { t -> viewModel.edit { it.copy(type = t) } },
                modifier = Modifier.fillMaxWidth(),
            )
            FormField(
                f.station, { v -> viewModel.edit { it.copy(station = v) } }, "Station",
                capitalization = KeyboardCapitalization.Words,
            )
            val picks = state.knownStations.filter { it != f.station.trim() }
            if (picks.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    picks.forEach { s ->
                        AssistChip(onClick = { viewModel.edit { it.copy(station = s) } }, label = { Text(s) })
                    }
                }
            }

            Section("Vehicle")
            FormField(
                f.vin, { v -> viewModel.edit { it.copy(vin = v) } }, "VIN",
                capitalization = KeyboardCapitalization.Characters,
                modifier = Modifier.onFocusChanged { if (!it.isFocused) viewModel.refreshWarnings() },
            )
            state.warnings.forEach { Text(it, color = WarnColor, style = MaterialTheme.typography.bodyMedium) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FormField(
                    f.year, { v -> viewModel.edit { it.copy(year = v.filter(Char::isDigit).take(4)) } }, "Year",
                    error = state.errors["year"], keyboardType = KeyboardType.Number,
                    modifier = Modifier.weight(1f).widthIn(max = 140.dp),
                )
                FormField(
                    f.make, { v -> viewModel.edit { it.copy(make = v) } }, "Make",
                    capitalization = KeyboardCapitalization.Words, modifier = Modifier.weight(2f),
                )
            }
            FormField(
                f.model, { v -> viewModel.edit { it.copy(model = v) } }, "Model",
                capitalization = KeyboardCapitalization.Words,
            )
            FormField(
                f.engine, { v -> viewModel.edit { it.copy(engine = v) } }, "Engine",
                supporting = "e.g. Cummins X12 500 hp", capitalization = KeyboardCapitalization.Words,
            )

            Section("Build")
            FormField(
                f.chassisMake, { v -> viewModel.edit { it.copy(chassisMake = v) } }, "Chassis make",
                supporting = "Ford, Freightliner, Pierce custom…", capitalization = KeyboardCapitalization.Words,
            )
            FormField(
                f.bodyBuilder, { v -> viewModel.edit { it.copy(bodyBuilder = v) } }, "Body builder",
                capitalization = KeyboardCapitalization.Words,
            )
            FormField(
                f.builderJobNumber, { v -> viewModel.edit { it.copy(builderJobNumber = v) } }, "Builder job / serial number",
                supporting = "What Pierce, E-One or the ambulance builder asks for when ordering parts",
                capitalization = KeyboardCapitalization.Characters,
            )
            FormField(
                f.notes, { v -> viewModel.edit { it.copy(notes = v) } }, "Notes", singleLine = false,
            )
            Button(
                onClick = viewModel::save,
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
            ) { Text(if (state.isNew) "Add rig" else "Save") }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}
