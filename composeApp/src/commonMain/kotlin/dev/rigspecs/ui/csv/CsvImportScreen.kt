package dev.rigspecs.ui.csv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.rigspecs.data.ImportRowKind
import dev.rigspecs.data.RigRepository
import dev.rigspecs.domain.CsvImport
import dev.rigspecs.domain.CsvLine
import dev.rigspecs.domain.ImportMatch
import dev.rigspecs.domain.SourceKind
import dev.rigspecs.domain.SourcedResult
import dev.rigspecs.domain.displayValue
import dev.rigspecs.domain.matchImportRow
import dev.rigspecs.domain.parseRigCsv
import dev.rigspecs.domain.toImportRow
import dev.rigspecs.ui.theme.TouchTarget
import dev.rigspecs.ui.theme.WarnColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A CSV row and what importing it would do to the profile. */
data class CsvPreviewRow(val line: CsvLine.Row, val match: ImportMatch)

data class CsvImportState(
    val unitNumber: String = "",
    val fileName: String? = null,
    val parsed: CsvImport? = null,
    val preview: List<CsvPreviewRow> = emptyList(),
    val replaceConflicts: Boolean = false,
    val importing: Boolean = false,
    val result: SourcedResult? = null,
) {
    private fun count(f: (ImportMatch) -> Boolean) = preview.count { f(it.match) }
    val newRows get() = count { it == ImportMatch.New }
    val fills get() = count { it is ImportMatch.FillsBlank }
    val same get() = count { it is ImportMatch.Same }
    val conflicts get() = count { it is ImportMatch.Conflict }
    val toImport get() = newRows + fills + if (replaceConflicts) conflicts else 0
}

class CsvImportViewModel(private val vehicleId: String, private val rigs: RigRepository) : ViewModel() {
    private val _state = MutableStateFlow(CsvImportState())
    val state: StateFlow<CsvImportState> = _state.asStateFlow()

    init {
        viewModelScope.launch { _state.update { it.copy(unitNumber = rigs.vehicle(vehicleId)?.unitNumber.orEmpty()) } }
    }

    /** Parses a picked file and works out what each row would do. Nothing is written yet. */
    fun load(fileName: String, text: String) = viewModelScope.launch {
        val parsed = parseRigCsv(text)
        val specs = rigs.observeSpecs(vehicleId).first()
        val parts = rigs.observeParts(vehicleId).first()
        val preview = parsed.rows.map { line ->
            val probe = line.row.toImportRow(id = "", departmentId = "", jobId = "", vehicleId = vehicleId, now = 0, userId = "")
            CsvPreviewRow(line, matchImportRow(probe, specs, parts))
        }
        _state.update { it.copy(fileName = fileName, parsed = parsed, preview = preview, result = null) }
    }

    fun setReplace(on: Boolean) = _state.update { it.copy(replaceConflicts = on) }

    /** Writes the rows as UNVERIFIED values; an editor confirms them on the review screen. */
    fun import() {
        val s = _state.value
        if (s.importing || s.toImport == 0) return
        _state.update { it.copy(importing = true) }
        viewModelScope.launch {
            val rows = s.preview.filter { it.match !is ImportMatch.Same }.map { it.line.row }
            val result = rigs.addSourcedRows(vehicleId, SourceKind.CSV, rows, replaceConflicts = s.replaceConflicts)
            _state.update { it.copy(importing = false, result = result) }
        }
    }
}

@Composable
fun CsvImportScreen(viewModel: CsvImportViewModel, onBack: () -> Unit, onReview: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var fileError by remember { mutableStateOf<String?>(null) }
    val files = rememberCsvFiles(
        onPicked = { name, text ->
            fileError = null
            viewModel.load(name, text)
        },
        onSaved = {},
        onError = { fileError = it },
    )
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import CSV" + if (state.unitNumber.isNotBlank()) " · ${state.unitNumber}" else "") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val result = state.result
            val parsed = state.parsed
            when {
                result != null -> item(key = "result") { ResultCard(result, onReview = onReview, onDone = onBack) }
                parsed == null -> item(key = "intro") {
                    Intro(onPick = files::pick, error = fileError)
                }
                parsed.fileError != null -> item(key = "file-error") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(state.fileName.orEmpty(), style = MaterialTheme.typography.titleMedium)
                        Text(parsed.fileError, color = WarnColor, style = MaterialTheme.typography.bodyLarge)
                        OutlinedButton(onClick = files::pick, modifier = Modifier.heightIn(min = TouchTarget)) { Text("Choose another file") }
                    }
                }
                else -> {
                    item(key = "summary") { Summary(state, onReplace = viewModel::setReplace, onImport = viewModel::import, onPick = files::pick) }
                    if (parsed.errors.isNotEmpty()) {
                        item(key = "errors-head") { Heading("Rows that can't be imported") }
                        items(parsed.errors, key = { "e${it.line}" }) { e ->
                            Text("Line ${e.line}: ${e.message}", color = WarnColor, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    item(key = "rows-head") { Heading("Rows") }
                    items(state.preview, key = { "r${it.line.line}" }) { PreviewRow(it, state.replaceConflicts) }
                }
            }
        }
    }
}

@Composable
private fun Intro(onPick: () -> Unit, error: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Import specs and part numbers from a spreadsheet saved as CSV.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            "Easiest: export this rig to CSV first (rig menu → Export to CSV), fill in the blanks in a spreadsheet, " +
                "save it as CSV and import it here. Other sheets work too if their first row names the columns: " +
                "component, property, value and unit for specs; component, oem_number and cross_references for parts.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "You see what each row will do before anything is saved. Imported values are marked unverified until an editor confirms them.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        error?.let { Text(it, color = WarnColor, style = MaterialTheme.typography.bodyMedium) }
        Button(onClick = onPick, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 16.dp)) { Text("Choose a CSV file") }
    }
}

@Composable
private fun Summary(state: CsvImportState, onReplace: (Boolean) -> Unit, onImport: () -> Unit, onPick: () -> Unit) {
    val parsed = state.parsed ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(state.fileName.orEmpty(), style = MaterialTheme.typography.titleMedium)
        val parts = listOfNotNull(
            state.fills.takeIf { it > 0 }?.let { "$it ${if (it == 1) "fills a blank field" else "fill blank fields"}" },
            state.newRows.takeIf { it > 0 }?.let { "$it ${if (it == 1) "is" else "are"} new" },
            state.same.takeIf { it > 0 }?.let { "$it already ${if (it == 1) "matches" else "match"}" },
            state.conflicts.takeIf { it > 0 }?.let { "$it ${if (it == 1) "differs" else "differ"} from the profile" },
        )
        Text(
            "${state.preview.size} ${if (state.preview.size == 1) "row" else "rows"}: ${parts.joinToString(", ").ifEmpty { "nothing to change" }}.",
            style = MaterialTheme.typography.bodyLarge,
        )
        val skipped = listOfNotNull(
            parsed.blanks.takeIf { it > 0 }?.let { "$it blank ${if (it == 1) "row" else "rows"} skipped" },
            parsed.errors.size.takeIf { it > 0 }?.let { "$it with ${if (it == 1) "an error" else "errors"}" },
        )
        if (skipped.isNotEmpty()) Text(skipped.joinToString(", ") + ".", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.conflicts > 0) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = TouchTarget).toggleable(state.replaceConflicts, role = Role.Switch, onValueChange = onReplace),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (state.conflicts == 1) "Replace the value that differs" else "Replace the ${state.conflicts} values that differ",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text("Off keeps what the profile has now.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = state.replaceConflicts, onCheckedChange = null)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
            Button(
                onClick = onImport,
                enabled = state.toImport > 0 && !state.importing,
                modifier = Modifier.weight(1f).heightIn(min = TouchTarget),
            ) { Text(if (state.toImport > 0) "Import ${state.toImport} ${if (state.toImport == 1) "row" else "rows"}" else "Nothing to import") }
            OutlinedButton(onClick = onPick, modifier = Modifier.heightIn(min = TouchTarget)) { Text("Other file") }
        }
    }
}

@Composable
private fun PreviewRow(row: CsvPreviewRow, replace: Boolean) {
    val c = row.line.row
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            "Line ${row.line.line} · " + listOf(c.component, c.name).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val probeValue = if (c.kind == ImportRowKind.SPEC) listOf(c.value, c.unit).filter { it.isNotBlank() }.joinToString(" ")
        else (listOf(c.oemNumber) + c.aftermarketNumbers).filter { it.isNotBlank() }.joinToString(" · ")
        Text(probeValue, style = MaterialTheme.typography.bodyLarge)
        val (what, warn) = when (val m = row.match) {
            ImportMatch.New -> "New row" to false
            is ImportMatch.FillsBlank -> "Fills a blank field" to false
            is ImportMatch.Same -> "Already in the profile, skipped" to false
            is ImportMatch.Conflict -> (if (replace) "Replaces ${m.current}" else "Differs from ${m.current}, kept as is") to true
        }
        Text(what, style = MaterialTheme.typography.bodyMedium, color = if (warn) WarnColor else MaterialTheme.colorScheme.secondary)
        HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun ResultCard(result: SourcedResult, onReview: () -> Unit, onDone: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Imported", style = MaterialTheme.typography.titleLarge)
        val lines = listOfNotNull(
            result.filled.takeIf { it > 0 }?.let { "$it blank ${if (it == 1) "field" else "fields"} filled" },
            result.added.takeIf { it > 0 }?.let { "$it ${if (it == 1) "row" else "rows"} added" },
            result.replaced.takeIf { it > 0 }?.let { "$it ${if (it == 1) "value" else "values"} replaced" },
            result.alreadyThere.takeIf { it > 0 }?.let { "$it already matched" },
            result.conflicts.size.takeIf { it > 0 }?.let { "$it kept as ${if (it == 1) "it was" else "they were"}" },
        )
        Text(lines.joinToString(", ").ifEmpty { "Nothing changed" } + ".", style = MaterialTheme.typography.bodyLarge)
        Text(
            "They're marked unverified until an editor confirms them against the source on the review screen.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onReview, modifier = Modifier.weight(1f).heightIn(min = TouchTarget)) { Text("Review now") }
            OutlinedButton(onClick = onDone, modifier = Modifier.weight(1f).heightIn(min = TouchTarget)) { Text("Done") }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}
