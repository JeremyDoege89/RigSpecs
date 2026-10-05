package dev.rigspecs.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.FireTruck
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.rigspecs.domain.VehicleType

fun VehicleType.icon(): ImageVector = when (this) {
    VehicleType.ENGINE, VehicleType.LADDER, VehicleType.TANKER, VehicleType.RESCUE, VehicleType.BRUSH ->
        Icons.Filled.FireTruck
    VehicleType.AMBULANCE -> Icons.Filled.LocalHospital
    VehicleType.CAR -> Icons.Filled.DirectionsCar
    VehicleType.OTHER -> Icons.Filled.LocalShipping
}

/** Puts text on the system clipboard. Platform code supplies it. */
fun interface Copier {
    fun copy(text: String)
}

@Composable
expect fun rememberCopier(): Copier

@Composable
fun FormField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    supporting: String? = null,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        isError = error != null,
        supportingText = (error ?: supporting)?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        enabled = enabled,
        trailingIcon = trailing,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, capitalization = capitalization),
    )
}

/** A read-only dropdown over a fixed list of choices. */
@Composable
fun <T> ChoiceField(
    label: String,
    selected: T,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    supporting: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = optionLabel(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            isError = error != null,
            supportingText = (error ?: supporting)?.let { { Text(it) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: @Composable (() -> Unit)? = null) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(
                body,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            action?.invoke()
        }
    }
}

/**
 * Extended FAB whose label reaches screen readers and tests. On this Material 3 version the inner
 * Text of ExtendedFloatingActionButton is missing from the semantics tree (the node is a nameless
 * button), so the label, role and click are declared explicitly.
 */
@Composable
fun LabeledFab(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        icon = { Icon(icon, contentDescription = null) },
        text = { Text(text) },
        modifier = modifier.clearAndSetSemantics {
            contentDescription = text
            role = Role.Button
            onClick { onClick(); true }
        },
    )
}

/** Bottom padding that keeps the last list row clear of a floating action button. */
val FabClearance = PaddingValues(bottom = 96.dp)

/** Intercepts the system back gesture where there is one (Android); a no-op on the desktop. */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)

/** Asks before leaving a form with unsaved entries. */
@Composable
fun DiscardChangesDialog(onDiscard: () -> Unit, onKeepEditing: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeepEditing,
        title = { Text("Discard changes?") },
        text = { Text("What you entered on this form hasn't been saved.") },
        confirmButton = {
            TextButton(onClick = onDiscard) { Text("Discard", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onKeepEditing) { Text("Keep editing") } },
    )
}
