package dev.rigspecs.ui.importing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.rigspecs.data.DocumentEntity
import dev.rigspecs.data.ImportRepository
import dev.rigspecs.data.RigRepository
import dev.rigspecs.data.SettingsRepository
import dev.rigspecs.data.importing.DocumentFiles
import dev.rigspecs.data.importing.DocumentPages
import dev.rigspecs.data.importing.ImportRunner
import dev.rigspecs.data.importing.formatPageRanges
import dev.rigspecs.data.importing.isPdfPath
import dev.rigspecs.data.importing.parsePageRanges
import dev.rigspecs.ui.common.FormField
import dev.rigspecs.ui.theme.TouchTarget
import dev.rigspecs.ui.theme.WarnColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ImportSetupState(
    val loading: Boolean = true,
    val document: DocumentEntity? = null,
    val isPdf: Boolean = true,
    val pageCount: Int = 0,
    val pagesText: String = "",
    val dense: Boolean = false,
    val ready: Boolean = false,
    val earlierRuns: Int = 0,
    /** Why the file can't be read, if it can't. */
    val problem: String? = null,
    val starting: Boolean = false,
    /** Set when the job has started; the screen moves on to review. */
    val startedJobId: String? = null,
) {
    val selected: List<Int>? get() = if (pageCount == 0) null else parsePageRanges(pagesText, pageCount)
}

class ImportSetupViewModel(
    private val documentId: String,
    private val rigs: RigRepository,
    private val imports: ImportRepository,
    private val settings: SettingsRepository,
    private val runner: ImportRunner,
    private val pages: DocumentPages,
    private val files: DocumentFiles,
) : ViewModel() {
    private val _state = MutableStateFlow(ImportSetupState())
    val state: StateFlow<ImportSetupState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val doc = rigs.document(documentId)
            val path = doc?.localPath.orEmpty()
            val problem = when {
                doc == null -> "This document was deleted."
                path.isBlank() || !files.exists(path) -> "This document has no file on this device. Add the PDF or a photo to import from it."
                else -> null
            }
            val count = if (problem == null) runCatching { pages.pageCount(path) }.getOrNull() else null
            _state.update {
                it.copy(
                    loading = false,
                    document = doc,
                    isPdf = isPdfPath(path),
                    pageCount = count ?: 0,
                    problem = problem ?: if (count == null) "This file couldn't be opened. Is it a PDF or a picture?" else null,
                    ready = settings.current().importReady,
                    earlierRuns = imports.jobsForDocument(documentId).size,
                )
            }
        }
    }

    fun setPages(text: String) = _state.update { it.copy(pagesText = text) }
    fun setDense(on: Boolean) = _state.update { it.copy(dense = on) }

    fun start() {
        val s = _state.value
        val doc = s.document ?: return
        val selected = s.selected ?: return
        if (s.starting || !s.ready) return
        _state.update { it.copy(starting = true) }
        viewModelScope.launch {
            val all = selected.size == s.pageCount
            val jobId = imports.startJob(doc.vehicleId, doc.id, if (all) "" else formatPageRanges(selected), selected.size, s.dense)
            runner.start(jobId)
            _state.update { it.copy(starting = false, startedJobId = jobId) }
        }
    }
}

@Composable
fun ImportSetupScreen(
    viewModel: ImportSetupViewModel,
    onBack: () -> Unit,
    onStarted: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.startedJobId) { if (state.startedJobId != null) onStarted() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import specs") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        if (state.loading) return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(state.document?.title ?: "Document", style = MaterialTheme.typography.titleLarge)
            state.problem?.let {
                Text(it, color = WarnColor, style = MaterialTheme.typography.bodyLarge)
                return@Column
            }
            Text(
                "The pages go to the department's extraction service, which reads specs and part numbers off them. " +
                    "You check every row before anything reaches the profile.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!state.ready) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.onSurface),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Assisted import isn't set up on this device", style = MaterialTheme.typography.titleMedium)
                        Text("It needs the department's server address, key and import passphrase, or it's switched off in Settings.", style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = onOpenSettings, modifier = Modifier.heightIn(min = TouchTarget)) { Text("Open Settings") }
                    }
                }
            }
            if (state.isPdf) {
                Text("${state.pageCount} pages", style = MaterialTheme.typography.titleMedium)
                val selected = state.selected
                FormField(
                    state.pagesText, viewModel::setPages, "Pages to read",
                    error = if (selected == null) "Use page numbers and ranges up to ${state.pageCount}, like 4, 210-230" else null,
                    supporting = when {
                        selected == null -> null
                        state.pagesText.isBlank() -> "All ${state.pageCount} pages. Most rigs only need the capacities, torque and parts pages."
                        else -> "${selected.size} of ${state.pageCount} pages"
                    },
                    keyboardType = KeyboardType.Text,
                )
            } else {
                Text("One photo", style = MaterialTheme.typography.titleMedium)
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = TouchTarget).toggleable(state.dense, role = Role.Switch, onValueChange = viewModel::setDense),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Dense parts tables", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Uses the slower, more careful model and fewer pages per pass. Costs more.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.dense, onCheckedChange = null)
            }
            if (state.earlierRuns > 0) {
                Text(
                    "This document was imported before. Rows you already confirmed or rejected stay as they are; anything still waiting is replaced by this run.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            Button(
                onClick = viewModel::start,
                enabled = state.ready && state.selected != null && !state.starting,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
            ) { Text(if (state.earlierRuns > 0) "Run the import again" else "Start import") }
        }
    }
}
