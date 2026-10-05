package dev.rigspecs.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rigspecs.data.DocumentEntity
import dev.rigspecs.data.ImportJobEntity
import dev.rigspecs.data.ImportRepository
import dev.rigspecs.data.PartEntity
import dev.rigspecs.data.RigRepository
import dev.rigspecs.data.SettingsRepository
import dev.rigspecs.data.SpecItemEntity
import dev.rigspecs.data.VehicleEntity
import dev.rigspecs.data.importing.ImportRunner
import dev.rigspecs.data.importing.DocumentFiles
import dev.rigspecs.domain.DocumentKind
import dev.rigspecs.domain.Portal
import dev.rigspecs.domain.ProfileTab
import dev.rigspecs.domain.Session
import dev.rigspecs.domain.VerificationStatus
import dev.rigspecs.domain.expandPortalUrl
import dev.rigspecs.domain.isAllowedPortalUrl
import dev.rigspecs.ui.importing.PickedDocument
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A portal button on the profile: where it goes and what gets copied first. */
data class PortalLink(val portal: Portal, val url: String, val identifier: String)

data class ProfileState(
    val loading: Boolean = true,
    /** Null once loaded means the rig was deleted (here or by sync). */
    val vehicle: VehicleEntity? = null,
    val specs: List<SpecItemEntity> = emptyList(),
    val parts: List<PartEntity> = emptyList(),
    val documents: List<DocumentEntity> = emptyList(),
    val tab: ProfileTab = ProfileTab.OVERVIEW,
    val canEdit: Boolean = false,
    val pendingImport: Int = 0,
    val portals: List<PortalLink> = emptyList(),
    /** Latest import run per document. */
    val latestJobs: Map<String, ImportJobEntity> = emptyMap(),
    val runningJobs: Set<String> = emptySet(),
) {
    val documentsById: Map<String, DocumentEntity> = documents.associateBy { it.id }
    fun specsFor(tab: ProfileTab) = specs.filter { it.category in tab.categories }
    val unverified: Int
        get() = specs.count { it.status == VerificationStatus.UNVERIFIED } + parts.count { it.status == VerificationStatus.UNVERIFIED }
    val toReview: Int get() = unverified + pendingImport
    val activeJobs: List<ImportJobEntity> get() = latestJobs.values.filter { it.id in runningJobs }
}

class ProfileViewModel(
    private val vehicleId: String,
    private val repository: RigRepository,
    private val imports: ImportRepository,
    private val settings: SettingsRepository,
    private val runner: ImportRunner,
    private val files: DocumentFiles,
    private val session: Session,
) : ViewModel() {
    private val tab = MutableStateFlow(ProfileTab.OVERVIEW)

    private val content = combine(
        repository.observeVehicle(vehicleId),
        repository.observeSpecs(vehicleId),
        repository.observeParts(vehicleId),
        repository.observeDocuments(vehicleId),
        tab,
    ) { vehicle, specs, parts, docs, tab ->
        ProfileState(false, vehicle, specs, parts, docs, tab, session.canEdit)
    }

    val state: StateFlow<ProfileState> = combine(
        content,
        imports.observePending(vehicleId),
        imports.observeJobs(vehicleId),
        settings.observe(),
        runner.running,
    ) { base, pending, jobs, s, running ->
        val v = base.vehicle
        val links = if (v == null) emptyList() else Portal.entries
            .filter { s.portalEnabled[it] == true && it.appliesTo(v) }
            .map { PortalLink(it, expandPortalUrl(s.portalUrls[it] ?: it.defaultUrl, v), it.identifier(v)) }
            .filter { isAllowedPortalUrl(it.url) }
        base.copy(
            pendingImport = pending.size,
            portals = links,
            latestJobs = jobs.groupBy { it.documentId }.mapValues { (_, list) -> list.maxBy { it.createdAt } },
            runningJobs = running,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfileState(canEdit = session.canEdit))

    fun selectTab(t: ProfileTab) {
        tab.value = t
    }

    fun deleteVehicle(onDone: () -> Unit) = viewModelScope.launch {
        repository.deleteVehicle(vehicleId)
        onDone()
    }

    /** [id] blank adds a new document. */
    fun saveDocument(id: String, title: String, kind: DocumentKind, pageHint: String) = viewModelScope.launch {
        val base = id.takeIf { it.isNotEmpty() }?.let { repository.document(it) }
            ?: DocumentEntity(id = "", departmentId = "", vehicleId = vehicleId, title = "", kind = kind, updatedAt = 0, updatedBy = "")
        repository.saveDocument(base.copy(title = title.trim(), kind = kind, pageHint = pageHint.trim()))
    }

    /** A file the user picked or photographed becomes a document; [onSaved] gets its id. */
    fun addFileDocument(file: PickedDocument, onSaved: (String) -> Unit) = viewModelScope.launch {
        val id = repository.saveDocument(
            DocumentEntity(
                id = "", departmentId = "", vehicleId = vehicleId, title = file.name,
                kind = if (file.isPdf) DocumentKind.MANUAL else DocumentKind.PHOTO, localPath = file.path,
                updatedAt = 0, updatedBy = "",
            ),
        )
        onSaved(id)
    }

    /** The file on this device goes too; its specs keep their values (and lose the citation). */
    fun deleteDocument(id: String) = viewModelScope.launch {
        val path = repository.document(id)?.localPath.orEmpty()
        repository.deleteDocument(id)
        if (path.isNotBlank()) files.delete(path)
    }
}
