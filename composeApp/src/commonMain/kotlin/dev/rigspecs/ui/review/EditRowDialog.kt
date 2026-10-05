package dev.rigspecs.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.rigspecs.data.ImportRowEntity
import dev.rigspecs.data.ImportRowKind
import dev.rigspecs.data.RowEdit
import dev.rigspecs.domain.PartSystem
import dev.rigspecs.domain.SpecCategory
import dev.rigspecs.ui.common.ChoiceField
import dev.rigspecs.ui.common.FormField
import dev.rigspecs.ui.theme.TouchTarget

/** Fix what the extraction got wrong, then confirm in one step. */
@Composable
fun EditRowDialog(row: ImportRowEntity, onConfirm: (RowEdit) -> Unit, onDismiss: () -> Unit) {
    var component by remember { mutableStateOf(row.component) }
    var name by remember { mutableStateOf(row.name) }
    var value by remember { mutableStateOf(row.value) }
    var unit by remember { mutableStateOf(row.unit) }
    var category by remember { mutableStateOf(row.category ?: SpecCategory.OTHER) }
    var system by remember { mutableStateOf(row.system ?: PartSystem.OTHER) }
    var oem by remember { mutableStateOf(row.oemNumber) }
    var refs by remember { mutableStateOf(row.aftermarketNumbers) }
    var critical by remember { mutableStateOf(row.safetyCritical) }
    val isSpec = row.kind == ImportRowKind.SPEC
    val valid = component.isNotBlank() && if (isSpec) name.isNotBlank() && value.isNotBlank() else oem.isNotBlank() || refs.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isSpec) "Fix this spec" else "Fix this part") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (isSpec) {
                    ChoiceField("Category", category, SpecCategory.entries, { it.label }, { category = it }, Modifier.fillMaxWidth())
                    FormField(component, { component = it }, "Component *")
                    FormField(name, { name = it }, "Property *")
                    FormField(value, { value = it }, "Value *")
                    FormField(unit, { unit = it }, "Unit", capitalization = KeyboardCapitalization.None)
                } else {
                    ChoiceField("System", system, PartSystem.entries, { it.label }, { system = it }, Modifier.fillMaxWidth())
                    FormField(component, { component = it }, "Part *")
                    FormField(oem, { oem = it }, "OEM part number", capitalization = KeyboardCapitalization.Characters)
                    FormField(refs, { refs = it }, "Cross-references", supporting = "One per line", singleLine = false, capitalization = KeyboardCapitalization.Characters)
                }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = TouchTarget).toggleable(critical, role = Role.Switch) { critical = it },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Safety-critical", modifier = Modifier.weight(1f))
                    Switch(checked = critical, onCheckedChange = null)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onConfirm(
                        RowEdit(
                            component = component, name = name, value = value, unit = unit,
                            category = category.takeIf { isSpec }, system = system.takeIf { !isSpec },
                            oemNumber = oem, aftermarketNumbers = refs, safetyCritical = critical,
                        ),
                    )
                },
            ) { Text("Confirm with changes") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
