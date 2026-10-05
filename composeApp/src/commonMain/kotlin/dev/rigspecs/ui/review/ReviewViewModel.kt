package dev.rigspecs.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rigspecs.data.ConfirmResult
import dev.rigspecs.data.DocumentEntity
import dev.rigspecs.data.ImportJobEntity
import dev.rigspecs.data.ImportRepository
import dev.rigspecs.data.ImportRowEntity
import dev.rigspecs.data.PartEntity
import dev.rigspecs.data.RigRepository
import dev.rigspecs.data.RowEdit
import dev.rigspecs.data.SpecItemEntity
import dev.rigspecs.data.VehicleEntity
import dev.rigspecs.data.importing.ImportRunner
import dev.rigspecs.domain.ImportJobStatus
import dev.rigspecs.domain.ImportMatch
import dev.rigspecs.domain.matchImportRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A staged row with what confirming it would do to the profile. */
data class ReviewItem(val row: ImportRowEntity, val match: ImportMatch, val document: DocumentEntity?)

/** An import run worth showing at the top: reading now, stopped, or still waiting for review. */
data class JobLine(val job: ImportJobEntity, val documentTitle: String, val running: Boolean) {
    /** RUNNING in the database but not in this process: the app was closed mid-import. */
    val interrupted: Boolean get() = !running && job.status == ImportJobStatus.RUNNING
}

data class ReviewState(
    val loading: Boolean = true,
    val vehicle: VehicleEntity? = null,
    val items: List<ReviewItem> = emptyList(),
    val unverifiedSpecs: List<SpecItemEntity> = emptyList(),
    val unverifiedParts: List<PartEntity> = emptyList(),
    val jobs: List<JobLine> = emptyList(),
    val documents: Map<String, DocumentEntity> = emptyMap(),
    val message: String? = null,
) {
    val isEmpty: Boolean get() = items.isEmpty() && unverifiedSpecs.isEmpty() && unverifiedParts.isEmpty()
}

class ReviewViewModel(
    private val vehicleId: String,
    private val rigs: RigRepository,
    private val imports: ImportRepository,
    private val runner: ImportRunner,
) : ViewModel() {
    private val message = MutableStateFlow<String?>(null)

    private val profile = combine(
        rigs.observeVehicle(vehicleId),
        rigs.observeSpecs(vehicleId),
        rigs.observeParts(vehicleId),
        rigs.observeDocuments(vehicleId),
    ) { v, specs, parts, docs -> Profile(v, specs, parts, docs.associateBy { it.id }) }

    val state: StateFlow<ReviewState> = combine(
        profile,
        imports.observePending(vehicleId),
        imports.observeJobs(vehicleId),
        runner.running,
        message,
    ) { p, pending, jobs, running, msg ->
        ReviewState(
            loading = false,
            vehicle = p.vehicle,
            items = pending.map { ReviewItem(it, matchImportRow(it, p.specs, p.parts), p.docs[jobs.firstOrNull { j -> j.id == it.jobId }?.documentId]) },
            unverifiedSpecs = p.specs.filter { it.status == dev.rigspecs.domain.VerificationStatus.UNVERIFIED },
            unverifiedParts = p.parts.filter { it.status == dev.rigspecs.domain.VerificationStatus.UNVERIFIED },
            jobs = jobs
                .filter { it.id in running || it.status in ShownStatuses }
                .map { JobLine(it, p.docs[it.documentId]?.title ?: "Document", it.id in running) },
            documents = p.docs,
            message = msg,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReviewState())

    fun confirm(rowId: String, edit: RowEdit? = null, replace: Boolean = false) = viewModelScope.launch {
        when (val r = imports.confirmRow(rowId, edit, replace)) {
            is ConfirmResult.Confirmed -> Unit
            is ConfirmResult.NeedsChoice -> message.value = "The profile now has ${r.current}. Choose Replace or Keep."
            ConfirmResult.Gone -> message.value = "That row was already handled."
        }
    }

    fun reject(rowId: String) = viewModelScope.launch { imports.rejectRow(rowId) }
    fun confirmSpec(id: String) = viewModelScope.launch { rigs.confirmSpec(id) }
    fun confirmPart(id: String) = viewModelScope.launch { rigs.confirmPart(id) }
    fun deleteSpec(id: String) = viewModelScope.launch { rigs.deleteSpec(id) }
    fun deletePart(id: String) = viewModelScope.launch { rigs.deletePart(id) }
    /** Confirms every unverified value except safety-critical ones, which stay one at a time. */
    fun confirmAll() = viewModelScope.launch {
        val n = rigs.confirmAllUnverified(vehicleId)
        message.value = "Confirmed $n ${if (n == 1) "value" else "values"}"
    }

    fun resume(jobId: String) = runner.start(jobId)
    fun cancel(jobId: String) = runner.cancel(jobId)
    fun messageShown() = message.update { null }

    private data class Profile(
        val vehicle: VehicleEntity?,
        val specs: List<SpecItemEntity>,
        val parts: List<PartEntity>,
        val docs: Map<String, DocumentEntity>,
    )

    private companion object {
        val ShownStatuses = setOf(ImportJobStatus.QUEUED, ImportJobStatus.RUNNING, ImportJobStatus.FAILED)
    }
}
