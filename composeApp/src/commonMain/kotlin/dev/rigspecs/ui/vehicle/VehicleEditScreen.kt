package dev.rigspecs.ui.vehicle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rigspecs.domain.VehicleType
import dev.rigspecs.ui.common.ChoiceField
import dev.rigspecs.ui.common.FormField
import dev.rigspecs.ui.scan.rememberVinScanner
import dev.rigspecs.ui.theme.TouchTarget
import dev.rigspecs.ui.theme.WarnColor

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VehicleEditScreen(
    viewModel: VehicleEditViewModel,
    onBack: () -> Unit,
    onSaved: (id: String, isNew: Boolean) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scan = rememberVinScanner(viewModel::onScanned)
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
            Section("VIN")
            FormField(
                f.vin, viewModel::setVin, "VIN",
                error = state.vinError,
                supporting = if (scan != null) "Scan the door-jamb barcode or type it" else null,
                capitalization = KeyboardCapitalization.Characters,
                modifier = Modifier.onFocusChanged { if (!it.isFocused) viewModel.refreshWarnings() },
                trailing = scan?.let {
                    {
                        IconButton(onClick = it, modifier = Modifier.size(TouchTarget)) {
                            Icon(Icons.Filled.QrCodeScanner, contentDescription = "Scan VIN barcode")
                        }
                    }
                },
            )
            state.warnings.forEach { Text(it, color = WarnColor, style = MaterialTheme.typography.bodyMedium) }
            DecodeCard(
                state.decode,
                canDecode = f.vin.isNotBlank(),
                onDecode = viewModel::decode,
                onApply = viewModel::applyDecode,
                onDismiss = viewModel::dismissDecode,
            )

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
                onSelect = viewModel::setType,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.isNew && state.templateSize > 0) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = TouchTarget)
                        .toggleable(state.applyTemplate, role = Role.Switch, onValueChange = viewModel::setApplyTemplate),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Start with the ${f.type.label.lowercase()} template", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${state.templateSize} blank fields: oil, coolant, lug nuts, filters, wipers…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = state.applyTemplate, onCheckedChange = null)
                }
            }
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
            FormField(
                f.engineSerial, { v -> viewModel.edit { it.copy(engineSerial = v) } }, "Engine serial number",
                supporting = "Cummins ESN or Detroit serial, from the engine dataplate. Engine parts catalogs search by it.",
                capitalization = KeyboardCapitalization.Characters,
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

@Composable
private fun DecodeCard(
    decode: DecodeUi,
    canDecode: Boolean,
    onDecode: () -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (decode) {
        DecodeUi.Idle -> OutlinedButton(
            onClick = onDecode,
            enabled = canDecode,
            modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget),
        ) { Text("Decode VIN") }
        DecodeUi.Working -> Row(
            Modifier.fillMaxWidth().heightIn(min = TouchTarget),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
            Text("Asking NHTSA…", style = MaterialTheme.typography.bodyLarge)
        }
        is DecodeUi.Failed -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(decode.message, color = WarnColor, style = MaterialTheme.typography.bodyLarge)
            OutlinedButton(onClick = onDecode, modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget)) { Text("Try again") }
        }
        is DecodeUi.Applied -> Text(
            if (decode.filled.isEmpty()) "Decoded. Every field it covers was already filled in."
            else "Decoded. Filled in ${decode.filled.joinToString(", ")}. Check them before saving.",
            color = MaterialTheme.colorScheme.secondary,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        is DecodeUi.Ready -> Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val s = decode.summary
                Text("NHTSA says", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    listOfNotNull(s.year?.toString(), s.make, s.model).joinToString(" "),
                    style = MaterialTheme.typography.titleLarge,
                )
                s.engine?.let { Text("Engine: $it", style = MaterialTheme.typography.bodyLarge) }
                s.bodyClass?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (decode.checkDigitWarning) {
                    Text(
                        "The VIN's check digit doesn't add up. Make sure it matches the sticker.",
                        color = WarnColor,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Button(
                        onClick = onApply,
                        enabled = !decode.fill.isEmpty,
                        modifier = Modifier.weight(1f).heightIn(min = TouchTarget),
                    ) { Text(if (decode.fill.isEmpty) "Nothing to fill" else "Use these") }
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = TouchTarget)) { Text("Dismiss") }
                }
            }
        }
    }
}
