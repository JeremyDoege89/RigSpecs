package dev.rigspecs.ui.settings

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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.rigspecs.data.SettingsRepository
import dev.rigspecs.data.importing.ExtractionService
import dev.rigspecs.domain.Portal
import dev.rigspecs.domain.isAllowedPortalUrl
import dev.rigspecs.ui.common.FormField
import dev.rigspecs.ui.common.SecretField
import dev.rigspecs.ui.theme.TouchTarget
import dev.rigspecs.ui.theme.WarnColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsForm(
    val portalUrls: Map<Portal, String> = emptyMap(),
    val portalEnabled: Map<Portal, Boolean> = emptyMap(),
    val importEnabled: Boolean = true,
    val serverUrl: String = "",
    val serverKey: String = "",
    val importSecret: String = "",
)

data class SettingsState(
    val loading: Boolean = true,
    val form: SettingsForm = SettingsForm(),
    val saved: SettingsForm = SettingsForm(),
    val checking: Boolean = false,
    val checkResult: Pair<Boolean, String>? = null,
) {
    val dirty: Boolean get() = form != saved
    val badPortals: Set<Portal> get() = form.portalUrls.filterValues { !isAllowedPortalUrl(it) }.keys
}

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val extractor: ExtractionService,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val s = settings.current()
            val form = SettingsForm(s.portalUrls, s.portalEnabled, s.importEnabled, s.serverUrl, s.serverKey, s.importSecret)
            _state.update { it.copy(loading = false, form = form, saved = form) }
        }
    }

    fun edit(change: (SettingsForm) -> SettingsForm) = _state.update { it.copy(form = change(it.form), checkResult = null) }

    fun save(then: () -> Unit = {}) {
        val s = _state.value
        if (s.badPortals.isNotEmpty()) return
        viewModelScope.launch {
            val f = s.form
            Portal.entries.forEach { p ->
                settings.set(p.urlKey, f.portalUrls[p].orEmpty().trim().ifEmpty { p.defaultUrl })
                settings.set(p.enabledKey, (f.portalEnabled[p] ?: true).toString())
            }
            settings.set(SettingsRepository.Keys.IMPORT_ENABLED, f.importEnabled.toString())
            settings.set(SettingsRepository.Keys.SERVER_URL, f.serverUrl.trim())
            settings.set(SettingsRepository.Keys.SERVER_KEY, f.serverKey.trim())
            settings.set(SettingsRepository.Keys.IMPORT_SECRET, f.importSecret.trim())
            _state.update { it.copy(saved = it.form) }
            then()
        }
    }

    /** Saves first, so the check uses exactly what's on screen. */
    fun check() = save {
        _state.update { it.copy(checking = true, checkResult = null) }
        viewModelScope.launch {
            val r = extractor.ping()
            _state.update { it.copy(checking = false, checkResult = r.ok to r.message) }
        }
    }
}

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (state.dirty) TextButton(onClick = { viewModel.save() }, enabled = state.badPortals.isEmpty()) { Text("Save") }
                },
            )
        },
    ) { padding ->
        if (state.loading) return@Scaffold
        val f = state.form
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Parts portals", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text(
                "The profile shows a button for each portal that fits the rig. Tapping it copies the rig's ESN, job number or VIN " +
                    "and opens the portal in your browser, where you're already signed in. If your portal has a search address, " +
                    "put {esn}, {job} or {vin} in it and the app fills it in.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Portal.entries.forEach { p ->
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SwitchRow(p.label, "Searches by ${p.identifierLabel}", f.portalEnabled[p] ?: true) { on ->
                    viewModel.edit { it.copy(portalEnabled = it.portalEnabled + (p to on)) }
                }
                FormField(
                    f.portalUrls[p].orEmpty(), { v -> viewModel.edit { it.copy(portalUrls = it.portalUrls + (p to v)) } }, "Address",
                    error = if (p in state.badPortals) "Use a web address starting with https://" else null,
                    keyboardType = KeyboardType.Uri, capitalization = KeyboardCapitalization.None,
                )
                if (f.portalUrls[p] != p.defaultUrl) {
                    TextButton(onClick = { viewModel.edit { it.copy(portalUrls = it.portalUrls + (p to p.defaultUrl)) } }) { Text("Reset to ${p.defaultUrl}") }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text("Assisted import", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            SwitchRow("Import specs from documents", "Reads manuals, parts lists and photos into rows you review", f.importEnabled) { on ->
                viewModel.edit { it.copy(importEnabled = on) }
            }
            Text(
                "The department's server holds the model key; this device only needs the server's address, its public key " +
                    "and the import passphrase. These stay on this device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FormField(
                f.serverUrl, { v -> viewModel.edit { it.copy(serverUrl = v) } }, "Server address",
                supporting = "https://<project>.supabase.co", keyboardType = KeyboardType.Uri, capitalization = KeyboardCapitalization.None,
            )
            FormField(f.serverKey, { v -> viewModel.edit { it.copy(serverKey = v) } }, "Server public (anon) key", capitalization = KeyboardCapitalization.None)
            SecretField(
                f.importSecret, { v -> viewModel.edit { it.copy(importSecret = v) } }, "Import passphrase",
                supporting = "The passphrase set on the server (IMPORT_SHARED_SECRET)",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = viewModel::check,
                    enabled = !state.checking && f.serverUrl.isNotBlank() && f.serverKey.isNotBlank() && f.importSecret.isNotBlank(),
                    modifier = Modifier.heightIn(min = TouchTarget),
                ) { Text(if (state.checking) "Checking…" else "Check connection") }
                state.checkResult?.let { (ok, message) ->
                    Text(message, color = if (ok) MaterialTheme.colorScheme.secondary else WarnColor, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Button(
                onClick = { viewModel.save() },
                enabled = state.dirty && state.badPortals.isEmpty(),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
            ) { Text(if (state.dirty) "Save" else "Saved") }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = TouchTarget).toggleable(checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
