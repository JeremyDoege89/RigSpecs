package dev.rigspecs.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rigspecs.data.DocumentEntity
import dev.rigspecs.data.PartEntity
import dev.rigspecs.data.RigRepository
import dev.rigspecs.data.SpecItemEntity
import dev.rigspecs.data.VehicleEntity
import dev.rigspecs.domain.DocumentKind
import dev.rigspecs.domain.ProfileTab
import dev.rigspecs.domain.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ProfileState(
    val loading: Boolean = true,
    /** Null once loaded means the rig was deleted (here or by sync). */
    val vehicle: VehicleEntity? = null,
    val specs: List<SpecItemEntity> = emptyList(),
    val parts: List<PartEntity> = emptyList(),
    val documents: List<DocumentEntity> = emptyList(),
    val tab: ProfileTab = ProfileTab.OVERVIEW,
    val canEdit: Boolean = false,
) {
    val documentsById: Map<String, DocumentEntity> = documents.associateBy { it.id }
    fun specsFor(tab: ProfileTab) = specs.filter { it.category in tab.categories }
}

class ProfileViewModel(
    private val vehicleId: String,
    private val repository: RigRepository,
    private val session: Session,
) : ViewModel() {
    private val tab = MutableStateFlow(ProfileTab.OVERVIEW)

    val state: StateFlow<ProfileState> = combine(
        repository.observeVehicle(vehicleId),
        repository.observeSpecs(vehicleId),
        repository.observeParts(vehicleId),
        repository.observeDocuments(vehicleId),
        tab,
    ) { vehicle, specs, parts, docs, tab ->
        ProfileState(false, vehicle, specs, parts, docs, tab, session.canEdit)
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

    fun deleteDocument(id: String) = viewModelScope.launch { repository.deleteDocument(id) }
}
